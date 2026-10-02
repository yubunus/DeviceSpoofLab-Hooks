package com.devicespooflab.hooks.utils;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import com.devicespooflab.hooks.XposedModuleImpl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// libxposed:service classes live both in the app classloader (loading
// XposedProvider) and the VectorModuleClassLoader (loading this module). To
// see the SEND_BINDER delivered to XposedProvider we resolve the helper
// through the app classloader and register a proxy listener there.
public final class XposedServiceBridge {

    private static final String TAG = "DeviceSpoofLab";

    private static final String CLS_HELPER =
            "io.github.libxposed.service.XposedServiceHelper";
    private static final String CLS_LISTENER =
            "io.github.libxposed.service.XposedServiceHelper$OnServiceListener";
    private static final String CLS_SERVICE =
            "io.github.libxposed.service.XposedService";

    private static final AtomicReference<Object> sService = new AtomicReference<>();
    private static final AtomicBoolean sNewApiAvailable = new AtomicBoolean(false);
    private static volatile Runnable sOnReady;
    private static volatile boolean sInitialized = false;

    private XposedServiceBridge() {}

    public static void markAvailableViaNewApi() {
        sNewApiAvailable.set(true);
        Runnable r = sOnReady;
        if (r != null) {
            try { r.run(); } catch (Throwable t) {
                Log.w(TAG, "XposedServiceBridge onReady (new-api) threw: "
                        + t.getMessage());
            }
        }
    }

    public static synchronized void init(android.content.Context appContext, Runnable onReady) {
        sOnReady = onReady;
        if (sInitialized) {
            if ((sService.get() != null || sNewApiAvailable.get()) && sOnReady != null) {
                try { sOnReady.run(); } catch (Throwable t) {
                    Log.w(TAG, "XposedServiceBridge onReady threw: " + t.getMessage());
                }
            }
            return;
        }

        ClassLoader appLoader = appContext != null ? appContext.getClassLoader()
                : resolveAppClassLoader();
        if (appLoader == null) {
            return;
        }

        try {
            Class<?> helperCls = Class.forName(CLS_HELPER, true, appLoader);
            Class<?> listenerCls = Class.forName(CLS_LISTENER, true, appLoader);

            Object listener = Proxy.newProxyInstance(appLoader,
                    new Class<?>[]{listenerCls}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onServiceBind".equals(name)
                                    && args != null && args.length == 1) {
                                sService.set(args[0]);
                                Log.i(TAG, "XposedService bound (Vector RemotePreferences ready)");
                                Runnable r = sOnReady;
                                if (r != null) {
                                    try { r.run(); } catch (Throwable t) {
                                        Log.w(TAG, "XposedServiceBridge onReady threw: "
                                                + t.getMessage());
                                    }
                                }
                            } else if ("onServiceDied".equals(name)) {
                                sService.set(null);
                            }
                            return null;
                        }
                    });

            helperCls.getMethod("registerListener", listenerCls).invoke(null, listener);
            sInitialized = true;
        } catch (ClassNotFoundException cnf) {
            // Expected in every target app: only the module's own APK carries
            // the service library.
            sInitialized = true;
        } catch (Throwable t) {
            Log.w(TAG, "XposedServiceBridge.init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Target apps never receive SEND_BINDER; fire onReady from the new-API
        // path so MainHook can still install the refresh loop.
        if (sNewApiAvailable.get() && sOnReady != null) {
            try { sOnReady.run(); } catch (Throwable t) {
                Log.w(TAG, "XposedServiceBridge onReady (new-api init) threw: "
                        + t.getMessage());
            }
        }
    }

    public static boolean isServiceAvailable() {
        return sNewApiAvailable.get() || sService.get() != null;
    }

    // Writable channel (own UI process after SEND_BINDER); editors must gate on this.
    public static boolean isServiceWritable() {
        return sService.get() != null;
    }

    public static SharedPreferences getRemotePreferences(String groupName) {
        Object service = sService.get();
        if (service != null) {
            try {
                return (SharedPreferences) Class.forName(CLS_SERVICE,
                                true, service.getClass().getClassLoader())
                        .getMethod("getRemotePreferences", String.class)
                        .invoke(service, groupName);
            } catch (Throwable t) {
                Log.w(TAG, "XposedService.getRemotePreferences(" + groupName + ") failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        if (sNewApiAvailable.get()) {
            return XposedModuleImpl.fetchRemotePreferences(groupName);
        }
        return null;
    }

    // Evict XposedService.mRemotePrefs[groupName] before re-fetching so the
    // refresh poll forces a new round-trip to the lspd daemon instead of
    // re-reading the frozen snapshot.
    public static SharedPreferences getRemotePreferencesFresh(String groupName) {
        Object service = sService.get();
        if (service != null) {
            try {
                Class<?> serviceCls = Class.forName(CLS_SERVICE,
                        true, service.getClass().getClassLoader());
                java.lang.reflect.Field f = serviceCls.getDeclaredField("mRemotePrefs");
                f.setAccessible(true);
                Object map = f.get(service);
                if (map instanceof java.util.Map) {
                    synchronized (service) {
                        ((java.util.Map<?, ?>) map).remove(groupName);
                    }
                }
                return (SharedPreferences) serviceCls
                        .getMethod("getRemotePreferences", String.class)
                        .invoke(service, groupName);
            } catch (Throwable t) {
                Log.w(TAG, "XposedService.getRemotePreferencesFresh(" + groupName
                        + ") cache-evict failed: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }
        if (sNewApiAvailable.get()) {
            return XposedModuleImpl.fetchRemotePreferences(groupName);
        }
        return null;
    }

    private static ClassLoader resolveAppClassLoader() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object thread = atCls.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            Application app = (Application) atCls.getMethod("getApplication").invoke(thread);
            return app != null ? app.getClassLoader() : null;
        } catch (Throwable t) {
            Log.w(TAG, "resolveAppClassLoader failed: " + t.getMessage());
            return null;
        }
    }
}
