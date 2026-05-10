package com.lightbox.app.abi;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import com.lightbox.app.engine.RealVirtualEngineBridge;

/**
 * Decides where a virtual-app request goes:
 *   - 64-bit (or pure-Java / mixed) apps run IN-PROCESS via the main app's
 *     embedded Bcore engine.
 *   - 32-bit-only apps are dispatched to the helper package
 *     ({@link HelperPackage#PACKAGE}) via an explicit Intent. The helper is
 *     a sibling APK built for armeabi-v7a with its own full copy of Bcore;
 *     only it can host a 32-bit zygote child on this device.
 *
 * This class replaces the v1.0.15 "plugin" architecture. The mistake in
 * v1.0.15 was that the plugin had no engine behind its BridgeService; here,
 * the helper is a full Bcore installation that hosts virtual processes in
 * its own address space, exactly like the main app does for 64-bit games.
 */
public final class AbiRouter {

    private static final String TAG = "AbiRouter";

    public enum Route {
        /** Run in the main app's in-process engine. */
        IN_PROCESS_MAIN,
        /** Dispatch to the 32-bit helper APK. */
        DISPATCH_TO_HELPER32
    }

    public static final class Decision {
        public final Route route;
        public final VirtualAbiResolver.AbiInfo abi;
        public final String reason;

        Decision(Route route, VirtualAbiResolver.AbiInfo abi, String reason) {
            this.route = route;
            this.abi = abi;
            this.reason = reason;
        }
    }

    private final Context ctx;
    private final RealVirtualEngineBridge inProcess;

    public AbiRouter(Context ctx, RealVirtualEngineBridge inProcess) {
        this.ctx = ctx.getApplicationContext();
        this.inProcess = inProcess;
    }

    /** Pure routing decision given an APK file. Does not launch anything. */
    public Decision decide(String apkPath) {
        VirtualAbiResolver.AbiInfo info = VirtualAbiResolver.resolve(apkPath);
        if (info.is32BitOnly()) {
            return new Decision(Route.DISPATCH_TO_HELPER32, info, "apk is armeabi-v7a only");
        }
        return new Decision(Route.IN_PROCESS_MAIN, info,
                info.has64Bit ? "apk has arm64 libs" :
                info.hasNoNative() ? "apk has no native libs (pure java)" :
                "fallback to main");
    }

    // ---------------------------------------------------------------------
    // Install — route 32-bit APKs to the helper's install action
    // ---------------------------------------------------------------------

    /**
     * Ask the helper to install an APK into its sandbox. Returns false if the
     * helper isn't installed (caller should prompt the user to install it).
     *
     * The helper consumes {@link HelperPackage#ACTION_INSTALL} in a
     * receiver it declares. Staged apkPath must be world-readable or on
     * shared storage; the helper cannot read files inside main's
     * {@code /data/data/com.lightbox.ng/files/...} directly.
     */
    public boolean dispatchInstallToHelper(String apkPath, int userId) {
        if (!HelperPackage.isInstalled(ctx)) {
            Log.w(TAG, "helper not installed, cannot dispatch install");
            return false;
        }
        Intent i = new Intent(HelperPackage.ACTION_INSTALL);
        i.setPackage(HelperPackage.PACKAGE);
        i.putExtra(HelperPackage.EXTRA_APK_PATH, apkPath);
        i.putExtra(HelperPackage.EXTRA_USER_ID, userId);
        try {
            ctx.sendBroadcast(i, HelperPackage.PERMISSION_BRIDGE);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "dispatch install failed: " + e.getMessage(), e);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Launch
    // ---------------------------------------------------------------------

    /**
     * Launch a virtual app. If it's 32-bit and the helper is installed,
     * the Intent is delivered to the helper's LaunchActivity. Otherwise
     * launched in-process by the main engine.
     *
     * Returns true if dispatch happened (not necessarily that the game is
     * running yet — just that we handed off successfully).
     */
    public boolean launch(String virtualPackageName, String apkPathHintOrNull, int userId) {
        Decision d = (apkPathHintOrNull != null)
                ? decide(apkPathHintOrNull)
                : new Decision(Route.IN_PROCESS_MAIN, null, "no apk hint, fallback");

        Log.i(TAG, "launch " + virtualPackageName + " -> " + d.route + " (" + d.reason + ")");

        if (d.route == Route.DISPATCH_TO_HELPER32 && HelperPackage.isInstalled(ctx)) {
            return launchViaHelper(virtualPackageName, userId);
        }
        return inProcess.launchApp(virtualPackageName);
    }

    private boolean launchViaHelper(String virtualPackageName, int userId) {
        Intent i = new Intent(HelperPackage.ACTION_LAUNCH);
        i.setPackage(HelperPackage.PACKAGE);
        i.putExtra(HelperPackage.EXTRA_PACKAGE_NAME, virtualPackageName);
        i.putExtra(HelperPackage.EXTRA_USER_ID, userId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startActivity to helper failed: " + e.getMessage(), e);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Uninstall — send to whichever side owns the virtual package
    // ---------------------------------------------------------------------

    public void dispatchUninstall(String virtualPackageName, boolean is32Bit, int userId) {
        if (is32Bit && HelperPackage.isInstalled(ctx)) {
            Intent i = new Intent(HelperPackage.ACTION_UNINSTALL);
            i.setPackage(HelperPackage.PACKAGE);
            i.putExtra(HelperPackage.EXTRA_PACKAGE_NAME, virtualPackageName);
            i.putExtra(HelperPackage.EXTRA_USER_ID, userId);
            try {
                ctx.sendBroadcast(i, HelperPackage.PERMISSION_BRIDGE);
                return;
            } catch (Exception e) {
                Log.e(TAG, "uninstall dispatch failed: " + e.getMessage(), e);
            }
        }
        inProcess.uninstallApp(virtualPackageName);
    }
}
