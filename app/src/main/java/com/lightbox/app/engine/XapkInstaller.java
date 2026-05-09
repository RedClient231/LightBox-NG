package com.lightbox.app.engine;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
 *   4. Install feature splits via the engine; skip config splits (the engine
 *      rejects those as non-base APKs).
 *   5. Copy .obb expansions to /sdcard/Android/obb/<package>/ (best-effort,
 *      requires MANAGE_EXTERNAL_STORAGE on Android 11+).
 *   6. Clean up the staging directory.
 *
 * Side effects / edge cases handled:
 *   - Zip slip: verify canonical path is within destRoot before writing.
 *   - Nested folder layout: recursive walks for manifest/APK/OBB location.
 *   - Missing manifest.json: falls back to APK filename heuristics + size.
 *   - Wrong-arch config splits in a universal APK install: skipped rather
 *     than passed to the engine's PackageParser which would reject them.
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
     * APKs are ZIPs too, so a positive result must be combined with a name/
     * MIME hint before routing to this installer.
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

            for (File split : splitApks) {
                String n = split.getName().toLowerCase(Locale.ROOT);
                boolean configSplit = n.startsWith("config.")
                        || n.startsWith("split_config.")
                        || n.contains(".config.");
                if (configSplit) {
                    // APKPure / bundletool XAPKs put the native .so files in
                    // config splits named like 'config.arm64_v8a.apk' — the
                    // base.apk usually has NO lib/ folder at all. The engine's
                    // PackageParser rejects config splits as base APKs, so we
                    // can't install them; but we MUST still extract their
                    // per-ABI lib/<abi>/*.so into the virtual app's lib dir
                    // or the game dlopen()s a missing library on launch and
                    // crashes (libmain.so / libgame.so / libunity.so etc.).
                    int extracted = extractNativeLibsFromConfigSplit(
                            split, packageName);
                    Log.i(TAG, "Extracted " + extracted + " .so from config split: "
                            + split.getName());
                    continue;
                }
                try {
                    boolean ok = engineBridge.installApk(split.getAbsolutePath());
                    Log.i(TAG, "Split install " + split.getName() + " -> " + ok);
                } catch (Exception e) {
                    Log.w(TAG, "Split install failed for " + split.getName()
                            + " (non-fatal): " + e.getMessage());
                }
            }

            if (!obbFiles.isEmpty()) {
                copyObbs(packageName, obbFiles);
            }

            return Result.success(packageName, appName);
        } catch (Exception e) {
            Log.e(TAG, "XAPK install failed", e);
            return Result.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            deleteRecursive(stagingDir);
        }
    }

    /**
     * Extract the current device's native libraries from a config/split APK
     * into the virtual app's lib directory, flat (no ABI subdir) — the layout
     * the Bcore engine's native loader expects.
     *
     * APKPure / bundletool XAPKs package native libs in per-ABI split APKs
     * (e.g. {@code config.arm64_v8a.apk}) whose {@code base.apk} contains no
     * {@code lib/} folder. The engine rejects config splits as base APKs, so
     * we can't install them normally — but we can still unzip their lib
     * entries and drop the files where the loader will find them at launch.
     *
     * <p>Selection policy:
     * <ul>
     *   <li>Read every {@code lib/<abi>/*.so} entry.</li>
     *   <li>Prefer entries whose {@code <abi>} matches the device's primary
     *       ABI ({@code Build.SUPPORTED_ABIS[0]}).</li>
     *   <li>Fall back to any other supported ABI the device reports, in the
     *       order the system prefers them.</li>
     *   <li>Never extract an arch the device can't execute (e.g. x86 on an
     *       arm device) — doing so would either waste disk or (worse)
     *       confuse a linker that picked the wrong file.</li>
     * </ul>
     *
     * @return number of .so files actually written
     */
    private int extractNativeLibsFromConfigSplit(File splitApk, String packageName) {
        // The engine's virtual lib dir: <host-filesDir-parent>/blackbox/data/app/<pkg>/lib/
        // This matches the path the logcat shows the native loader using:
        //   /data/user/0/com.lightbox.app/blackbox/data/app/<pkg>/lib/libfoo.so
        File virtualLibDir;
        try {
            File hostFilesDir = context.getFilesDir();
            File hostDataRoot = hostFilesDir.getParentFile(); // /data/user/0/<host>
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
                ? Build.SUPPORTED_ABIS
                : new String[0];
        if (deviceAbis.length == 0) {
            Log.w(TAG, "Build.SUPPORTED_ABIS is empty; cannot pick an ABI");
            return 0;
        }

        int written = 0;
        try (ZipFile zf = new ZipFile(splitApk)) {
            // Try each ABI in preference order; stop once we find a matching
            // ABI inside the split. Most config splits carry exactly one ABI
            // anyway, but base-master.apk variants may carry several.
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

    /**
     * Copy every {@code lib/<abi>/*.so} entry out of {@code zf} into
     * {@code targetDir} flat (basename only). Returns the count written.
     * Zip-slip-guarded.
     */
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

            // Basename only — the engine's loader expects a flat lib/ dir,
            // matching the layout its own CopyExecutor produces for
            // single-APK installs.
            String basename = name.substring(name.lastIndexOf('/') + 1);
            if (basename.isEmpty() || basename.contains("..")) {
                Log.w(TAG, "Skipping suspicious lib entry: " + name);
                continue;
            }

            File out = new File(targetDir, basename);
            if (!out.getCanonicalPath().startsWith(targetCanonical)) {
                Log.w(TAG, "Skipping zip-slip lib entry: " + name);
                continue;
            }

            try (InputStream in = zf.getInputStream(e);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
            }
            // Make readable+executable for the app's UID. Some Android
            // versions require exec bit before the linker will mmap an
            // executable segment from the file.
            //noinspection ResultOfMethodCallIgnored
            out.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            out.setExecutable(true, false);
            count++;
        }
        return count;
    }

    private void extractZip(File src, File destRoot) throws Exception {
        String destRootCanonical = destRoot.getCanonicalPath();
        try (ZipFile zf = new ZipFile(src)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/")) {
                    Log.w(TAG, "Skipping suspicious zip entry: " + name);
                    continue;
                }
                File out = new File(destRoot, name);
                if (!out.getCanonicalPath().startsWith(destRootCanonical)) {
                    Log.w(TAG, "Skipping path-traversal entry: " + name);
                    continue;
                }
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
            File target = new File(android, "obb/" + packageName);
            if (!target.exists() && !target.mkdirs()) return;
            for (File obb : obbFiles) {
                File dst = new File(target, obb.getName());
                try (FileInputStream in = new FileInputStream(obb);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Log.i(TAG, "Copied OBB: " + obb.getName() + " -> " + dst.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "OBB copy failed (non-fatal): " + e.getMessage());
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
