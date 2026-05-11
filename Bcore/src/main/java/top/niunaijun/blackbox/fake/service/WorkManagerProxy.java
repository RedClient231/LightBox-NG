package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Proxy for androidx.work.WorkManager.
 *
 * Some virtual apps (e.g. Bus Simulator Indonesia, PUBG Mobile) bundle
 * WorkManager or reference it transitively. When the class is absent from
 * the virtual app's classpath, the engine's ClassLoader throws
 * ClassNotFoundException, which previously caused cascading failures.
 *
 * This proxy now handles the missing-class case gracefully:
 *  - getWho() returns null when WorkManager is not available.
 *  - All proxied methods check for null 'who' and return safe no-op values.
 *  - No crash, no warning spam — just silent graceful degradation.
 */
public class WorkManagerProxy extends ClassInvocationStub {
    public static final String TAG = "WorkManagerProxy";

    /** Cached flag: true if WorkManager class is loadable. */
    private static volatile Boolean sWorkManagerAvailable = null;

    public WorkManagerProxy() {
        super();
    }

    /**
     * Check if androidx.work.WorkManager is available in the current
     * classloader. Result is cached after first check.
     */
    private static boolean isWorkManagerAvailable() {
        if (sWorkManagerAvailable != null) return sWorkManagerAvailable;
        try {
            Class.forName("androidx.work.WorkManager");
            sWorkManagerAvailable = true;
        } catch (ClassNotFoundException e) {
            sWorkManagerAvailable = false;
        }
        return sWorkManagerAvailable;
    }

    @Override
    protected Object getWho() {
        if (!isWorkManagerAvailable()) {
            // WorkManager not in classpath — return null, proxied methods
            // will handle this gracefully.
            Slog.d(TAG, "WorkManager not available in virtual app classpath; "
                    + "returning null proxy target");
            return null;
        }
        try {
            Context context = BlackBoxCore.getContext();
            if (context != null) {
                Class<?> workManagerClass = Class.forName("androidx.work.WorkManager");
                Method getInstanceMethod = workManagerClass.getMethod("getInstance", Context.class);
                return getInstanceMethod.invoke(null, context);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get WorkManager instance", e);
        }
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        // No-op
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // ── Helper: check if the real 'who' target is available ──
    private static boolean isWhoAvailable(Object who) {
        return who != null;
    }

    // ── Helper: safe method invoke with null-check ──
    private static Object safeInvoke(Object who, Method method, Object[] args,
                                      String methodName) throws Throwable {
        if (!isWhoAvailable(who)) {
            Slog.d(TAG, methodName + ": WorkManager unavailable, returning no-op");
            return null;
        }
        return method.invoke(who, args);
    }

    @ProxyMethod("enqueue")
    public static class Enqueue extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return safeInvoke(who, method, args, "enqueue");
            } catch (Exception e) {
                Slog.w(TAG, "enqueue() failed, returning null", e);
                return null;
            }
        }
    }

    @ProxyMethod("enqueueUniqueWork")
    public static class EnqueueUniqueWork extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return safeInvoke(who, method, args, "enqueueUniqueWork");
            } catch (Exception e) {
                Slog.w(TAG, "enqueueUniqueWork() failed, returning null", e);
                return null;
            }
        }
    }

    @ProxyMethod("enqueueUniquePeriodicWork")
    public static class EnqueueUniquePeriodicWork extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return safeInvoke(who, method, args, "enqueueUniquePeriodicWork");
            } catch (Exception e) {
                Slog.w(TAG, "enqueueUniquePeriodicWork() failed, returning null", e);
                return null;
            }
        }
    }

    @ProxyMethod("cancelAllWork")
    public static class CancelAllWork extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return safeInvoke(who, method, args, "cancelAllWork");
            } catch (Exception e) {
                Slog.w(TAG, "cancelAllWork() failed, returning null", e);
                return null;
            }
        }
    }

    @ProxyMethod("cancelWorkById")
    public static class CancelWorkById extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return safeInvoke(who, method, args, "cancelWorkById");
            } catch (Exception e) {
                Slog.w(TAG, "cancelWorkById() failed, returning null", e);
                return null;
            }
        }
    }

    @ProxyMethod("getWorkInfos")
    public static class GetWorkInfos extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (!isWhoAvailable(who)) {
                    Slog.d(TAG, "getWorkInfos: WorkManager unavailable, returning empty list");
                    return java.util.Collections.emptyList();
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "getWorkInfos() failed, returning empty list", e);
                return java.util.Collections.emptyList();
            }
        }
    }

    @ProxyMethod("getWorkInfoById")
    public static class GetWorkInfoById extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return safeInvoke(who, method, args, "getWorkInfoById");
            } catch (Exception e) {
                Slog.w(TAG, "getWorkInfoById() failed, returning null", e);
                return null;
            }
        }
    }

    @ProxyMethod("cancelAllWorkByTag")
    public static class CancelAllWorkByTag extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return safeInvoke(who, method, args, "cancelAllWorkByTag");
            } catch (Exception e) {
                Slog.w(TAG, "cancelAllWorkByTag() failed, returning null", e);
                return null;
            }
        }
    }
}
