package com.lightbox.app;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

import java.io.File;

public class LightBoxApp extends Application {

    private static final String TAG = "LightBoxApp";
    private static Context sContext;

    public static Context getAppContext() {
        return sContext;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        sContext = base;

        Log.i(TAG, "LightBox engine initializing...");
        try {
            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }
            });
            Log.i(TAG, "Engine attachBaseContext completed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Engine attachBaseContext failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "LightBox engine starting...");
        try {
            BlackBoxCore.get().doCreate();
            Log.i(TAG, "Engine doCreate completed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Engine doCreate failed: " + e.getMessage(), e);
        }
    }
}
