package com.lightbox.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Single source of truth for the "virtual root" user toggle.
 *
 * This preference controls whether apps running inside LightBox-NG's sandbox
 * see a rooted environment. It has ZERO effect on the host device; the host
 * device's actual root state is never touched.
 *
 * The native virtual-root layer (to be wired into Bcore's hook system in a
 * subsequent commit) reads this preference at virtual-process startup and
 * installs fake responses for:
 *   - stat / access on /system/bin/su, /system/xbin/su, /sbin/su, /magisk
 *   - exec("su") / Runtime.exec("su")
 *   - __system_property_get("ro.build.tags"), "ro.debuggable", "ro.secure"
 *   - Common Magisk files, SuperSU package checks
 *
 * Design note: preference is read at virtual-process spawn, not per-call.
 * Toggling while a virtualized game is running will NOT affect that game —
 * the user must stop and relaunch it.
 */
public final class VirtualRootPrefs {
    private static final String PREFS = "lightbox_ng_settings";
    private static final String KEY_VIRTUAL_ROOT = "virtual_root_enabled";

    private VirtualRootPrefs() {}

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_VIRTUAL_ROOT, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_VIRTUAL_ROOT, enabled).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
