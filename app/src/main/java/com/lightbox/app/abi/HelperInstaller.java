package com.lightbox.app.abi;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Finds the bundled helper APK (shipped inside main's {@code assets/}) and
 * prompts the user to install it. The user must approve the install because
 * Android rightly does not let one app install another silently.
 *
 * The helper APK is built by CI and placed at {@code assets/helper32.apk}
 * during the main APK assembly. If it's missing (local dev builds), this
 * class is a no-op and the main app falls back to asking the user to
 * sideload the helper from the GitHub release.
 */
public final class HelperInstaller {

    private static final String TAG = "HelperInstaller";
    private static final String ASSET_NAME = "helper32.apk";

    private HelperInstaller() {}

    /** True if {@code assets/helper32.apk} is present in main's APK. */
    public static boolean isHelperAssetAvailable(Context ctx) {
        try (InputStream ignored = ctx.getAssets().open(ASSET_NAME)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Copy the bundled helper APK to the cache dir and fire a VIEW Intent
     * so the user's package installer prompts for approval.
     *
     * Throws if the asset is missing — callers should check
     * {@link #isHelperAssetAvailable(Context)} first.
     */
    public static void promptInstall(Context ctx) throws Exception {
        File cacheDir = new File(ctx.getCacheDir(), "helper");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IllegalStateException("cannot create cache dir for helper");
        }
        File apkFile = new File(cacheDir, ASSET_NAME);
        try (InputStream in = ctx.getAssets().open(ASSET_NAME);
             FileOutputStream out = new FileOutputStream(apkFile)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Must match the provider authority declared in main's manifest.
            uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".helper_fileprovider", apkFile);
        } else {
            uri = Uri.fromFile(apkFile);
        }

        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "no package installer found", e);
            throw e;
        }
    }
}
