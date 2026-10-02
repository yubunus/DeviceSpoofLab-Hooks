package com.devicespooflab.hooks.hooks;

import android.accounts.Account;

import com.devicespooflab.hooks.utils.ConfigManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// AccountManager.getAccounts is hooked; getAccountsByType is intentionally not.
public class AccountHooks {

    private static final String TAG = "DeviceSpoofLab-Account";

    // Installed once the switch is on; the hooks check it again on every call,
    // so it can be turned off without restarting the app.
    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> am = XposedHelpers.findClassIfExists(
                "android.accounts.AccountManager", lpparam.classLoader);
        if (am == null) return;

        try {
            XposedHelpers.findAndHookMethod(am, "getAccounts",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (ConfigManager.isHideAccountsEnabled()) {
                                param.setResult(new Account[0]);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook getAccounts: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(am, "getAccountsAsUser",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (ConfigManager.isHideAccountsEnabled()) {
                                param.setResult(new Account[0]);
                            }
                        }
                    });
        } catch (Throwable t) { /* hidden API; may be missing */ }
    }
}
