package com.devicespooflab.hooks.hooks;

import android.system.Os;
import android.system.StructUtsname;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.Properties;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// The kernel as Java reports it: Os.uname() and the os.version system
// property. A blank kernel.* value leaves the phone's own in place.
public class KernelHooks {

    private static final String TAG = "DeviceSpoofLab-Kernel";

    private static volatile String sRealOsVersion;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Os.class, "uname",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.getResult() instanceof StructUtsname)) return;
                            StructUtsname real = (StructUtsname) param.getResult();
                            String release = ConfigManager.getKernelOsRelease();
                            String version = ConfigManager.getKernelVersion();
                            String hostname = ConfigManager.getKernelHostname();
                            if (release.isEmpty() && version.isEmpty() && hostname.isEmpty()) {
                                return;
                            }
                            param.setResult(new StructUtsname(real.sysname,
                                    hostname.isEmpty() ? real.nodename : hostname,
                                    release.isEmpty() ? real.release : release,
                                    version.isEmpty() ? real.version : version,
                                    real.machine));
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Os.uname: " + t);
        }
        applyDefaults();
    }

    // Re-run after a live config refresh. os.version is filled once from
    // uname() when the runtime starts and sits in System's unchangeable set,
    // which System.setProperty refuses to touch, so it is replaced in there.
    public static void applyDefaults() {
        try {
            Object props = XposedHelpers.getStaticObjectField(System.class, "unchangeableProps");
            if (!(props instanceof Properties)) return;
            Properties unchangeable = (Properties) props;
            if (sRealOsVersion == null) {
                sRealOsVersion = unchangeable.getProperty("os.version");
            }
            String release = ConfigManager.getKernelOsRelease();
            String wanted = release.isEmpty() ? sRealOsVersion : release;
            if (wanted != null && !wanted.equals(unchangeable.getProperty("os.version"))) {
                unchangeable.setProperty("os.version", wanted);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to set os.version: " + t);
        }
    }
}
