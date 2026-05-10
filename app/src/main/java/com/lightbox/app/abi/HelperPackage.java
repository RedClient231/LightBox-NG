package com.lightbox.app.abi;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

/**
 * Constants and presence-check helpers for the 32-bit helper package.
 *
 * The helper package is a sibling APK with the same signature as the main
 * app. It owns the {@code armeabi-v7a} primaryCpuAbi, so its processes fork
 * from {@code zygote} (not {@code zygote64}) and can {@code dlopen()} 32-bit
 * virtual-game .so files.
 */
public final class HelperPackage {

    private static final String TAG = "HelperPackage";

    /** Must match the helper module's {@code applicationId}. */
    public static final String PACKAGE = "com.lightbox.ng.arm32";

    /** Intent action for "launch this virtual app inside the helper". */
    public static final String ACTION_LAUNCH = "com.lightbox.ng.arm32.action.LAUNCH";

    /** Intent action for "install this APK inside the helper's sandbox". */
    public static final String ACTION_INSTALL = "com.lightbox.ng.arm32.action.INSTALL";

    /**
     * Intent action for "install this XAPK/APKS/APKM bundle inside the
     * helper's sandbox". Carries {@link #EXTRA_APK_PATH} pointing to the
     * bundle file (world-readable, on shared storage).
     */
    public static final String ACTION_INSTALL_BUNDLE =
            "com.lightbox.ng.arm32.action.INSTALL_BUNDLE";

    /** Intent action for "uninstall this virtual package from the helper's sandbox". */
    public static final String ACTION_UNINSTALL = "com.lightbox.ng.arm32.action.UNINSTALL";

    /** Extra: virtual package name (string). */
    public static final String EXTRA_PACKAGE_NAME = "package_name";

    /** Extra: staged APK path on disk (string). */
    public static final String EXTRA_APK_PATH = "apk_path";

    /**
     * Extra: {@code content://} URI for the staged APK or bundle, readable
     * by the helper via {@code ContentResolver.openInputStream}.
     *
     * Android 11+ scoped storage blocks the helper (different UID) from
     * opening files under {@code /storage/emulated/0/Android/data/<main>/},
     * even with {@code MANAGE_EXTERNAL_STORAGE}. A FileProvider URI with
     * {@code FLAG_GRANT_READ_URI_PERMISSION} is the canonical cross-package
     * read path. Value is a stringified URI (see {@link android.net.Uri#toString()}).
     */
    public static final String EXTRA_APK_URI = "apk_uri";

    /**
     * Extra: hint for a human-readable filename the helper can use when
     * copying from the URI to its own cache. Optional.
     */
    public static final String EXTRA_DISPLAY_NAME = "display_name";

    /** Extra: virtual user id (int, default 0). */
    public static final String EXTRA_USER_ID = "user_id";

    /** Signature-level permission the helper exports and main holds. */
    public static final String PERMISSION_BRIDGE =
            "com.lightbox.ng.permission.HELPER_BRIDGE";

    /**
     * ContentProvider authority published by the helper, used by main to
     * enumerate the 32-bit games installed inside the helper's sandbox.
     * Option A (shared library view) relies on this provider.
     */
    public static final String PROVIDER_AUTHORITY =
            "com.lightbox.ng.arm32.installed_games";

    /** Base URI for the helper's installed-games provider. */
    public static final Uri INSTALLED_URI =
            Uri.parse("content://" + PROVIDER_AUTHORITY + "/installed");

    private HelperPackage() {}

    /**
     * True when the helper APK is installed on this device.
     * Returns false if the check fails for any reason (ambiguous → safe default).
     */
    public static boolean isInstalled(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(PACKAGE, 0);
            return pi != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            Log.w(TAG, "isInstalled check failed: " + e.getMessage());
            return false;
        }
    }
}
