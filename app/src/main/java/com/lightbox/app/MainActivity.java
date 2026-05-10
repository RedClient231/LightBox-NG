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

import com.lightbox.app.abi.AbiRouter;
import com.lightbox.app.abi.HelperInstaller;
import com.lightbox.app.abi.HelperPackage;
import com.lightbox.app.abi.VirtualAbiResolver;
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
    private AbiRouter abiRouter;
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
        abiRouter = new AbiRouter(this, engineBridge);
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
                    // peekBundle() scans split APKs too — critical for
                    // Unity games (e.g. Traffic Rider) whose base.apk has
                    // no libs but config.armeabi_v7a.apk does. Without
                    // peeking, the resolver would call the bundle
                    // "pure Java" and route it to main.
                    VirtualAbiResolver.AbiInfo bundleAbi =
                            VirtualAbiResolver.peekBundle(stagedPath);
                    Log.i(TAG, "bundle abi: " + bundleAbi);
                    installBundleByAbi(stagedPath, bundleAbi, displayName);
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

            // ABI-aware install routing: if the APK is 32-bit-only, the install
            // MUST land inside the helper's sandbox so its .so files end up in a
            // location a 32-bit process can dlopen. Installing a 32-bit game into
            // the main (64-bit) sandbox works at install time but fails at launch
            // with the exact error we hit before: "libmain.so is 32-bit instead
            // of 64-bit".
            // ABI-aware install routing. Cases:
            //   32-bit-only  -> helper sandbox ONLY (dlopen of 32-bit .so
            //                   into main's 64-bit :pN would crash at launch).
            //   64-bit-only  -> main sandbox ONLY.
            //   MIXED        -> BOTH. Unlocks GameGuardian: GG ships both
            //                   ABIs, so installing in main lets it see
            //                   arm64 games, and installing in helper lets
            //                   it see armv7 games. GG can only instrument
            //                   processes inside its own Bcore sandbox.
            //   pure-Java    -> main sandbox ONLY (ABI irrelevant).
            VirtualAbiResolver.AbiInfo abi = VirtualAbiResolver.resolve(apkPath);
            Log.i(TAG, "install abi: " + abi);

            boolean installInMain = abi.is64BitOnly() || abi.hasNoNative() || abi.isMixed();
            boolean installInHelper = abi.is32BitOnly() || abi.isMixed();

            if (installInHelper) {
                if (!HelperPackage.isInstalled(this)) {
                    if (abi.is32BitOnly()) {
                        runOnUiThread(() -> promptInstallHelper(info.appName));
                        return;
                    }
                    // Mixed + helper missing: don't block. Fall through to
                    // main-only install; user can sideload the helper later.
                    Log.w(TAG, "mixed-ABI " + info.packageName
                            + " -> helper-missing; installing into main only");
                } else {
                    dispatchSingleApkToHelper(apkPath, info.packageName, info.appName);
                }
            }

            if (installInMain) {
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
            }
        } catch (Exception e) {
            Log.e(TAG, "APK install failed", e);
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.error_install_failed_detail, e.getMessage()),
                    Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Stage a single APK, build a FileProvider URI, grant it to the helper,
     * and fire the install broadcast. Caller has already decided the helper
     * should get this package.
     */
    private boolean dispatchSingleApkToHelper(String apkPath, String packageName,
                                              String displayAppName) {
        String sharedStaging = stageForHelper(apkPath, packageName);
        if (sharedStaging == null) {
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.error_install_failed),
                    Toast.LENGTH_SHORT).show());
            return false;
        }
        android.net.Uri apkUri = null;
        try {
            apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".helper_fileprovider",
                    new File(sharedStaging));
            grantUriPermission(HelperPackage.PACKAGE, apkUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.e(TAG, "FileProvider URI build failed (single APK)", e);
        }
        boolean dispatched = abiRouter.dispatchInstallToHelper(
                sharedStaging, apkUri, packageName + ".apk", 0);
        runOnUiThread(() -> {
            if (dispatched) {
                Toast.makeText(this,
                        "Installing " + displayAppName + " (32-bit space)...",
                        Toast.LENGTH_SHORT).show();
                loadClonedApps();
            } else {
                Toast.makeText(this,
                        getString(R.string.error_install_failed),
                        Toast.LENGTH_SHORT).show();
            }
        });
        return dispatched;
    }

    /**
     * Bundle (XAPK/APKS/APKM) counterpart of {@link #installApkFromPath}.
     * Same dual-install rules: 32-only -> helper, 64-only / pure-Java ->
     * main, mixed -> both (enables the GameGuardian-in-helper workflow).
     */
    private void installBundleByAbi(String bundlePath, VirtualAbiResolver.AbiInfo abi,
                                    String displayName) {
        boolean installInMain = abi.is64BitOnly() || abi.hasNoNative() || abi.isMixed();
        boolean installInHelper = abi.is32BitOnly() || abi.isMixed();
        final String safeName = displayName != null ? displayName : "bundle.xapk";

        if (installInHelper) {
            if (!HelperPackage.isInstalled(this)) {
                if (abi.is32BitOnly()) {
                    runOnUiThread(() -> promptInstallHelper(safeName));
                    return;
                }
                Log.w(TAG, "mixed-ABI bundle " + safeName
                        + " -> helper-missing; installing into main only");
            } else {
                String sharedStaging = stageBundleForHelper(bundlePath, safeName);
                if (sharedStaging != null) {
                    android.net.Uri bundleUri = null;
                    try {
                        bundleUri = androidx.core.content.FileProvider.getUriForFile(
                                this,
                                getPackageName() + ".helper_fileprovider",
                                new File(sharedStaging));
                        grantUriPermission(HelperPackage.PACKAGE, bundleUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) {
                        Log.e(TAG, "FileProvider URI build failed (bundle)", e);
                    }
                    final android.net.Uri finalUri = bundleUri;
                    final String finalStaging = sharedStaging;
                    boolean dispatched = abiRouter.dispatchBundleInstallToHelper(
                            finalStaging, finalUri, safeName, 0);
                    runOnUiThread(() -> Toast.makeText(this,
                            dispatched
                                    ? "Installing " + safeName + " (32-bit space)..."
                                    : getString(R.string.error_install_failed),
                            Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.error_install_failed),
                            Toast.LENGTH_SHORT).show());
                }
            }
        }

        if (installInMain) {
            XapkInstaller.Result result = xapkInstaller.install(bundlePath);
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
        }
    }

    /**
     * Copy the APK to external cache so the helper (different UID) can read it.
     * Returns absolute path, or null on failure.
     */
    private String stageForHelper(String apkPath, String packageName) {
        try {
            File extCache = getExternalCacheDir();
            if (extCache == null) return null;
            File shared = new File(extCache, "helper_stage");
            if (!shared.exists() && !shared.mkdirs()) return null;
            File dest = new File(shared, packageName + ".apk");
            try (InputStream in = new java.io.FileInputStream(apkPath);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            // World-readable so the helper UID can open it.
            //noinspection ResultOfMethodCallIgnored
            dest.setReadable(true, false);
            return dest.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "stageForHelper failed", e);
            return null;
        }
    }

    /**
     * Stage an XAPK/APKS/APKM bundle for the helper. Same mechanics as
     * {@link #stageForHelper(String, String)} but preserves the source
     * filename so helper's XapkInstaller sees a familiar extension.
     */
    private String stageBundleForHelper(String bundlePath, String displayName) {
        try {
            File extCache = getExternalCacheDir();
            if (extCache == null) return null;
            File shared = new File(extCache, "helper_stage");
            if (!shared.exists() && !shared.mkdirs()) return null;
            // Ensure extension survives into the helper so isBundleFile() works.
            String name = displayName != null ? sanitizeFilename(displayName) : "bundle.xapk";
            if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(xapk|apks|apkm)$")) {
                name = name + ".xapk";
            }
            File dest = new File(shared, System.currentTimeMillis() + "_" + name);
            try (InputStream in = new java.io.FileInputStream(bundlePath);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            //noinspection ResultOfMethodCallIgnored
            dest.setReadable(true, false);
            return dest.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "stageBundleForHelper failed", e);
            return null;
        }
    }

    private void promptInstallHelper(String triggeringAppName) {
        String msg = "To install " + triggeringAppName +
                " (a 32-bit game), LightBox-NG needs its helper APK. Install it now?";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("32-bit helper required")
                .setMessage(msg)
                .setPositiveButton("Install", (d, w) -> {
                    try {
                        if (HelperInstaller.isHelperAssetAvailable(this)) {
                            HelperInstaller.promptInstall(this);
                        } else {
                            Toast.makeText(this,
                                    "Helper APK not bundled in this build. " +
                                            "Download LightBox-NG-helper32.apk from the GitHub release and install it manually.",
                                    Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this,
                                "Could not prompt helper install: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
        java.util.Set<String> mainPackages = new java.util.HashSet<>();
        try {
            java.util.List<ClonedApp> mainApps = engineBridge.getInstalledApps();
            if (mainApps != null) {
                for (ClonedApp a : mainApps) {
                    if (a.getPackageName() != null) mainPackages.add(a.getPackageName());
                }
                clonedApps.addAll(mainApps);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cloned apps: " + e.getMessage(), e);
        }
        // Merge in 32-bit games installed inside the helper (Option A — one
        // unified library view). Soft-fail: if the helper isn't present or its
        // provider errors, we just show the 64-bit half.
        //
        // If the same package exists on BOTH sides (mixed-ABI install, e.g.
        // GameGuardian), we keep both rows and disambiguate their labels so
        // the user can tell which sandbox each instance belongs to and pick
        // the right one when targeting a specific game.
        try {
            if (HelperPackage.isInstalled(this)) {
                android.database.Cursor c = getContentResolver().query(
                        HelperPackage.INSTALLED_URI, null, null, null, null);
                if (c != null) {
                    try {
                        int pkgIdx = c.getColumnIndex("package_name");
                        int nameIdx = c.getColumnIndex("app_name");
                        while (c.moveToNext()) {
                            String pkg = pkgIdx >= 0 ? c.getString(pkgIdx) : null;
                            if (pkg == null || pkg.isEmpty()) continue;
                            String rawName = (nameIdx >= 0 && !c.isNull(nameIdx))
                                    ? c.getString(nameIdx) : pkg;
                            boolean dualInstall = mainPackages.contains(pkg);

                            // Helper row — always flagged for launch routing.
                            com.lightbox.app.model.ClonedApp helperRow =
                                    new com.lightbox.app.model.ClonedApp();
                            helperRow.setPackageName(pkg);
                            helperRow.setAppName(dualInstall
                                    ? rawName + " (32-bit space)"
                                    : rawName);
                            helperRow.setOwnedByHelper(true);
                            clonedApps.add(helperRow);

                            // If the package is ALSO in main, relabel the
                            // main-side row we already added so the user
                            // can tell them apart at a glance.
                            if (dualInstall) {
                                for (com.lightbox.app.model.ClonedApp a : clonedApps) {
                                    if (!a.isOwnedByHelper()
                                            && pkg.equals(a.getPackageName())) {
                                        a.setAppName(a.getAppName() + " (64-bit space)");
                                        break;
                                    }
                                }
                            }
                        }
                    } finally {
                        c.close();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to merge helper games: " + e.getMessage());
        }
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(clonedApps.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(clonedApps.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void launchClonedApp(ClonedApp app) {
        try {
            // Fast path: if the row came from the helper's InstalledGamesProvider,
            // dispatch directly. Main's Bcore does not have this package, so
            // any ABI inspection here would falsely conclude "no native libs"
            // and route to IN_PROCESS_MAIN — which is how Traffic Rider died
            // in M2.2: install worked, launch picked the wrong side.
            if (app.isOwnedByHelper()) {
                Log.i(TAG, "launchClonedApp: " + app.getPackageName()
                        + " is helper-owned, dispatching to helper directly");
                boolean launched = abiRouter.launchInHelper(app.getPackageName(), 0);
                if (!launched) {
                    Toast.makeText(this, getString(R.string.error_launch_failed),
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }

            // Main-owned virtual app: use the extracted native-lib dir,
            // not just the APK path — for Unity games the APK has no libs
            // and naive scanning says "pure Java".
            RealVirtualEngineBridge.VirtualPaths paths =
                    engineBridge.getVirtualPaths(app.getPackageName());
            boolean launched = abiRouter.launch(
                    app.getPackageName(),
                    paths != null ? paths.apkPath : null,
                    paths != null ? paths.nativeLibDir : null,
                    0);
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
                        // Route uninstall to whichever side owns the package.
                        // Helper-owned rows must go through the bridge so the
                        // helper's Bcore does the teardown — main's Bcore
                        // doesn't know about these packages.
                        abiRouter.dispatchUninstall(app.getPackageName(),
                                app.isOwnedByHelper(), 0);
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
