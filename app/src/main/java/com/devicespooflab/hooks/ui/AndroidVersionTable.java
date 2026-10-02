package com.devicespooflab.hooks.ui;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AndroidVersionTable {

    public static final class Entry {
        public final int sdk;
        public final String release;
        public final String displayName;
        // AOSP base build for this version and its security patch.
        public final String buildId;
        public final String securityPatch;

        Entry(int sdk, String release, String displayName, String buildId, String securityPatch) {
            this.sdk = sdk;
            this.release = release;
            this.displayName = displayName;
            this.buildId = buildId;
            this.securityPatch = securityPatch;
        }
    }

    // Real Pixel/Nexus builds (checked against Google's OTA metadata). The
    // release is what devices report: 8.x uses three parts and 12L reports "12".
    private static final Entry[] ENTRIES = {
            new Entry(26, "8.0.0", "Android 8 (SDK 26)", "OPR6.170623.013", "2017-08-05"),
            new Entry(27, "8.1.0", "Android 8.1 (SDK 27)", "OPM7.181205.001", "2018-12-05"),
            new Entry(28, "9", "Android 9 (SDK 28)", "PQ3A.190801.002", "2019-08-01"),
            new Entry(29, "10", "Android 10 (SDK 29)", "QQ3A.200805.001", "2020-08-05"),
            new Entry(30, "11", "Android 11 (SDK 30)", "RQ3A.211001.001", "2021-10-01"),
            new Entry(31, "12", "Android 12 (SDK 31)", "SQ1A.220205.002", "2022-02-05"),
            new Entry(32, "12", "Android 12L (SDK 32)", "SQ3A.220705.004", "2022-07-05"),
            new Entry(33, "13", "Android 13 (SDK 33)", "TQ3A.230901.001", "2023-09-01"),
            new Entry(34, "14", "Android 14 (SDK 34)", "UQ1A.240205.004", "2024-02-05"),
            new Entry(35, "15", "Android 15 (SDK 35)", "AP4A.250205.002", "2025-02-05"),
            new Entry(36, "16", "Android 16 (SDK 36)", "BP4A.251205.006", "2025-12-05"),
            new Entry(37, "17", "Android 17 (SDK 37)", "CP3A.260905.009", "2026-09-05"),
    };

    public static final int DEFAULT_INDEX = 10;

    // Every partition fingerprint the config carries; all must agree.
    static final String[] FINGERPRINT_KEYS = {
            "ro.build.fingerprint",
            "ro.product.build.fingerprint",
            "ro.system.build.fingerprint",
            "ro.system_ext.build.fingerprint",
            "ro.vendor.build.fingerprint",
            "ro.odm.build.fingerprint",
            "ro.bootimage.build.fingerprint",
            "ro.system_dlkm.build.fingerprint",
            "ro.vendor_dlkm.build.fingerprint",
    };

    // When the build was made. They belong to one exact build, so they are
    // cleared (and derived from the security patch again) when it changes.
    static final String[] BUILD_DATE_KEYS = {
            "ro.build.date", "ro.build.date.utc", "ro.vendor.build.date.utc"};

    public static int size() {
        return ENTRIES.length;
    }

    public static Entry get(int index) {
        if (index < 0) return ENTRIES[0];
        if (index >= ENTRIES.length) return ENTRIES[ENTRIES.length - 1];
        return ENTRIES[index];
    }

    public static int currentIndex() {
        String sdkStr = ConfigManager.getRawProperty("ro.build.version.sdk");
        int sdk;
        try { sdk = Integer.parseInt(sdkStr.trim()); }
        catch (NumberFormatException e) { return DEFAULT_INDEX; }
        for (int i = 0; i < ENTRIES.length; i++) {
            if (ENTRIES[i].sdk == sdk) return i;
        }
        return DEFAULT_INDEX;
    }

    // Moves the build to the entry's AOSP base build and patch. The device
    // identity and the incremental stay as they are.
    public static Map<String, String> buildUpdates(Entry entry) {
        Map<String, String> out = new LinkedHashMap<>();
        String release = entry.release;

        putVersion(out, release, entry.sdk);
        out.put("ro.build.id", entry.buildId);
        out.put("ro.build.display.id", entry.buildId);
        out.put("ro.product.build.id", entry.buildId);
        out.put("build.id.prefix", buildIdPrefix(entry.buildId));
        out.put("ro.build.version.security_patch", entry.securityPatch);
        for (String key : BUILD_DATE_KEYS) {
            out.put(key, "");
        }

        for (String key : FINGERPRINT_KEYS) {
            rewriteFingerprintKey(out, key, release, entry.buildId);
        }

        rewriteDescription(out, release, entry.buildId);

        return out;
    }

    static void putVersion(Map<String, String> out, String release, int sdkInt) {
        String sdk = Integer.toString(sdkInt);

        out.put("ro.build.version.release", release);
        out.put("ro.build.version.release_or_codename", release);
        out.put("ro.build.version.release_or_preview_display", release);
        out.put("ro.build.version.sdk", sdk);
        // Blank = "<sdk>.0"; a preset whose build has a minor version sets it.
        out.put("ro.build.version.sdk_full", "");
        out.put("ro.product.build.version.release", release);
        out.put("ro.product.build.version.release_or_codename", release);
        out.put("ro.product.build.version.sdk", sdk);

        for (String partition : new String[]{
                "vendor", "vendor_dlkm", "odm", "bootimage", "system_dlkm"}) {
            out.put("ro.%s.build.version.release".replace("%s", partition), release);
            out.put("ro.%s.build.version.release_or_codename".replace("%s", partition), release);
        }
    }

    // RandomGenerator uses the first 4 characters when it has to invent a build ID.
    static String buildIdPrefix(String buildId) {
        return buildId.length() > 4 ? buildId.substring(0, 4) : buildId;
    }

    private static void rewriteFingerprintKey(
            Map<String, String> out, String key, String release, String buildId) {
        String fp = ConfigManager.getRawProperty(key);
        String updated = rewriteFingerprint(fp, release, buildId);
        if (updated != null && !updated.isEmpty()) {
            out.put(key, updated);
        }
    }

    // Swap the release and build ID segments of a fingerprint like
    // google/cheetah/cheetah:15/AP4A.241205.013/12621605:user/release-keys.
    static String rewriteFingerprint(String fp, String newRelease, String newBuildId) {
        if (fp == null || fp.isEmpty()) return fp;
        int colonIdx = fp.indexOf(':');
        if (colonIdx < 0) return fp;
        int releaseEnd = fp.indexOf('/', colonIdx + 1);
        if (releaseEnd < 0) return fp.substring(0, colonIdx + 1) + newRelease;
        int buildIdEnd = fp.indexOf('/', releaseEnd + 1);
        if (buildIdEnd < 0) return fp.substring(0, colonIdx + 1) + newRelease + "/" + newBuildId;
        return fp.substring(0, colonIdx + 1) + newRelease + "/" + newBuildId + fp.substring(buildIdEnd);
    }

    // "<name>-user <release> <build id> <incremental> release-keys"
    private static void rewriteDescription(Map<String, String> out, String release, String buildId) {
        String desc = ConfigManager.getRawProperty("ro.build.description");
        if (desc == null || desc.isEmpty()) return;
        String[] parts = desc.split(" ");
        if (parts.length >= 2) {
            parts[1] = release;
            if (parts.length >= 3) parts[2] = buildId;
            StringBuilder rebuilt = new StringBuilder(desc.length());
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) rebuilt.append(' ');
                rebuilt.append(parts[i]);
            }
            out.put("ro.build.description", rebuilt.toString());
        }
    }

    private AndroidVersionTable() {}
}
