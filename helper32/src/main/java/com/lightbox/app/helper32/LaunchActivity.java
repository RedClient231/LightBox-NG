package com.lightbox.app.helper32;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.lightbox.app.abi.HelperPackage;
import com.lightbox.app.engine.RealVirtualEngineBridge;

/**
 * Transparent entry point into the helper process, triggered by the main app.
 *
 * Receives {@link HelperPackage#ACTION_LAUNCH} with the virtual package name.
 * Spins up (or reuses) Bcore in this 32-bit process and calls
 * {@code launchApk(...)}. Bcore then handles all the normal virtualization
 * steps — {@code BActivityManagerService}, proxy process allocation,
 * {@code ProxyActivity$PN} bringup — inside this armeabi-v7a process.
 *
 * Why an Activity and not a Service: on Android 12+ background activity
 * launch restrictions block a Service from legitimately starting the
 * virtualized app's proxy Activity. Using a foreground Activity in the
 * helper side-steps the whole background-launch problem cleanly.
 */
public class LaunchActivity extends Activity {

    private static final String TAG = "Helper32.Launch";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) { finish(); return; }
        String pkg = intent.getStringExtra(HelperPackage.EXTRA_PACKAGE_NAME);
        int userId = intent.getIntExtra(HelperPackage.EXTRA_USER_ID, 0);
        if (pkg == null || pkg.isEmpty()) {
            Log.w(TAG, "no package in intent, ignoring");
            finish();
            return;
        }

        Log.i(TAG, "launching virtual app " + pkg + " (user=" + userId + ") in helper process");
        try {
            RealVirtualEngineBridge bridge = new RealVirtualEngineBridge();
            boolean ok = bridge.launchApp(pkg);
            Log.i(TAG, "helper launch result: " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "helper launch threw: " + t.getMessage(), t);
        } finally {
            // Transparent activity; terminate immediately so the user
            // doesn't see an empty window in recents.
            finish();
        }
    }
}
