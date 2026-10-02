package com.devicespooflab.hooks.hooks;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// Install metadata of the app's own package: installed from the Play Store,
// 60-120 days before it really was. Other packages keep their real data.
public class PackageInfoHooks {

    private static final String TAG = "DeviceSpoofLab-PackageInfo";
    private static final long DAY_MS = 86_400_000L;
    // PackageInstaller.PACKAGE_SOURCE_STORE
    private static final int PACKAGE_SOURCE_STORE = 2;

    // The process's own app; null when it is a system app or the module itself.
    private static volatile String sOwnPackage;

    // PackageInfo objects already patched. The framework caches them and hands
    // the same object out again, and the overloads call each other.
    private static final Set<Object> sPatchedInfos =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    // InstallSourceInfo objects handed out for the own package.
    private static final Set<Object> sOwnInstallSources =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        sOwnPackage = resolveOwnPackage(lpparam);
        hookPackageInfoFields(lpparam);
        hookGetInstallerPackageName(lpparam);
        hookGetInstallSourceInfo(lpparam);
    }

    private static String resolveOwnPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String packageName = lpparam.packageName;
        ApplicationInfo appInfo = lpparam.appInfo;
        if (!lpparam.isFirstApplication) {
            // Not the process's own app: ask the runtime instead.
            try {
                android.app.Application app = (android.app.Application) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentApplication");
                if (app == null) return null;
                packageName = app.getPackageName();
                appInfo = app.getApplicationInfo();
            } catch (Throwable t) {
                return null;
            }
        }
        if (packageName == null || ConfigManager.isOwnPackageProcess(packageName)) {
            return null;
        }
        int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        if (appInfo != null && (appInfo.flags & systemFlags) != 0) {
            return null;
        }
        return packageName;
    }

    private static boolean isOwnPackage(String packageName) {
        return packageName != null && packageName.equals(sOwnPackage);
    }

    private static void hookPackageInfoFields(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> appPm = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);
        if (appPm == null) return;

        XC_MethodHook patcher = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (result instanceof PackageInfo) {
                    patch((PackageInfo) result);
                } else if (result instanceof List) {
                    for (Object item : (List<?>) result) {
                        if (item instanceof PackageInfo) patch((PackageInfo) item);
                    }
                }
            }
        };

        // getPackageInfo(String, int) and getPackageInfo(String, PackageInfoFlags)
        try {
            XposedHelpers.findAndHookMethod(appPm, "getPackageInfo",
                    String.class, int.class, patcher);
        } catch (Throwable t) { logFail("getPackageInfo(String,int)", t); }

        try {
            XposedHelpers.findAndHookMethod(appPm, "getInstalledPackages",
                    int.class, patcher);
        } catch (Throwable t) { logFail("getInstalledPackages(int)", t); }

        try {
            Class<?> flags = XposedHelpers.findClassIfExists(
                    "android.content.pm.PackageManager$PackageInfoFlags", lpparam.classLoader);
            if (flags != null) {
                XposedHelpers.findAndHookMethod(appPm, "getPackageInfo",
                        String.class, flags, patcher);
                XposedHelpers.findAndHookMethod(appPm, "getInstalledPackages",
                        flags, patcher);
            }
        } catch (Throwable t) { /* Android 13+ overload */ }
    }

    private static void hookGetInstallerPackageName(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> appPm = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);
        if (appPm == null) return;

        try {
            XposedHelpers.findAndHookMethod(appPm, "getInstallerPackageName",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (isOwnPackage((String) param.args[0])) {
                                param.setResult(ConfigManager.getInstallerPackage());
                            }
                        }
                    });
        } catch (Throwable t) { logFail("getInstallerPackageName", t); }
    }

    private static void hookGetInstallSourceInfo(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> appPm = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);
        if (appPm == null) return;

        Class<?> sourceInfo = XposedHelpers.findClassIfExists(
                "android.content.pm.InstallSourceInfo", lpparam.classLoader);
        if (sourceInfo == null) return;

        // InstallSourceInfo has no public constructor, so the object is kept
        // and its getters answer for it; the ones of other packages don't.
        try {
            XposedHelpers.findAndHookMethod(appPm, "getInstallSourceInfo",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result != null && isOwnPackage((String) param.args[0])) {
                                sOwnInstallSources.add(result);
                            }
                        }
                    });
        } catch (Throwable t) { logFail("getInstallSourceInfo", t); }

        // What a Play Store install reports. The originating package stays as
        // it is: only privileged callers ever see one.
        hookSourceGetter(sourceInfo, "getInstallingPackageName", true);
        hookSourceGetter(sourceInfo, "getInitiatingPackageName", false);
        hookSourceGetter(sourceInfo, "getUpdateOwnerPackageName", true);
        try {
            XposedHelpers.findAndHookMethod(sourceInfo, "getPackageSource",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sOwnInstallSources.contains(param.thisObject)) {
                                param.setResult(PACKAGE_SOURCE_STORE);
                            }
                        }
                    });
        } catch (Throwable t) { /* Android 13+ */ }
    }

    private static void hookSourceGetter(Class<?> sourceInfo, String getter,
                                         final boolean installer) {
        try {
            XposedHelpers.findAndHookMethod(sourceInfo, getter,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sOwnInstallSources.contains(param.thisObject)) {
                                param.setResult(installer
                                        ? ConfigManager.getInstallerPackage()
                                        : ConfigManager.getInitiatingInstallerPackage());
                            }
                        }
                    });
        } catch (Throwable t) { /* getter may be absent on older Android */ }
    }

    // Moves the first install back by a fixed number of days. Anchored to the
    // real install time, so it never moves between reads, and lastUpdateTime
    // (the day this very APK arrived) stays real.
    private static void patch(PackageInfo pi) {
        if (pi == null || !isOwnPackage(pi.packageName) || pi.firstInstallTime <= 0) return;
        if (!sPatchedInfos.add(pi)) return;
        long seed = ConfigManager.getFingerprintSeed();
        long offsetDays = 60 + ((seed & Long.MAX_VALUE) % 61);
        pi.firstInstallTime -= offsetDays * DAY_MS;
    }

    private static void logFail(String what, Throwable t) {
        XposedBridge.log(TAG + ": failed to hook " + what + ": " + t);
    }
}
