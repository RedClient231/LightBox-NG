package com.lightbox.app.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class PackageInstallManager {

    private static final String TAG = "PackageInstallManager";

    private final Context context;
    private final RealVirtualEngineBridge engineBridge;

    public PackageInstallManager(Context context, RealVirtualEngineBridge engineBridge) {
        this.context = context;
        this.engineBridge = engineBridge;
    }

    public boolean installApk(String apkPath) {
        if (!engineBridge.isEngineReady()) {
            Log.e(TAG, "Cannot install: engine not ready");
            return false;
        }

        File apkFile = new File(apkPath);
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file does not exist: " + apkPath);
            return false;
        }

        if (!apkFile.canRead()) {
            Log.e(TAG, "APK file not readable: " + apkPath);
            return false;
        }

        Log.i(TAG, "Installing APK: " + apkPath + " (" + apkFile.length() + " bytes)");
        return engineBridge.installApk(apkPath);
    }

    public File getApkStagingDir() {
        File dir = new File(context.getFilesDir(), "apk_staging");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
