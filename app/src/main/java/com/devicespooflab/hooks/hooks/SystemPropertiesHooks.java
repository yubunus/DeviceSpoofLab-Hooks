package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SystemPropertiesHooks {

    private static final String TAG = "DeviceSpoofLab-SystemProps";
    private static final String SYSTEM_PROPERTIES_CLASS = "android.os.SystemProperties";
    private static final Set<Class<?>> HOOKED_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookSystemProperties(lpparam.classLoader);

            try {
                ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
                if (systemClassLoader != null && systemClassLoader != lpparam.classLoader) {
                    hookSystemProperties(systemClassLoader);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook SystemProperties: " + e.getMessage());
        }
    }

    // A spoofed value can be empty: the property then reads as unset, and the
    // typed getters fall back to the caller's default the way the real ones do.
    private static void hookSystemProperties(ClassLoader classLoader) {
        Class<?> sysPropClass = XposedHelpers.findClassIfExists(SYSTEM_PROPERTIES_CLASS, classLoader);

        if (sysPropClass == null) {
            return;
        }
        if (!HOOKED_CLASSES.add(sysPropClass)) {
            return;
        }

        // Hook get(String key)
        try {
            XposedHelpers.findAndHookMethod(sysPropClass, "get",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        String spoofedValue = ConfigManager.getSystemProperty(key, null);

                        if (spoofedValue != null) {
                            param.setResult(spoofedValue);
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook get(String): " + e.getMessage());
        }

        // Hook get(String key, String def)
        try {
            XposedHelpers.findAndHookMethod(sysPropClass, "get",
                String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        String spoofedValue = ConfigManager.getSystemProperty(key, null);

                        if (spoofedValue != null) {
                            // An unset property gives the default; a null default
                            // gives the empty string.
                            Object def = param.args[1];
                            param.setResult(!spoofedValue.isEmpty() ? spoofedValue
                                    : def != null ? def : "");
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook get(String, String): " + e.getMessage());
        }

        // Hook getInt(String key, int def)
        try {
            XposedHelpers.findAndHookMethod(sysPropClass, "getInt",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        String spoofedValue = ConfigManager.getSystemProperty(key, null);

                        if (spoofedValue != null) {
                            try {
                                param.setResult(Integer.parseInt(spoofedValue));
                            } catch (NumberFormatException e) {
                                param.setResult(param.args[1]);
                            }
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook getInt(String, int): " + e.getMessage());
        }

        // Hook getBoolean(String key, boolean def)
        try {
            XposedHelpers.findAndHookMethod(sysPropClass, "getBoolean",
                String.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        String spoofedValue = ConfigManager.getSystemProperty(key, null);

                        if (spoofedValue != null) {
                            // Same words the platform accepts; anything else is the default.
                            switch (spoofedValue) {
                                case "1": case "y": case "yes": case "on": case "true":
                                    param.setResult(true);
                                    break;
                                case "0": case "n": case "no": case "off": case "false":
                                    param.setResult(false);
                                    break;
                                default:
                                    param.setResult(param.args[1]);
                            }
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook getBoolean(String, boolean): " + e.getMessage());
        }

        // Hook getLong(String key, long def)
        try {
            XposedHelpers.findAndHookMethod(sysPropClass, "getLong",
                String.class, long.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        String spoofedValue = ConfigManager.getSystemProperty(key, null);

                        if (spoofedValue != null) {
                            try {
                                param.setResult(Long.parseLong(spoofedValue));
                            } catch (NumberFormatException e) {
                                param.setResult(param.args[1]);
                            }
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook getLong(String, long): " + e.getMessage());
        }
    }
}
