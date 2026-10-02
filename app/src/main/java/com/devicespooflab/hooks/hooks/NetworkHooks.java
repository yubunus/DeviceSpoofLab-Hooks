package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.net.NetworkInterface;
import java.util.Collections;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class NetworkHooks {

    private static final String TAG = "DeviceSpoofLab-Network";
    private static final byte[] EMPTY_MAC = new byte[0];

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookWifiInfo(lpparam);
        hookWifiManager(lpparam);
        hookBluetoothAdapter(lpparam);
        hookNetworkInterface();
    }

    private static void hookWifiInfo(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> wifiInfo = XposedHelpers.findClassIfExists(
                "android.net.wifi.WifiInfo", lpparam.classLoader);
        if (wifiInfo == null) return;

        try {
            XposedHelpers.findAndHookMethod(wifiInfo, "getMacAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getWifiMacAddress();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (Throwable t) { logFail("WifiInfo.getMacAddress", t); }

        try {
            XposedHelpers.findAndHookMethod(wifiInfo, "getBSSID",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getWifiBssid();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (Throwable t) { logFail("WifiInfo.getBSSID", t); }

        try {
            XposedHelpers.findAndHookMethod(wifiInfo, "getSSID",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // Android quotes an SSID and leaves the "unknown"
                            // marker bare.
                            String ssid = ConfigManager.getWifiSsid();
                            param.setResult(ssid.isEmpty()
                                    ? "<unknown ssid>" : "\"" + ssid + "\"");
                        }
                    });
        } catch (Throwable t) { logFail("WifiInfo.getSSID", t); }
    }

    private static void hookWifiManager(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> wm = XposedHelpers.findClassIfExists(
                "android.net.wifi.WifiManager", lpparam.classLoader);
        if (wm == null) return;

        try {
            XposedHelpers.findAndHookMethod(wm, "getScanResults",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // Scan-result MAC addresses are equally fingerprintable;
                            // empty list is the safest spoof.
                            param.setResult(Collections.emptyList());
                        }
                    });
        } catch (Throwable t) { logFail("WifiManager.getScanResults", t); }

        // Some apps reach into WifiManager.getCurrentNetwork().getSSID() — those go
        // through WifiInfo, already covered.
    }

    private static void hookBluetoothAdapter(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> ba = XposedHelpers.findClassIfExists(
                "android.bluetooth.BluetoothAdapter", lpparam.classLoader);
        if (ba == null) return;

        try {
            XposedHelpers.findAndHookMethod(ba, "getAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String mac = ConfigManager.getBluetoothMacAddress();
                            if (mac != null) param.setResult(mac.toUpperCase());
                        }
                    });
        } catch (Throwable t) { logFail("BluetoothAdapter.getAddress", t); }

        try {
            XposedHelpers.findAndHookMethod(ba, "getName",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(ConfigManager.getBluetoothName());
                        }
                    });
        } catch (Throwable t) { logFail("BluetoothAdapter.getName", t); }

        // Settings.Secure.bluetooth_address path — settings hook handles strings,
        // but BluetoothAdapter.getAddress hides the well-known reflection too.
    }

    private static void hookNetworkInterface() {
        try {
            XposedHelpers.findAndHookMethod(NetworkInterface.class, "getHardwareAddress",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            NetworkInterface ni = (NetworkInterface) param.thisObject;
                            String name = (ni == null) ? null : ni.getName();
                            if (name == null) return;
                            // Loopback and dummy interfaces have no MAC; preserve null.
                            byte[] original = (byte[]) param.getResult();
                            if (original == null) return;

                            String mac;
                            if (name.startsWith("wlan")) {
                                mac = ConfigManager.getWifiMacAddress();
                            } else if (name.startsWith("bt") || name.startsWith("bnep")) {
                                mac = ConfigManager.getBluetoothMacAddress();
                            } else {
                                // Other interfaces: zero them out rather than leak.
                                param.setResult(new byte[]{0, 0, 0, 0, 0, 0});
                                return;
                            }
                            if (mac == null) return;
                            param.setResult(macStringToBytes(mac));
                        }
                    });
        } catch (Throwable t) { logFail("NetworkInterface.getHardwareAddress", t); }

        // The interface list itself is left alone: every phone has its rmnet,
        // dummy and p2p interfaces, and a list without them stands out.
    }

    private static byte[] macStringToBytes(String mac) {
        if (mac == null) return EMPTY_MAC;
        String[] parts = mac.split(":");
        if (parts.length != 6) return EMPTY_MAC;
        byte[] out = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        } catch (NumberFormatException e) {
            return EMPTY_MAC;
        }
        return out;
    }

    private static void logFail(String what, Throwable t) {
        XposedBridge.log(TAG + ": failed to hook " + what + ": " + t);
    }
}
