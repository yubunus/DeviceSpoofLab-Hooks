package com.devicespooflab.hooks;

import android.os.Build;
import android.util.Log;

import com.devicespooflab.hooks.hooks.AccountHooks;
import com.devicespooflab.hooks.hooks.AdvertisingIdHooks;
import com.devicespooflab.hooks.hooks.AppSetIdHooks;
import com.devicespooflab.hooks.hooks.BatteryHooks;
import com.devicespooflab.hooks.hooks.BuildHooks;
import com.devicespooflab.hooks.hooks.CameraHooks;
import com.devicespooflab.hooks.hooks.DisplayHooks;
import com.devicespooflab.hooks.hooks.EuiccHooks;
import com.devicespooflab.hooks.hooks.HardwareHooks;
import com.devicespooflab.hooks.hooks.InputDeviceHooks;
import com.devicespooflab.hooks.hooks.KernelHooks;
import com.devicespooflab.hooks.hooks.LocaleHooks;
import com.devicespooflab.hooks.hooks.MediaDrmHooks;
import com.devicespooflab.hooks.hooks.NetworkHooks;
import com.devicespooflab.hooks.hooks.PackageInfoHooks;
import com.devicespooflab.hooks.hooks.PackageManagerHooks;
import com.devicespooflab.hooks.hooks.SensorHooks;
import com.devicespooflab.hooks.hooks.SettingsHooks;
import com.devicespooflab.hooks.hooks.StorageHooks;
import com.devicespooflab.hooks.hooks.SystemPropertiesHooks;
import com.devicespooflab.hooks.hooks.TelephonyHooks;
import com.devicespooflab.hooks.hooks.WebViewHooks;
import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.utils.XposedServiceBridge;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DeviceSpoofLab";

    // handleLoadPackage runs for every package loaded into the process: the
    // app itself, then GMS dynamite modules, the WebView and so on. The
    // framework hooks are process-wide, so only the first call installs them.
    private static final AtomicBoolean sProcessHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sDisplayHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sAccountsHooked = new AtomicBoolean(false);
    // Build.VERSION.SDK_INT before BuildHooks replaces it.
    private static volatile int sRealDeviceSdk;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ConfigManager.init();
        } catch (Exception e) {
            Log.e(TAG, "Failed to init config: " + e.getMessage(), e);
            XposedBridge.log(TAG + ": Failed to init config: " + e.getMessage());
            return;
        }

        if (sRealDeviceSdk == 0) {
            sRealDeviceSdk = Build.VERSION.SDK_INT;
            ConfigManager.setRealSdkIntFull(readSdkIntFull());
        }
        final int realDeviceSdk = sRealDeviceSdk;

        if (!sProcessHooked.compareAndSet(false, true)) {
            hookLoadedPackage(lpparam, realDeviceSdk);
            return;
        }

        // XposedServiceHelper has independent static state in the app loader vs
        // the module loader. Defer init until Application.attach so the bridge
        // can bind through the app loader and see the same mListener that
        // XposedProvider notifies on SEND_BINDER.
        final boolean isOwnPackage = ConfigManager.isOwnPackageProcess(lpparam.processName)
                || "com.devicespooflab.hooks".equals(lpparam.packageName);

        // Under the modern entry RemotePreferences is readable as soon as the
        // module is loaded. Pulling the saved config now puts it in place
        // before any app code runs; otherwise Application static init and
        // attachBaseContext would still see the embedded default profile.
        if (!isOwnPackage && XposedServiceBridge.isServiceAvailable()) {
            ConfigManager.loadFromRemotePreferences();
        }

        // Nothing is logged in a hooked process unless verbose is on, and never
        // an identifier value: an app can read its own log lines.
        boolean verbose = ConfigManager.isVerboseLoggingEnabled();
        logInfo(verbose, TAG + ": Loading hooks for " + lpparam.packageName
                + " (config from " + ConfigManager.getLoadSource() + ")");

        final Runnable onBinderReady = new Runnable() {
            @Override
            public void run() {
                if (isOwnPackage) {
                    // Fallback only: with the module out of its own scope this
                    // branch never runs (MainActivity publishes directly). It
                    // survives for users who re-scope the module to itself, e.g.
                    // on stock LSPosed. The new-API RemotePreferences is
                    // read-only, so publishing needs the writable SEND_BINDER.
                    if (!XposedServiceBridge.isServiceWritable()) return;
                    ConfigManager.publishToRemotePreferences();
                } else {
                    boolean loaded = ConfigManager.loadFromRemotePreferences();
                    if (loaded) {
                        applyLoadedConfig(lpparam);
                    }
                    // Vector's read-only RemotePreferences caches a frozen
                    // snapshot at construction and never fires the change
                    // listener for daemon writes, so we poll _generation.
                    installRemoteRefreshLoop(lpparam);
                    logInfo(ConfigManager.isVerboseLoggingEnabled(),
                            TAG + ": RemotePreferences load=" + loaded);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod("android.app.Application",
                    lpparam.classLoader, "attach",
                    android.content.Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.content.Context ctx =
                                        (android.content.Context) param.args[0];
                                XposedServiceBridge.init(ctx, onBinderReady);
                                // The own UI process is no longer in scope, so it
                                // never reaches here; it publishes directly from
                                // MainActivity via the binder delivered to
                                // XposedProvider. This path now only serves target
                                // apps: seed from RemotePreferences when the new-API
                                // channel is up but config is still empty.
                                if (!isOwnPackage
                                        && ConfigManager.getIdentifierValue("imei").isEmpty()
                                        && XposedServiceBridge.isServiceAvailable()) {
                                    if (ConfigManager.loadFromRemotePreferences()) {
                                        applyLoadedConfig(lpparam);
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "Application.attach hook failed: "
                                        + t.getMessage());
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook Application.attach: " + t.getMessage());
        }

        // BuildHooks first so direct Build.* reads pick up spoofed values.
        try {
            BuildHooks.hook(lpparam);
            logInfo(verbose, TAG + ": BuildHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": BuildHooks failed: " + e.getMessage());
        }

        // Skip SystemProperties / Locale / Display / Native in our own process so
        // MainActivity (the config editor) reads the real host values instead of
        // looping back its own spoof.
        if (!isOwnPackage) {
            try {
                SystemPropertiesHooks.hook(lpparam);
                logInfo(verbose, TAG + ": SystemPropertiesHooks loaded");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": SystemPropertiesHooks failed: " + e.getMessage());
            }
        } else {
            logInfo(verbose, TAG + ": SystemPropertiesHooks skipped for module process");
        }

        try {
            HardwareHooks.hook(lpparam);
            logInfo(verbose, TAG + ": HardwareHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": HardwareHooks failed: " + e.getMessage());
        }

        try {
            TelephonyHooks.hook(lpparam);
            logInfo(verbose, TAG + ": TelephonyHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": TelephonyHooks failed: " + e.getMessage());
        }

        try {
            SettingsHooks.hook(lpparam);
            logInfo(verbose, TAG + ": SettingsHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": SettingsHooks failed: " + e.getMessage());
        }

        try {
            AdvertisingIdHooks.hook(lpparam);
            logInfo(verbose, TAG + ": AdvertisingIdHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": AdvertisingIdHooks failed: " + e.getMessage());
        }

        if (realDeviceSdk >= 30) {
            try {
                AppSetIdHooks.hook(lpparam, realDeviceSdk);
                logInfo(verbose, TAG + ": AppSetIdHooks loaded");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": AppSetIdHooks failed: " + e.getMessage());
            }
        }

        try {
            MediaDrmHooks.hook(lpparam);
            logInfo(verbose, TAG + ": MediaDrmHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": MediaDrmHooks failed: " + e.getMessage());
        }

        try {
            WebViewHooks.hook(lpparam);
            logInfo(verbose, TAG + ": WebViewHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": WebViewHooks failed: " + e.getMessage());
        }

        try {
            PackageManagerHooks.hook(lpparam);
            logInfo(verbose, TAG + ": PackageManagerHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": PackageManagerHooks failed: " + e.getMessage());
        }

        try {
            NetworkHooks.hook(lpparam);
            logInfo(verbose, TAG + ": NetworkHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": NetworkHooks failed: " + e.getMessage());
        }

        try {
            SensorHooks.hook(lpparam);
            logInfo(verbose, TAG + ": SensorHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": SensorHooks failed: " + e.getMessage());
        }

        try {
            CameraHooks.hook(lpparam);
            logInfo(verbose, TAG + ": CameraHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": CameraHooks failed: " + e.getMessage());
        }

        try {
            StorageHooks.hook(lpparam);
            logInfo(verbose, TAG + ": StorageHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": StorageHooks failed: " + e.getMessage());
        }

        if (!isOwnPackage) {
            try {
                LocaleHooks.hook(lpparam);
                logInfo(verbose, TAG + ": LocaleHooks loaded");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": LocaleHooks failed: " + e.getMessage());
            }
            try {
                KernelHooks.hook(lpparam);
                logInfo(verbose, TAG + ": KernelHooks loaded");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": KernelHooks failed: " + e.getMessage());
            }
        } else {
            logInfo(verbose, TAG + ": LocaleHooks, KernelHooks skipped for module process");
        }

        try {
            PackageInfoHooks.hook(lpparam);
            logInfo(verbose, TAG + ": PackageInfoHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": PackageInfoHooks failed: " + e.getMessage());
        }

        try {
            BatteryHooks.hook(lpparam);
            logInfo(verbose, TAG + ": BatteryHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": BatteryHooks failed: " + e.getMessage());
        }

        if (realDeviceSdk >= 28) {
            try {
                EuiccHooks.hook(lpparam, realDeviceSdk);
                logInfo(verbose, TAG + ": EuiccHooks loaded");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": EuiccHooks failed: " + e.getMessage());
            }
        }

        try {
            InputDeviceHooks.hook(lpparam);
            logInfo(verbose, TAG + ": InputDeviceHooks loaded");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": InputDeviceHooks failed: " + e.getMessage());
        }

        // DisplayHooks, AccountHooks and the native layer sit behind config
        // flags. NativeHooks waits for applyLoadedConfig: a flag is only
        // certain once RemotePreferences has answered.
        installFlaggedHooks(lpparam, isOwnPackage);

        logInfo(verbose, TAG + ": All hooks initialized for " + lpparam.packageName);
    }

    // Build.VERSION.SDK_INT_FULL before BuildHooks replaces it. The field is
    // new in Android 16; 0 where it doesn't exist.
    private static int readSdkIntFull() {
        try {
            return Build.VERSION.class.getField("SDK_INT_FULL").getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    // A further package loaded into an already hooked process. Only the hooks
    // on classes that package carries itself (its own copy of the GMS client
    // library) are still to do.
    private static void hookLoadedPackage(XC_LoadPackage.LoadPackageParam lpparam,
                                          int realDeviceSdk) {
        try {
            AdvertisingIdHooks.hook(lpparam);
            if (realDeviceSdk >= 30) {
                AppSetIdHooks.hook(lpparam, realDeviceSdk);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hooks for loaded package " + lpparam.packageName
                    + " failed: " + t.getMessage());
        }
    }

    // The saved config has just been loaded (at startup or again later):
    // push it into everything that keeps a copy.
    private static void applyLoadedConfig(XC_LoadPackage.LoadPackageParam lpparam) {
        BuildHooks.refreshStaticFields(lpparam.classLoader);
        LocaleHooks.applyDefaults();
        KernelHooks.applyDefaults();
        WebViewHooks.onConfigChanged();
        installFlaggedHooks(lpparam, false);
        maybeInstallNativeHooks(lpparam);
    }

    // Opt-in hooks. Their flags read as "off" until the saved config is in, so
    // this runs after every load; each part installs at most once, and the
    // hooks themselves keep checking the flag, so it can also be turned off.
    private static void installFlaggedHooks(XC_LoadPackage.LoadPackageParam lpparam,
                                            boolean isOwnPackage) {
        if (isOwnPackage) {
            return;
        }
        boolean verbose = ConfigManager.isVerboseLoggingEnabled();
        if (ConfigManager.isDisplaySpoofEnabled() && sDisplayHooked.compareAndSet(false, true)) {
            try {
                DisplayHooks.hook(lpparam);
                logInfo(verbose, TAG + ": DisplayHooks loaded");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": DisplayHooks failed: " + e.getMessage());
            }
        }
        if (ConfigManager.isHideAccountsEnabled() && sAccountsHooked.compareAndSet(false, true)) {
            try {
                AccountHooks.hook(lpparam);
                logInfo(verbose, TAG + ": AccountHooks loaded");
            } catch (Exception e) {
                XposedBridge.log(TAG + ": AccountHooks failed: " + e.getMessage());
            }
        }
    }

    // Installs the native PLT hooks once the real config is available. The
    // native layer targets an app's own bundled .so libraries, so it is opt-in
    // (hooks.native_props) and never runs in the module's own process. Libraries
    // loaded after this point are picked up by NativeHooks.rehook() on the
    // refresh tick.
    private static void maybeInstallNativeHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ConfigManager.isNativePropsEnabled()) {
            return;
        }
        try {
            HashMap<String, String> settings = ConfigManager.getNativeSettings();
            // lpparam is the process's first package, the app itself. Only its
            // libraries are hooked: the WebView and Play services code loaded
            // into the process are other packages'.
            settings.put("app.package", lpparam.packageName);
            NativeHooks.tryInstall(ConfigManager.getAllSpoofedProperties(), settings);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NativeHooks failed: " + t.getMessage());
        }
    }

    private static void logInfo(boolean verbose, String message) {
        if (verbose) {
            XposedBridge.log(message);
        }
    }

    private static final long REFRESH_INTERVAL_MS = 2_000L;
    private static final AtomicBoolean sRefreshInstalled = new AtomicBoolean(false);
    private static android.os.HandlerThread sRefreshThread;
    private static android.os.Handler sRefreshHandler;

    private static void installRemoteRefreshLoop(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!sRefreshInstalled.compareAndSet(false, true)) return;
        try {
            sRefreshThread = new android.os.HandlerThread("spoof-refresh",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            sRefreshThread.start();
            sRefreshHandler = new android.os.Handler(sRefreshThread.getLooper());
            final Runnable tick = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (ConfigManager.refreshFromRemoteIfNewer()) {
                            applyLoadedConfig(lpparam);
                        }
                        // Pick up app-owned native libs loaded since install
                        // (e.g. after System.loadLibrary). No-op unless the
                        // native layer is installed.
                        NativeHooks.rehook();
                    } catch (Throwable t) {
                        Log.w(TAG, "Remote refresh tick failed: " + t.getMessage());
                    } finally {
                        sRefreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
                    }
                }
            };
            sRefreshHandler.postDelayed(tick, REFRESH_INTERVAL_MS);
            logInfo(ConfigManager.isVerboseLoggingEnabled(), TAG
                    + ": Remote refresh loop installed (interval=" + REFRESH_INTERVAL_MS + "ms)");
        } catch (Throwable t) {
            sRefreshInstalled.set(false);
            Log.w(TAG, "installRemoteRefreshLoop failed: " + t.getMessage());
        }
    }
}
