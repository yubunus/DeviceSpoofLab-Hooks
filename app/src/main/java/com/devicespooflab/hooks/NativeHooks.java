package com.devicespooflab.hooks;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.devicespooflab.hooks.utils.ConfigManager;

import de.robv.android.xposed.XposedBridge;

public final class NativeHooks {

    private static final String TAG = "DeviceSpoofLab-Native";
    private static final String LIB_NAME = "ds_native";

    private static volatile boolean libraryLoaded = false;
    private static volatile boolean installed = false;

    private NativeHooks() {}

    private static native int nativeInstall(HashMap<String, String> props,
                                            HashMap<String, String> settings);

    private static native int nativeRehook();

    private static native String nativeQuery(String key);

    // props: what a property read is answered with (an empty value reads as
    // unset). settings: the native layer's own knobs, never served as properties.
    public static synchronized boolean tryInstall(Map<String, String> props,
                                                  Map<String, String> settings) {
        if (installed) {
            return true;
        }
        if (!loadLibrary()) {
            return false;
        }
        try {
            int n = nativeInstall(new HashMap<>(props), new HashMap<>(settings));
            installed = (n >= 0);
            if (ConfigManager.isVerboseLoggingEnabled()) {
                XposedBridge.log(TAG + ": nativeInstall returned " + n);
            }
            return installed;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": nativeInstall threw: " + t);
            return false;
        }
    }

    /**
     * Re-scans loaded libraries and hooks any newly loaded app-owned one. An
     * app's native libs are usually not mapped yet when {@link #tryInstall}
     * runs, so this is re-driven from the config refresh loop (MainHook), off
     * the app's threads. Hooking System.loadLibrary to get an exact signal is
     * not viable: it breaks ART's caller-based lookup and the app then fails to
     * load its own lib. A no-op until the library is installed.
     */
    public static void rehook() {
        if (!installed) {
            return;
        }
        try {
            nativeRehook();
        } catch (Throwable ignored) {
        }
    }

    public static String debugQuery(String key) {
        if (!installed) return null;
        try {
            return nativeQuery(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean loadLibrary() {
        if (libraryLoaded) return true;

        try {
            System.loadLibrary(LIB_NAME);
            libraryLoaded = true;
            return true;
        } catch (UnsatisfiedLinkError ignored) {
        }

        // Fallback: look the .so up via the module classloader, then a small
        // set of known absolute paths. Runs during app startup; no filesystem walk.
        String absPath = locateLibraryOnDisk();
        if (absPath == null) {
            XposedBridge.log(TAG + ": could not locate " + LIB_NAME + " .so");
            return false;
        }
        try {
            System.load(absPath);
            libraryLoaded = true;
            if (ConfigManager.isVerboseLoggingEnabled()) {
                XposedBridge.log(TAG + ": loaded native lib via System.load(" + absPath + ")");
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": System.load(" + absPath + ") failed: " + t);
            return false;
        }
    }

    private static String locateLibraryOnDisk() {
        String modulePkg = "com.devicespooflab.hooks";
        String soName = System.mapLibraryName(LIB_NAME);

        String classLoaderPath = findLibraryFromClassLoader();
        if (isUsableLibraryPath(classLoaderPath, soName)) {
            return classLoaderPath;
        }

        String[] exactPaths = new String[] {
                "/data/data/" + modulePkg + "/lib/" + soName,
                "/data/user/0/" + modulePkg + "/lib/" + soName,
        };
        for (String path : exactPaths) {
            if (path.contains(modulePkg) && isUsableLibraryPath(path, soName)) {
                return path;
            }
        }

        return null;
    }

    private static String findLibraryFromClassLoader() {
        ClassLoader loader = NativeHooks.class.getClassLoader();
        if (loader == null) {
            return null;
        }
        Class<?> clazz = loader.getClass();
        while (clazz != null) {
            try {
                Method method = clazz.getDeclaredMethod("findLibrary", String.class);
                method.setAccessible(true);
                Object value = method.invoke(loader, LIB_NAME);
                if (value instanceof String && !((String) value).isEmpty()) {
                    return (String) value;
                }
                return null;
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isUsableLibraryPath(String path, String soName) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (!path.endsWith("/" + soName)) {
            return false;
        }
        try {
            File file = new File(path);
            return file.isFile() && file.canRead();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
