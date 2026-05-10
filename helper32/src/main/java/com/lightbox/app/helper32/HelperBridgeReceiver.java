package com.lightbox.app.helper32;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.lightbox.app.abi.HelperPackage;
import com.lightbox.app.engine.RealVirtualEngineBridge;

/**
 * Handles install / uninstall requests dispatched to the helper by the
 * main app for 32-bit virtual packages.
 *
 * Signature-level permission gates this receiver at the manifest layer, so
 * we do not re-check the caller here. The only sender that could get here
 * is the main app (or the helper's own process for local testing).
 */
public class HelperBridgeReceiver extends BroadcastReceiver {

    private static final String TAG = "Helper32.Receiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        String pkg = intent.getStringExtra(HelperPackage.EXTRA_PACKAGE_NAME);
        String apkPath = intent.getStringExtra(HelperPackage.EXTRA_APK_PATH);

        RealVirtualEngineBridge bridge = new RealVirtualEngineBridge();

        switch (action) {
            case HelperPackage.ACTION_INSTALL: {
                if (apkPath == null) {
                    Log.w(TAG, "install request with no apk_path");
                    return;
                }
                Log.i(TAG, "helper installing " + apkPath);
                boolean ok = bridge.installApk(apkPath);
                Log.i(TAG, "helper install result: " + ok);
                // Publish change so main can re-query the installed-games provider.
                context.getContentResolver().notifyChange(HelperPackage.INSTALLED_URI, null);
                break;
            }
            case HelperPackage.ACTION_UNINSTALL: {
                if (pkg == null) {
                    Log.w(TAG, "uninstall request with no package_name");
                    return;
                }
                Log.i(TAG, "helper uninstalling " + pkg);
                bridge.uninstallApp(pkg);
                context.getContentResolver().notifyChange(HelperPackage.INSTALLED_URI, null);
                break;
            }
            default:
                Log.w(TAG, "unknown action: " + action);
        }
    }
}
