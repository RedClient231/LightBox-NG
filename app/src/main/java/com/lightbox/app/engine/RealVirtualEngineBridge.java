package com.lightbox.app.engine;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.core.system.user.BUserInfo;

import com.lightbox.app.LightBoxApp;
import com.lightbox.app.model.ClonedApp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RealVirtualEngineBridge {

    private static final String TAG = "RealVirtualEngineBridge";

    public boolean isEngineReady() {
        try {
            BlackBoxCore.get();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Engine not ready: " + e.getMessage());
            return false;
        }
    }

    public List<ClonedApp> getInstalledApps() {
        List<ClonedApp> apps = new ArrayList<>();
        try {
            int userId = getOrCreateDefaultUser();
            List<ApplicationInfo> installedApps = BlackBoxCore.get().getInstalledApplications(0, userId);
            if (installedApps != null) {
                PackageManager pm = LightBoxApp.getAppContext().getPackageManager();
                for (ApplicationInfo appInfo : installedApps) {
                    ClonedApp app = new ClonedApp();
                    app.setPackageName(appInfo.packageName);
                    try {
                        CharSequence label = pm.getApplicationLabel(appInfo);
                        app.setAppName(label != null ? label.toString() : appInfo.packageName);
                    } catch (Exception e) {
                        app.setAppName(appInfo.packageName);
                    }
                    try {
                        Drawable icon = pm.getApplicationIcon(appInfo);
                        app.setIcon(icon);
                    } catch (Exception e) {
                        // no icon available
                    }
                    apps.add(app);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get installed apps: " + e.getMessage(), e);
        }
        return apps;
    }

    public boolean installApk(String apkPath) {
        try {
            int userId = getOrCreateDefaultUser();
            Log.i(TAG, "Installing APK: " + apkPath + " for user " + userId);
            InstallResult result = BlackBoxCore.get().installPackageAsUser(new File(apkPath), userId);
            Log.i(TAG, "Install result: " + result.success + (result.msg != null ? " msg: " + result.msg : ""));
            return result.success;
        } catch (Exception e) {
            Log.e(TAG, "Install failed: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean launchApp(String packageName) {
        try {
            int userId = getOrCreateDefaultUser();
            Log.i(TAG, "Launching: " + packageName + " for user " + userId);
            boolean result = BlackBoxCore.get().launchApk(packageName, userId);
            Log.i(TAG, "Launch result: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Launch failed for " + packageName + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Lightweight POJO carrying both the virtual APK path and the extracted
     * native-lib dir for a given virtual package. Either field may be null
     * if the engine can't resolve it.
     */
    public static final class VirtualPaths {
        public final String apkPath;
        public final String nativeLibDir;
        public VirtualPaths(String apkPath, String nativeLibDir) {
            this.apkPath = apkPath;
            this.nativeLibDir = nativeLibDir;
        }
    }

    /**
     * Return BOTH the on-disk APK and the extracted nativeLibraryDir for a
     * virtual package. AbiRouter needs the lib dir because some APKs (e.g.
     * Unity games) carry their .so files in split APKs — the base APK has
     * no libs and a naive lib-scan calls them "pure Java", which leads to
     * routing a 32-bit game into the 64-bit main.
     */
    public VirtualPaths getVirtualPaths(String packageName) {
        try {
            int userId = getOrCreateDefaultUser();
            android.content.pm.ApplicationInfo info =
                    BlackBoxCore.getBPackageManager()
                            .getApplicationInfo(packageName, 0, userId);
            if (info == null) return new VirtualPaths(null, null);
            return new VirtualPaths(info.sourceDir, info.nativeLibraryDir);
        } catch (Exception e) {
            Log.w(TAG, "getVirtualPaths failed for " + packageName + ": " + e.getMessage());
            return new VirtualPaths(null, null);
        }
    }

    /**
     * Return the on-disk path to the APK that backs a virtual package, or
     * null if the engine can't find it. AbiRouter needs this to decide
     * whether a launch must be dispatched to the 32-bit helper.
     */
    public String getVirtualApkPath(String packageName) {
        try {
            int userId = getOrCreateDefaultUser();
            // BPackageManager owns the virtual PMS data.
            android.content.pm.ApplicationInfo info =
                    BlackBoxCore.getBPackageManager()
                            .getApplicationInfo(packageName, 0, userId);
            if (info != null && info.sourceDir != null && !info.sourceDir.isEmpty()) {
                return info.sourceDir;
            }
        } catch (Exception e) {
            Log.w(TAG, "getVirtualApkPath failed for " + packageName + ": " + e.getMessage());
        }
        return null;
    }

    public void uninstallApp(String packageName) {
        try {
            int userId = getOrCreateDefaultUser();
            Log.i(TAG, "Uninstalling: " + packageName + " for user " + userId);
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userId);
            Log.i(TAG, "Uninstall completed: " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "Uninstall failed for " + packageName + ": " + e.getMessage(), e);
        }
    }

    private int getOrCreateDefaultUser() {
        try {
            List<BUserInfo> users = BlackBoxCore.get().getUsers();
            if (users != null && !users.isEmpty()) {
                return users.get(0).id;
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get users: " + e.getMessage(), e);
            return 0;
        }
    }
}
