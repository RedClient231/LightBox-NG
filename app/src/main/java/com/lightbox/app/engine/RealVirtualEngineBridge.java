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
