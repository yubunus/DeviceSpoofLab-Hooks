package com.devicespooflab.hooks.hooks;

import android.os.StatFs;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.io.File;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// Size of the phone's internal storage, through StatFs and java.io.File
// alike. Other filesystems (/system, an SD card, ...) keep their real numbers.
public class StorageHooks {

    private static final String TAG = "DeviceSpoofLab-Storage";
    private static final long BLOCK_SIZE = 4096L;
    private static final String PATH_FIELD = "ds_path";

    private static final int BLOCK = 0;
    private static final int TOTAL = 1;
    private static final int AVAILABLE = 2;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        // StatFs doesn't keep its path, so remember it per object.
        XC_MethodHook rememberPath = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                XposedHelpers.setAdditionalInstanceField(param.thisObject, PATH_FIELD, param.args[0]);
            }
        };
        try {
            XposedHelpers.findAndHookConstructor(StatFs.class, String.class, rememberPath);
            XposedHelpers.findAndHookMethod(StatFs.class, "restat", String.class, rememberPath);
        } catch (Throwable t) { logFail("StatFs(String)", t); }

        hookStatFs("getBlockSize", BLOCK, false, true);
        hookStatFs("getBlockSizeLong", BLOCK, false, false);
        hookStatFs("getBlockCount", TOTAL, true, true);
        hookStatFs("getBlockCountLong", TOTAL, true, false);
        hookStatFs("getAvailableBlocks", AVAILABLE, true, true);
        hookStatFs("getAvailableBlocksLong", AVAILABLE, true, false);
        hookStatFs("getFreeBlocks", AVAILABLE, true, true);
        hookStatFs("getFreeBlocksLong", AVAILABLE, true, false);
        hookStatFs("getTotalBytes", TOTAL, false, false);
        hookStatFs("getAvailableBytes", AVAILABLE, false, false);
        hookStatFs("getFreeBytes", AVAILABLE, false, false);

        hookFile("getTotalSpace", TOTAL);
        hookFile("getFreeSpace", AVAILABLE);
        hookFile("getUsableSpace", AVAILABLE);
    }

    private static void hookStatFs(String method, final int what, final boolean inBlocks,
                                   final boolean asInt) {
        try {
            XposedHelpers.findAndHookMethod(StatFs.class, method,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object path = XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject, PATH_FIELD);
                            if (!(path instanceof String) || !isInternalStorage((String) path)) {
                                return;
                            }
                            long value = value(what);
                            if (inBlocks) value /= BLOCK_SIZE;
                            if (asInt) {
                                param.setResult((int) value);
                            } else {
                                param.setResult(value);
                            }
                        }
                    });
        } catch (Throwable t) { logFail("StatFs." + method, t); }
    }

    private static void hookFile(String method, final int what) {
        try {
            XposedHelpers.findAndHookMethod(File.class, method,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 0 = the path doesn't name a partition; that stays.
                            if (!(param.getResult() instanceof Long) || (Long) param.getResult() <= 0L) {
                                return;
                            }
                            if (isInternalStorage(((File) param.thisObject).getAbsolutePath())) {
                                param.setResult(value(what));
                            }
                        }
                    });
        } catch (Throwable t) { logFail("File." + method, t); }
    }

    private static long value(int what) {
        switch (what) {
            case TOTAL:
                return ConfigManager.getStorageTotalBytes();
            case AVAILABLE:
                return ConfigManager.getStorageAvailableBytes();
            default:
                return BLOCK_SIZE;
        }
    }

    // /data and the emulated "sdcard" that lives on the same partition.
    private static boolean isInternalStorage(String path) {
        return path.equals("/data") || path.startsWith("/data/")
                || path.startsWith("/storage/emulated") || path.startsWith("/storage/self")
                || path.equals("/sdcard") || path.startsWith("/sdcard/")
                || path.startsWith("/mnt/sdcard") || path.startsWith("/mnt/user/");
    }

    private static void logFail(String what, Throwable t) {
        XposedBridge.log(TAG + ": failed to hook " + what + ": " + t);
    }
}
