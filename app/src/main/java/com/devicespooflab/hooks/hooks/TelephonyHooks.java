package com.devicespooflab.hooks.hooks;

import com.devicespooflab.hooks.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class TelephonyHooks {

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> telephonyManager = XposedHelpers.findClassIfExists(
                "android.telephony.TelephonyManager",
                lpparam.classLoader
        );

        if (telephonyManager == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getDeviceId",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMEI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        // Per slot: a dual-SIM phone has one IMEI for each of its two slots, and
        // nothing beyond them.
        XC_MethodHook slotImeiHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int slot = (int) param.args[0];
                if (slot < 0 || slot > 1) return;
                String v = ConfigManager.getIMEI(slot);
                if (v != null) param.setResult(v);
            }
        };

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getDeviceId", int.class,
                    slotImeiHook);
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getImei",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMEI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getImei", int.class,
                    slotImeiHook);
        } catch (NoSuchMethodError ignored) {
        }

        // The first 8 digits of the IMEI / MEID, which name the phone model.
        // Android hands these out without any permission.
        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getTypeAllocationCode",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = leadingCode(ConfigManager.getIMEI());
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getTypeAllocationCode", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int slot = (int) param.args[0];
                            if (slot < 0 || slot > 1) return;
                            String v = leadingCode(ConfigManager.getIMEI(slot));
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        XC_MethodHook manufacturerCodeHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String v = leadingCode(ConfigManager.getMEID());
                if (v != null) param.setResult(v);
            }
        };

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getManufacturerCode",
                    manufacturerCodeHook);
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getManufacturerCode", int.class,
                    manufacturerCodeHook);
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getMeid",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getMEID();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getMeid", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getMEID();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSubscriberId",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMSI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSubscriberId", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getIMSI();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimSerialNumber",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getICCID();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimSerialNumber", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getICCID();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getLine1Number",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getPhoneNumber();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getLine1Number", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String v = ConfigManager.getPhoneNumber();
                            if (v != null) param.setResult(v);
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        // Hook network operator methods (MCC/MNC)
        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getNetworkOperator",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String mccMnc = ConfigManager.getSystemProperty("gsm.operator.numeric", null);
                            if (mccMnc != null) {
                                param.setResult(mccMnc);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getNetworkOperatorName",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String operatorName = ConfigManager.getSystemProperty("gsm.operator.alpha", null);
                            if (operatorName != null) {
                                param.setResult(operatorName);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimOperator",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String simMccMnc = ConfigManager.getSystemProperty("gsm.sim.operator.numeric", null);
                            if (simMccMnc != null) {
                                param.setResult(simMccMnc);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimOperatorName",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String simOperatorName = ConfigManager.getSystemProperty("gsm.sim.operator.alpha", null);
                            if (simOperatorName != null) {
                                param.setResult(simOperatorName);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getSimCountryIso",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String simCountry = ConfigManager.getSystemProperty("gsm.sim.operator.iso-country", null);
                            if (simCountry != null) {
                                param.setResult(simCountry);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(telephonyManager, "getNetworkCountryIso",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String networkCountry = ConfigManager.getSystemProperty("gsm.operator.iso-country", null);
                            if (networkCountry != null) {
                                param.setResult(networkCountry);
                            }
                        }
                    });
        } catch (NoSuchMethodError ignored) {
        }

        // Android 13+ replaced TelephonyManager.getLine1Number() with
        // SubscriptionManager.getPhoneNumber(int) and getPhoneNumber(int, int).
        Class<?> subscriptionManager = XposedHelpers.findClassIfExists(
                "android.telephony.SubscriptionManager", lpparam.classLoader);
        if (subscriptionManager != null) {
            XC_MethodHook phoneNumberHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String v = ConfigManager.getPhoneNumber();
                    if (v != null) param.setResult(v);
                }
            };
            try {
                XposedHelpers.findAndHookMethod(subscriptionManager, "getPhoneNumber",
                        int.class, phoneNumberHook);
            } catch (NoSuchMethodError ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(subscriptionManager, "getPhoneNumber",
                        int.class, int.class, phoneNumberHook);
            } catch (NoSuchMethodError ignored) {
            }
        }
    }

    private static String leadingCode(String id) {
        return id != null && id.length() >= 8 ? id.substring(0, 8) : null;
    }
}
