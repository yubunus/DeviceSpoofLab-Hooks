package com.devicespooflab.hooks.utils;

import com.devicespooflab.hooks.ui.IdentifierRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConfigManager {

    private static final String DEFAULT_CPUINFO_FEATURES =
            "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp"
            + " cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve"
            + " asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes"
            + " svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm"
            + " bf16 dgh bti";

    private static final String DEFAULT_CPUINFO_PARTS =
            "0xd05,0xd05,0xd05,0xd05,0xd41,0xd41,0xd44,0xd44";
    private static final String DEFAULT_CPUINFO_VARIANTS =
            "0x1,0x1,0x1,0x1,0x1,0x1,0x1,0x1";
    private static final String DEFAULT_CPUINFO_REVISIONS =
            "0,0,0,0,0,0,0,0";

    private static final String[] CONFIG_PATHS = {
        "/data/data/com.devicespooflab.hooks/files/device_profile.conf",
    };

    private static volatile Map<String, String> allProperties = null;

    // Where the live config came from, for the verbose startup line.
    private static volatile String loadSource = "embedded defaults";

    private static volatile String cachedIMEI = null;
    private static volatile String cachedMEID = null;
    private static volatile String cachedIMSI = null;
    private static volatile String cachedICCID = null;
    private static volatile String cachedPhoneNumber = null;
    private static volatile String cachedSerial = null;
    private static volatile String cachedBootloader = null;
    private static volatile String cachedBuildId = null;
    private static volatile String cachedBuildFingerprint = null;
    private static volatile String cachedBuildIncremental = null;
    private static volatile String cachedGAID = null;
    private static volatile String cachedGSFId = null;
    private static volatile String cachedAndroidId = null;
    private static volatile byte[] cachedMediaDrmId = null;
    private static volatile String cachedAppSetId = null;

    public static synchronized void init() {
        // Idempotent: LSPosed calls handleLoadPackage once per package loaded
        // in a process. Re-running init would clobber a live config that the
        // Application.attach hook has already pulled from RemotePreferences.
        if (allProperties != null) return;
        Map<String, String> loaded = readXSharedPreferences();
        if (loaded == null || loaded.isEmpty()) {
            loaded = readConfigFile();
        }
        fillFromDefaults(loaded);
        resetCaches();
        allProperties = Collections.unmodifiableMap(new HashMap<>(loaded));
    }

    // Bridge classes live on the XposedBridge classloader, not the module
    // classloader, when loaded by Vector's zygisk path. Resolve through the
    // bridge's loader so XSharedPreferences is actually found.
    @SuppressWarnings("unchecked")
    private static Map<String, String> readXSharedPreferences() {
        try {
            ClassLoader bridgeLoader = de.robv.android.xposed.XposedBridge.class.getClassLoader();
            Class<?> xprefsClass = bridgeLoader == null
                    ? Class.forName("de.robv.android.xposed.XSharedPreferences")
                    : Class.forName("de.robv.android.xposed.XSharedPreferences", true, bridgeLoader);
            Object prefs = xprefsClass
                    .getConstructor(String.class, String.class)
                    .newInstance("com.devicespooflab.hooks", "config");
            try {
                xprefsClass.getMethod("makeWorldReadable").invoke(prefs);
            } catch (Throwable ignored) {
            }
            try {
                xprefsClass.getMethod("reload").invoke(prefs);
            } catch (Throwable ignored) {
            }
            Map<String, ?> raw = (Map<String, ?>) xprefsClass
                    .getMethod("getAll").invoke(prefs);
            if (raw == null || raw.isEmpty()) return null;
            Map<String, String> out = new HashMap<>(raw.size());
            for (Map.Entry<String, ?> e : raw.entrySet()) {
                Object v = e.getValue();
                out.put(e.getKey(), v == null ? "" : v.toString());
            }
            loadSource = "XSharedPreferences";
            return out;
        } catch (ClassNotFoundException e) {
            // Expected under the modern libxposed entry, which has no
            // XSharedPreferences; the config then comes from RemotePreferences.
            return null;
        } catch (Throwable t) {
            android.util.Log.w("DeviceSpoofLab",
                    "XSharedPreferences failed: " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
            return null;
        }
    }

    public static String getLoadSource() {
        return loadSource;
    }

    public static Map<String, String> getRawProperties() {
        Map<String, String> props = allProperties;
        if (props == null) {
            init();
            props = allProperties;
        }
        return props;
    }

    public static boolean isOwnPackageProcess(String processName) {
        if (processName == null) return false;
        return processName.equals("com.devicespooflab.hooks")
                || processName.startsWith("com.devicespooflab.hooks:");
    }

    public static synchronized boolean loadFromRemotePreferences() {
        // libxposed:service memoizes RemotePreferences per group, so the fresh
        // variant is required both for startup load and for refresh-poll reload.
        android.content.SharedPreferences prefs =
                XposedServiceBridge.getRemotePreferencesFresh("config");
        if (prefs == null) return false;
        try {
            Map<String, ?> raw = prefs.getAll();
            if (raw == null || raw.isEmpty()) return false;
            Map<String, String> result = new HashMap<>(raw.size());
            for (Map.Entry<String, ?> e : raw.entrySet()) {
                if (e.getKey() == null) continue;
                Object v = e.getValue();
                result.put(e.getKey(), v == null ? "" : v.toString());
            }
            fillFromDefaults(result);
            resetCaches();
            allProperties = Collections.unmodifiableMap(result);
            loadSource = "RemotePreferences";
            return true;
        } catch (Throwable t) {
            android.util.Log.w("DeviceSpoofLab",
                    "loadFromRemotePreferences failed: " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
            return false;
        }
    }

    public static synchronized boolean publishToRemotePreferences() {
        android.content.SharedPreferences prefs =
                XposedServiceBridge.getRemotePreferences("config");
        if (prefs == null) return false;
        try {
            if (allProperties == null) init();
            String generation = String.valueOf(System.currentTimeMillis());
            android.content.SharedPreferences.Editor editor = prefs.edit().clear();
            for (Map.Entry<String, String> e : allProperties.entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                editor.putString(k, e.getValue() == null ? "" : e.getValue());
            }
            editor.putString(REMOTE_GENERATION_KEY, generation);
            boolean ok = editor.commit();
            if (ok) {
                Map<String, String> mutable = new HashMap<>(allProperties);
                mutable.put(REMOTE_GENERATION_KEY, generation);
                allProperties = Collections.unmodifiableMap(mutable);
            }
            android.util.Log.i("DeviceSpoofLab",
                    "publishToRemotePreferences commit=" + ok
                            + " entries=" + allProperties.size()
                            + " generation=" + generation);
            return ok;
        } catch (Throwable t) {
            android.util.Log.w("DeviceSpoofLab",
                    "publishToRemotePreferences failed: " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
            return false;
        }
    }

    // Cross-process freshness marker stamped on each publish; target processes
    // compare it against their in-memory copy to detect new edits.
    private static final String REMOTE_GENERATION_KEY = "_generation";

    public static String getLocalRemoteGeneration() {
        Map<String, String> props = allProperties;
        if (props == null) return null;
        return props.get(REMOTE_GENERATION_KEY);
    }

    // True when a newer config was loaded; the caller re-applies it.
    public static boolean refreshFromRemoteIfNewer() {
        android.content.SharedPreferences prefs =
                XposedServiceBridge.getRemotePreferencesFresh("config");
        if (prefs == null) return false;
        String remoteGen;
        try {
            remoteGen = prefs.getString(REMOTE_GENERATION_KEY, null);
        } catch (Throwable t) {
            return false;
        }
        if (remoteGen == null || remoteGen.isEmpty()) return false;
        String localGen = getLocalRemoteGeneration();
        if (remoteGen.equals(localGen)) return false;
        boolean reloaded = loadFromRemotePreferences();
        if (reloaded && isVerboseLoggingEnabled()) {
            android.util.Log.i("DeviceSpoofLab",
                    "Remote config refreshed: " + localGen + " -> " + remoteGen);
        }
        return reloaded;
    }

    private static void resetCaches() {
        cachedIMEI = null;
        cachedMEID = null;
        cachedIMSI = null;
        cachedICCID = null;
        cachedPhoneNumber = null;
        cachedSerial = null;
        cachedBootloader = null;
        cachedBuildId = null;
        cachedBuildFingerprint = null;
        cachedBuildIncremental = null;
        cachedGAID = null;
        cachedGSFId = null;
        cachedAndroidId = null;
        cachedMediaDrmId = null;
        cachedAppSetId = null;
        cachedWifiMac = null;
        cachedBssid = null;
        cachedBluetoothMac = null;
        cachedEid = null;
    }

    // Defaults that hold a fact about one exact device or build and that a
    // config saved by an older version may lack. Such a config can describe
    // any device, so it gets none of them from the embedded profile: they read
    // as unset, derived or passed through until the app writes them.
    private static final Set<String> PROFILE_ONLY_DEFAULTS = new HashSet<>(Arrays.asList(
            "ro.build.date", "ro.build.date.utc", "ro.vendor.build.date.utc",
            "ro.build.host", "ro.build.user", "ro.build.version.sdk_full",
            "ro.product.first_api_level", "gsm.version.baseband",
            "ro.bootloader", "bootloader.prefix", "kernel.osrelease", "kernel.version"));

    private static void fillFromDefaults(Map<String, String> loaded) {
        boolean hasProfile = loaded.containsKey("ro.product.model");
        for (Map.Entry<String, String> e : getEmbeddedDefaults().entrySet()) {
            if (loaded.containsKey(e.getKey())) continue;
            if (hasProfile && PROFILE_ONLY_DEFAULTS.contains(e.getKey())) continue;
            loaded.put(e.getKey(), e.getValue());
        }
    }

    private static Map<String, String> readConfigFile() {
        Map<String, String> config = new HashMap<>();

        for (String configPath : CONFIG_PATHS) {
            File configFile = new File(configPath);
            if (configFile.exists() && configFile.canRead()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();

                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }

                        int equalIndex = line.indexOf('=');
                        if (equalIndex > 0) {
                            String key = line.substring(0, equalIndex).trim();
                            String value = line.substring(equalIndex + 1).trim();

                            if (value.startsWith("\"") && value.endsWith("\"")) {
                                value = value.substring(1, value.length() - 1);
                            }

                            config.put(key, value);
                        }
                    }
                    loadSource = "config file";
                    return config;
                } catch (Exception e) {
                    android.util.Log.w("DeviceSpoofLab",
                            "config read failed " + configPath + ": " + e.getMessage());
                }
            }
        }

        loadSource = "embedded defaults";
        return getEmbeddedDefaults();
    }

    private static Map<String, String> getEmbeddedDefaults() {
        Map<String, String> defaults = new HashMap<>();

        defaults.put("ro.product.brand", "google");
        defaults.put("ro.product.manufacturer", "Google");
        defaults.put("ro.product.model", "Pixel 7 Pro");
        defaults.put("ro.product.name", "cheetah");
        defaults.put("ro.product.device", "cheetah");
        defaults.put("ro.product.board", "cheetah");
        defaults.put("ro.hardware", "cheetah");
        defaults.put("ro.board.platform", "gs201");

        String[] partitions = {"product", "system", "system_ext", "vendor", "vendor_dlkm", "odm", "bootimage", "system_dlkm"};
        for (String partition : partitions) {
            defaults.put("ro.product." + partition + ".brand", "google");
            defaults.put("ro.product." + partition + ".manufacturer", "Google");
            defaults.put("ro.product." + partition + ".model", "Pixel 7 Pro");
            defaults.put("ro.product." + partition + ".name", "cheetah");
            defaults.put("ro.product." + partition + ".device", "cheetah");
        }
        // Pixels on Android 16 QPR2+ ship a generic system image.
        defaults.put("ro.product.system.device", "generic");
        defaults.put("ro.product.system.model", "Generic System");
        defaults.put("ro.product.system.name", "generic_system_google");

        defaults.put("ro.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");
        defaults.put("ro.build.id", "BP4A.251205.006");
        defaults.put("ro.build.display.id", "BP4A.251205.006");
        defaults.put("ro.build.version.incremental", "14401865");
        defaults.put("build.id.prefix", "BP4A");
        defaults.put("ro.build.type", "user");
        defaults.put("ro.build.tags", "release-keys");
        defaults.put("ro.build.description", "cheetah-user 16 BP4A.251205.006 14401865 release-keys");
        defaults.put("ro.build.product", "cheetah");
        defaults.put("ro.build.device", "cheetah");
        defaults.put("ro.build.characteristics", "nosdcard");
        defaults.put("ro.build.flavor", "cheetah-user");

        defaults.put("ro.build.version.release", "16");
        defaults.put("ro.build.version.release_or_codename", "16");
        defaults.put("ro.build.version.release_or_preview_display", "16");
        defaults.put("ro.build.version.sdk", "36");
        defaults.put("ro.build.version.codename", "REL");
        defaults.put("ro.build.version.security_patch", "2025-12-05");
        defaults.put("ro.build.version.sdk_full", "36.1");
        defaults.put("ro.build.date", "Fri Nov  7 12:51:59 UTC 2025");
        defaults.put("ro.build.date.utc", "1762519919");
        // Blank = same as ro.build.date.utc.
        defaults.put("ro.vendor.build.date.utc", "");
        defaults.put("ro.build.host", "5e9b4618686f");
        defaults.put("ro.build.user", "android-build");
        defaults.put("ro.product.first_api_level", "33");

        defaults.put("ro.product.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");
        defaults.put("ro.product.build.id", "BP4A.251205.006");
        defaults.put("ro.product.build.tags", "release-keys");
        defaults.put("ro.product.build.type", "user");
        defaults.put("ro.product.build.version.incremental", "14401865");
        defaults.put("ro.product.build.version.release", "16");
        defaults.put("ro.product.build.version.release_or_codename", "16");
        defaults.put("ro.product.build.version.sdk", "36");

        defaults.put("ro.system.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");
        defaults.put("ro.system_ext.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");
        defaults.put("ro.vendor.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");
        defaults.put("ro.odm.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");
        defaults.put("ro.bootimage.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");
        defaults.put("ro.system_dlkm.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");
        defaults.put("ro.vendor_dlkm.build.fingerprint", "google/cheetah/cheetah:16/BP4A.251205.006/14401865:user/release-keys");

        defaults.put("ro.vendor.build.version.release", "16");
        defaults.put("ro.vendor.build.version.release_or_codename", "16");
        defaults.put("ro.vendor_dlkm.build.version.release", "16");
        defaults.put("ro.vendor_dlkm.build.version.release_or_codename", "16");
        defaults.put("ro.odm.build.version.release", "16");
        defaults.put("ro.odm.build.version.release_or_codename", "16");
        defaults.put("ro.bootimage.build.version.release", "16");
        defaults.put("ro.bootimage.build.version.release_or_codename", "16");
        defaults.put("ro.system_dlkm.build.version.release", "16");
        defaults.put("ro.system_dlkm.build.version.release_or_codename", "16");

        defaults.put("ro.debuggable", "0");
        defaults.put("ro.secure", "1");
        defaults.put("ro.adb.secure", "1");
        defaults.put("ro.build.selinux", "0");
        defaults.put("ro.boot.verifiedbootstate", "green");
        defaults.put("ro.boot.flash.locked", "1");
        defaults.put("ro.boot.vbmeta.device_state", "locked");
        defaults.put("ro.boot.warranty_bit", "0");
        defaults.put("sys.oem_unlock_allowed", "0");
        defaults.put("ro.boot.veritymode", "enforcing");
        defaults.put("ro.crypto.state", "encrypted");
        defaults.put("ro.kernel.qemu", "0");
        defaults.put("ro.boot.qemu", "0");

        defaults.put("ro.boot.qemu.avd_name", "");
        defaults.put("ro.boot.qemu.camera_hq_edge_processing", "0");
        defaults.put("ro.boot.qemu.camera_protocol_ver", "0");
        defaults.put("ro.boot.qemu.cpuvulkan.version", "0");
        defaults.put("ro.boot.qemu.gltransport.drawFlushInterval", "0");
        defaults.put("ro.boot.qemu.gltransport.name", "");
        defaults.put("ro.boot.qemu.hwcodec.avcdec", "0");
        defaults.put("ro.boot.qemu.hwcodec.hevcdec", "0");
        defaults.put("ro.boot.qemu.hwcodec.vpxdec", "0");
        defaults.put("ro.boot.qemu.settings.system.screen_off_timeout", "0");
        defaults.put("ro.boot.qemu.virtiowifi", "0");
        defaults.put("ro.boot.qemu.vsync", "0");

        defaults.put("ro.boot.hardware", "cheetah");
        defaults.put("ro.boot.hardware.vulkan", "mali");
        defaults.put("ro.boot.hardware.gltransport", "");
        defaults.put("ro.boot.mode", "normal");
        defaults.put("ro.product.cpu.abi", "arm64-v8a");
        // The Pixel 7 Pro is 64-bit only. Blank 64 / 32 lists are taken from
        // the full list.
        defaults.put("ro.product.cpu.abilist", "arm64-v8a");
        defaults.put("ro.product.cpu.abilist64", "");
        defaults.put("ro.product.cpu.abilist32", "");
        defaults.put("ro.arch", "arm64");
        defaults.put("ro.sf.lcd_density", "560");
        defaults.put("ro.treble.enabled", "true");

        defaults.put("hardware.cpu.cores", "8");
        defaults.put("cpuinfo.cores", "8");
        defaults.put("cpuinfo.bogomips", "38.40");
        defaults.put("cpuinfo.features", DEFAULT_CPUINFO_FEATURES);
        defaults.put("cpuinfo.implementer", "0x41");
        defaults.put("cpuinfo.architecture", "8");
        defaults.put("cpuinfo.variants", DEFAULT_CPUINFO_VARIANTS);
        defaults.put("cpuinfo.parts", DEFAULT_CPUINFO_PARTS);
        defaults.put("cpuinfo.revisions", DEFAULT_CPUINFO_REVISIONS);
        defaults.put("cpuinfo.hardware", "Cheetah");
        defaults.put("cpuinfo.revision", "0001");

        defaults.put("ro.hardware.vulkan", "mali");
        // Not set on real Pixels; blank = passthrough.
        defaults.put("ro.hardware.gralloc", "");
        defaults.put("ro.hardware.power", "");
        defaults.put("ro.hardware.egl", "mali");
        defaults.put("ro.soc.model", "GS201");
        defaults.put("ro.soc.manufacturer", "Google");
        // Blank: Titan M2 for a Google profile on Android 17+, unset otherwise.
        defaults.put("ro.strongbox.manufacturer", "");
        defaults.put("ro.strongbox.model", "");

        defaults.put("screen.width", "1440");
        defaults.put("screen.height", "3120");
        defaults.put("screen.density", "560");

        defaults.put("memory.total_kb", "12582912");
        defaults.put("memory.available_kb", "10485760");
        // Blank = passthrough: the heap limit the app really runs with.
        defaults.put("memory.class_mb", "");
        defaults.put("memory.large_class_mb", "");
        defaults.put("native_heap.scale", "4");
        // Blank = passthrough. These describe the heap this very process runs
        // with (the memory class is read from them), so the real ones stay.
        defaults.put("dalvik.vm.heapsize", "");
        defaults.put("dalvik.vm.heapgrowthlimit", "");
        defaults.put("dalvik.vm.heapmaxfree", "");
        defaults.put("dalvik.vm.heapminfree", "");
        defaults.put("dalvik.vm.heapstartsize", "");
        defaults.put("dalvik.vm.heaptargetutilization", "");

        defaults.put("gsm.operator.alpha", "T-Mobile");
        defaults.put("gsm.operator.numeric", "310260");
        defaults.put("gsm.sim.operator.alpha", "T-Mobile");
        defaults.put("gsm.sim.operator.numeric", "310260");
        defaults.put("gsm.sim.operator.iso-country", "us");
        defaults.put("gsm.version.baseband", "g5300q-250909-251024-B-14326967");
        // Empty = passthrough; MainActivity seeds a concrete TZ on first launch.
        defaults.put("persist.sys.timezone", "");
        defaults.put("persist.sys.usb.config", "none");

        // Blank = derive from the real WebView UA with the spoofed model/release/build.
        defaults.put("webview.user_agent", "");

        // Blank means generate or derive at runtime.
        defaults.put("SERIAL_NUMBER", "");
        defaults.put("ro.serialno", "");
        defaults.put("ro.boot.serialno", "");
        // Per build, not per device: the bootloader this build ships with.
        defaults.put("ro.bootloader", "cloudripper-16.4-14097579");
        defaults.put("bootloader.prefix", "cloudripper-16.4");
        defaults.put("ANDROID_ID", "");

        defaults.put("gpu.vendor", "ARM");
        defaults.put("gpu.renderer", "Mali-G710");
        defaults.put("gpu.unmasked_vendor", "ARM");
        defaults.put("gpu.unmasked_renderer", "Mali-G710");

        defaults.put("wifi.mac", "");
        defaults.put("wifi.bssid", "");
        defaults.put("wifi.ssid", "");
        defaults.put("bluetooth.mac", "");
        defaults.put("bluetooth.name", "");

        defaults.put("battery.capacity_uah", "5050000");
        defaults.put("battery.charge_counter_uah", "3800000");
        defaults.put("battery.energy_counter_nwh", "14250000000");

        defaults.put("storage.total_bytes", "137438953472");
        defaults.put("storage.available_bytes", "84296499200");

        // Blank = passthrough (the phone's own kernel).
        defaults.put("kernel.osrelease", "6.1.145-android14-11-gc1de4747ac59-ab14219743");
        defaults.put("kernel.version", "#1 SMP PREEMPT Mon Oct  6 16:50:48 UTC 2025");
        defaults.put("kernel.hostname", "localhost");

        defaults.put("locale.language", "en");
        defaults.put("locale.country", "US");
        defaults.put("persist.sys.locale", "en-US");

        defaults.put("install.installer_package", "com.android.vending");
        defaults.put("install.initiating_package", "com.android.vending");

        defaults.put("fingerprint.seed", "");

        defaults.put("hooks.hide_accounts", "0");
        // Off by default: spoofed display metrics break app layouts.
        defaults.put("hooks.spoof_display", "0");
        // Off by default: native PLT hooks only reach an app's own bundled .so
        // libraries and add a small startup cost, so they are opt-in. The Java
        // layer already spoofs the values apps read through the framework.
        defaults.put("hooks.native_props", "0");
        defaults.put("debug.verbose", "0");

        // 1 = spoof, 0 = passthrough.
        defaults.put("enabled.imei", "1");
        defaults.put("enabled.meid", "1");
        defaults.put("enabled.imsi", "1");
        defaults.put("enabled.iccid", "1");
        defaults.put("enabled.phone_number", "1");
        defaults.put("enabled.serial", "1");
        defaults.put("enabled.android_id", "1");
        defaults.put("enabled.gsf_id", "1");
        defaults.put("enabled.bootloader", "1");
        defaults.put("enabled.gaid", "1");
        defaults.put("enabled.app_set_id", "1");
        defaults.put("enabled.media_drm_id", "1");
        defaults.put("enabled.wifi_mac", "1");
        defaults.put("enabled.wifi_bssid", "1");
        defaults.put("enabled.bluetooth_mac", "1");
        defaults.put("enabled.eid", "1");
        defaults.put("enabled.build_fingerprint", "1");
        defaults.put("enabled.build_id", "1");
        defaults.put("enabled.build_incremental", "1");
        defaults.put("enabled.security_patch", "1");
        defaults.put("enabled.fingerprint_seed", "1");

        // Blank slots are generated on first read; the UI persists them.
        defaults.put("identifier.imei", "");
        defaults.put("identifier.meid", "");
        defaults.put("identifier.imsi", "");
        defaults.put("identifier.iccid", "");
        defaults.put("identifier.phone_number", "");
        defaults.put("identifier.gsf_id", "");
        defaults.put("identifier.gaid", "");
        defaults.put("identifier.app_set_id", "");
        defaults.put("identifier.media_drm_id", "");
        defaults.put("euicc.eid", "");

        return defaults;
    }

    public static String getDefaultConfigText() {
        return formatConfigText(getEmbeddedDefaults());
    }

    private static String formatConfigText(Map<String, String> defaults) {
        StringBuilder sb = new StringBuilder(12000);
        Set<String> emitted = new LinkedHashSet<>();

        sb.append("# DeviceSpoofLab-Hooks Auto-Generated Config\n");
        sb.append("# Default profile: Google Pixel 7 Pro (Android 16)\n");
        sb.append("# Edit values here to spoof a different Android device profile.\n");
        sb.append("# Blank identifier fields are generated or derived at runtime.\n\n");

        appendConfigBlock(sb, defaults, emitted, "Per-identifier toggles (1=spoof, 0=passthrough)",
                "enabled.imei", "enabled.meid", "enabled.imsi", "enabled.iccid",
                "enabled.phone_number", "enabled.serial", "enabled.android_id",
                "enabled.gsf_id", "enabled.bootloader", "enabled.gaid",
                "enabled.app_set_id", "enabled.media_drm_id", "enabled.wifi_mac",
                "enabled.wifi_bssid", "enabled.bluetooth_mac", "enabled.eid",
                "enabled.build_fingerprint", "enabled.build_id",
                "enabled.build_incremental", "enabled.security_patch",
                "enabled.fingerprint_seed");

        appendConfigBlock(sb, defaults, emitted, "Persisted random identifier values (blank = generate at runtime)",
                "identifier.imei", "identifier.meid", "identifier.imsi", "identifier.iccid",
                "identifier.phone_number", "identifier.gsf_id", "identifier.gaid",
                "identifier.app_set_id", "identifier.media_drm_id", "euicc.eid");

        appendConfigBlock(sb, defaults, emitted, "Device identity",
                "ro.product.brand", "ro.product.manufacturer", "ro.product.model",
                "ro.product.name", "ro.product.device", "ro.product.board",
                "ro.hardware", "ro.board.platform", "ro.soc.manufacturer", "ro.soc.model",
                "ro.strongbox.manufacturer", "ro.strongbox.model");

        appendConfigBlock(sb, defaults, emitted, "Partition identity",
                "ro.product.product.brand", "ro.product.product.manufacturer",
                "ro.product.product.model", "ro.product.product.name", "ro.product.product.device",
                "ro.product.system.brand", "ro.product.system.manufacturer",
                "ro.product.system.model", "ro.product.system.name", "ro.product.system.device",
                "ro.product.system_ext.brand", "ro.product.system_ext.manufacturer",
                "ro.product.system_ext.model", "ro.product.system_ext.name", "ro.product.system_ext.device",
                "ro.product.vendor.brand", "ro.product.vendor.manufacturer",
                "ro.product.vendor.model", "ro.product.vendor.name", "ro.product.vendor.device",
                "ro.product.vendor_dlkm.brand", "ro.product.vendor_dlkm.manufacturer",
                "ro.product.vendor_dlkm.model", "ro.product.vendor_dlkm.name", "ro.product.vendor_dlkm.device",
                "ro.product.odm.brand", "ro.product.odm.manufacturer",
                "ro.product.odm.model", "ro.product.odm.name", "ro.product.odm.device",
                "ro.product.bootimage.brand", "ro.product.bootimage.manufacturer",
                "ro.product.bootimage.model", "ro.product.bootimage.name", "ro.product.bootimage.device",
                "ro.product.system_dlkm.brand", "ro.product.system_dlkm.manufacturer",
                "ro.product.system_dlkm.model", "ro.product.system_dlkm.name", "ro.product.system_dlkm.device");

        appendConfigBlock(sb, defaults, emitted, "Build information",
                "ro.build.fingerprint", "ro.build.id", "ro.build.display.id",
                "build.id.prefix", "ro.build.version.incremental", "ro.build.type",
                "ro.build.tags", "ro.build.description", "ro.build.product",
                "ro.build.device", "ro.build.characteristics", "ro.build.flavor",
                "ro.build.version.release", "ro.build.version.release_or_codename",
                "ro.build.version.release_or_preview_display", "ro.build.version.sdk",
                "ro.build.version.sdk_full", "ro.build.version.codename",
                "ro.build.version.security_patch", "ro.build.date", "ro.build.date.utc",
                "ro.vendor.build.date.utc", "ro.build.host", "ro.build.user",
                "ro.product.first_api_level");

        appendConfigBlock(sb, defaults, emitted, "Partition build information",
                "ro.product.build.fingerprint", "ro.product.build.id",
                "ro.product.build.tags", "ro.product.build.type",
                "ro.product.build.version.incremental", "ro.product.build.version.release",
                "ro.product.build.version.release_or_codename", "ro.product.build.version.sdk",
                "ro.system.build.fingerprint", "ro.system_ext.build.fingerprint",
                "ro.vendor.build.fingerprint", "ro.odm.build.fingerprint",
                "ro.bootimage.build.fingerprint", "ro.system_dlkm.build.fingerprint",
                "ro.vendor_dlkm.build.fingerprint", "ro.vendor.build.version.release",
                "ro.vendor.build.version.release_or_codename",
                "ro.vendor_dlkm.build.version.release",
                "ro.vendor_dlkm.build.version.release_or_codename",
                "ro.odm.build.version.release", "ro.odm.build.version.release_or_codename",
                "ro.bootimage.build.version.release",
                "ro.bootimage.build.version.release_or_codename",
                "ro.system_dlkm.build.version.release",
                "ro.system_dlkm.build.version.release_or_codename");

        appendConfigBlock(sb, defaults, emitted, "Security and emulator denylists",
                "ro.debuggable", "ro.secure", "ro.adb.secure", "ro.build.selinux",
                "ro.boot.verifiedbootstate", "ro.boot.flash.locked",
                "ro.boot.vbmeta.device_state", "ro.boot.warranty_bit",
                "sys.oem_unlock_allowed", "ro.boot.veritymode", "ro.crypto.state",
                "ro.kernel.qemu", "ro.boot.qemu");

        appendConfigBlock(sb, defaults, emitted, "Hardware, CPU, memory, and display",
                "ro.boot.hardware", "ro.boot.hardware.vulkan", "ro.boot.hardware.gltransport",
                "ro.boot.mode", "ro.product.cpu.abi", "ro.product.cpu.abilist",
                "ro.product.cpu.abilist64", "ro.product.cpu.abilist32", "ro.arch",
                "ro.sf.lcd_density", "ro.treble.enabled", "ro.hardware.vulkan",
                "ro.hardware.gralloc", "ro.hardware.power", "ro.hardware.egl",
                "hardware.cpu.cores", "cpuinfo.cores", "cpuinfo.bogomips",
                "cpuinfo.features", "cpuinfo.implementer", "cpuinfo.architecture",
                "cpuinfo.variants", "cpuinfo.parts", "cpuinfo.revisions",
                "cpuinfo.hardware", "cpuinfo.revision", "memory.total_kb",
                "memory.available_kb", "memory.class_mb", "memory.large_class_mb",
                "native_heap.scale", "screen.width", "screen.height", "screen.density",
                "dalvik.vm.heapsize", "dalvik.vm.heapgrowthlimit",
                "dalvik.vm.heapmaxfree", "dalvik.vm.heapminfree",
                "dalvik.vm.heapstartsize", "dalvik.vm.heaptargetutilization");

        appendConfigBlock(sb, defaults, emitted, "Identifiers",
                "SERIAL_NUMBER", "ro.serialno", "ro.boot.serialno", "ro.bootloader",
                "bootloader.prefix", "ANDROID_ID", "fingerprint.seed");

        appendConfigBlock(sb, defaults, emitted, "Network, Bluetooth, WebView, and GPU",
                "wifi.mac", "wifi.bssid", "wifi.ssid", "bluetooth.mac",
                "bluetooth.name", "webview.user_agent", "gpu.vendor",
                "gpu.renderer", "gpu.unmasked_vendor", "gpu.unmasked_renderer");

        appendConfigBlock(sb, defaults, emitted, "Battery, storage, kernel, locale, and install metadata",
                "battery.capacity_uah", "battery.charge_counter_uah",
                "battery.energy_counter_nwh", "storage.total_bytes",
                "storage.available_bytes", "kernel.osrelease", "kernel.version",
                "kernel.hostname", "locale.language", "locale.country", "persist.sys.locale",
                "install.installer_package", "install.initiating_package");

        appendConfigBlock(sb, defaults, emitted, "Carrier and GSM",
                "gsm.operator.alpha", "gsm.operator.numeric", "gsm.sim.operator.alpha",
                "gsm.sim.operator.numeric", "gsm.sim.operator.iso-country",
                "gsm.version.baseband", "persist.sys.timezone", "persist.sys.usb.config");

        appendConfigBlock(sb, defaults, emitted, "Behavior flags",
                "hooks.hide_accounts", "hooks.spoof_display", "hooks.native_props",
                "debug.verbose");

        List<String> remaining = new ArrayList<>();
        for (String key : defaults.keySet()) {
            if (!emitted.contains(key)) {
                remaining.add(key);
            }
        }
        Collections.sort(remaining);
        if (!remaining.isEmpty()) {
            appendConfigBlock(sb, defaults, emitted, "Additional properties",
                    remaining.toArray(new String[0]));
        }

        return sb.toString();
    }

    private static void appendConfigBlock(StringBuilder sb, Map<String, String> defaults,
                                          Set<String> emitted, String title, String... keys) {
        sb.append("# ").append(title).append('\n');
        for (String key : keys) {
            if (!defaults.containsKey(key)) {
                continue;
            }
            sb.append(key).append('=').append(defaults.get(key)).append('\n');
            emitted.add(key);
        }
        sb.append('\n');
    }

    private static String getConfigValue(String key) {
        Map<String, String> props = allProperties;
        if (props == null) {
            init();
            props = allProperties;
        }
        return props.get(key);
    }

    private static boolean hasConfigValue(String key) {
        String value = getConfigValue(key);
        return value != null && !value.isEmpty();
    }

    // Config keys that are settings of this module, not system properties. A
    // real device has no property by these names, so a property read must
    // never be answered from them.
    private static final String[] INTERNAL_PREFIXES = {
            "enabled.", "identifier.", "hooks.", "screen.", "memory.", "cpuinfo.",
            "gpu.", "battery.", "storage.", "kernel.", "locale.", "install.", "_"};
    private static final Set<String> INTERNAL_KEYS = new HashSet<>(Arrays.asList(
            "debug.verbose", "hardware.cpu.cores", "native_heap.scale",
            "build.id.prefix", "bootloader.prefix", "SERIAL_NUMBER", "ANDROID_ID",
            "fingerprint.seed", "wifi.mac", "wifi.bssid", "wifi.ssid",
            "bluetooth.mac", "bluetooth.name", "webview.user_agent", "euicc.eid"));

    public static boolean isInternalKey(String key) {
        if (INTERNAL_KEYS.contains(key)) return true;
        for (String prefix : INTERNAL_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    // Properties whose real value names the phone behind the profile. Without a
    // configured value they read as unset, the way they do on a device that
    // doesn't define them, rather than as the phone's own.
    private static final Set<String> MASKED_KEYS = new HashSet<>(Arrays.asList(
            "gsm.version.baseband", "ro.boot.hardware.sku",
            "ro.boot.product.hardware.sku"));

    public static String getSystemProperty(String key, String defaultValue) {
        if (key == null || isInternalKey(key)) {
            return defaultValue;
        }
        String identifier = IdentifierRegistry.identifierForKey(key);
        if (identifier != null && !isIdentifierEnabled(identifier)) {
            return defaultValue;
        }
        if (isSuppressedKey(key)) {
            return defaultValue;
        }
        String value = getConfigValue(key);

        if ("ro.serialno".equals(key) || "ro.boot.serialno".equals(key)) {
            String resolved = (value == null || value.isEmpty()) ? getSerial() : value;
            return resolved != null ? resolved : defaultValue;
        }

        if ("ro.bootloader".equals(key)) {
            String resolved = (value == null || value.isEmpty()) ? getBuildBootloader() : value;
            return resolved != null ? resolved : defaultValue;
        }

        if ("ro.build.fingerprint".equals(key)) {
            String resolved = (value == null || value.isEmpty()) ? getBuildFingerprint() : value;
            return resolved != null ? resolved : defaultValue;
        }

        if ("ro.build.id".equals(key) || "ro.build.display.id".equals(key)) {
            String resolved = (value == null || value.isEmpty()) ? getBuildId() : value;
            return resolved != null ? resolved : defaultValue;
        }

        if ("ro.build.version.incremental".equals(key)) {
            String resolved = (value == null || value.isEmpty()) ? getBuildVersionIncremental() : value;
            return resolved != null ? resolved : defaultValue;
        }

        if ("ro.build.version.sdk_full".equals(key) && value != null && !value.isEmpty()) {
            return limitSdkFull(value);
        }

        // Empty = passthrough; special-cased keys above handle empty-as-generate.
        if (value != null && !value.isEmpty()) {
            return value;
        }
        String derived = getDerivedProperty(key);
        return derived != null ? derived : defaultValue;
    }

    // Properties the profile doesn't carry but that must still agree with it.
    // Null = no opinion, the real property passes through.
    private static String getDerivedProperty(String key) {
        if (MASKED_KEYS.contains(key)) {
            return "";
        }
        if ("ro.build.host".equals(key) || "ro.build.user".equals(key)) {
            return "android-build";
        }
        if ("ro.strongbox.manufacturer".equals(key) || "ro.strongbox.model".equals(key)) {
            // The security chip behind Android 17's Build.STRONGBOX_*. Pixel
            // builds declare their Titan M2 from Android 17 on; any other
            // profile without a value of its own reads as unset, never as the
            // chip of the phone it runs on.
            if (!"Google".equalsIgnoreCase(getConfigValue("ro.product.manufacturer"))
                    || getBuildVersionSdk() < 37) {
                return "";
            }
            return key.endsWith(".manufacturer") ? "Google" : "Titan-M2";
        }
        if ("ro.build.version.sdk_full".equals(key)) {
            if (!hasConfigValue("ro.build.version.sdk")) return null;
            int sdk = getBuildVersionSdk();
            // The property exists from Android 16 on.
            return sdk >= 36 ? sdk + ".0" : "";
        }
        if (isAbiListKey(key)) {
            String all = getConfigValue("ro.product.cpu.abilist");
            if (all == null || all.isEmpty()) return null;
            if (key.endsWith("abilist64")) return filterAbis(all, true);
            if (key.endsWith("abilist32")) return filterAbis(all, false);
            return all;
        }
        if (key.startsWith("ro.") && key.endsWith("build.date.utc")) {
            long seconds = getBuildDateUtcSeconds(key);
            return seconds > 0 ? Long.toString(seconds) : null;
        }
        if (key.startsWith("ro.") && key.endsWith("build.date")) {
            long seconds = getBuildDateUtcSeconds(key);
            if (seconds <= 0) return null;
            java.util.Calendar cal = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"), Locale.US);
            cal.setTimeInMillis(seconds * 1000L);
            // The form `date` prints at build time: "Fri Dec  5 00:00:00 UTC 2025".
            return String.format(Locale.US, "%1$ta %1$tb %1$2te %1$tT UTC %1$tY", cal);
        }
        return null;
    }

    // Build.VERSION.SDK_INT_FULL of the phone itself (major * 100000 + minor),
    // handed in by MainHook before BuildHooks replaces the field. 0 = not
    // known: below Android 16, or outside a hooked process.
    private static volatile int realSdkIntFull;

    public static void setRealSdkIntFull(int sdkIntFull) {
        realSdkIntFull = sdkIntFull;
    }

    // A profile of the phone's own Android version never reports a minor
    // version above the phone's: "36.1" on a phone that runs 36.0 reads "36.0".
    // An app that guards a 36.1 API by SDK_INT_FULL would otherwise call a
    // method the phone doesn't have. Build.VERSION.SDK_INT_FULL is derived
    // from this property, so the two agree.
    private static String limitSdkFull(String full) {
        int real = realSdkIntFull;
        int dot = full.indexOf('.');
        if (real <= 0 || dot <= 0) {
            return full;
        }
        try {
            int major = Integer.parseInt(full.substring(0, dot).trim());
            int minor = Integer.parseInt(full.substring(dot + 1).trim());
            if (major == real / 100000 && minor > real % 100000) {
                return major + "." + (real % 100000);
            }
        } catch (NumberFormatException ignored) {
        }
        return full;
    }

    // ro.product.cpu.abilist and its 64 / 32 and per-partition copies
    // (ro.vendor.product.cpu.abilist32, ...).
    private static boolean isAbiListKey(String key) {
        return key.endsWith(".cpu.abilist") || key.endsWith(".cpu.abilist64")
                || key.endsWith(".cpu.abilist32");
    }

    private static String filterAbis(String abis, boolean want64) {
        StringBuilder out = new StringBuilder();
        for (String abi : abis.split(",")) {
            abi = abi.trim();
            if (abi.isEmpty() || abi.contains("64") != want64) continue;
            if (out.length() > 0) out.append(',');
            out.append(abi);
        }
        return out.toString();
    }

    // A process only exists on a device with ABIs of its own bitness. When the
    // profile has none (a 32-bit app under a 64-bit-only profile, or a 64-bit
    // app under a 32-bit-only one), its ABI lists would contradict the process
    // and steer native-library loaders wrong, so the phone's own lists stay
    // visible there.
    public static boolean isAbiSpoofSuppressed() {
        boolean is64;
        try {
            is64 = android.os.Process.is64Bit();
        } catch (Throwable t) {
            return false;
        }
        String own = getConfigValue(is64
                ? "ro.product.cpu.abilist64" : "ro.product.cpu.abilist32");
        if (own == null || own.isEmpty()) {
            String all = getConfigValue("ro.product.cpu.abilist");
            own = all == null ? "" : filterAbis(all, is64);
        }
        return own.isEmpty();
    }

    private static final Set<String> VENDOR_SIDE_PARTITIONS = new HashSet<>(Arrays.asList(
            "vendor", "vendor_dlkm", "odm", "odm_dlkm", "bootimage"));

    // Build time of the partition a ro.<partition>.build.date[.utc] key names.
    // Vendor-side partitions follow ro.vendor.build.date.utc when the profile
    // has one (OEMs build them apart from the system image).
    private static long getBuildDateUtcSeconds(String key) {
        String[] parts = key.split("\\.");
        if (parts.length > 2 && VENDOR_SIDE_PARTITIONS.contains(parts[1])
                && isIdentifierEnabled("build_id")) {
            String vendor = getConfigValue("ro.vendor.build.date.utc");
            if (vendor != null && !vendor.isEmpty()) {
                try {
                    return Long.parseLong(vendor.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return getBuildDateUtcSeconds();
    }

    // Build time in seconds; -1 = leave the phone's own. A profile without
    // ro.build.date.utc gets the day of its security patch, else the date code
    // of its build ID (Google's YYMMDD): both sit within weeks of the real
    // build date, while the phone's own value belongs to a different build.
    public static long getBuildDateUtcSeconds() {
        if (!isIdentifierEnabled("build_id")) return -1L;
        String configured = getConfigValue("ro.build.date.utc");
        if (configured != null && !configured.isEmpty()) {
            try {
                return Long.parseLong(configured.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        long seconds = dayToEpochSeconds(getBuildVersionSecurityPatch());
        if (seconds > 0) return seconds;
        String buildId = getConfigValue("ro.build.id");
        if (buildId != null) {
            String[] parts = buildId.split("\\.");
            if (parts.length >= 2 && parts[1].matches("\\d{6}")) {
                String code = parts[1];
                return dayToEpochSeconds("20" + code.substring(0, 2) + "-"
                        + code.substring(2, 4) + "-" + code.substring(4, 6));
            }
        }
        return -1L;
    }

    // "2025-12-05" -> midnight UTC of that day, -1 if it isn't a date.
    private static long dayToEpochSeconds(String day) {
        if (day == null) return -1L;
        try {
            return java.time.LocalDate.parse(day.trim())
                    .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    public static synchronized String getIMEI() {
        if (!isIdentifierEnabled("imei")) return null;
        if (cachedIMEI == null) {
            String configured = getConfigValue("identifier.imei");
            cachedIMEI = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateIMEI();
        }
        return cachedIMEI;
    }

    // Slot 0 is the configured IMEI. The second slot of a dual-SIM phone has
    // its own: the same TAC and the next serial number.
    public static String getIMEI(int slot) {
        String imei = getIMEI();
        if (imei == null || slot <= 0) return imei;
        return RandomGenerator.nextImei(imei);
    }

    public static synchronized String getMEID() {
        if (!isIdentifierEnabled("meid")) return null;
        if (cachedMEID == null) {
            String configured = getConfigValue("identifier.meid");
            cachedMEID = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateMEID();
        }
        return cachedMEID;
    }

    public static synchronized String getIMSI() {
        if (!isIdentifierEnabled("imsi")) return null;
        if (cachedIMSI == null) {
            String configured = getConfigValue("identifier.imsi");
            cachedIMSI = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateIMSI();
        }
        return cachedIMSI;
    }

    public static synchronized String getICCID() {
        if (!isIdentifierEnabled("iccid")) return null;
        if (cachedICCID == null) {
            String configured = getConfigValue("identifier.iccid");
            cachedICCID = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateICCID();
        }
        return cachedICCID;
    }

    public static synchronized String getPhoneNumber() {
        if (!isIdentifierEnabled("phone_number")) return null;
        if (cachedPhoneNumber == null) {
            String configured = getConfigValue("identifier.phone_number");
            cachedPhoneNumber = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generatePhoneNumber();
        }
        return cachedPhoneNumber;
    }

    public static synchronized String getSerial() {
        if (!isIdentifierEnabled("serial")) return null;
        if (cachedSerial == null) {
            if (hasConfigValue("SERIAL_NUMBER")) {
                cachedSerial = getConfigValue("SERIAL_NUMBER");
            } else if (hasConfigValue("ro.serialno")) {
                cachedSerial = getConfigValue("ro.serialno");
            } else if (hasConfigValue("ro.boot.serialno")) {
                cachedSerial = getConfigValue("ro.boot.serialno");
            } else {
                cachedSerial = RandomGenerator.generateSerial();
            }
        }
        return cachedSerial;
    }

    public static synchronized String getGAID() {
        if (!isIdentifierEnabled("gaid")) return null;
        if (cachedGAID == null) {
            String configured = getConfigValue("identifier.gaid");
            cachedGAID = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateGAID();
        }
        return cachedGAID;
    }

    public static synchronized String getGSFId() {
        if (!isIdentifierEnabled("gsf_id")) return null;
        if (cachedGSFId == null) {
            String configured = getConfigValue("identifier.gsf_id");
            cachedGSFId = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateGSFId();
        }
        return cachedGSFId;
    }

    public static synchronized String getAndroidId() {
        if (!isIdentifierEnabled("android_id")) return null;
        if (cachedAndroidId == null) {
            if (hasConfigValue("ANDROID_ID")) {
                cachedAndroidId = getConfigValue("ANDROID_ID");
            } else {
                cachedAndroidId = RandomGenerator.generateAndroidId();
            }
        }
        return cachedAndroidId;
    }

    public static synchronized byte[] getMediaDrmId() {
        if (!isIdentifierEnabled("media_drm_id")) return null;
        if (cachedMediaDrmId == null) {
            String configured = getConfigValue("identifier.media_drm_id");
            if (configured != null && !configured.isEmpty()) {
                cachedMediaDrmId = hexToBytes(configured);
            }
            if (cachedMediaDrmId == null) {
                cachedMediaDrmId = RandomGenerator.generateMediaDrmId();
            }
        }
        return cachedMediaDrmId;
    }

    public static synchronized String getAppSetId() {
        if (!isIdentifierEnabled("app_set_id")) return null;
        if (cachedAppSetId == null) {
            String configured = getConfigValue("identifier.app_set_id");
            cachedAppSetId = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateGAID();
        }
        return cachedAppSetId;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        int len = hex.length();
        if ((len & 1) != 0) return null;
        byte[] out = new byte[len / 2];
        try {
            for (int i = 0; i < len; i += 2) {
                int hi = Character.digit(hex.charAt(i), 16);
                int lo = Character.digit(hex.charAt(i + 1), 16);
                if (hi < 0 || lo < 0) return null;
                out[i / 2] = (byte) ((hi << 4) | lo);
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    public static boolean isConfigAvailable() {
        Map<String, String> props = allProperties;
        if (props == null) {
            init();
            props = allProperties;
        }
        return !props.isEmpty();
    }

    // Derived and masked properties, listed for the native map: the native
    // hooks can't call back here on every read.
    private static final String[] DERIVED_KEYS = {
            "gsm.version.baseband", "ro.boot.hardware.sku", "ro.boot.product.hardware.sku",
            "ro.build.host", "ro.build.user",
            "ro.strongbox.manufacturer", "ro.strongbox.model",
            "ro.build.version.sdk_full", "ro.build.date", "ro.build.date.utc",
            "ro.system.build.date.utc", "ro.system_ext.build.date.utc",
            "ro.product.build.date.utc", "ro.vendor.build.date.utc",
            "ro.vendor_dlkm.build.date.utc", "ro.odm.build.date.utc",
            "ro.odm_dlkm.build.date.utc", "ro.bootimage.build.date.utc",
            "ro.system_dlkm.build.date.utc",
            "ro.product.cpu.abilist64", "ro.product.cpu.abilist32",
            "ro.system.product.cpu.abilist", "ro.system.product.cpu.abilist64",
            "ro.system.product.cpu.abilist32", "ro.vendor.product.cpu.abilist",
            "ro.vendor.product.cpu.abilist64", "ro.vendor.product.cpu.abilist32",
            "ro.odm.product.cpu.abilist", "ro.odm.product.cpu.abilist64",
            "ro.odm.product.cpu.abilist32"};

    // What a native property read is answered with: real property names only,
    // never the module's own settings. An empty value reads as unset.
    public static HashMap<String, String> getAllSpoofedProperties() {
        Map<String, String> props = allProperties;
        if (props == null) {
            init();
            props = allProperties;
        }
        HashMap<String, String> out = new HashMap<>(props.size() + 8);
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            if (isInternalKey(key)) continue;
            String identifier = IdentifierRegistry.identifierForKey(key);
            if (identifier != null && !isIdentifierEnabled(identifier)) continue;
            if (isSuppressedKey(key)) continue;
            out.put(key, v);
        }

        // Mirror getSystemProperty's special-cased keys so native sees the same answer.
        String serial = getSerial();
        if (serial != null && !serial.isEmpty()) {
            out.put("ro.serialno", serial);
            out.put("ro.boot.serialno", serial);
        }
        putIfNonEmpty(out, "ro.bootloader", getBuildBootloader());
        putIfNonEmpty(out, "ro.build.fingerprint", getBuildFingerprint());
        putIfNonEmpty(out, "ro.build.id", getBuildId());
        putIfNonEmpty(out, "ro.build.display.id", getBuildDisplay());
        putIfNonEmpty(out, "ro.build.version.incremental", getBuildVersionIncremental());

        for (String key : DERIVED_KEYS) {
            String v = getSystemProperty(key, null);
            if (v != null) out.put(key, v);
        }

        return out;
    }

    // Settings of the native layer itself. Kept apart from the property map
    // so no property read is ever answered from them.
    public static HashMap<String, String> getNativeSettings() {
        HashMap<String, String> out = new HashMap<>();
        out.put("debug.verbose", isVerboseLoggingEnabled() ? "1" : "0");
        putIfNonEmpty(out, "kernel.osrelease", getKernelOsRelease());
        putIfNonEmpty(out, "kernel.version", getKernelVersion());
        putIfNonEmpty(out, "kernel.hostname", getKernelHostname());
        putIfNonEmpty(out, "wifi.mac", getWifiMacAddress());
        putIfNonEmpty(out, "bluetooth.mac", getBluetoothMacAddress());
        return out;
    }

    // Props owned by an opt-in surface: passed through while that surface is off.
    private static boolean isSuppressedKey(String key) {
        if ("ro.sf.lcd_density".equals(key)) {
            return !isDisplaySpoofEnabled();
        }
        return ("ro.product.cpu.abi".equals(key) || isAbiListKey(key)) && isAbiSpoofSuppressed();
    }

    private static void putIfNonEmpty(Map<String, String> out, String key, String value) {
        if (value != null && !value.isEmpty()) {
            out.put(key, value);
        }
    }

    public static synchronized String getBuildFingerprint() {
        if (!isIdentifierEnabled("build_fingerprint")) return null;
        String fingerprint = getConfigValue("ro.build.fingerprint");
        if (fingerprint != null && !fingerprint.isEmpty()) {
            return fingerprint;
        }
        if (cachedBuildFingerprint == null) {
            cachedBuildFingerprint = RandomGenerator.generateFingerprint(
                    getBuildBrand(),
                    getBuildProduct(),
                    getBuildDevice(),
                    getBuildVersionRelease(),
                    getBuildId(),
                    getBuildVersionIncremental(),
                    getBuildType(),
                    getBuildTags()
            );
        }
        return cachedBuildFingerprint;
    }

    public static String getBuildModel() {
        return getConfigValue("ro.product.model");
    }

    public static String getBuildDevice() {
        return getConfigValue("ro.product.device");
    }

    public static String getBuildManufacturer() {
        return getConfigValue("ro.product.manufacturer");
    }

    public static String getBuildBrand() {
        return getConfigValue("ro.product.brand");
    }

    public static String getBuildProduct() {
        return getConfigValue("ro.product.name");
    }

    public static String getBuildBoard() {
        return getConfigValue("ro.product.board");
    }

    public static String getBuildHardware() {
        return getConfigValue("ro.hardware");
    }

    public static synchronized String getBuildBootloader() {
        if (!isIdentifierEnabled("bootloader")) return null;
        if (cachedBootloader == null) {
            String bootloader = getConfigValue("ro.bootloader");
            if (bootloader == null || bootloader.isEmpty()) {
                // Seeded from the profile's own identifiers, so every app and
                // every launch gets the same string.
                String prefix = propStringDef("bootloader.prefix", getBuildDevice());
                cachedBootloader = RandomGenerator.generateBootloader(prefix,
                        getRawProperty("ANDROID_ID") + getRawProperty("SERIAL_NUMBER"));
            } else {
                cachedBootloader = bootloader;
            }
        }
        return cachedBootloader;
    }

    public static synchronized String getBuildId() {
        if (!isIdentifierEnabled("build_id")) return null;
        String buildId = getConfigValue("ro.build.id");
        if (buildId != null && !buildId.isEmpty()) {
            return buildId;
        }
        if (cachedBuildId == null) {
            cachedBuildId = RandomGenerator.generateBuildId(
                    propStringDef("build.id.prefix", "AA1A")
            );
        }
        return cachedBuildId;
    }

    public static String getBuildDisplay() {
        if (!isIdentifierEnabled("build_id")) return null;
        String display = getConfigValue("ro.build.display.id");
        return (display != null && !display.isEmpty()) ? display : getBuildId();
    }

    public static String getBuildTags() {
        return getConfigValue("ro.build.tags");
    }

    public static String getBuildType() {
        return getConfigValue("ro.build.type");
    }

    public static String getBuildVersionRelease() {
        return getConfigValue("ro.build.version.release");
    }

    public static int getBuildVersionSdk() {
        String sdk = getConfigValue("ro.build.version.sdk");
        try {
            return Integer.parseInt(sdk);
        } catch (Exception e) {
            return 36;
        }
    }

    public static String getBuildVersionSecurityPatch() {
        if (!isIdentifierEnabled("security_patch")) return null;
        return getConfigValue("ro.build.version.security_patch");
    }

    public static synchronized String getBuildVersionIncremental() {
        if (!isIdentifierEnabled("build_incremental")) return null;
        String incremental = getConfigValue("ro.build.version.incremental");
        if (incremental != null && !incremental.isEmpty()) {
            return incremental;
        }
        if (cachedBuildIncremental == null) {
            cachedBuildIncremental = RandomGenerator.generateIncremental();
        }
        return cachedBuildIncremental;
    }

    public static String getBuildVersionCodename() {
        return getConfigValue("ro.build.version.codename");
    }

    public static String getBuildDescription() {
        return getConfigValue("ro.build.description");
    }

    public static String getBuildCharacteristics() {
        return getConfigValue("ro.build.characteristics");
    }

    public static String getBuildFlavor() {
        return getConfigValue("ro.build.flavor");
    }

    // Pre-1.3 defaults: Chrome-style, hardcoded Pixel 7 Pro on Android 15 (1.1)
    // or 16 (1.2). Treated as unset so saved configs switch to the derived UA.
    private static final String LEGACY_USER_AGENT_HEAD = "Mozilla/5.0 (Linux; Android ";
    private static final String LEGACY_USER_AGENT_TAIL = "; Pixel 7 Pro) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static boolean isLegacyUserAgent(String ua) {
        return (LEGACY_USER_AGENT_HEAD + "15" + LEGACY_USER_AGENT_TAIL).equals(ua)
                || (LEGACY_USER_AGENT_HEAD + "16" + LEGACY_USER_AGENT_TAIL).equals(ua);
    }

    // Null = derive from the real WebView UA (see WebViewHooks).
    public static String getWebViewUserAgent() {
        String ua = getConfigValue("webview.user_agent");
        if (ua == null || ua.isEmpty() || isLegacyUserAgent(ua)) return null;
        return ua;
    }

    public static String getCpuAbi() {
        return getConfigValue("ro.product.cpu.abi");
    }

    public static String getCpuAbiList() {
        return getConfigValue("ro.product.cpu.abilist");
    }

    public static String getCpuAbiList64() {
        return getConfigValue("ro.product.cpu.abilist64");
    }

    public static String getCpuAbiList32() {
        return getConfigValue("ro.product.cpu.abilist32");
    }

    private static volatile String cachedWifiMac = null;
    private static volatile String cachedBssid = null;
    private static volatile String cachedBluetoothMac = null;
    private static volatile String cachedEid = null;

    public static synchronized String getWifiMacAddress() {
        if (!isIdentifierEnabled("wifi_mac")) return null;
        if (cachedWifiMac == null) {
            String configured = getConfigValue("wifi.mac");
            cachedWifiMac = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateMacAddress();
        }
        return cachedWifiMac;
    }

    public static synchronized String getWifiBssid() {
        if (!isIdentifierEnabled("wifi_bssid")) return null;
        if (cachedBssid == null) {
            String configured = getConfigValue("wifi.bssid");
            cachedBssid = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateBssid();
        }
        return cachedBssid;
    }

    // Empty = none configured.
    public static String getWifiSsid() {
        return getRawProperty("wifi.ssid");
    }

    public static synchronized String getBluetoothMacAddress() {
        if (!isIdentifierEnabled("bluetooth_mac")) return null;
        if (cachedBluetoothMac == null) {
            String configured = getConfigValue("bluetooth.mac");
            cachedBluetoothMac = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateMacAddress();
        }
        return cachedBluetoothMac;
    }

    public static String getBluetoothName() {
        String configured = getConfigValue("bluetooth.name");
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        String model = getBuildModel();
        return (model != null && !model.isEmpty()) ? model : "Android Device";
    }

    public static boolean isHideAccountsEnabled() {
        return propBoolDef("hooks.hide_accounts", false);
    }

    public static boolean isDisplaySpoofEnabled() {
        return propBoolDef("hooks.spoof_display", false);
    }

    public static boolean isNativePropsEnabled() {
        return propBoolDef("hooks.native_props", false);
    }

    public static boolean isVerboseLoggingEnabled() {
        return propBoolDef("debug.verbose", false);
    }

    public static synchronized String getEid() {
        if (!isIdentifierEnabled("eid")) return null;
        if (cachedEid == null) {
            String configured = getConfigValue("euicc.eid");
            cachedEid = (configured != null && !configured.isEmpty())
                    ? configured : RandomGenerator.generateEid();
        }
        return cachedEid;
    }

    public static int getScreenWidth() {
        return propIntDef("screen.width", 1440);
    }

    public static int getScreenHeight() {
        return propIntDef("screen.height", 3120);
    }

    public static int getScreenDensity() {
        return propIntDef("screen.density", 560);
    }

    public static int getCpuCoreCount() {
        return propIntDef("hardware.cpu.cores", propIntDef("cpuinfo.cores", 8));
    }

    public static String getCpuInfoBogoMips() {
        return propStringDef("cpuinfo.bogomips", "38.40");
    }

    public static String getCpuInfoFeatures() {
        return propStringDef("cpuinfo.features", DEFAULT_CPUINFO_FEATURES);
    }

    public static String getCpuInfoImplementer() {
        return propStringDef("cpuinfo.implementer", "0x41");
    }

    public static String getCpuInfoArchitecture() {
        return propStringDef("cpuinfo.architecture", "8");
    }

    public static String getCpuInfoVariants() {
        return propStringDef("cpuinfo.variants", DEFAULT_CPUINFO_VARIANTS);
    }

    public static String getCpuInfoParts() {
        return propStringDef("cpuinfo.parts", DEFAULT_CPUINFO_PARTS);
    }

    public static String getCpuInfoRevisions() {
        return propStringDef("cpuinfo.revisions", DEFAULT_CPUINFO_REVISIONS);
    }

    public static String getCpuInfoHardware() {
        String fallback = getBuildHardware();
        if (fallback == null || fallback.isEmpty()) {
            fallback = getBuildDevice();
        }
        return propStringDef("cpuinfo.hardware",
                fallback == null || fallback.isEmpty() ? "Android" : fallback);
    }

    public static String getCpuInfoRevision() {
        return propStringDef("cpuinfo.revision", "0001");
    }

    public static long getMemoryTotalKb() {
        return propLongDef("memory.total_kb", 12L * 1024L * 1024L);
    }

    public static long getMemoryAvailableKb() {
        return propLongDef("memory.available_kb",
                Math.max(0L, getMemoryTotalKb() - (2L * 1024L * 1024L)));
    }

    public static long getMemoryTotalBytes() {
        String bytes = getConfigValue("memory.total_bytes");
        if (bytes != null && !bytes.isEmpty()) {
            try { return Long.parseLong(bytes); } catch (NumberFormatException ignored) {}
        }
        return getMemoryTotalKb() * 1024L;
    }

    // 0 = passthrough: the heap limit the app really runs with.
    public static int getMemoryClassMb() {
        return propIntDef("memory.class_mb", 0);
    }

    public static int getLargeMemoryClassMb() {
        return propIntDef("memory.large_class_mb", 0);
    }

    public static int getNativeHeapScale() {
        return propIntDef("native_heap.scale", 4);
    }

    public static long getBatteryCapacityUah() {
        return propLongDef("battery.capacity_uah", 5050000L);
    }

    public static long getBatteryChargeCounterUah() {
        return propLongDef("battery.charge_counter_uah", 3800000L);
    }

    public static long getBatteryEnergyCounterNwh() {
        return propLongDef("battery.energy_counter_nwh", 14250000000L);
    }

    public static long getStorageTotalBytes() {
        return propLongDef("storage.total_bytes", 137438953472L);
    }

    public static long getStorageAvailableBytes() {
        return propLongDef("storage.available_bytes", 84296499200L);
    }

    // Placeholder strings that were the defaults before 1.3: no real kernel.
    private static final String LEGACY_KERNEL_RELEASE =
            "5.10.157-android13-4-00006-g1234567-ab12345";
    private static final String LEGACY_KERNEL_VERSION =
            "#1 SMP PREEMPT Tue Dec  3 21:01:46 UTC 2024";

    // Empty = passthrough (the phone's own kernel), for all three.
    public static String getKernelOsRelease() {
        String release = getRawProperty("kernel.osrelease");
        return LEGACY_KERNEL_RELEASE.equals(release) ? "" : release;
    }

    public static String getKernelVersion() {
        String version = getRawProperty("kernel.version");
        return LEGACY_KERNEL_VERSION.equals(version) ? "" : version;
    }

    public static String getKernelHostname() {
        return getRawProperty("kernel.hostname");
    }

    public static String getLocaleLanguage() {
        return propStringDef("locale.language", "en");
    }

    public static String getLocaleCountry() {
        return propStringDef("locale.country", "US");
    }

    // Empty = passthrough (keep the device timezone).
    public static String getTimeZoneId() {
        return getRawProperty("persist.sys.timezone").trim();
    }

    public static String getInstallerPackage() {
        return propStringDef("install.installer_package", "com.android.vending");
    }

    public static String getInitiatingInstallerPackage() {
        return propStringDef("install.initiating_package", "com.android.vending");
    }

    public static String getGpuVendor() {
        return propStringDef("gpu.vendor", "ARM");
    }

    public static String getGpuRenderer() {
        return propStringDef("gpu.renderer", "Mali-G710");
    }

    // 0 means "use a default seed".
    public static long getFingerprintSeed() {
        if (!isIdentifierEnabled("fingerprint_seed")) return 0L;
        String configured = getConfigValue("fingerprint.seed");
        if (configured != null && !configured.isEmpty()) {
            try { return Long.parseLong(configured); } catch (NumberFormatException ignored) {}
        }
        String androidId = getAndroidId();
        return RandomGenerator.stableSeed(androidId == null ? "" : androidId);
    }

    public static boolean isIdentifierEnabled(String id) {
        return propBoolDef("enabled." + id, true);
    }

    public static synchronized void setIdentifierEnabled(String id, boolean enabled) {
        if (allProperties == null) init();
        Map<String, String> updated = new HashMap<>(allProperties);
        updated.put("enabled." + id, enabled ? "1" : "0");
        resetCaches();
        allProperties = Collections.unmodifiableMap(updated);
    }

    public static synchronized void setIdentifierValue(String id, String value) {
        if (allProperties == null) init();
        IdentifierRegistry.Definition d = IdentifierRegistry.byId(id);
        if (d == null) return;
        String stored = value == null ? "" : value;
        Map<String, String> updated = new HashMap<>(allProperties);
        for (String key : d.configKeys) {
            updated.put(key, stored);
        }
        resetCaches();
        allProperties = Collections.unmodifiableMap(updated);
    }

    public static synchronized void setProperties(Map<String, String> updates) {
        if (allProperties == null) init();
        if (updates == null || updates.isEmpty()) return;
        Map<String, String> updated = new HashMap<>(allProperties);
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (e.getKey() == null) continue;
            updated.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        resetCaches();
        allProperties = Collections.unmodifiableMap(updated);
    }

    public static String getRawProperty(String key) {
        String v = getConfigValue(key);
        return v == null ? "" : v;
    }

    // Every key that spells out the build ID or the incremental.
    private static final String[] BUILD_STRING_KEYS = {
            "ro.build.id", "ro.build.display.id", "ro.product.build.id",
            "ro.build.version.incremental", "ro.product.build.version.incremental",
            "ro.build.description", "ro.build.fingerprint", "ro.product.build.fingerprint",
            "ro.system.build.fingerprint", "ro.system_ext.build.fingerprint",
            "ro.vendor.build.fingerprint", "ro.odm.build.fingerprint",
            "ro.bootimage.build.fingerprint", "ro.system_dlkm.build.fingerprint",
            "ro.vendor_dlkm.build.fingerprint"};

    // One-time upgrade of a config saved by 1.1 / 1.2. A value still equal to
    // an old built-in default moves to today's default; whatever the user
    // changed stays. The device's own values only move while the profile is
    // still the default Pixel 7 Pro, and the Android version never changes.
    public static synchronized Map<String, String> getLegacyDefaultUpdates() {
        if (allProperties == null) init();
        Map<String, String> defaults = getEmbeddedDefaults();
        Map<String, String> out = new HashMap<>();

        if (isLegacyUserAgent(getRawProperty("webview.user_agent"))) {
            out.put("webview.user_agent", "");
        }
        moveDefault(out, "memory.class_mb", "512", "");
        moveDefault(out, "memory.large_class_mb", "1024", "");
        moveDefault(out, "dalvik.vm.heapsize", "576m", "");
        moveDefault(out, "dalvik.vm.heapgrowthlimit", "256m", "");
        moveDefault(out, "dalvik.vm.heapmaxfree", "8m", "");
        moveDefault(out, "dalvik.vm.heapminfree", "512k", "");
        moveDefault(out, "dalvik.vm.heapstartsize", "8m", "");
        moveDefault(out, "dalvik.vm.heaptargetutilization", "0.75", "");

        if (!"cheetah".equals(getRawProperty("ro.product.device"))
                || !"Pixel 7 Pro".equals(getRawProperty("ro.product.model"))) {
            // Another device: the placeholder kernel just goes.
            moveDefault(out, "kernel.osrelease", LEGACY_KERNEL_RELEASE, "");
            moveDefault(out, "kernel.version", LEGACY_KERNEL_VERSION, "");
            return out;
        }

        if (getKernelOsRelease().isEmpty() && getKernelVersion().isEmpty()) {
            out.put("kernel.osrelease", defaults.get("kernel.osrelease"));
            out.put("kernel.version", defaults.get("kernel.version"));
        }
        moveDefault(out, "ro.soc.model", "gs201", "GS201");
        moveDefault(out, "ro.hardware.gralloc", "gs201", "");
        moveDefault(out, "ro.hardware.power", "gs201-power", "");
        moveDefault(out, "ro.sf.lcd_density", "512", "560");
        moveDefault(out, "screen.density", "512", "560");
        moveDefault(out, "gpu.renderer", "Mali-G710 MC10", "Mali-G710");
        moveDefault(out, "gpu.unmasked_renderer", "Mali-G710 MC10", "Mali-G710");
        moveDefault(out, "ro.product.cpu.abilist", "arm64-v8a,armeabi-v7a,armeabi", "arm64-v8a");
        moveDefault(out, "ro.product.cpu.abilist64", "arm64-v8a", "");
        moveDefault(out, "ro.product.cpu.abilist32", "armeabi-v7a,armeabi", "");
        String bootloaderPrefix = getRawProperty("bootloader.prefix");
        if ((bootloaderPrefix.isEmpty() || "cheetah-1.2".equals(bootloaderPrefix))
                && getRawProperty("ro.bootloader").isEmpty()) {
            out.put("bootloader.prefix", defaults.get("bootloader.prefix"));
            out.put("ro.bootloader", defaults.get("ro.bootloader"));
        }
        // Pixels ship a generic system image from Android 16 on.
        if (getBuildVersionSdk() >= 36
                && "cheetah".equals(getRawProperty("ro.product.system.device"))
                && "cheetah".equals(getRawProperty("ro.product.system.name"))
                && "Pixel 7 Pro".equals(getRawProperty("ro.product.system.model"))) {
            for (String key : new String[]{"ro.product.system.device",
                    "ro.product.system.name", "ro.product.system.model"}) {
                out.put(key, defaults.get(key));
            }
        }
        fillUnset(out, defaults, "gsm.version.baseband", "ro.product.first_api_level");

        // The 1.2 default build never existed: BP4A.250605.009 isn't a Pixel 7
        // Pro build, and 12621605 belongs to the Android 15 build 1.1 shipped.
        String newId = defaults.get("ro.build.id");
        String newIncremental = defaults.get("ro.build.version.incremental");
        String id = getRawProperty("ro.build.id");
        boolean swapId = "BP4A.250605.009".equals(id);
        boolean swapIncremental = "12621605".equals(getRawProperty("ro.build.version.incremental"))
                && !"AP4A.241205.013".equals(id);
        for (String key : BUILD_STRING_KEYS) {
            String value = getRawProperty(key);
            String updated = value;
            if (swapId) updated = updated.replace("BP4A.250605.009", newId);
            if (swapIncremental) updated = updated.replace("12621605", newIncremental);
            if (!updated.equals(value)) out.put(key, updated);
        }
        if (swapId) {
            moveDefault(out, "ro.build.version.security_patch", "2025-06-05",
                    defaults.get("ro.build.version.security_patch"));
        }
        // Now the default build: it gets that build's own date, host and user.
        if ((swapId || newId.equals(id)) && (swapIncremental
                || newIncremental.equals(getRawProperty("ro.build.version.incremental")))) {
            fillUnset(out, defaults, "ro.build.date", "ro.build.date.utc", "ro.build.host",
                    "ro.build.user", "ro.build.version.sdk_full");
        }
        return out;
    }

    private static void moveDefault(Map<String, String> out, String key,
                                    String oldDefault, String newDefault) {
        if (oldDefault.equals(getRawProperty(key))) {
            out.put(key, newDefault);
        }
    }

    private static void fillUnset(Map<String, String> out, Map<String, String> defaults,
                                  String... keys) {
        for (String key : keys) {
            if (getRawProperty(key).isEmpty()) {
                out.put(key, defaults.get(key));
            }
        }
    }

    public static String getIdentifierValue(String id) {
        Map<String, String> props = allProperties;
        if (props == null) {
            init();
            props = allProperties;
        }
        IdentifierRegistry.Definition d = IdentifierRegistry.byId(id);
        if (d == null) return "";
        String v = props.get(d.primaryKey());
        return v == null ? "" : v;
    }

    public static synchronized void saveConfig(File target) throws IOException {
        if (allProperties == null) init();
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        String text = formatConfigText(allProperties);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(text.getBytes());
            fos.flush();
            try { fos.getFD().sync(); } catch (Exception ignored) {}
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to remove existing " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Failed to rename " + tmp + " to " + target);
        }
        target.setReadable(true, false);
    }

    public static synchronized void reload() {
        Map<String, String> loaded = readXSharedPreferences();
        if (loaded == null || loaded.isEmpty()) {
            loaded = readConfigFile();
        }
        fillFromDefaults(loaded);
        resetCaches();
        allProperties = Collections.unmodifiableMap(new HashMap<>(loaded));
    }

    private static String propStringDef(String key, String defaultValue) {
        String v = getConfigValue(key);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    private static int propIntDef(String key, int defaultValue) {
        String v = getConfigValue(key);
        if (v == null || v.isEmpty()) return defaultValue;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static long propLongDef(String key, long defaultValue) {
        String v = getConfigValue(key);
        if (v == null || v.isEmpty()) return defaultValue;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean propBoolDef(String key, boolean defaultValue) {
        String v = getConfigValue(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return "1".equals(v) || "true".equalsIgnoreCase(v)
                || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }
}
