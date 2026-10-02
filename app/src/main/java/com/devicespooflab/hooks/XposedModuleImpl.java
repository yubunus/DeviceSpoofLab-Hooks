package com.devicespooflab.hooks;

import android.content.SharedPreferences;
import android.util.Log;

import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.utils.XposedServiceBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArraySet;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

// New-API entry point for Vector LSPosed (JingMatrix). Vector only delivers
// the IXposedService binder — and therefore writable RemotePreferences — to
// modules declared via META-INF/xposed/java_init.list. This class receives
// that binder; MainHook still does all of the hook installation.
public final class XposedModuleImpl extends XposedModule {

    private static final String TAG = "DeviceSpoofLab";

    private static volatile XposedModuleImpl sInstance;

    // Vector's libxposed-api runtime instantiates the module via the NO-ARG
    // constructor (getDeclaredConstructor().newInstance()) and injects the
    // framework reference afterwards through XposedInterfaceWrapper
    // .attachFramework(), so inherited getRemotePreferences() works without a
    // constructor argument. A 2-arg ctor here makes Vector throw
    // NoSuchMethodException: <init> [] and the module never loads in targets.
    public XposedModuleImpl() {
        sInstance = this;
    }

    public static XposedModuleImpl get() {
        return sInstance;
    }

    public static SharedPreferences fetchRemotePreferences(String group) {
        XposedModuleImpl instance = sInstance;
        if (instance == null) return null;
        try {
            return instance.getRemotePreferences(group);
        } catch (Throwable t) {
            Log.w(TAG, "fetchRemotePreferences(" + group + ") failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        // Read-only RemotePreferences is live now; writes wait for the
        // IXposedService binder from MainHook's Application.attach hook.
        // Nothing is logged here: this runs inside the hooked app, and the
        // verbose flag isn't known before the config is loaded.
        XposedServiceBridge.markAvailableViaNewApi();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            XC_LoadPackage.LoadPackageParam lp = newLoadPackageParam();
            lp.packageName = param.getPackageName();
            lp.processName = getProcessName(param);
            lp.classLoader = param.getDefaultClassLoader();
            lp.appInfo = param.getApplicationInfo();
            lp.isFirstApplication = param.isFirstPackage();
            new MainHook().handleLoadPackage(lp);
        } catch (Throwable t) {
            Log.e(TAG, "onPackageLoaded delegation failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    private static String getProcessName(XposedModuleInterface.PackageLoadedParam param) {
        try {
            Method method = param.getClass().getMethod("getProcessName");
            method.setAccessible(true);
            Object value = method.invoke(param);
            if (value instanceof String && !((String) value).isEmpty()) {
                return (String) value;
            }
        } catch (Throwable ignored) {
        }
        return param.getPackageName();
    }

    // Runtime LoadPackageParam takes a CopyOnWriteArraySet; the api-82 stub
    // exposes only a package-private no-arg ctor. Reflect to bypass the mismatch.
    private static XC_LoadPackage.LoadPackageParam newLoadPackageParam() throws Exception {
        for (Constructor<?> c : XC_LoadPackage.LoadPackageParam.class.getDeclaredConstructors()) {
            c.setAccessible(true);
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 0) {
                return (XC_LoadPackage.LoadPackageParam) c.newInstance();
            }
            if (params.length == 1 && CopyOnWriteArraySet.class.isAssignableFrom(params[0])) {
                return (XC_LoadPackage.LoadPackageParam)
                        c.newInstance(new CopyOnWriteArraySet<>());
            }
        }
        try {
            return allocateLoadPackageParam();
        } catch (Throwable t) {
            NoSuchMethodException e =
                    new NoSuchMethodException("LoadPackageParam constructor not found");
            e.initCause(t);
            throw e;
        }
    }

    private static XC_LoadPackage.LoadPackageParam allocateLoadPackageParam() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        Object value = allocateInstance.invoke(unsafe, XC_LoadPackage.LoadPackageParam.class);
        if (value instanceof XC_LoadPackage.LoadPackageParam) {
            Log.w(TAG, "LoadPackageParam created via Unsafe fallback");
            return (XC_LoadPackage.LoadPackageParam) value;
        }
        throw new ClassCastException("Unexpected LoadPackageParam allocation result");
    }
}
