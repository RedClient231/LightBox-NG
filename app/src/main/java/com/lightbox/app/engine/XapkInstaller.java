package com.lightbox.app.engine;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;

/**
 * Extracts and installs XAPK / APKS / APKM bundles.
 *
 * Supported layouts:
 *   - XAPK (APKPure): ZIP with manifest.json + base APK + optional splits +
 *     optional .obb expansions.
 *   - APKS (bundletool): ZIP with splits/base-master.apk + splits/base-*.apk.
 *   - APKM (APKMirror): same shape as XAPK.
 *
 * Flow:
 *   1. Extract the ZIP into a staging directory (zip-slip guarded).
 *   2. Pick the base APK — prefer an APK whose parsed packageName matches
 *      manifest.json, otherwise the largest non-config APK.
 *   3. Install the base via the engine.
 *   4. Copy compatible split APKs to <virtualAppDir>/splits/ and persist
 *      their paths in BPackageSettings.splitCodePaths (NOT install them
 *      as independent packages — the engine's PackageParser rejects them).
 *   5. Extract native libs from ABI-compatible splits.
 *   6. Copy Unity IL2CPP metadata fallback from any split that contains it.
 *   7. Copy .obb expansions and Android/data payloads to virtual storage.
 *   8. Clean up the staging directory.
 *
 * CRITICAL: Do NOT call installPackageAsUser / installApk for config/split
 * APKs. They are not standalone APKs and will fail with
 * "getPackageArchiveInfo error — Expected base APK, but found split".
 */
public class XapkInstaller {

    private static final String TAG = "XapkInstaller";

    private final Context context;
    private final RealVirtualEngineBridge engineBridge;

    public XapkInstaller(Context context, RealVirtualEngineBridge engineBridge) {
        this.context = context;
        this.engineBridge = engineBridge;
    }

    public static final class Result {
        public final boolean success;
        public final String packageName;
        public final String appName;
        public final String errorMessage;

        private Result(boolean success, String packageName, String appName, String errorMessage) {
            this.success = success;
            this.packageName = packageName;
            this.appName = appName;
            this.errorMessage = errorMessage;
        }

        public static Result success(String packageName, String appName) {
            return new Result(true, packageName, appName, null);
        }

        public static Result failure(String error) {
            return new Result(false, null, null, error);
        }
    }

    /** True when the filename looks like an XAPK/APKS/APKM bundle. */
    public static boolean isBundleFile(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xapk") || lower.endsWith(".apks")
                || lower.endsWith(".apkm");
    }

    /**
     * Sniff the first 4 bytes for the ZIP magic "PK\x03\x04".
     */
    public static boolean looksLikeZip(InputStream in) {
        try {
            byte[] magic = new byte[4];
            int read = 0;
            while (read < 4) {
                int n = in.read(magic, read, 4 - read);
                if (n < 0) break;
                read += n;
            }
            return read == 4 && magic[0] == 0x50 && magic[1] == 0x4B
                    && magic[2] == 0x03 && magic[3] == 0x04;
        } catch (Exception e) {
            return false;
        }
    }

    /** Install a bundle file on disk. Runs on caller's thread. */
    public Result install(String bundlePath) {
        File bundleFile = new File(bundlePath);
        if (!bundleFile.exists()) {
            return Result.failure("Bundle file not found: " + bundlePath);
        }

        File stagingDir = new File(context.getFilesDir(),
                "xapk_staging/" + System.currentTimeMillis());
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory()) {
            return Result.failure("Could not create staging dir: " + stagingDir);
        }

        try {
            extractZip(bundleFile, stagingDir);

            // ── Parse manifest.json ──
            File manifest = findFileByName(stagingDir, "manifest.json");
            String declaredPackage = null;
            String declaredAppName = null;
            List<String> declaredSplits = new ArrayList<>();
            List<String> declaredObbs = new ArrayList<>();

            if (manifest != null) {
                try {
                    JSONObject root = new JSONObject(readAll(manifest));
                    declaredPackage = root.optString("package_name",
                            root.optString("package", null));
                    declaredAppName = root.optString("name",
                            root.optString("label", null));
                    if (root.has("split_apks")) {
                        JSONArray arr = root.getJSONArray("split_apks");
                        for (int i = 0; i < arr.length(); i++) {
                            String f = arr.getJSONObject(i).optString("file", null);
                            if (f != null) declaredSplits.add(f);
                        }
                    }
                    if (root.has("expansions")) {
                        JSONArray arr = root.getJSONArray("expansions");
                        for (int i = 0; i < arr.length(); i++) {
                            String f = arr.getJSONObject(i).optString("file", null);
                            if (f != null) declaredObbs.add(f);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "manifest.json parse failed, using content scan: "
                            + e.getMessage());
                }
            }

            // ── Identify APKs ──
            List<File> allApks = collectFilesBySuffix(stagingDir, ".apk");
            if (allApks.isEmpty()) {
                return Result.failure("No APK files inside bundle");
            }

            File baseApk = pickBaseApk(allApks, declaredPackage);
            if (baseApk == null) {
                return Result.failure("Could not identify the base APK in bundle");
            }

            List<File> splitApks = new ArrayList<>();
            if (!declaredSplits.isEmpty()) {
                for (String rel : declaredSplits) {
                    File f = resolveRel(stagingDir, rel);
                    if (f != null && f.exists() && !f.equals(baseApk)) {
                        splitApks.add(f);
                    }
                }
            } else {
                for (File f : allApks) {
                    if (f.equals(baseApk)) continue;
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.startsWith("config.") || n.startsWith("split_config.")
                            || n.startsWith("base-")) {
                        splitApks.add(f);
                    }
                }
            }

            List<File> obbFiles = new ArrayList<>();
            if (!declaredObbs.isEmpty()) {
                for (String rel : declaredObbs) {
                    File f = resolveRel(stagingDir, rel);
                    if (f != null && f.exists()) obbFiles.add(f);
                }
            } else {
                obbFiles.addAll(collectFilesBySuffix(stagingDir, ".obb"));
            }

            Log.i(TAG, "Installing bundle — base=" + baseApk.getName()
                    + " splits=" + splitApks.size() + " obbs=" + obbFiles.size());

            // ── Step 1: Install base APK ──
            if (!engineBridge.installApk(baseApk.getAbsolutePath())) {
                return Result.failure("Base APK install rejected by engine");
            }

            PackageMetadataReader.ApkInfo info =
                    PackageMetadataReader.readApkInfo(context, baseApk.getAbsolutePath());
            String packageName = info != null ? info.packageName : declaredPackage;
            String appName = info != null && info.appName != null
                    ? info.appName
                    : (declaredAppName != null ? declaredAppName : packageName);
            if (packageName == null) {
                return Result.failure("Bundle installed but package name is unknown");
            }

            // ── Step 2: Persist split APKs under <virtualAppDir>/splits/ ──
            File virtualAppDir = BEnvironment.getAppDir(packageName);
            File splitDir = new File(virtualAppDir, "splits");
            if (!splitDir.exists() && !splitDir.mkdirs()) {
                Log.w(TAG, "Could not create split dir: " + splitDir);
            }

            String[] deviceAbis = Build.SUPPORTED_ABIS != null
                    ? Build.SUPPORTED_ABIS : new String[0];

            List<String> persistedSplitPaths = new ArrayList<>();
            List<String> persistedSplitNames = new ArrayList<>();

            for (File split : splitApks) {
                String n = split.getName().toLowerCase(Locale.ROOT);
                boolean configSplit = n.startsWith("config.")
                        || n.startsWith("split_config.")
                        || n.contains(".config.");

                // Select only compatible splits
                if (configSplit && !isCompatibleSplit(split, deviceAbis)) {
                    Log.i(TAG, "Skipping incompatible split: " + split.getName());
                    continue;
                }

                // Copy split to persistent dir
                File dst = new File(splitDir, sanitizeSplitFileName(split.getName()));
                try {
                    copyFile(split, dst);
                    persistedSplitPaths.add(dst.getAbsolutePath());
                    persistedSplitNames.add(split.getName());
                    Log.i(TAG, "Persisted split path: " + dst.getAbsolutePath());
                } catch (Exception e) {
                    Log.w(TAG, "Failed to persist split " + split.getName()
                            + ": " + e.getMessage());
                    // For ABI splits, this is critical
                    if (isAbiSplit(n)) {
                        return Result.failure("Failed to persist ABI split: "
                                + split.getName());
                    }
                    // For locale/density splits, continue
                    continue;
                }

                // Extract native libs from this split
                if (configSplit) {
                    int extracted = extractNativeLibsFromConfigSplit(split, packageName);
                    Log.i(TAG, "Extracted " + extracted + " .so from config split: "
                            + split.getName());
                }

                // Unity IL2CPP metadata fallback
                copyUnityIl2CppMetadataIfPresent(dst, packageName);
            }

            // ── Step 3: Persist split paths in BPackageSettings ──
            if (!persistedSplitPaths.isEmpty()) {
                try {
                    BPackageManagerService pmService = BPackageManagerService.get();
                    if (pmService != null) {
                        BPackageSettings ps = pmService.getBPackageSetting(packageName);
                        if (ps != null) {
                            ps.splitCodePaths = new ArrayList<>(persistedSplitPaths);
                            ps.splitNames = new ArrayList<>(persistedSplitNames);
                            ps.save();
                            Log.i(TAG, "Saved " + persistedSplitPaths.size()
                                    + " split paths for " + packageName);
                        } else {
                            Log.w(TAG, "BPackageSettings not found for " + packageName
                                    + "; split paths not persisted");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to persist split paths in BPackageSettings: "
                            + e.getMessage());
                }
            }

            // ── Step 4: Copy OBB files to virtual storage ──
            if (!obbFiles.isEmpty()) {
                copyObbs(packageName, obbFiles);
            }

            // ── Step 5: Copy Android/data and Android/obb from XAPK ──
            extractAndroidPayloads(bundleFile, packageName);

            return Result.success(packageName, appName);
        } catch (Exception e) {
            Log.e(TAG, "XAPK install failed", e);
            return Result.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            deleteRecursive(stagingDir);
        }
    }

    // ────────────────────────────────────────────────────────────
    // Split compatibility selection
    // ────────────────────────────────────────────────────────────

    /**
     * Check if a split APK is compatible with the current device.
     * ABI splits must match the device's primary ABI.
     * Locale and density splits are always considered compatible.
     */
    private boolean isCompatibleSplit(File splitApk, String[] deviceAbis) {
        String name = splitApk.getName().toLowerCase(Locale.ROOT);

        // Check if this is an ABI split
        for (String abi : deviceAbis) {
            String abiLabel = abi.toLowerCase(Locale.ROOT).replace("-", "_");
            if (name.contains(abiLabel)) {
                return true;
            }
        }

        // If it's an ABI split but didn't match, skip it
        if (isAbiSplit(name)) {
            return false;
        }

        // Locale, density, and other config splits are compatible
        return true;
    }

    private boolean isAbiSplit(String name) {
        return name.contains("arm64_v8a") || name.contains("armeabi_v7a")
                || name.contains("x86_64") || name.contains("x86")
                || name.contains("mips") || name.contains("arm64-v8a")
                || name.contains("armeabi-v7a");
    }

    private String sanitizeSplitFileName(String name) {
        // Replace characters that might cause issues in filenames
        return name.replace("..", "").replace("/", "_").replace("\\", "_");
    }

    // ────────────────────────────────────────────────────────────
    // Native library extraction from config splits
    // ────────────────────────────────────────────────────────────

    private int extractNativeLibsFromConfigSplit(File splitApk, String packageName) {
        File virtualLibDir;
        try {
            File hostFilesDir = context.getFilesDir();
            File hostDataRoot = hostFilesDir.getParentFile();
            virtualLibDir = new File(hostDataRoot,
                    "blackbox/data/app/" + packageName + "/lib");
            if (!virtualLibDir.exists() && !virtualLibDir.mkdirs()) {
                Log.w(TAG, "Could not create virtual lib dir: " + virtualLibDir);
                return 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve virtual lib dir: " + e.getMessage());
            return 0;
        }

        String[] deviceAbis = Build.SUPPORTED_ABIS != null
                ? Build.SUPPORTED_ABIS : new String[0];
        if (deviceAbis.length == 0) return 0;

        int written = 0;
        try (ZipFile zf = new ZipFile(splitApk)) {
            for (String abi : deviceAbis) {
                int thisPass = extractLibsForAbi(zf, abi.toLowerCase(Locale.ROOT),
                        virtualLibDir);
                if (thisPass > 0) {
                    written += thisPass;
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read split " + splitApk.getName()
                    + ": " + e.getMessage());
        }
        return written;
    }

    private int extractLibsForAbi(ZipFile zf, String abi, File targetDir)
            throws Exception {
        String prefix = "lib/" + abi + "/";
        String targetCanonical = targetDir.getCanonicalPath();
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.isDirectory()) continue;
            String name = e.getName();
            if (!name.toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
            if (!name.toLowerCase(Locale.ROOT).endsWith(".so")) continue;

            String basename = name.substring(name.lastIndexOf('/') + 1);
            if (basename.isEmpty() || basename.contains("..")) continue;

            File out = new File(targetDir, basename);
            if (!out.getCanonicalPath().startsWith(targetCanonical)) continue;

            try (InputStream in = zf.getInputStream(e);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
            }
            out.setReadable(true, false);
            out.setExecutable(true, false);
            count++;
        }
        return count;
    }

    // ────────────────────────────────────────────────────────────
    // Unity IL2CPP metadata fallback (Fix 5)
    // ────────────────────────────────────────────────────────────

    /**
     * If a split APK contains Unity IL2CPP metadata under any path ending
     * in global-metadata.dat, copy it to the virtual external files directory
     * where Unity expects to find it at runtime.
     *
     * This fixes Bus Simulator Indonesia and other Unity IL2CPP games that
     * crash with: "IL2CPP ERROR: Could not open .../il2cpp/Metadata/global-metadata.dat"
     */
    private void copyUnityIl2CppMetadataIfPresent(File splitApk, String packageName) {
        final String metadataSuffix = "global-metadata.dat";
        try (ZipFile zip = new ZipFile(splitApk)) {
            ZipEntry match = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.endsWith(metadataSuffix)) {
                    match = e;
                    break;
                }
            }
            if (match == null) return;

            File filesDir = getVirtualExternalFilesDir(packageName);
            File metadataDir = new File(filesDir, "il2cpp/Metadata");
            if (!metadataDir.exists() && !metadataDir.mkdirs()) {
                Log.w(TAG, "Could not create IL2CPP metadata dir: " + metadataDir);
                return;
            }
            File out = new File(metadataDir, "global-metadata.dat");

            try (InputStream in = zip.getInputStream(match);
                 OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                byte[] buf = new byte[256 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    os.write(buf, 0, read);
                }
            }
            Log.i(TAG, "Copied Unity IL2CPP metadata from " + splitApk.getName()
                    + " to " + out.getAbsolutePath());
        } catch (IOException e) {
            Log.w(TAG, "Unable to scan/copy Unity metadata from split: " + splitApk, e);
        }
    }

    /**
     * Resolve the virtual external files directory for a package.
     * Maps to the same virtual storage root LightBox-NG exposes to apps.
     * Uses BEnvironment helpers when available; falls back to the path
     * pattern from the crash log.
     */
    private File getVirtualExternalFilesDir(String packageName) {
        try {
            // Use BEnvironment if available
            File dataDir = BEnvironment.getDataDir(packageName, 0);
            // Virtual external storage pattern:
            // <host-external>/Android/data/<hostPkg>/files/blackbox/storage/emulated/0/Android/data/<pkg>/files
            File hostExternal = context.getExternalFilesDir(null);
            if (hostExternal != null) {
                File androidData = hostExternal.getParentFile().getParentFile();
                File virtualStorage = new File(androidData,
                        context.getPackageName() + "/files/blackbox/storage/emulated/0/Android/data/"
                                + packageName + "/files");
                if (!virtualStorage.exists()) {
                    virtualStorage.mkdirs();
                }
                return virtualStorage;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve virtual external files dir: " + e.getMessage());
        }
        // Fallback
        return context.getExternalFilesDir(null);
    }

    // ────────────────────────────────────────────────────────────
    // Android/data and Android/obb payloads from XAPK (Fix 6)
    // ────────────────────────────────────────────────────────────

    /**
     * Extract Android/obb/<pkg>/** and Android/data/<pkg>/** entries
     * from the XAPK ZIP and copy them to the virtual storage paths.
     */
    private void extractAndroidPayloads(File xapkFile, String packageName) {
        try {
            File hostExternal = context.getExternalFilesDir(null);
            if (hostExternal == null) return;
            File androidRoot = hostExternal.getParentFile().getParentFile();
            File virtualStorageRoot = new File(androidRoot,
                    context.getPackageName() + "/files/blackbox/storage/emulated/0");

            long totalCopied = 0;
            try (ZipFile zf = new ZipFile(xapkFile)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();

                    // Match Android/obb/<packageName>/** or Android/data/<packageName>/**
                    boolean isObb = name.startsWith("Android/obb/" + packageName + "/");
                    boolean isData = name.startsWith("Android/data/" + packageName + "/");

                    if (!isObb && !isData) continue;

                    // Validate: prevent path traversal
                    File outFile = safeResolve(virtualStorageRoot, name);
                    if (outFile == null) continue;

                    outFile.getParentFile().mkdirs();
                    try (InputStream in = zf.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[32 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            totalCopied += n;
                        }
                    }
                    Log.d(TAG, "Extracted XAPK payload: " + name);
                }
            }
            if (totalCopied > 0) {
                Log.i(TAG, "Total XAPK Android payload copied: " + totalCopied + " bytes");
            }
        } catch (Exception e) {
            Log.w(TAG, "XAPK Android payload extraction failed (non-fatal): " + e.getMessage());
        }
    }

    private static File safeResolve(File root, String relativeName) throws IOException {
        String normalized = relativeName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")) {
            return null;
        }
        File out = new File(root, normalized);
        String rootPath = root.getCanonicalPath() + File.separator;
        String outPath = out.getCanonicalPath();
        if (!outPath.startsWith(rootPath)) {
            return null;
        }
        return out;
    }

    // ────────────────────────────────────────────────────────────
    // ZIP extraction and file utilities
    // ────────────────────────────────────────────────────────────

    private void extractZip(File src, File destRoot) throws Exception {
        String destRootCanonical = destRoot.getCanonicalPath();
        try (ZipFile zf = new ZipFile(src)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/")) continue;
                File out = new File(destRoot, name);
                if (!out.getCanonicalPath().startsWith(destRootCanonical)) continue;
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
        }
    }

    private File pickBaseApk(List<File> allApks, String manifestPkg) {
        for (File f : allApks) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.equals("base.apk") || n.equals("base-master.apk")) return f;
        }
        File best = null;
        long bestSize = -1;
        for (File f : allApks) {
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (lower.startsWith("config.") || lower.startsWith("split_config.")
                    || lower.contains(".config.")) continue;
            if (manifestPkg != null) {
                PackageMetadataReader.ApkInfo info =
                        PackageMetadataReader.readApkInfo(context, f.getAbsolutePath());
                if (info != null && manifestPkg.equals(info.packageName)) {
                    if (f.length() > bestSize) { best = f; bestSize = f.length(); }
                }
            } else {
                if (f.length() > bestSize) { best = f; bestSize = f.length(); }
            }
        }
        if (best == null) {
            for (File f : allApks) {
                if (f.length() > bestSize) { best = f; bestSize = f.length(); }
            }
        }
        return best;
    }

    private File resolveRel(File root, String rel) {
        File direct = new File(root, rel);
        if (direct.exists()) return direct;
        return findFileByName(root, new File(rel).getName());
    }

    private File findFileByName(File dir, String name) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File c : children) {
            if (c.isFile() && c.getName().equals(name)) return c;
            if (c.isDirectory()) {
                File found = findFileByName(c, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private List<File> collectFilesBySuffix(File dir, String suffix) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] children = dir.listFiles();
        if (children == null) return out;
        String lower = suffix.toLowerCase(Locale.ROOT);
        for (File c : children) {
            if (c.isFile() && c.getName().toLowerCase(Locale.ROOT).endsWith(lower)) {
                out.add(c);
            } else if (c.isDirectory()) {
                out.addAll(collectFilesBySuffix(c, suffix));
            }
        }
        return out;
    }

    private void copyObbs(String packageName, List<File> obbFiles) {
        try {
            File extFilesDir = context.getExternalFilesDir(null);
            if (extFilesDir == null) return;
            File android = extFilesDir.getParentFile().getParentFile();

            // Copy to real OBB dir
            File realObb = new File(android, "obb/" + packageName);
            if (!realObb.exists() && !realObb.mkdirs()) return;
            for (File obb : obbFiles) {
                File dst = new File(realObb, obb.getName());
                try { copyFile(obb, dst); } catch (Exception ignored) {}
                Log.i(TAG, "Copied OBB: " + obb.getName() + " -> " + dst.getAbsolutePath());
            }

            // Also copy to virtual storage OBB path
            File virtualStorageRoot = new File(android,
                    context.getPackageName() + "/files/blackbox/storage/emulated/0");
            File virtualObb = new File(virtualStorageRoot, "Android/obb/" + packageName);
            if (!virtualObb.exists()) virtualObb.mkdirs();
            for (File obb : obbFiles) {
                File dst = new File(virtualObb, obb.getName());
                try { copyFile(obb, dst); } catch (Exception ignored) {}
                Log.i(TAG, "Copied OBB to virtual: " + obb.getName()
                        + " -> " + dst.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "OBB copy failed (non-fatal): " + e.getMessage());
        }
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n));
        }
        return sb.toString();
    }

    private static void deleteRecursive(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        fileOrDir.delete();
    }
}
