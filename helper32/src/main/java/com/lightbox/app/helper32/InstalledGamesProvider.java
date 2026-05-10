package com.lightbox.app.helper32;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import com.lightbox.app.engine.RealVirtualEngineBridge;
import com.lightbox.app.model.ClonedApp;

import java.util.List;

/**
 * Exposes the list of virtual games installed inside the helper's sandbox
 * so the main app can show one unified library (Option A, the VPhoneGaGa
 * UX — user sees everything in one place regardless of ABI).
 *
 * Read-only. Write-back from main happens via
 * {@link HelperBridgeReceiver} broadcasts, which then call
 * {@link android.content.ContentResolver#notifyChange(Uri, android.database.ContentObserver)}
 * so any live cursors in main get refreshed.
 */
public class InstalledGamesProvider extends ContentProvider {

    private static final String TAG = "Helper32.Provider";

    private static final String[] COLUMNS = {"package_name", "app_name"};

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        try {
            RealVirtualEngineBridge bridge = new RealVirtualEngineBridge();
            List<ClonedApp> apps = bridge.getInstalledApps();
            for (ClonedApp a : apps) {
                cursor.addRow(new Object[]{ a.getPackageName(), a.getAppName() });
            }
        } catch (Throwable t) {
            Log.e(TAG, "query failed: " + t.getMessage(), t);
        }
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.lightbox.virtual_game"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
}
