package com.devicespooflab.hooks.hooks;

import android.content.pm.FeatureInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class PackageManagerHooks {

    private static final String TAG = "DeviceSpoofLab-PackageManager";

    // What every phone has, whatever its maker: reported as present even where
    // the host lacks it (an emulator, a tablet). Hardware that differs from
    // phone to phone (NFC, fingerprint, gyroscope, barometer, Vulkan levels,
    // ...) is not listed: the real answer stays, since an app that is told a
    // feature exists goes on to use it.
    private static final Set<String> BASELINE_FEATURES = new HashSet<>(Arrays.asList(
        "android.hardware.camera",
        "android.hardware.camera.any",
        "android.hardware.camera.autofocus",
        "android.hardware.camera.flash",
        "android.hardware.camera.front",

        "android.hardware.sensor.accelerometer",

        "android.hardware.telephony",
        "android.hardware.telephony.gsm",
        "android.hardware.wifi",
        "android.hardware.wifi.direct",
        "android.hardware.bluetooth",
        "android.hardware.bluetooth_le",

        "android.hardware.touchscreen",
        "android.hardware.touchscreen.multitouch",
        "android.hardware.touchscreen.multitouch.distinct",
        "android.hardware.touchscreen.multitouch.jazzhand",
        "android.hardware.screen.portrait",
        "android.hardware.screen.landscape",

        "android.hardware.location",
        "android.hardware.location.gps",
        "android.hardware.location.network",

        "android.hardware.audio.output",
        "android.hardware.microphone",

        "android.hardware.usb.host",
        "android.hardware.usb.accessory",

        "android.software.device_admin",
        "android.software.managed_users",
        "android.software.webview",
        "android.software.backup",
        "android.software.app_widgets",
        "android.software.home_screen",
        "android.software.input_methods",
        "android.software.autofill",
        "android.software.verified_boot",
        "android.software.secure_lock_screen"
    ));

    private static final Set<String> DENIED_FEATURES = new HashSet<>(Arrays.asList(
        "android.hardware.sensor.emulator",
        "goldfish"
    ));

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> appPackageManagerClass = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager", lpparam.classLoader);

            if (appPackageManagerClass != null) {
                hookHasSystemFeature(appPackageManagerClass);
                hookGetSystemAvailableFeatures(appPackageManagerClass);
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook PackageManager: " + e.getMessage());
        }
    }

    private static boolean isDenied(String feature) {
        String lower = feature.toLowerCase();
        for (String denied : DENIED_FEATURES) {
            if (lower.contains(denied)) {
                return true;
            }
        }
        return false;
    }

    private static void hookHasSystemFeature(Class<?> pmClass) {
        try {
            XposedHelpers.findAndHookMethod(pmClass, "hasSystemFeature",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String feature = (String) param.args[0];

                        if (feature == null) {
                            return;
                        }

                        if (isDenied(feature)) {
                            param.setResult(false);
                            return;
                        }

                        if (BASELINE_FEATURES.contains(feature)) {
                            param.setResult(true);
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook hasSystemFeature(String): " + e.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(pmClass, "hasSystemFeature",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String feature = (String) param.args[0];

                        if (feature == null) {
                            return;
                        }

                        if (isDenied(feature)) {
                            param.setResult(false);
                            return;
                        }

                        // A baseline feature is listed as version 0, so it only
                        // answers a query for that.
                        if ((int) param.args[1] <= 0 && BASELINE_FEATURES.contains(feature)) {
                            param.setResult(true);
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook hasSystemFeature(String, int): " + e.getMessage());
        }
    }

    // Same answer as hasSystemFeature: denied entries dropped, baseline
    // features the host lacks added.
    private static void hookGetSystemAvailableFeatures(Class<?> pmClass) {
        try {
            XposedHelpers.findAndHookMethod(pmClass, "getSystemAvailableFeatures",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (!(result instanceof FeatureInfo[])) {
                            return;
                        }
                        FeatureInfo[] features = (FeatureInfo[]) result;

                        List<FeatureInfo> listed = new ArrayList<>(features.length);
                        Set<String> missing = new HashSet<>(BASELINE_FEATURES);
                        boolean changed = false;
                        for (FeatureInfo feature : features) {
                            String name = feature == null ? null : feature.name;
                            if (name != null && isDenied(name)) {
                                changed = true;
                                continue;
                            }
                            if (name != null) {
                                missing.remove(name);
                            }
                            listed.add(feature);
                        }
                        for (String name : missing) {
                            FeatureInfo added = new FeatureInfo();
                            added.name = name;
                            listed.add(added);
                            changed = true;
                        }
                        if (changed) {
                            param.setResult(listed.toArray(new FeatureInfo[0]));
                        }
                    }
                });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook getSystemAvailableFeatures(): " + e.getMessage());
        }
    }
}
