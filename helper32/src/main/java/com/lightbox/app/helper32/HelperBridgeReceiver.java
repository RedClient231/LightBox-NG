package com.lightbox.app.helper32;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.lightbox.app.abi.HelperPackage;
import com.lightbox.app.engine.RealVirtualEngineBridge;
import com.lightbox.app.engine.XapkInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Handles install / uninstall requests dispatched to the helper by the
 * main app for 32-bit virtual packages.
 *
 * Signature-level permission gates this receiver at the manifest layer,
 * so we do not re-check the caller here. The only sender that could get
 * here is the main app (or the helper's own process for local testing).
 *
 * Android 11+ scoped-storage handling: main may pass an {@code apk_path}
 * pointing at its own external cache, but that path is not readable from
 * this process. When {@link HelperPackage#EXTRA_APK_URI} is also present,
 * we prefer the URI: open a stream via ContentResolver, materialise it to
 * our own cache, then run the installer on the local copy.
 */
public class HelperBridgeReceiver extends BroadcastReceiver {

    private static final String TAG = "Helper32.Receiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        final String action = intent.getAction();
        final String pkg = intent.getStringExtra(HelperPackage.EXTRA_PACKAGE_NAME);
        final String apkPath = intent.getStringExtra(HelperPackage.EXTRA_APK_PATH);
        final String apkUriStr = intent.getStringExtra(HelperPackage.EXTRA_APK_URI);
        final String displayHint = intent.getStringExtra(HelperPackage.EXTRA_DISPLAY_NAME);

        final RealVirtualEngineBridge bridge = new RealVirtualEngineBridge();

        // goAsync lets us do disk I/O off the main thread without the
        // receiver timing out. Broadcast receivers get ~10s synchronously;
        // copying a 70 MB XAPK can exceed that on slow flash.
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                switch (action) {
                    case HelperPackage.ACTION_INSTALL:
                        handleInstallApk(context, bridge, apkPath, apkUriStr, displayHint);
                        context.getContentResolver()
                                .notifyChange(HelperPackage.INSTALLED_URI, null);
                        break;

                    case HelperPackage.ACTION_INSTALL_BUNDLE:
                        handleInstallBundle(context, bridge, apkPath, apkUriStr, displayHint);
                        context.getContentResolver()
                                .notifyChange(HelperPackage.INSTALLED_URI, null);
                        break;

                    case HelperPackage.ACTION_UNINSTALL:
                        if (pkg == null) {
                            Log.w(TAG, "uninstall request with no package_name");
                        } else {
                            Log.i(TAG, "helper uninstalling " + pkg);
                            bridge.uninstallApp(pkg);
                            context.getContentResolver()
                                    .notifyChange(HelperPackage.INSTALLED_URI, null);
                        }
                        break;

                    default:
                        Log.w(TAG, "unknown action: " + action);
                }
            } catch (Throwable t) {
                Log.e(TAG, "onReceive work failed: " + t.getMessage(), t);
            } finally {
                pr.finish();
            }
        }, "Helper32-Bridge").start();
    }

    // ---- single APK ----------------------------------------------------

    private void handleInstallApk(Context ctx, RealVirtualEngineBridge bridge,
                                  String apkPath, String uriStr, String displayHint) {
        File toInstall = resolveToLocalFile(ctx, apkPath, uriStr, displayHint, ".apk");
        if (toInstall == null) {
            Log.w(TAG, "install: no readable file (path=" + apkPath
                    + " uri=" + uriStr + ")");
            return;
        }
        Log.i(TAG, "helper installing " + toInstall.getAbsolutePath());
        boolean ok = bridge.installApk(toInstall.getAbsolutePath());
        Log.i(TAG, "helper install result: " + ok);
        // Clean up the local copy; helper's engine has extracted what it needs.
        //noinspection ResultOfMethodCallIgnored
        toInstall.delete();
    }

    // ---- XAPK / APKS / APKM bundle ------------------------------------

    private void handleInstallBundle(Context ctx, RealVirtualEngineBridge bridge,
                                     String apkPath, String uriStr, String displayHint) {
        File toInstall = resolveToLocalFile(ctx, apkPath, uriStr, displayHint, ".xapk");
        if (toInstall == null) {
            Log.w(TAG, "install-bundle: no readable file (path=" + apkPath
                    + " uri=" + uriStr + ")");
            return;
        }
        Log.i(TAG, "helper installing bundle " + toInstall.getAbsolutePath());
        XapkInstaller installer = new XapkInstaller(ctx, bridge);
        XapkInstaller.Result res = installer.install(toInstall.getAbsolutePath());
        Log.i(TAG, "helper bundle install result: success=" + res.success
                + " pkg=" + res.packageName
                + (res.errorMessage != null ? " err=" + res.errorMessage : ""));
        //noinspection ResultOfMethodCallIgnored
        toInstall.delete();
    }

    // ---- path/URI resolution ------------------------------------------

    /**
     * Try the direct path first (works when main staged to shared storage
     * pre–scoped-storage). If unreadable, fall back to the content URI —
     * {@code FLAG_GRANT_READ_URI_PERMISSION} must have been set by the
     * sender. Copies the stream to our own cache so the installer can
     * re-open it as a random-access {@code ZipFile}.
     */
    private File resolveToLocalFile(Context ctx, String pathOrNull, String uriOrNull,
                                    String displayHint, String defaultExt) {
        // 1. Direct read attempt.
        if (pathOrNull != null) {
            File f = new File(pathOrNull);
            if (f.exists() && f.canRead() && f.length() > 0) {
                return f;
            }
            Log.d(TAG, "direct path not readable, will try URI: " + pathOrNull);
        }

        // 2. Content URI via the sender's FileProvider.
        if (uriOrNull == null) return null;
        try {
            Uri uri = Uri.parse(uriOrNull);
            String name = sanitiseName(displayHint, defaultExt);
            File cacheDir = new File(ctx.getCacheDir(), "from_main");
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Log.w(TAG, "could not create cache dir " + cacheDir);
                return null;
            }
            File dest = new File(cacheDir, System.currentTimeMillis() + "_" + name);
            ContentResolver cr = ctx.getContentResolver();
            try (InputStream in = cr.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in == null) {
                    Log.w(TAG, "openInputStream returned null for " + uri);
                    return null;
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                Log.i(TAG, "copied " + total + " bytes from URI to " + dest);
            }
            return dest.length() > 0 ? dest : null;
        } catch (Exception e) {
            Log.e(TAG, "resolveToLocalFile URI read failed: " + e.getMessage(), e);
            return null;
        }
    }

    private static String sanitiseName(String hint, String defaultExt) {
        String base = hint != null && !hint.isEmpty() ? hint : ("bundle" + defaultExt);
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        String lower = base.toLowerCase(Locale.ROOT);
        if (defaultExt.equals(".xapk")
                && !(lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apkm"))) {
            base = base + defaultExt;
        } else if (defaultExt.equals(".apk") && !lower.endsWith(".apk")) {
            base = base + defaultExt;
        }
        return base;
    }
}
