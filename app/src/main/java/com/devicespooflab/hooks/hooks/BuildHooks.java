package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class BuildHooks {

    private static final String TAG = "DeviceSpoofLab-Build";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> buildClass = findBuildClass(lpparam.classLoader);
            if (buildClass == null) {
                XposedBridge.log(TAG + ": Build class not found");
                return;
            }

            spoofBuildFields(buildClass);
            hookGetSerial(buildClass);
            hookGetRadioVersion(buildClass);
            hookBuildGetString(buildClass);
            hookBuildGetLong(buildClass);
            hookPartitionMethods(lpparam.classLoader);
            spoofVersionFields(lpparam.classLoader);
            spoofHttpAgent();

            if (ConfigManager.isVerboseLoggingEnabled()) {
                XposedBridge.log(TAG + ": Successfully spoofed Build static fields and methods");
            }

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook Build methods: " + e.getMessage());
        }
    }

    public static void refreshStaticFields(ClassLoader classLoader) {
        try {
            Class<?> buildClass = findBuildClass(classLoader);
            if (buildClass == null) {
                XposedBridge.log(TAG + ": Build class not found during refresh");
                return;
            }

            spoofBuildFields(buildClass);
            spoofVersionFields(classLoader);
            spoofHttpAgent();

            if (ConfigManager.isVerboseLoggingEnabled()) {
                XposedBridge.log(TAG + ": Refreshed Build static fields");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to refresh Build static fields: " + t.getMessage());
        }
    }

    private static Class<?> findBuildClass(ClassLoader classLoader) {
        Class<?> buildClass = XposedHelpers.findClassIfExists("android.os.Build", classLoader);
        if (buildClass == null) {
            buildClass = XposedHelpers.findClassIfExists("android.os.Build", ClassLoader.getSystemClassLoader());
        }
        return buildClass;
    }

    private static void spoofBuildFields(Class<?> buildClass) {
        setStringField(buildClass, "BOARD", ConfigManager.getBuildBoard());
        setStringField(buildClass, "BOOTLOADER", ConfigManager.getBuildBootloader());
        setStringField(buildClass, "BRAND", ConfigManager.getBuildBrand());
        setStringField(buildClass, "DEVICE", ConfigManager.getBuildDevice());
        setStringField(buildClass, "DISPLAY", ConfigManager.getBuildDisplay());
        setStringField(buildClass, "FINGERPRINT", ConfigManager.getBuildFingerprint());
        setStringField(buildClass, "HARDWARE", ConfigManager.getBuildHardware());
        setStringField(buildClass, "HOST", prop("ro.build.host", "android-build"));
        setStringField(buildClass, "ID", ConfigManager.getBuildId());
        setStringField(buildClass, "MANUFACTURER", ConfigManager.getBuildManufacturer());
        setStringField(buildClass, "MODEL", ConfigManager.getBuildModel());
        setStringField(buildClass, "ODM_SKU", prop("ro.boot.product.hardware.sku", "unknown"));
        setStringField(buildClass, "PRODUCT", ConfigManager.getBuildProduct());
        // Read before the modem reports its version, so "unknown" on a normal
        // boot; getRadioVersion() is the live value.
        setStringField(buildClass, "RADIO", "unknown");
        setStringField(buildClass, "SERIAL", ConfigManager.getSerial());
        setStringField(buildClass, "SKU", prop("ro.boot.hardware.sku", "unknown"));
        setStringField(buildClass, "SOC_MANUFACTURER", prop("ro.soc.manufacturer", ConfigManager.getBuildManufacturer()));
        setStringField(buildClass, "SOC_MODEL", prop("ro.soc.model", "gs201"));
        // Android 17. Without a chip in the profile they read like a device
        // that declares none.
        setStringField(buildClass, "STRONGBOX_MANUFACTURER", prop("ro.strongbox.manufacturer", "unsupported"));
        setStringField(buildClass, "STRONGBOX_MODEL", prop("ro.strongbox.model", "unsupported"));
        setStringField(buildClass, "TAGS", ConfigManager.getBuildTags());
        long buildTime = getBuildTimeMillis("ro.build.date.utc");
        if (buildTime > 0) {
            setLongField(buildClass, "TIME", buildTime);
        }
        setStringField(buildClass, "TYPE", ConfigManager.getBuildType());
        setStringField(buildClass, "UNKNOWN", "unknown");
        setStringField(buildClass, "USER", prop("ro.build.user", "android-build"));

        spoofAbiFields(buildClass);

        String buildType = ConfigManager.getBuildType();
        setBooleanField(buildClass, "IS_DEBUGGABLE", propBoolean("ro.debuggable", false));
        setBooleanField(buildClass, "IS_EMULATOR", false);
        setBooleanField(buildClass, "IS_ENG", "eng".equals(buildType));
        setBooleanField(buildClass, "IS_TREBLE_ENABLED", propBoolean("ro.treble.enabled", true));
        setBooleanField(buildClass, "IS_USER", "user".equals(buildType));
        setBooleanField(buildClass, "IS_USERDEBUG", "userdebug".equals(buildType));
    }

    private static void spoofVersionFields(ClassLoader classLoader) {
        Class<?> versionClass = XposedHelpers.findClassIfExists("android.os.Build$VERSION", classLoader);
        if (versionClass == null) {
            versionClass = XposedHelpers.findClassIfExists("android.os.Build$VERSION", ClassLoader.getSystemClassLoader());
        }
        if (versionClass == null) {
            return;
        }

        int sdk = ConfigManager.getBuildVersionSdk();
        int previewSdk = propInt("ro.build.version.preview_sdk", 0);

        setStringField(versionClass, "BASE_OS", prop("ro.build.version.base_os", ""));
        setStringField(versionClass, "CODENAME", ConfigManager.getBuildVersionCodename());
        setStringField(versionClass, "INCREMENTAL", ConfigManager.getBuildVersionIncremental());
        setIntField(versionClass, "PREVIEW_SDK_INT", previewSdk);
        setStringField(versionClass, "PREVIEW_SDK_FINGERPRINT", prop("ro.build.version.preview_sdk_fingerprint", "REL"));
        setStringField(versionClass, "RELEASE", ConfigManager.getBuildVersionRelease());
        setStringField(versionClass, "RELEASE_OR_CODENAME", prop(
                "ro.build.version.release_or_codename",
                ConfigManager.getBuildVersionRelease()
        ));
        setStringField(versionClass, "RELEASE_OR_PREVIEW_DISPLAY", prop(
                "ro.build.version.release_or_preview_display",
                prop("ro.build.version.release_or_codename", ConfigManager.getBuildVersionRelease())
        ));
        setIntField(versionClass, "RESOURCES_SDK_INT", previewSdk > 0 ? sdk + 1 : sdk);
        setStringField(versionClass, "SDK", String.valueOf(sdk));
        setIntField(versionClass, "SDK_INT", sdk);
        // Android 16+: SDK_INT * 100000 + the minor version ("36.1" -> 3600001).
        setIntField(versionClass, "SDK_INT_FULL", sdkIntFull(sdk));
        setIntField(versionClass, "RESOURCES_SDK_INT_FULL",
                sdkIntFull(sdk) + (previewSdk > 0 ? 100000 : 0));
        setStringField(versionClass, "SECURITY_PATCH", ConfigManager.getBuildVersionSecurityPatch());
        setStringArrayField(versionClass, "ACTIVE_CODENAMES", new String[0]);
        // MEDIA_PERFORMANCE_CLASS, KNOWN_CODENAMES and MIN_SUPPORTED_TARGET_SDK_INT
        // stay the phone's own: the first steers what apps ask of the real
        // hardware, the other two belong to the platform the app runs on.
    }

    // SUPPORTED_ABIS and the CPU_ABI pair, the way Build derives them: CPU_ABI /
    // CPU_ABI2 are the first two ABIs of the process's own bitness. Left alone
    // when the profile has no ABI of that bitness (see isAbiSpoofSuppressed).
    private static void spoofAbiFields(Class<?> buildClass) {
        if (ConfigManager.isAbiSpoofSuppressed()) {
            return;
        }
        String[] abis = splitCsv(prop("ro.product.cpu.abilist", ""));
        if (abis.length == 0) {
            return;
        }
        String[] abis64 = splitCsv(prop("ro.product.cpu.abilist64", ""));
        String[] abis32 = splitCsv(prop("ro.product.cpu.abilist32", ""));
        setStringArrayField(buildClass, "SUPPORTED_ABIS", abis);
        setStringArrayField(buildClass, "SUPPORTED_64_BIT_ABIS", abis64);
        setStringArrayField(buildClass, "SUPPORTED_32_BIT_ABIS", abis32);

        String[] own = android.os.Process.is64Bit() ? abis64 : abis32;
        if (own.length > 0) {
            setStringField(buildClass, "CPU_ABI", own[0]);
            setStringField(buildClass, "CPU_ABI2", own.length > 1 ? own[1] : "");
        }
    }

    private static int sdkIntFull(int sdk) {
        String full = prop("ro.build.version.sdk_full", "");
        int dot = full.indexOf('.');
        try {
            int major = Integer.parseInt(dot < 0 ? full : full.substring(0, dot));
            int minor = dot < 0 ? 0 : Integer.parseInt(full.substring(dot + 1));
            if (major == sdk && minor >= 0 && minor < 100000) {
                return major * 100000 + minor;
            }
        } catch (NumberFormatException ignored) {
        }
        return sdk * 100000;
    }

    private static volatile String sRealHttpAgent;
    private static volatile String sSpoofedHttpAgent;

    // "Dalvik/2.1.0 (Linux; U; Android 16; Pixel 7 Pro Build/BP4A.251205.006)"
    private static final java.util.regex.Pattern HTTP_AGENT = java.util.regex.Pattern.compile(
            "(Dalvik/\\S+ \\(Linux; U; Android )[^;)]*?(?:; (.*?))?( Build/[^)]*)?\\)");

    // The runtime builds http.agent from the real Build before any module code
    // runs, and HttpURLConnection sends it as its default User-Agent. Rebuilt
    // here the way RuntimeInit does it. An agent the app set itself is kept.
    private static void spoofHttpAgent() {
        try {
            String current = System.getProperty("http.agent");
            if (current == null) return;
            String real = sRealHttpAgent;
            if (real == null) {
                real = current;
            } else if (!current.equals(sSpoofedHttpAgent) && !current.equals(real)) {
                return;
            }
            java.util.regex.Matcher m = HTTP_AGENT.matcher(real);
            if (!m.matches()) return;

            String release = ConfigManager.getBuildVersionRelease();
            String model = ConfigManager.getBuildModel();
            String buildId = ConfigManager.getBuildId();
            StringBuilder agent = new StringBuilder(m.group(1));
            agent.append(release == null || release.isEmpty() ? "1.0" : release);
            if (model != null && !model.isEmpty()) {
                agent.append("; ").append(model);
            } else if (m.group(2) != null) {
                agent.append("; ").append(m.group(2));
            }
            if (buildId != null && !buildId.isEmpty()) {
                agent.append(" Build/").append(buildId);
            } else if (m.group(3) != null) {
                agent.append(m.group(3));
            }
            agent.append(')');

            sRealHttpAgent = real;
            sSpoofedHttpAgent = agent.toString();
            System.setProperty("http.agent", sSpoofedHttpAgent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to set http.agent: " + t.getMessage());
        }
    }

    private static void hookGetSerial(Class<?> buildClass) {
        try {
            XposedHelpers.findAndHookMethod(buildClass, "getSerial",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String v = ConfigManager.getSerial();
                        if (v != null) param.setResult(v);
                    }
                });
        } catch (NoSuchMethodError e) {
            // Method doesn't exist on Android < 8
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook getSerial(): " + e.getMessage());
        }
    }

    private static void hookGetRadioVersion(Class<?> buildClass) {
        try {
            XposedHelpers.findAndHookMethod(buildClass, "getRadioVersion",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // Never null. The platform returns null for an unset
                            // baseband, but a phone always has one, so apps
                            // don't check for it.
                            String radio = ConfigManager.getSystemProperty("gsm.version.baseband", null);
                            if (radio != null) {
                                param.setResult(radio.isEmpty() ? "unknown" : radio);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook getRadioVersion(): " + e.getMessage());
        }
    }

    private static void hookBuildGetString(Class<?> buildClass) {
        try {
            XposedHelpers.findAndHookMethod(buildClass, "getString",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String key = (String) param.args[0];
                            String spoofedValue = ConfigManager.getSystemProperty(key, null);
                            if (spoofedValue != null) {
                                // Build reads an unset property as "unknown".
                                param.setResult(spoofedValue.isEmpty() ? "unknown" : spoofedValue);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook Build.getString(): " + e.getMessage());
        }
    }

    private static void hookBuildGetLong(Class<?> buildClass) {
        try {
            XposedHelpers.findAndHookMethod(buildClass, "getLong",
                    String.class, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String key = (String) param.args[0];
                            String spoofedValue = ConfigManager.getSystemProperty(key, null);
                            if (spoofedValue == null) {
                                return;
                            }

                            try {
                                param.setResult(Long.parseLong(spoofedValue));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook Build.getLong(): " + e.getMessage());
        }
    }

    private static void hookPartitionMethods(ClassLoader classLoader) {
        Class<?> partitionClass = XposedHelpers.findClassIfExists("android.os.Build$Partition", classLoader);
        if (partitionClass == null) {
            partitionClass = XposedHelpers.findClassIfExists("android.os.Build$Partition", ClassLoader.getSystemClassLoader());
        }
        if (partitionClass == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(partitionClass, "getFingerprint",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String partitionName = getPartitionName(param.thisObject);
                            String spoofedValue = getPartitionFingerprint(partitionName);
                            if (spoofedValue != null) {
                                param.setResult(spoofedValue);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook Partition.getFingerprint(): " + e.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(partitionClass, "getBuildTimeMillis",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            String partitionName = getPartitionName(param.thisObject);
                            long time = getBuildTimeMillis(partitionName == null || partitionName.isEmpty()
                                    ? "ro.build.date.utc" : "ro." + partitionName + ".build.date.utc");
                            if (time > 0) {
                                param.setResult(time);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to hook Partition.getBuildTimeMillis(): " + e.getMessage());
        }
    }

    private static String getPartitionName(Object partition) {
        try {
            Object name = XposedHelpers.callMethod(partition, "getName");
            return name instanceof String ? (String) name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getPartitionFingerprint(String partitionName) {
        if (partitionName == null || partitionName.isEmpty()) {
            return ConfigManager.getBuildFingerprint();
        }

        String value = ConfigManager.getSystemProperty("ro." + partitionName + ".build.fingerprint", null);
        return value != null ? value : ConfigManager.getBuildFingerprint();
    }

    private static void setStringField(Class<?> clazz, String fieldName, String value) {
        if (value == null) {
            return;
        }
        setStaticField(clazz, fieldName, value);
    }

    private static void setStringArrayField(Class<?> clazz, String fieldName, String[] value) {
        if (value == null) {
            return;
        }
        setStaticField(clazz, fieldName, value);
    }

    private static void setIntField(Class<?> clazz, String fieldName, int value) {
        setStaticField(clazz, fieldName, value);
    }

    private static void setBooleanField(Class<?> clazz, String fieldName, boolean value) {
        setStaticField(clazz, fieldName, value);
    }

    private static void setLongField(Class<?> clazz, String fieldName, long value) {
        setStaticField(clazz, fieldName, value);
    }

    // Plain reflection, not XposedHelpers.setStatic*Field: those log a stack
    // trace and rethrow as IllegalAccessError, which hides the case below.
    private static void setStaticField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field field = XposedHelpers.findField(clazz, fieldName);
            try {
                field.set(null, value);
            } catch (IllegalAccessException e) {
                putStaticWithUnsafe(field, value);
            }
        } catch (NoSuchFieldError ignored) {
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to set " + clazz.getName() + "." + fieldName + ": " + t.getMessage());
        }
    }

    private static volatile Object sUnsafe;
    private static volatile Method sFieldGetOffset;
    private static volatile boolean sUnsafeLogged;

    // Android 17 refuses reflective writes to static final fields in apps that
    // target API 37+ (IllegalAccessException); Unsafe writes are not checked.
    // ART stores static fields inside the Class object, at the offset returned
    // by the hidden Field.getOffset(). That method and theUnsafe are on the
    // unsupported (allowed) non-SDK list; the private Field.offset is not.
    private static void putStaticWithUnsafe(Field field, Object value) throws Throwable {
        if (sUnsafe == null) {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sFieldGetOffset = Field.class.getMethod("getOffset");
            sUnsafe = theUnsafe.get(null);
        }

        Class<?> type = field.getType();
        String writer;
        Class<?> valueType;
        if (type == int.class) {
            writer = "putInt";
            valueType = int.class;
        } else if (type == long.class) {
            writer = "putLong";
            valueType = long.class;
        } else if (type == boolean.class) {
            writer = "putBoolean";
            valueType = boolean.class;
        } else if (!type.isPrimitive() && type.isInstance(value)) {
            // Unsafe skips the type check reflection would have done.
            writer = "putObject";
            valueType = Object.class;
        } else {
            throw new IllegalArgumentException("no Unsafe write for " + type.getName());
        }

        long offset = (Integer) sFieldGetOffset.invoke(field);
        sUnsafe.getClass().getMethod(writer, Object.class, long.class, valueType)
                .invoke(sUnsafe, field.getDeclaringClass(), offset, value);
        if (!sUnsafeLogged && ConfigManager.isVerboseLoggingEnabled()) {
            sUnsafeLogged = true;
            XposedBridge.log(TAG + ": static final writes blocked (target SDK 37+), using Unsafe");
        }
    }

    private static String prop(String key, String defaultValue) {
        String value = ConfigManager.getSystemProperty(key, null);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    private static int propInt(String key, int defaultValue) {
        String value = prop(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean propBoolean(String key, boolean defaultValue) {
        String value = prop(key, String.valueOf(defaultValue));
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static long propLong(String key, long defaultValue) {
        String value = prop(key, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // Build time behind a ro.[<partition>.]build.date.utc key; -1 = no opinion.
    private static long getBuildTimeMillis(String key) {
        long seconds = propLong(key, -1L);
        return seconds > 0 ? seconds * 1000L : -1L;
    }

    private static String[] splitCsv(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }

        String[] rawValues = value.split(",");
        int count = 0;
        for (String rawValue : rawValues) {
            if (!rawValue.trim().isEmpty()) {
                count++;
            }
        }

        String[] values = new String[count];
        int index = 0;
        for (String rawValue : rawValues) {
            String trimmed = rawValue.trim();
            if (!trimmed.isEmpty()) {
                values[index++] = trimmed;
            }
        }
        return values;
    }
}
