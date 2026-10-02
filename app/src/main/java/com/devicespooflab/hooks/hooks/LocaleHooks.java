package com.devicespooflab.hooks.hooks;

import android.content.res.Configuration;
import android.os.LocaleList;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.Locale;
import java.util.TimeZone;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class LocaleHooks {

    private static final String TAG = "DeviceSpoofLab-Locale";

    // Spoofed defaults currently applied (null = passthrough). The process-wide
    // defaults are set once instead of hooking the getters: Calendar, Date,
    // SimpleDateFormat and ICU read libcore's internal default ref directly and
    // never go through TimeZone.getDefault()/Locale.getDefault().
    private static volatile TimeZone sTimeZone;
    private static volatile LocaleList sLocales;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookTimeZoneReset();
        hookLocaleListReset();
        hookResourcesConfiguration(lpparam);
        applyDefaults();
    }

    // The zone currently applied, null = passthrough.
    public static String getSpoofedTimeZoneId() {
        TimeZone tz = sTimeZone;
        return tz == null ? null : tz.getID();
    }

    // Re-run after a live config refresh so edits apply without a restart.
    public static void applyDefaults() {
        applyTimeZone();
        applyLocale();
    }

    private static void applyTimeZone() {
        try {
            String id = ConfigManager.getTimeZoneId();
            if (id.isEmpty()) return;
            TimeZone tz = TimeZone.getTimeZone(id);
            // getTimeZone silently falls back to GMT for unknown IDs.
            if (!id.equals(tz.getID())) {
                XposedBridge.log(TAG + ": unknown timezone " + id + ", leaving default");
                return;
            }
            sTimeZone = tz;
            TimeZone.setDefault(tz);
            // Native localtime() reads TZ before persist.sys.timezone.
            try {
                android.system.Os.setenv("TZ", id, true);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to apply timezone: " + t);
        }
    }

    private static void applyLocale() {
        try {
            String language = ConfigManager.getLocaleLanguage();
            if (language.isEmpty()) return;
            LocaleList locales = new LocaleList(
                    new Locale(language, ConfigManager.getLocaleCountry()));
            sLocales = locales;
            LocaleList.setDefault(locales);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to apply locale: " + t);
        }
    }

    // ActivityThread resets the default with setDefault(null) at bind time and on
    // ACTION_TIMEZONE_CHANGED; substitute the spoofed zone. Explicit app calls
    // with a non-null zone are left alone.
    private static void hookTimeZoneReset() {
        try {
            XposedHelpers.findAndHookMethod(TimeZone.class, "setDefault",
                    TimeZone.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            TimeZone tz = sTimeZone;
                            if (param.args[0] == null && tz != null) {
                                param.args[0] = tz;
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook TimeZone.setDefault: " + t);
        }
    }

    // The framework applies the system locales through the hidden two-arg
    // overload (the public one delegates to it). Apps switching their own
    // language use Locale.setDefault, which stays untouched.
    private static void hookLocaleListReset() {
        try {
            XposedHelpers.findAndHookMethod(LocaleList.class, "setDefault",
                    LocaleList.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            LocaleList locales = sLocales;
                            if (locales != null) {
                                param.args[0] = locales;
                                param.args[1] = 0;
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook LocaleList.setDefault: " + t);
        }
    }

    // Resources pick their locale from the Configuration handed to
    // ResourcesImpl, so the app UI and getConfiguration().getLocales() agree with
    // Locale.getDefault(). Patches a copy; the caller's object may be shared.
    private static void hookResourcesConfiguration(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> implClass = XposedHelpers.findClassIfExists(
                "android.content.res.ResourcesImpl", lpparam.classLoader);
        if (implClass == null) return;
        XC_MethodHook patchLocales = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                LocaleList locales = sLocales;
                if (locales == null || param.args.length == 0
                        || !(param.args[0] instanceof Configuration)) {
                    return;
                }
                Configuration config = (Configuration) param.args[0];
                if (locales.equals(config.getLocales())) {
                    return;
                }
                Configuration patched = new Configuration(config);
                patched.setLocales(locales);
                param.args[0] = patched;
            }
        };
        try {
            XposedHelpers.findAndHookMethod(implClass, "updateConfiguration",
                    Configuration.class, android.util.DisplayMetrics.class,
                    "android.content.res.CompatibilityInfo", patchLocales);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook ResourcesImpl.updateConfiguration: " + t);
        }
        // Android 15+: the constructor goes straight to this private method, so
        // a new ResourcesImpl (every Activity gets one) never passes through
        // the public one above and would keep the phone's own language. Absent
        // on older versions, where the constructor calls updateConfiguration.
        try {
            XposedBridge.hookAllMethods(implClass, "updateConfigurationImpl", patchLocales);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook ResourcesImpl.updateConfigurationImpl: " + t);
        }
    }
}
