package com.devicespooflab.hooks.hooks;

import android.app.ActivityManager;
import android.os.Debug;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HardwareHooks {

    private static final String TAG = "DeviceSpoofLab-Hardware";
    private static final AtomicBoolean RUNTIME_CORES_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean DEBUG_MEMORY_HOOKED = new AtomicBoolean(false);
    private static final Set<Class<?>> HOOKED_ACTIVITY_MANAGER_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookRuntimeCores();
            hookActivityManagerMemory(lpparam);
            hookDebugMemory();
            if (ConfigManager.isVerboseLoggingEnabled()) {
                XposedBridge.log(TAG + ": Successfully hooked hardware specs");
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook hardware: " + e.getMessage());
        }
    }

    private static void hookRuntimeCores() {
        if (!RUNTIME_CORES_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "availableProcessors",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(ConfigManager.getCpuCoreCount());
                    }
                });
        } catch (Exception e) {
            RUNTIME_CORES_HOOKED.set(false);
            XposedBridge.log(TAG + ": Failed to hook Runtime.availableProcessors(): " + e.getMessage());
        }
    }

    private static void hookActivityManagerMemory(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityManagerClass = XposedHelpers.findClassIfExists(
                "android.app.ActivityManager", lpparam.classLoader);

            if (activityManagerClass == null) {
                return;
            }
            if (!HOOKED_ACTIVITY_MANAGER_CLASSES.add(activityManagerClass)) {
                return;
            }

            XposedHelpers.findAndHookMethod(activityManagerClass, "getMemoryInfo",
                ActivityManager.MemoryInfo.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ActivityManager.MemoryInfo memInfo = (ActivityManager.MemoryInfo) param.args[0];
                        if (memInfo != null) {
                            long originalTotal = memInfo.totalMem;
                            long configuredTotal = Math.max(0L, ConfigManager.getMemoryTotalBytes());
                            long configuredAvailable = Math.max(0L, ConfigManager.getMemoryAvailableKb() * 1024L);
                            memInfo.totalMem = configuredTotal;
                            if (originalTotal > 0) {
                                long originalAvailable = Math.max(0L,
                                        Math.min(originalTotal, memInfo.availMem));
                                double availableRatio = (double) originalAvailable / originalTotal;
                                memInfo.availMem = Math.min(configuredTotal,
                                        (long) (configuredTotal * availableRatio));
                            } else {
                                memInfo.availMem = Math.min(configuredTotal, configuredAvailable);
                            }
                        }
                    }
                });

            XposedHelpers.findAndHookMethod(activityManagerClass, "getMemoryClass",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // Unset = the real class, which is what the heap
                        // limit of this process (Runtime.maxMemory) matches.
                        int configured = ConfigManager.getMemoryClassMb();
                        if (configured > 0) {
                            param.setResult(configured);
                        }
                    }
                });

            XposedHelpers.findAndHookMethod(activityManagerClass, "getLargeMemoryClass",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int configured = ConfigManager.getLargeMemoryClassMb();
                        if (configured > 0) {
                            param.setResult(configured);
                        }
                    }
                });

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook ActivityManager memory: " + e.getMessage());
        }
    }

    private static void hookDebugMemory() {
        if (!DEBUG_MEMORY_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Debug.class, "getNativeHeapSize",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        long originalSize = (Long) param.getResult();
                        param.setResult(originalSize * Math.max(1, ConfigManager.getNativeHeapScale()));
                    }
                });
        } catch (Exception e) {
            DEBUG_MEMORY_HOOKED.set(false);
        }
    }
}
