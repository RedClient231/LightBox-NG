package com.lightbox.app.abi;

import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
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
     * Quick ELF ABI check for a single .so file. Kept for completeness; not
     * on the hot path but useful when an APK has been extracted already.
     */
    public static boolean isElf64(File soFile) {
        try (InputStream in = new java.io.FileInputStream(soFile)) {
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
