package com.lightbox.app.abi;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves the ABI of an APK by looking at its {@code lib/} entries. Used to
 * decide whether a virtual app must run in the arm64 main process or the
 * armeabi-v7a helper process.
 *
 * Hard constraint this class helps us respect: once an Android app process
 * has been forked from {@code zygote64}, it cannot {@code dlopen()} a 32-bit
 * shared object, and vice versa. There is no way around this in userland.
 * The only lever a non-root sandbox has is to pick which installed package
 * (= which pre-declared primaryCpuAbi) hosts each virtual game.
 */
public final class VirtualAbiResolver {

    private static final String TAG = "VirtualAbiResolver";

    /** What we learned about an APK's native library situation. */
    public static final class AbiInfo {
        public final String[] availableAbis;
        public final boolean has64Bit;
        public final boolean has32Bit;
        public final String source;

        AbiInfo(String[] availableAbis, boolean has64, boolean has32, String source) {
            this.availableAbis = availableAbis;
            this.has64Bit = has64;
            this.has32Bit = has32;
            this.source = source;
        }

        /** True when the APK only carries 32-bit libs. Must run in the v7a helper. */
        public boolean is32BitOnly() { return has32Bit && !has64Bit; }

        /** True when the APK only carries 64-bit libs. Must run in the v8a main. */
        public boolean is64BitOnly() { return has64Bit && !has32Bit; }

        /** True for both (rare, but happens with libs built for both). */
        public boolean isMixed() { return has64Bit && has32Bit; }

        /** True for pure-Java / no native code APKs. */
        public boolean hasNoNative() { return !has64Bit && !has32Bit; }

        @Override
        public String toString() {
            return "AbiInfo{available=" + String.join(",", availableAbis)
                    + ", 32=" + has32Bit + ", 64=" + has64Bit + ", src=" + source + "}";
        }
    }

    private VirtualAbiResolver() {}

    /**
     * Read the APK at {@code apkPath} and return what native ABIs it carries.
     * Never returns null. On IO failure returns an AbiInfo with both flags
     * false so the caller can fall back to host-default routing.
     */
    public static AbiInfo resolve(String apkPath) {
        if (apkPath == null) return empty("null-path");
        File f = new File(apkPath);
        if (!f.exists() || !f.canRead()) return empty("no-file");

        List<String> found = new ArrayList<>(4);
        boolean has64 = false, has32 = false;
        try (ZipFile zip = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                if (!name.startsWith("lib/") || !name.endsWith(".so")) continue;
                String[] parts = name.split("/");
                if (parts.length < 3) continue;
                String abi = parts[1];
                if (!found.contains(abi)) found.add(abi);
                if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) has64 = true;
                else if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi) || "x86".equals(abi)) has32 = true;
            }
        } catch (Exception ex) {
            Log.w(TAG, "resolve(" + apkPath + ") failed: " + ex.getMessage());
            return empty("io-error");
        }
        return new AbiInfo(found.toArray(new String[0]), has64, has32, "apk-lib-scan");
    }

    /**
     * Convenience: true when the APK at {@code apkPath} strictly requires
     * a 32-bit host process. Returns false for mixed and for pure-Java apps
     * (those run happily in the 64-bit main).
     */
    public static boolean needs32BitHost(String apkPath) {
        return resolve(apkPath).is32BitOnly();
    }

    /**
     * Peek into an XAPK / APKS / APKM bundle and return what ABIs any of its
     * inner APKs (base or config splits) carry. This is what makes ABI
     * routing work for Unity games: their base.apk has no .so files but
     * a {@code config.armeabi_v7a.apk} split does, and we MUST see that
     * split when deciding whether to route to the 32-bit helper.
     *
     * Looks two levels deep in the zip — one level for APK entries inside
     * the bundle, one level inside each APK's {@code lib/<abi>/} entries.
     */
    public static AbiInfo peekBundle(String bundlePath) {
        if (bundlePath == null) return empty("null-path");
        File f = new File(bundlePath);
        if (!f.exists() || !f.canRead()) return empty("no-file");

        List<String> found = new ArrayList<>(4);
        boolean has64 = false, has32 = false;
        try (ZipFile outer = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> outerEntries = outer.entries();
            while (outerEntries.hasMoreElements()) {
                ZipEntry outerEntry = outerEntries.nextElement();
                String outerName = outerEntry.getName();
                if (outerEntry.isDirectory()) continue;
                // Accept any APK entry, including nested "splits/config.*.apk".
                if (!outerName.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;

                // Fast-path hint: the split's filename often encodes its ABI,
                // e.g. config.armeabi_v7a.apk / config.arm64_v8a.apk. This
                // catches the common case without opening the inner APK.
                String lower = outerName.toLowerCase(Locale.ROOT);
                if (lower.contains("armeabi_v7a") || lower.contains("armeabi-v7a")) has32 = true;
                if (lower.contains("arm64_v8a") || lower.contains("arm64-v8a")) has64 = true;
                if (lower.contains("x86_64")) has64 = true;
                if (lower.contains("x86")) has32 = true;

                // Authoritative: stream the inner APK and look at its lib/.
                // Copy to a temp file because ZipInputStream doesn't support
                // nested random-access zip reads on all JREs.
                File tmp = File.createTempFile("inner_apk_", ".apk");
                try (InputStream in = outer.getInputStream(outerEntry);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (Exception ex) {
                    Log.w(TAG, "could not extract inner apk " + outerName + ": " + ex.getMessage());
                    tmp.delete();
                    continue;
                }

                try (ZipFile inner = new ZipFile(tmp)) {
                    Enumeration<? extends ZipEntry> innerEntries = inner.entries();
                    while (innerEntries.hasMoreElements()) {
                        String innerName = innerEntries.nextElement().getName();
                        if (!innerName.startsWith("lib/") || !innerName.endsWith(".so")) continue;
                        String[] parts = innerName.split("/");
                        if (parts.length < 3) continue;
                        String abi = parts[1];
                        if (!found.contains(abi)) found.add(abi);
                        if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) has64 = true;
                        else if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi) || "x86".equals(abi)) has32 = true;
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "could not scan inner apk lib/: " + ex.getMessage());
                } finally {
                    tmp.delete();
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "peekBundle(" + bundlePath + ") failed: " + ex.getMessage());
            return empty("io-error");
        }
        return new AbiInfo(found.toArray(new String[0]), has64, has32, "bundle-peek");
    }

    /**
     * Resolve ABI for an already-installed virtual app by inspecting its
     * extracted native library directory. This is the ONLY reliable path
     * post-install because Bcore expands libs out of the APK into
     * {@code /data/.../blackbox/data/app/<pkg>/lib/*.so}.
     *
     * Used by {@code AbiRouter.launch()} — at launch time we do not have
     * the original bundle, only the on-disk APK and lib dir.
     */
    public static AbiInfo resolveInstalled(String apkPath, String nativeLibDir) {
        // First, any info we can squeeze out of the APK alone.
        AbiInfo apk = (apkPath != null) ? resolve(apkPath) : empty("no-apk");
        boolean has64 = apk.has64Bit;
        boolean has32 = apk.has32Bit;
        List<String> found = new ArrayList<>();
        for (String a : apk.availableAbis) found.add(a);

        if (nativeLibDir != null) {
            File libDir = new File(nativeLibDir);
            // Case 1: flat layout — Bcore places extracted .so files directly
            // in the package's lib dir. Read the ELF class of any .so.
            File[] flat = libDir.listFiles((dir, name) -> name.endsWith(".so"));
            if (flat != null) {
                for (File so : flat) {
                    boolean is64 = isElf64(so);
                    if (is64) { has64 = true; if (!found.contains("arm64-v8a")) found.add("arm64-v8a"); }
                    else       { has32 = true; if (!found.contains("armeabi-v7a")) found.add("armeabi-v7a"); }
                }
            }
            // Case 2: abi-subdir layout (less common in virtualized apps).
            for (String abi : new String[]{"arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64"}) {
                File sub = new File(libDir, abi);
                if (sub.isDirectory()) {
                    File[] sos = sub.listFiles((d, n) -> n.endsWith(".so"));
                    if (sos != null && sos.length > 0) {
                        if (!found.contains(abi)) found.add(abi);
                        if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) has64 = true;
                        else has32 = true;
                    }
                }
            }
        }
        return new AbiInfo(found.toArray(new String[0]), has64, has32, "apk+libdir");
    }

    /**
     * Quick ELF ABI check for a single .so file. Kept for completeness; not
     * on the hot path but useful when an APK has been extracted already.
     */
    public static boolean isElf64(File soFile) {
        try (InputStream in = new FileInputStream(soFile)) {
            byte[] header = new byte[5];
            if (in.read(header) < 5) return false;
            // 0x7f 'E' 'L' 'F', byte 4 = EI_CLASS (1=ELF32, 2=ELF64)
            return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L'
                    && header[3] == 'F' && header[4] == 2;
        } catch (Exception e) {
            return false;
        }
    }

    private static AbiInfo empty(String why) {
        return new AbiInfo(new String[0], false, false, why);
    }
}
