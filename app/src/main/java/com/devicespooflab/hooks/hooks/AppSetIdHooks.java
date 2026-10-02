package com.devicespooflab.hooks.hooks;

import android.os.Binder;
import android.os.Build;
import android.os.IInterface;
import android.os.Parcel;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AppSetIdHooks {

    private static final String TAG = "DeviceSpoofLab-AppSetId";
    private static final int MIN_SDK = 30;

    private static final String IAPPSET_SERVICE_DESCRIPTOR =
            "com.google.android.gms.appset.internal.IAppSetService";
    private static final String IAPPSET_CALLBACK_DESCRIPTOR =
            "com.google.android.gms.appset.internal.IAppSetIdCallback";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hook(lpparam, Build.VERSION.SDK_INT);
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam, int realDeviceSdk) {
        if (realDeviceSdk < MIN_SDK) {
            return;
        }

        try {
            hookClientSide(lpparam);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": client hook failed: " + e.getMessage());
        }

        if ("com.google.android.gms".equals(lpparam.packageName)) {
            // Not inside a client app that merely loaded GMS code.
            if (AdvertisingIdHooks.isProcessOwner(lpparam)) {
                try {
                    hookGmsServerSide();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": GMS hook failed: " + t.getMessage());
                }
            }
        } else if (lpparam.isFirstApplication && !"android".equals(lpparam.packageName)) {
            // App processes are the clients; system_server has no GMS client code.
            hookClientCallback();
        }
    }

    // ---- Client-side substitution ----

    // Each loaded package can bring its own copy of these; hooked once each.
    private static final Set<Class<?>> sClientHookedClasses =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    private static void hookClientSide(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> appSetIdInfoClass = XposedHelpers.findClassIfExists(
                "com.google.android.gms.appset.AppSetIdInfo", lpparam.classLoader);
        if (appSetIdInfoClass != null && sClientHookedClasses.add(appSetIdInfoClass)) {
            try {
                XposedHelpers.findAndHookMethod(appSetIdInfoClass, "getId",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getAppSetId();
                                if (v != null) param.setResult(v);
                            }
                        });
            } catch (NoSuchMethodError ignored) {
            }
            try {
                // Scope: 1 = APP (per-app id), 2 = DEVELOPER (shared).
                XposedHelpers.findAndHookMethod(appSetIdInfoClass, "getScope",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(1);
                            }
                        });
            } catch (NoSuchMethodError ignored) {
            }
            // Rewrite constructor args so reflective field reads also see
            // the spoofed value.
            try {
                XposedHelpers.findAndHookConstructor(appSetIdInfoClass,
                        String.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getAppSetId();
                                if (v != null) param.args[0] = v;
                                param.args[1] = 1;
                            }
                        });
            } catch (NoSuchMethodError ignored) {
            }
        }

        // AIDL service proxy — for callers that bypass AppSetIdInfo entirely.
        Class<?> appSetServiceStub = XposedHelpers.findClassIfExists(
                "com.google.android.gms.appset.internal.IAppSetService$Stub$Proxy",
                lpparam.classLoader);
        if (appSetServiceStub != null && sClientHookedClasses.add(appSetServiceStub)) {
            try {
                XposedHelpers.findAndHookMethod(appSetServiceStub, "getAppSetIdInfo",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object info = param.getResult();
                                if (info == null) return;
                                String v = ConfigManager.getAppSetId();
                                if (v == null) return;
                                try {
                                    XposedHelpers.setObjectField(info, "id", v);
                                    XposedHelpers.setIntField(info, "scope", 1);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
            } catch (NoSuchMethodError ignored) {
            }
        }

        // Android 14+ Privacy Sandbox AppSetId. The framework constructs this
        // from the IPC reply, so the constructor hook catches the value as it
        // crosses into app code.
        Class<?> systemAppSetId = XposedHelpers.findClassIfExists(
                "android.adservices.appsetid.AppSetId", lpparam.classLoader);
        if (systemAppSetId != null && sClientHookedClasses.add(systemAppSetId)) {
            try {
                XposedHelpers.findAndHookMethod(systemAppSetId, "getId",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getAppSetId();
                                if (v != null) param.setResult(v);
                            }
                        });
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(systemAppSetId, "getScope",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                param.setResult(1);
                            }
                        });
            } catch (Throwable ignored) {
            }
            for (Constructor<?> c : systemAppSetId.getDeclaredConstructors()) {
                Class<?>[] types = c.getParameterTypes();
                if (types.length >= 1 && types[0] == String.class) {
                    try {
                        XposedBridge.hookMethod(c, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getAppSetId();
                                if (v != null) param.args[0] = v;
                                if (param.args.length > 1
                                        && param.args[1] instanceof Integer) {
                                    param.args[1] = 1;
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    // ---- Client-side: patch the callback transaction, independent of R8 ----

    private static final AtomicBoolean sCallbackWatcherInstalled = new AtomicBoolean(false);
    private static final Set<Class<?>> sCallbackHookedClasses =
            Collections.synchronizedSet(new HashSet<Class<?>>());
    private static final String EXTRA_PATCHED_DATA = "ds_appset_data";

    // A shrunk app has no AppSetIdInfo to hook by name: R8 renames it and
    // inlines the whole result path into the callback stub's onTransact, which
    // it may also merge with unrelated binders. The stub still announces its
    // AIDL descriptor through attachInterface, so find it that way and patch
    // the id while it is still inside the incoming transaction. Needs no GMS
    // in the module scope.
    private static void hookClientCallback() {
        if (!sCallbackWatcherInstalled.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(Binder.class, "attachInterface",
                    IInterface.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!IAPPSET_CALLBACK_DESCRIPTOR.equals(param.args[1])) return;
                                Object stub = param.thisObject;
                                if (stub != null) {
                                    installCallbackOnTransactHook(stub.getClass());
                                }
                            } catch (Throwable ignored) {
                                // Discovery must never disturb the binder setup.
                            }
                        }
                    });
        } catch (Throwable t) {
            sCallbackWatcherInstalled.set(false);
            XposedBridge.log(TAG + ": callback watcher failed: " + t.getMessage());
        }
    }

    private static void installCallbackOnTransactHook(Class<?> stubClass) {
        Class<?> declaring = findOnTransactDeclaringClass(stubClass);
        if (declaring == null || !sCallbackHookedClasses.add(declaring)) return;
        try {
            XposedHelpers.findAndHookMethod(declaring, "onTransact",
                    int.class, Parcel.class, Parcel.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // AIDL: onResult(Status, AppSetInfoParcel) = TX code 1.
                                if ((int) param.args[0] != 1) return;
                                // The class can serve other binders as well (a
                                // base stub shared across the client library,
                                // R8 class merging); check this instance.
                                if (!IAPPSET_CALLBACK_DESCRIPTOR.equals(
                                        ((Binder) param.thisObject).getInterfaceDescriptor())) {
                                    return;
                                }
                                Parcel patched = patchCallbackData((Parcel) param.args[1]);
                                if (patched != null) {
                                    param.args[1] = patched;
                                    param.setObjectExtra(EXTRA_PATCHED_DATA, patched);
                                }
                            } catch (Throwable ignored) {
                                // The callback must run on the real data then.
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if ((int) param.args[0] != 1) return;
                            Object patched = param.getObjectExtra(EXTRA_PATCHED_DATA);
                            if (patched instanceof Parcel) {
                                ((Parcel) patched).recycle();
                            }
                        }
                    });
            if (ConfigManager.isVerboseLoggingEnabled()) {
                XposedBridge.log(TAG + ": callback stub hooked: " + declaring.getName());
            }
        } catch (Throwable t) {
            sCallbackHookedClasses.remove(declaring);
            XposedBridge.log(TAG + ": callback hook on " + declaring.getName()
                    + " failed: " + t.getMessage());
        }
    }

    private static Class<?> findOnTransactDeclaringClass(Class<?> cls) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class && walk != Binder.class) {
            try {
                walk.getDeclaredMethod("onTransact", int.class,
                        Parcel.class, Parcel.class, int.class);
                return walk;
            } catch (NoSuchMethodException ignored) {
            }
            walk = walk.getSuperclass();
        }
        return null;
    }

    // SafeParcelable wire format: an object is this marker plus its byte size,
    // then one (id | size << 16) header per field. A size of 0xFFFF means the
    // real size follows in the next int; SafeParcelWriter always writes
    // objects and strings that way.
    private static final int SAFE_PARCEL_OBJECT = 0x4F45;

    private static boolean isLongSize(int header) {
        return (header & 0xFFFF0000) == 0xFFFF0000;
    }

    private static int readSafeParcelSize(Parcel p, int header) {
        return isLongSize(header) ? p.readInt() : (header >>> 16);
    }

    // onResult(Status, AppSetInfoParcel): after the interface token, each
    // argument is a presence int followed by a SafeParcelable. The id is
    // String field 1 of the second one, the scope int field 2. Returns a
    // patched copy, or null to leave the transaction alone. The incoming
    // Parcel is the read-only binder buffer, so it is only ever read here.
    // Every size is range-checked before use: Parcel aborts the process on a
    // negative data position.
    private static Parcel patchCallbackData(Parcel data) {
        String spoof = ConfigManager.getAppSetId();
        if (spoof == null) return null;
        final int start = data.dataPosition();
        Parcel out = null;
        try {
            data.enforceInterface(IAPPSET_CALLBACK_DESCRIPTOR);
            if (data.readInt() != 0) {
                // Status: skipped whole.
                int header = data.readInt();
                if ((header & 0xFFFF) != SAFE_PARCEL_OBJECT) return null;
                int size = readSafeParcelSize(data, header);
                int pos = data.dataPosition();
                if (size < 0 || size > data.dataSize() - pos) return null;
                data.setDataPosition(pos + size);
            }
            if (data.readInt() == 0) return null;
            int header = data.readInt();
            if ((header & 0xFFFF) != SAFE_PARCEL_OBJECT) return null;
            int objectSize = readSafeParcelSize(data, header);
            int objectStart = data.dataPosition();
            if (objectSize < 0 || objectSize > data.dataSize() - objectStart) return null;
            int objectEnd = objectStart + objectSize;

            int idStart = -1;
            int idEnd = -1;
            boolean longSizes = isLongSize(header);
            int scopePos = -1;
            while (data.dataPosition() < objectEnd) {
                int field = data.readInt();
                int size = readSafeParcelSize(data, field);
                int pos = data.dataPosition();
                if (size < 0 || size > objectEnd - pos) return null;
                if ((field & 0xFFFF) == 1) {
                    idStart = pos;
                    idEnd = pos + size;
                    longSizes &= isLongSize(field);
                } else if ((field & 0xFFFF) == 2 && size == 4) {
                    scopePos = pos;
                }
                data.setDataPosition(pos + size);
            }
            // No id field, or a null id: nothing to replace.
            if (idEnd <= idStart) return null;

            out = Parcel.obtain();
            out.appendFrom(data, 0, idStart);
            out.writeString(spoof);
            int delta = out.dataPosition() - idEnd;
            out.appendFrom(data, idEnd, data.dataSize() - idEnd);
            if (delta != 0) {
                // A spoofed id of another length moves both enclosing sizes,
                // which can only be rewritten in their long form.
                if (!longSizes) return null;
                out.setDataPosition(idStart - 4);
                out.writeInt(idEnd - idStart + delta);
                out.setDataPosition(objectStart - 4);
                out.writeInt(objectSize + delta);
            }
            if (scopePos >= 0) {
                // Scope: 1 = APP, as the other hooks report it.
                out.setDataPosition(scopePos > idStart ? scopePos + delta : scopePos);
                out.writeInt(1);
            }
            out.setDataPosition(0);
            Parcel patched = out;
            out = null;
            return patched;
        } catch (Throwable t) {
            return null;
        } finally {
            if (out != null) out.recycle();
            data.setDataPosition(start);
        }
    }

    // ---- GMS-side: rewrite the payload before it leaves the server ----

    private static final AtomicBoolean sAttachWatcherInstalled = new AtomicBoolean(false);
    private static final Set<String> sApiBinderHookedClasses =
            Collections.synchronizedSet(new HashSet<String>());

    private static void hookGmsServerSide() {
        if (!sAttachWatcherInstalled.compareAndSet(false, true)) return;
        // Every AIDL Stub calls Binder.attachInterface(this, DESCRIPTOR) in
        // its constructor. The descriptor string survives R8, so this gives
        // us a stable hook on the (renamed) AppSet Stub class without having
        // to chase chimera plumbing.
        try {
            XposedHelpers.findAndHookMethod(android.os.Binder.class, "attachInterface",
                    IInterface.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!IAPPSET_SERVICE_DESCRIPTOR.equals(param.args[1])) return;
                            Object stub = param.args[0];
                            if (stub != null) {
                                installAppSetStubHooks(stub.getClass());
                            }
                        }
                    });
        } catch (Throwable t) {
            sAttachWatcherInstalled.set(false);
            XposedBridge.log(TAG + ": attachInterface watcher failed: " + t.getMessage());
        }
    }

    private static void installAppSetStubHooks(Class<?> stubClass) {
        if (!sApiBinderHookedClasses.add(stubClass.getName())) return;
        // Hook every non-framework declared method on the stub. The AppSet
        // AIDL method is (AppSetIdRequestParams, IAppSetIdCallback) under R8
        // renaming; wrap any IInterface arg so the server's callback
        // delivery passes through our payload rewrite.
        for (Method m : stubClass.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            String n = m.getName();
            if ("asBinder".equals(n) || "onTransact".equals(n)
                    || "getInterfaceDescriptor".equals(n)
                    || "queryLocalInterface".equals(n)
                    || "dispatchTransaction".equals(n)
                    || "toString".equals(n)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) return;
                        for (int i = 0; i < param.args.length; i++) {
                            Object a = param.args[i];
                            if (a instanceof IInterface) {
                                Object wrapped = wrapCallback(a);
                                if (wrapped != null) param.args[i] = wrapped;
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + stubClass.getSimpleName()
                        + "." + n + " failed: " + t.getMessage());
            }
        }
    }

    // Wrap the callback so every invocation passes through us. When the
    // server calls back with an AppSetInfoParcel-shaped object, rewrite its
    // String field before the call serialises into binder.
    private static Object wrapCallback(Object cb) {
        Class<?>[] ifaces = collectInterfaces(cb.getClass());
        if (ifaces.length == 0) return null;
        final Object delegate = cb;
        try {
            return Proxy.newProxyInstance(cb.getClass().getClassLoader(), ifaces,
                    (proxy, method, args) -> {
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg == null) continue;
                                maybeRewriteAppSetPayload(arg);
                            }
                        }
                        return method.invoke(delegate, args);
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?>[] collectInterfaces(Class<?> cls) {
        LinkedHashSet<Class<?>> set = new LinkedHashSet<>();
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            for (Class<?> i : walk.getInterfaces()) set.add(i);
            walk = walk.getSuperclass();
        }
        return set.toArray(new Class<?>[0]);
    }

    // The App Set ID is a UUID (RFC 4122). Match the id slot by value, not by
    // field position, so an unrelated leading String field never receives it.
    private static final Pattern APP_SET_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // The AppSet callback payload (com.google.android.gms.appset.AppSetInfoParcel
    // in current GMS) keeps its canonical class name because it is a Parcelable,
    // but R8 renames its fields, so we cannot match the id by name. Locate it by
    // its UUID value instead — robust to field ordering and to any extra String
    // field (package name, class id) the parcel may carry — and force scope =
    // APP (1) to match the client-side hooks when the scope int is unambiguous.
    private static void maybeRewriteAppSetPayload(Object obj) {
        Class<?> c = obj.getClass();
        String n = c.getName();
        if (!n.contains("AppSet") && !n.contains("appset")) return;
        String spoof = ConfigManager.getAppSetId();
        if (spoof == null) return;

        Field idField = null;
        Field soleStringField = null;
        int stringFieldCount = 0;
        Field soleIntField = null;
        int intFieldCount = 0;

        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t == String.class) {
                stringFieldCount++;
                soleStringField = f;
                if (idField == null) {
                    try {
                        f.setAccessible(true);
                        Object cur = f.get(obj);
                        if (cur instanceof String
                                && APP_SET_ID_PATTERN.matcher((String) cur).matches()) {
                            idField = f;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } else if (t == int.class) {
                intFieldCount++;
                soleIntField = f;
            }
        }

        // Prefer the UUID-shaped field; fall back to the only String field when
        // the payload has exactly one (still unambiguous). Never guess among
        // several non-UUID strings — that is how the old positional rewrite put
        // the id into the wrong slot and silently failed.
        Field target = idField != null ? idField
                : (stringFieldCount == 1 ? soleStringField : null);
        if (target == null) {
            XposedBridge.log(TAG + ": " + n + " exposed no UUID-shaped id ("
                    + stringFieldCount + " string fields); skipping rewrite");
            return;
        }
        try {
            target.setAccessible(true);
            target.set(obj, spoof);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": id rewrite on " + n + " failed: " + t.getMessage());
            return;
        }

        // Only force scope once we are confident this is AppSetInfoParcel (UUID
        // id matched) and the scope int is unambiguous. A second int field is
        // most likely a SafeParcelable versionCode; overwriting it would corrupt
        // parcel readback, so leave scope untouched in that case.
        if (idField != null && intFieldCount == 1) {
            try {
                soleIntField.setAccessible(true);
                soleIntField.setInt(obj, 1);
            } catch (Throwable ignored) {
            }
        }
    }
}
