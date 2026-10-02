package com.devicespooflab.hooks.ui;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// One device identity. toProperties() fans every field out to all the config
// keys that must agree with it, so switching devices can't leave a stale
// partition model or fingerprint behind.
public final class DeviceProfile {

    // Partitions with their own ro.product.<partition>.* identity copy.
    private static final String[] PARTITIONS = {
            "product", "system", "system_ext", "vendor", "vendor_dlkm", "odm",
            "bootimage", "system_dlkm"};

    // Facts about one exact build or device that only a preset knows (it sets
    // them in props). Blank = derived, unset or passed through, never left
    // over from the device that was configured before.
    private static final String[] PRESET_ONLY_KEYS = {
            "ro.build.host", "ro.build.user", "ro.build.version.base_os",
            "ro.product.first_api_level", "gsm.version.baseband",
            "ro.product.cpu.abilist", "ro.product.cpu.abilist64", "ro.product.cpu.abilist32",
            "ro.strongbox.manufacturer", "ro.strongbox.model",
            "kernel.osrelease", "kernel.version"};

    public String brand = "";
    public String manufacturer = "";
    public String model = "";
    // Retail name for preset labels ("Galaxy S25 Ultra"); never written.
    public String marketName = "";
    public String name = "";
    public String device = "";
    public String board = "";
    public String hardware = "";
    public String platform = "";
    public String socManufacturer = "";
    public String socModel = "";
    public String buildId = "";
    public String incremental = "";
    public String securityPatch = "";
    public String release = "";
    public int sdk;
    // Blank = brand/name/device:release/id/incremental:user/release-keys.
    public String fingerprint = "";
    public String characteristics = "";
    public String bootloaderPrefix = "";
    public String gpuVendor = "";
    public String gpuRenderer = "";
    public String egl = "";
    // Blank = same as egl.
    public String vulkan = "";
    public int screenW;
    public int screenH;
    public int density;
    public long memoryKb;
    public int cpuCores;
    // Applied last, e.g. a Samsung display ID, bootloader or vendor fingerprint.
    public final Map<String, String> props = new LinkedHashMap<>();

    public Map<String, String> toProperties() {
        Map<String, String> out = new LinkedHashMap<>();

        out.put("ro.product.brand", brand);
        out.put("ro.product.manufacturer", manufacturer);
        out.put("ro.product.model", model);
        out.put("ro.product.name", name);
        out.put("ro.product.device", device);
        out.put("ro.product.board", board);
        for (String partition : PARTITIONS) {
            out.put("ro.product." + partition + ".brand", brand);
            out.put("ro.product." + partition + ".manufacturer", manufacturer);
            out.put("ro.product." + partition + ".model", model);
            out.put("ro.product." + partition + ".name", name);
            out.put("ro.product." + partition + ".device", device);
        }
        out.put("ro.hardware", hardware);
        out.put("ro.boot.hardware", hardware);
        out.put("ro.board.platform", platform);
        out.put("ro.soc.manufacturer", socManufacturer);
        out.put("ro.soc.model", socModel);
        // HAL names are platform-specific, so the old profile's would contradict
        // the new one. Presets that have them set them in props.
        out.put("ro.hardware.gralloc", "");
        out.put("ro.hardware.power", "");

        out.put("ro.build.id", buildId);
        out.put("ro.build.display.id", buildId);
        out.put("ro.product.build.id", buildId);
        out.put("build.id.prefix", AndroidVersionTable.buildIdPrefix(buildId));
        out.put("ro.build.version.incremental", incremental);
        out.put("ro.product.build.version.incremental", incremental);
        out.put("ro.build.version.security_patch", securityPatch);
        AndroidVersionTable.putVersion(out, release, sdk);
        out.put("ro.build.description", allSet(name, release, buildId, incremental)
                ? name + "-user " + release + " " + buildId + " " + incremental + " release-keys"
                : "");
        out.put("ro.build.flavor", name.isEmpty() ? "" : name + "-user");
        out.put("ro.build.product", device);
        out.put("ro.build.device", device);
        out.put("ro.build.characteristics", characteristics);
        String fp = fingerprint();
        for (String key : AndroidVersionTable.FINGERPRINT_KEYS) {
            out.put(key, fp);
        }

        out.put("bootloader.prefix", bootloaderPrefix);
        // Blank regenerates from bootloader.prefix; Samsung presets pin it in props.
        out.put("ro.bootloader", "");
        out.put("gpu.vendor", gpuVendor);
        out.put("gpu.renderer", gpuRenderer);
        out.put("gpu.unmasked_vendor", gpuVendor);
        out.put("gpu.unmasked_renderer", gpuRenderer);
        String vulkanName = vulkan.isEmpty() ? egl : vulkan;
        out.put("ro.hardware.egl", egl);
        out.put("ro.hardware.vulkan", vulkanName);
        out.put("ro.boot.hardware.vulkan", vulkanName);
        out.put("screen.width", number(screenW));
        out.put("screen.height", number(screenH));
        out.put("screen.density", number(density));
        out.put("ro.sf.lcd_density", number(density));
        out.put("memory.total_kb", number(memoryKb));
        // Blank = derived from the total.
        out.put("memory.available_kb", "");
        out.put("hardware.cpu.cores", number(cpuCores));

        for (String key : AndroidVersionTable.BUILD_DATE_KEYS) {
            out.put(key, "");
        }
        for (String key : PRESET_ONLY_KEYS) {
            out.put(key, "");
        }

        out.putAll(props);
        return out;
    }

    // Only the keys whose value the edit changes. The rest of the config stays
    // as it is, so preset extras (a Samsung display ID, bootloader or vendor
    // fingerprint) survive edits to unrelated fields.
    public static Map<String, String> changedProperties(DeviceProfile before, DeviceProfile after) {
        Map<String, String> old = before.toProperties();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : after.toProperties().entrySet()) {
            if (!e.getValue().equals(old.get(e.getKey()))) {
                out.put(e.getKey(), e.getValue());
            }
        }
        // A different build wasn't made on the old one's date.
        if (out.containsKey("ro.build.id") || out.containsKey("ro.build.version.security_patch")) {
            for (String key : AndroidVersionTable.BUILD_DATE_KEYS) {
                if (!raw(key).isEmpty()) {
                    out.put(key, "");
                }
            }
        }
        return out;
    }

    public static DeviceProfile fromConfig() {
        DeviceProfile p = new DeviceProfile();
        p.brand = raw("ro.product.brand");
        p.manufacturer = raw("ro.product.manufacturer");
        p.model = raw("ro.product.model");
        p.name = raw("ro.product.name");
        p.device = raw("ro.product.device");
        p.board = raw("ro.product.board");
        p.hardware = raw("ro.hardware");
        p.platform = raw("ro.board.platform");
        p.socManufacturer = raw("ro.soc.manufacturer");
        p.socModel = raw("ro.soc.model");
        p.buildId = raw("ro.build.id");
        p.incremental = raw("ro.build.version.incremental");
        p.securityPatch = raw("ro.build.version.security_patch");
        p.release = raw("ro.build.version.release");
        p.sdk = (int) parse(raw("ro.build.version.sdk"));
        p.characteristics = raw("ro.build.characteristics");
        p.bootloaderPrefix = raw("bootloader.prefix");
        p.gpuVendor = raw("gpu.vendor");
        p.gpuRenderer = raw("gpu.renderer");
        p.egl = raw("ro.hardware.egl");
        String vulkanName = raw("ro.hardware.vulkan");
        p.vulkan = vulkanName.equals(p.egl) ? "" : vulkanName;
        p.screenW = (int) parse(raw("screen.width"));
        p.screenH = (int) parse(raw("screen.height"));
        p.density = (int) parse(raw("screen.density"));
        p.memoryKb = parse(raw("memory.total_kb"));
        p.cpuCores = (int) parse(raw("hardware.cpu.cores"));
        // Blank (auto) while it still matches the fields, so edits re-derive it.
        String fp = raw("ro.build.fingerprint");
        p.fingerprint = fp.equals(p.derivedFingerprint()) ? "" : fp;
        return p;
    }

    public String fingerprint() {
        return fingerprint.isEmpty() ? derivedFingerprint() : fingerprint;
    }

    // "Samsung · Galaxy S25 Ultra (SM-S938B) · Android 16"
    public String label() {
        String product = marketName.isEmpty() || marketName.equals(model)
                ? model : marketName + " (" + model + ")";
        return displayBrand() + " · " + product + " · Android " + release;
    }

    // "Google Pixel 7 Pro"; no brand prefix when the model already has it.
    public String displayName() {
        if (model.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT))) {
            return model;
        }
        return (displayBrand() + " " + model).trim();
    }

    private String derivedFingerprint() {
        if (!allSet(brand, name, device, release, buildId, incremental)) return "";
        return brand + "/" + name + "/" + device + ":" + release + "/" + buildId + "/"
                + incremental + ":user/release-keys";
    }

    private String displayBrand() {
        if (brand.isEmpty()) return "";
        return brand.substring(0, 1).toUpperCase(Locale.ROOT) + brand.substring(1);
    }

    private static boolean allSet(String... values) {
        for (String v : values) {
            if (v.isEmpty()) return false;
        }
        return true;
    }

    private static String raw(String key) {
        return ConfigManager.getRawProperty(key);
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String number(long value) {
        return value > 0 ? Long.toString(value) : "";
    }
}
