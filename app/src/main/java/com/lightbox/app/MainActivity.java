package com.lightbox.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.lightbox.app.engine.PackageInstallManager;
import com.lightbox.app.engine.PackageMetadataReader;
import com.lightbox.app.engine.RealVirtualEngineBridge;
import com.lightbox.app.engine.XapkInstaller;
import com.lightbox.app.model.ClonedApp;
import com.lightbox.app.ui.AppListAdapter;
import com.lightbox.app.ui.ImportDialogFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ImportDialogFragment.ImportCallback {

    private static final String TAG = "MainActivity";
    private static final int REQ_STORAGE_PERMS = 1001;
    private static final int REQ_MANAGE_STORAGE = 1002;

    private RecyclerView recyclerView;
    private AppListAdapter adapter;
    private TextView emptyView;
    private final List<ClonedApp> clonedApps = new ArrayList<>();

    private RealVirtualEngineBridge engineBridge;
    private PackageInstallManager installManager;
    private XapkInstaller xapkInstaller;

    // OpenDocument (not GetContent) because the latter hides XAPK files when
    // the caller specifies a strict MIME type — many file managers label
    // XAPKs as application/octet-stream or application/zip.
    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) handleImportUri(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setSupportActionBar(findViewById(R.id.toolbar));

        engineBridge = new RealVirtualEngineBridge();
        installManager = new PackageInstallManager(this, engineBridge);
        xapkInstaller = new XapkInstaller(this, engineBridge);

        recyclerView = findViewById(R.id.recycler_view);
        emptyView = findViewById(R.id.empty_view);
        FloatingActionButton fab = findViewById(R.id.fab_add);

        adapter = new AppListAdapter(clonedApps, this::launchClonedApp, this::showAppOptions);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        fab.setOnClickListener(v -> showImportDialog());

        loadClonedApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadClonedApps();
    }

    private void showImportDialog() {
        ImportDialogFragment.newInstance().show(getSupportFragmentManager(), "import_dialog");
    }

    @Override
    public void onPickFromStorage() {
        ensureAllFilesAccess();
        filePickerLauncher.launch(new String[]{
                "application/vnd.android.package-archive",
                "application/zip",
                "application/octet-stream",
                "*/*"
        });
    }

    @Override
    public void onPickInstalledApp() {
        showInstalledAppsPicker();
    }

    private void showInstalledAppsPicker() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installedApps = pm.getInstalledApplications(0);
        List<ApplicationInfo> launchable = new ArrayList<>();
        for (ApplicationInfo app : installedApps) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                launchable.add(app);
            }
        }
        String[] names = new String[launchable.size()];
        String[] packages = new String[launchable.size()];
        for (int i = 0; i < launchable.size(); i++) {
            names[i] = pm.getApplicationLabel(launchable.get(i)).toString();
            packages[i] = launchable.get(i).packageName;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_installed_app)
                .setItems(names, (dialog, which) -> installFromInstalled(packages[which]))
                .show();
    }

    private void installFromInstalled(String packageName) {
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(packageName, 0);
            new Thread(() -> installApkFromPath(appInfo.sourceDir)).start();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package not found: " + packageName, e);
            Toast.makeText(this, getString(R.string.error_package_not_found), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleImportUri(Uri uri) {
        new Thread(() -> {
            try {
                String displayName = queryDisplayName(uri);
                boolean looksLikeBundle = XapkInstaller.isBundleFile(displayName);

                if (!looksLikeBundle) {
                    String mime = getContentResolver().getType(uri);
                    boolean mimeLooksZip = mime != null
                            && (mime.toLowerCase(Locale.ROOT).contains("zip")
                                || mime.toLowerCase(Locale.ROOT).contains("xapk"));
                    boolean nameHintsBundle = displayName != null
                            && (displayName.toLowerCase(Locale.ROOT).contains("xapk")
                                || displayName.toLowerCase(Locale.ROOT).contains("apks")
                                || displayName.toLowerCase(Locale.ROOT).contains("apkm"));
                    if (mimeLooksZip || nameHintsBundle) {
                        try (InputStream sniff = getContentResolver().openInputStream(uri)) {
                            if (sniff != null && XapkInstaller.looksLikeZip(sniff)) {
                                looksLikeBundle = true;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                String stagedPath = copyUriToStaging(uri,
                        displayName != null ? displayName
                                : ("import_" + System.currentTimeMillis()
                                        + (looksLikeBundle ? ".xapk" : ".apk")));
                if (stagedPath == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.error_failed_to_read_apk),
                            Toast.LENGTH_SHORT).show());
                    return;
                }

                if (looksLikeBundle) {
                    XapkInstaller.Result result = xapkInstaller.install(stagedPath);
                    runOnUiThread(() -> {
                        if (result.success) {
                            Toast.makeText(this,
                                    getString(R.string.app_installed, result.appName),
                                    Toast.LENGTH_SHORT).show();
                            loadClonedApps();
                        } else {
                            Toast.makeText(this,
                                    getString(R.string.error_install_failed_detail,
                                            result.errorMessage),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    new File(stagedPath).delete();
                } else {
                    installApkFromPath(stagedPath);
                }
            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.error_install_failed_detail, e.getMessage()),
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void installApkFromPath(String apkPath) {
        try {
            PackageMetadataReader.ApkInfo info =
                    PackageMetadataReader.readApkInfo(this, apkPath);
            if (info == null) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.error_invalid_apk),
                        Toast.LENGTH_SHORT).show());
                return;
            }
            boolean success = installManager.installApk(apkPath);
            runOnUiThread(() -> {
                if (success) {
                    Toast.makeText(this,
                            getString(R.string.app_installed, info.appName),
                            Toast.LENGTH_SHORT).show();
                    loadClonedApps();
                } else {
                    Toast.makeText(this,
                            getString(R.string.error_install_failed),
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "APK install failed", e);
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.error_install_failed_detail, e.getMessage()),
                    Toast.LENGTH_SHORT).show());
        }
    }

    private String copyUriToStaging(Uri uri, String destName) {
        try {
            File stagingDir = new File(getFilesDir(), "apk_staging");
            if (!stagingDir.exists()) stagingDir.mkdirs();
            File dest = new File(stagingDir, sanitizeFilename(destName));
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in == null) return null;
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dest.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "copyUriToStaging failed", e);
            return null;
        }
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) return "import.bin";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        String path = uri.getPath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0) return path.substring(slash + 1);
            return path;
        }
        return null;
    }

    private void ensureAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQ_MANAGE_STORAGE);
                } catch (Exception e) {
                    Log.w(TAG, "Could not request MANAGE_EXTERNAL_STORAGE: " + e.getMessage());
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_STORAGE_PERMS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void loadClonedApps() {
        clonedApps.clear();
        try {
            clonedApps.addAll(engineBridge.getInstalledApps());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cloned apps: " + e.getMessage(), e);
        }
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(clonedApps.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(clonedApps.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void launchClonedApp(ClonedApp app) {
        try {
            boolean launched = engineBridge.launchApp(app.getPackageName());
            if (!launched) {
                Toast.makeText(this, getString(R.string.error_launch_failed),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Launch failed for " + app.getPackageName(), e);
            Toast.makeText(this,
                    getString(R.string.error_launch_failed_detail, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppOptions(ClonedApp app) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(app.getAppName())
                .setItems(new String[]{
                        getString(R.string.action_launch),
                        getString(R.string.action_uninstall)
                }, (dialog, which) -> {
                    if (which == 0) launchClonedApp(app);
                    else if (which == 1) uninstallApp(app);
                })
                .show();
    }

    private void uninstallApp(ClonedApp app) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_uninstall_title)
                .setMessage(getString(R.string.confirm_uninstall_message, app.getAppName()))
                .setPositiveButton(R.string.uninstall, (dialog, which) -> {
                    try {
                        engineBridge.uninstallApp(app.getPackageName());
                        loadClonedApps();
                        Toast.makeText(this, getString(R.string.app_uninstalled),
                                Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Uninstall failed", e);
                        Toast.makeText(this, getString(R.string.error_uninstall_failed),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_about) { showAbout(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showAbout() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
