package com.devicespooflab.hooks.utils;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class RandomGenerator {

    private static final SecureRandom random = new SecureRandom();

    public static String generateIMEI() {
        String tac = "35847631";

        StringBuilder serial = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            serial.append(random.nextInt(10));
        }

        String imeiWithoutCheck = tac + serial.toString();
        int checkDigit = calculateLuhnCheckDigit(imeiWithoutCheck);

        return imeiWithoutCheck + checkDigit;
    }

    // The IMEI a phone's next SIM slot carries: same TAC, serial number + 1,
    // check digit recomputed. Anything that isn't a 15-digit IMEI comes back
    // unchanged.
    public static String nextImei(String imei) {
        if (imei == null || !imei.matches("\\d{15}")) {
            return imei;
        }
        int serial = (Integer.parseInt(imei.substring(8, 14)) + 1) % 1000000;
        String body = imei.substring(0, 8) + String.format(Locale.US, "%06d", serial);
        return body + calculateLuhnCheckDigit(body);
    }

    public static String generateMEID() {
        StringBuilder meid = new StringBuilder();
        String hexChars = "0123456789ABCDEF";
        for (int i = 0; i < 14; i++) {
            meid.append(hexChars.charAt(random.nextInt(16)));
        }
        return meid.toString();
    }

    public static String generateIMSI() {
        String mcc = "310";
        String mnc = "260";

        StringBuilder msin = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            msin.append(random.nextInt(10));
        }

        return mcc + mnc + msin.toString();
    }

    public static String generateICCID() {
        String prefix = "8901";
        String issuer = "260";

        StringBuilder account = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            account.append(random.nextInt(10));
        }

        String iccidWithoutCheck = prefix + issuer + account.toString();
        int checkDigit = calculateLuhnCheckDigit(iccidWithoutCheck);

        return iccidWithoutCheck + checkDigit;
    }

    public static String generatePhoneNumber() {
        int areaCode = 200 + random.nextInt(800);
        int exchange = 200 + random.nextInt(800);
        int subscriber = random.nextInt(10000);

        return String.format("+1%03d%03d%04d", areaCode, exchange, subscriber);
    }

    public static String generateSerial() {
        String chars = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder serial = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            serial.append(chars.charAt(random.nextInt(chars.length())));
        }
        return serial.toString();
    }

    public static String generateGAID() {
        return UUID.randomUUID().toString();
    }

    public static byte[] generateMediaDrmId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return bytes;
    }

    public static String generateGSFId() {
        StringBuilder gsf = new StringBuilder();
        String hexChars = "0123456789abcdef";
        for (int i = 0; i < 16; i++) {
            gsf.append(hexChars.charAt(random.nextInt(16)));
        }
        return gsf.toString();
    }

    public static String generateAndroidId() {
        StringBuilder androidId = new StringBuilder();
        String hexChars = "0123456789abcdef";
        for (int i = 0; i < 16; i++) {
            androidId.append(hexChars.charAt(random.nextInt(16)));
        }
        return androidId.toString();
    }

    public static String generateFingerprint() {
        return generateFingerprint(null, null, null, null, null, null, null, null);
    }

    public static String generateFingerprint(String brand, String product, String device,
                                             String release, String buildId, String incremental,
                                             String type, String tags) {
        String safeBrand = safeToken(brand, "android");
        String safeProduct = safeToken(product, "generic");
        String safeDevice = safeToken(device, safeProduct);
        String safeRelease = safeToken(release, "15");
        String safeBuildId = safeToken(buildId, generateBuildId());
        String safeIncremental = safeToken(incremental, generateIncremental());
        String safeType = safeToken(type, "user");
        String safeTags = safeToken(tags, "release-keys");

        return String.format(
            "%s/%s/%s:%s/%s/%s:%s/%s",
            safeBrand,
            safeProduct,
            safeDevice,
            safeRelease,
            safeBuildId,
            safeIncremental,
            safeType,
            safeTags
        );
    }

    public static String generateBuildId() {
        return generateBuildId("AA1A");
    }

    public static String generateBuildId(String configuredPrefix) {
        String prefix = safeBuildPrefix(configuredPrefix);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMdd", Locale.US);
        String date = dateFormat.format(new Date());
        int build = random.nextInt(999) + 1;
        return String.format("%s.%s.%03d", prefix, date, build);
    }

    public static String generateIncremental() {
        return String.format("%08d", random.nextInt(100000000));
    }

    public static String generateBootloader() {
        return generateBootloader("device-1.0");
    }

    public static String generateBootloader(String configuredPrefix) {
        StringBuilder hex = new StringBuilder();
        String hexChars = "0123456789ABCDEF";
        for (int i = 0; i < 8; i++) {
            hex.append(hexChars.charAt(random.nextInt(16)));
        }
        return safeBootloaderPrefix(configuredPrefix) + "-" + hex.toString();
    }

    // Same shape as above, but stable: derived from seedSource instead of
    // drawn at random. A blank source falls back to a random value.
    public static String generateBootloader(String configuredPrefix, String seedSource) {
        if (seedSource == null || seedSource.isEmpty()) {
            return generateBootloader(configuredPrefix);
        }
        long seed = stableSeed(configuredPrefix + "/" + seedSource);
        return safeBootloaderPrefix(configuredPrefix) + "-"
                + String.format(Locale.US, "%08X", (int) (seed ^ (seed >>> 32)));
    }

    public static String generateSecurityPatch() {
        Calendar cal = Calendar.getInstance();
        int daysAgo = random.nextInt(90);
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return dateFormat.format(cal.getTime());
    }

    public static String generateHex(int length) {
        StringBuilder hex = new StringBuilder();
        String hexChars = "0123456789abcdef";
        for (int i = 0; i < length; i++) {
            hex.append(hexChars.charAt(random.nextInt(16)));
        }
        return hex.toString();
    }

    // Locally-administered, non-multicast: first octet low nibble is 2/6/A/E.
    public static String generateMacAddress() {
        int firstOctet = (random.nextInt(64) << 2) | 0x02;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02x", firstOctet));
        for (int i = 1; i < 6; i++) {
            sb.append(":").append(String.format("%02x", random.nextInt(256)));
        }
        return sb.toString();
    }

    public static String generateBssid() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02x", random.nextInt(256) & 0xfe));
        for (int i = 1; i < 6; i++) {
            sb.append(":").append(String.format("%02x", random.nextInt(256)));
        }
        return sb.toString();
    }

    public static String generateEid() {
        StringBuilder sb = new StringBuilder();
        sb.append("89");
        sb.append("049032");
        for (int i = 0; i < 24; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    public static String generateHardwareId() {
        return generateHex(32);
    }

    // FNV-1a 64; per-install seed derived from android_id.
    public static long stableSeed(String stableSource) {
        if (stableSource == null) return 0L;
        long h = 1469598103934665603L;
        for (int i = 0; i < stableSource.length(); i++) {
            h ^= (long)(stableSource.charAt(i) & 0xff);
            h *= 1099511628211L;
        }
        return h;
    }

    private static String safeToken(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim()
                .replace(' ', '_')
                .replace('/', '_')
                .replace(':', '_');
    }

    private static String safeBuildPrefix(String value) {
        String prefix = safeToken(value, "AA1A").toUpperCase(Locale.US);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                out.append(c);
            }
        }
        return out.length() == 0 ? "AA1A" : out.toString();
    }

    private static String safeBootloaderPrefix(String value) {
        String prefix = safeToken(value, "device-1.0").toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.') {
                out.append(c);
            }
        }
        return out.length() == 0 ? "device-1.0" : out.toString();
    }

    private static int calculateLuhnCheckDigit(String number) {
        int sum = 0;
        boolean alternate = true;

        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(number.charAt(i));

            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }

            sum += digit;
            alternate = !alternate;
        }

        return (10 - (sum % 10)) % 10;
    }
}
