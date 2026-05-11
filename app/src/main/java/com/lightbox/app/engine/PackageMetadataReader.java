package com.lightbox.app.engine;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class PackageMetadataReader {

    private static final String TAG = "PackageMetadataReader";

    public static class ApkInfo {
        public String packageName;
        public String appName;
        public int versionCode;
        public String versionName;
    }

    public static ApkInfo readApkInfo(Context context, String apkPath) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apkPath, 0);
            if (info == null) {
                Log.e(TAG, "Could not read package info from: " + apkPath);
                return null;
            }
            ApkInfo apkInfo = new ApkInfo();
            apkInfo.packageName = info.packageName;
            apkInfo.versionCode = info.versionCode;
            apkInfo.versionName = info.versionName;
            try {
                PackageInfo fullInfo = pm.getPackageArchiveInfo(apkPath,
                        PackageManager.GET_ACTIVITIES);
                if (fullInfo != null && fullInfo.applicationInfo != null) {
                    fullInfo.applicationInfo.sourceDir = apkPath;
                    fullInfo.applicationInfo.publicSourceDir = apkPath;
                    apkInfo.appName = pm.getApplicationLabel(fullInfo.applicationInfo).toString();
                }
            } catch (Exception e) {
                apkInfo.appName = info.packageName;
            }
            if (apkInfo.appName == null) {
                apkInfo.appName = info.packageName;
            }
            return apkInfo;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read APK info: " + e.getMessage(), e);
            return null;
        }
    }

    public static String copyApkFromUri(Context context, Uri uri) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Log.e(TAG, "Could not open URI: " + uri);
                return null;
            }

            File stagingDir = new File(context.getFilesDir(), "apk_staging");
            if (!stagingDir.exists()) {
                stagingDir.mkdirs();
            }

            File tempFile = new File(stagingDir, "temp_" + System.currentTimeMillis() + ".apk");
            FileOutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();
            inputStream.close();

            Log.i(TAG, "APK copied to: " + tempFile.getAbsolutePath() + " (" + tempFile.length() + " bytes)");
            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy APK from URI: " + e.getMessage(), e);
            return null;
        }
    }
}
