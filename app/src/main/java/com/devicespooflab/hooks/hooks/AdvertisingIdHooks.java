package com.devicespooflab.hooks.hooks;

import android.content.ComponentName;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.io.FileDescriptor;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// GAID spoof. Client-side AdvertisingIdClient hook, plus a wrapper around the
// adid service binder for clients R8 renamed or that transact on it directly.
// GMS-side AdvertisingIdChimeraService.onBind hook + IAdvertisingIdService$Stub.onTransact
// parcel rewrite as a fallback when R8 renames the service.
public class AdvertisingIdHooks {

    private static final String GAID_STUB_NAME =
            "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService$Stub";
    // The AIDL interface descriptor survives R8 obfuscation (it's the binder
    // token), unlike the Stub/Chimera class names. Every Stub ctor calls
    // Binder.attachInterface(this, DESCRIPTOR), so this is our obfuscation-proof
    // discovery key inside GMS.
    private static final String GAID_AIDL_DESCRIPTOR =
            "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService";
    private static final String CHIMERA_SERVICE_NAME_A =
            "com.google.android.gms.adid.service.AdvertisingIdChimeraService";
    private static final String CHIMERA_SERVICE_NAME_B =
            "com.google.android.gms.ads.identifier.service.AdvertisingIdChimeraService";

    private static final AtomicBoolean sStubHookInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sChimeraOnBindInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean sGetIdHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sAttachIfaceHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sClientBinderHooked = new AtomicBoolean(false);

    private static final List<XC_MethodHook.Unhook> sWatcherUnhooks = new ArrayList<>();
    private static final AtomicBoolean sWatcherRetired = new AtomicBoolean(false);
    private static volatile long sWatcherDeadlineNanos = 0L;
    private static final long WATCHER_BUDGET_NANOS = 120_000_000_000L;

    // hook() runs for every package loaded into the process, and each can bring
    // its own copy of the client classes; a class is hooked once.
    private static final Set<Class<?>> sHookedClasses =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> advertisingIdInfoClass = XposedHelpers.findClassIfExists(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info",
                lpparam.classLoader);
        if (advertisingIdInfoClass != null && sHookedClasses.add(advertisingIdInfoClass)) {
            try {
                XposedHelpers.findAndHookMethod(advertisingIdInfoClass, "getId",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getGAID();
                                if (v != null) param.setResult(v);
                            }
                        });
            } catch (NoSuchMethodError ignored) {
            }
            // Some callers read the adId field via reflection or through the
            // cached Info instance without invoking getId(). Rewriting the
            // constructor argument means any accessor sees the spoofed value.
            try {
                XposedHelpers.findAndHookConstructor(advertisingIdInfoClass,
                        String.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getGAID();
                                if (v != null) param.args[0] = v;
                            }
                        });
            } catch (NoSuchMethodError ignored) {
            }
        }

        // AIDL stub proxy — covers callers that bypass the Info class and
        // invoke IAdvertisingIdService directly via reflection.
        Class<?> adIdServiceStub = XposedHelpers.findClassIfExists(
                "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService$Stub$Proxy",
                lpparam.classLoader);
        if (adIdServiceStub != null && sHookedClasses.add(adIdServiceStub)) {
            try {
                XposedHelpers.findAndHookMethod(adIdServiceStub, "getId",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getGAID();
                                if (v != null) param.setResult(v);
                            }
                        });
            } catch (NoSuchMethodError ignored) {
            }
        }

        // Android 14+ Privacy Sandbox AdId. The framework constructs this
        // from the IPC reply, so the constructor hook catches the value as
        // it crosses into app code.
        Class<?> adIdClass = XposedHelpers.findClassIfExists(
                "android.adservices.adid.AdId", lpparam.classLoader);
        if (adIdClass != null && sHookedClasses.add(adIdClass)) {
            try {
                XposedHelpers.findAndHookMethod(adIdClass, "getAdId",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getGAID();
                                if (v != null) param.setResult(v);
                            }
                        });
            } catch (NoSuchMethodError ignored) {
            }
            for (java.lang.reflect.Constructor<?> c : adIdClass.getDeclaredConstructors()) {
                Class<?>[] types = c.getParameterTypes();
                if (types.length >= 1 && types[0] == String.class) {
                    try {
                        XposedBridge.hookMethod(c, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String v = ConfigManager.getGAID();
                                if (v != null) param.args[0] = v;
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // GMS-side: hook the actual server impl so it never produces the
        // real id in the first place. attachInterface discovery is the reliable
        // path (descriptor survives obfuscation); the name-based watcher stays
        // as a fallback for builds where the descriptor differs. Only in a
        // process that belongs to GMS: its code is also loaded into client apps
        // (dynamite modules), under the same package name.
        if ("com.google.android.gms".equals(lpparam.packageName)) {
            if (isProcessOwner(lpparam)) {
                installGmsAdIdAttachInterfaceHook();
                hookIAdvertisingIdServiceStub(lpparam);
            }
        } else if (lpparam.isFirstApplication && !"android".equals(lpparam.packageName)) {
            // Every other app process is a client of that service. Not
            // system_server: the hook calls into GMS, and nothing there may
            // wait on an app.
            installClientBinderHook(lpparam);
        }
    }

    // True when the package being loaded is the one this process runs as.
    static boolean isProcessOwner(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.appInfo == null) return lpparam.isFirstApplication;
        return lpparam.appInfo.uid == android.os.Process.myUid();
    }

    // ---- Client-side: rewrite the reply on the binder the app talks to ----

    // R8 renames, merges and inlines the GMS client classes hooked by name in
    // hook(), and ad SDKs often skip them and transact on the raw binder. What
    // no client can avoid is binding the adid service and reading getId() off
    // the IBinder it is handed, so swap that binder for a wrapper as the
    // connection is delivered. Needs no GMS in the module scope.
    private static void installClientBinderHook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!sClientBinderHooked.compareAndSet(false, true)) return;
        try {
            Class<?> dispatcher = XposedHelpers.findClassIfExists(
                    "android.app.LoadedApk$ServiceDispatcher", lpparam.classLoader);
            if (dispatcher == null) {
                XposedBridge.log("DeviceSpoofLab-GAID: ServiceDispatcher not found, "
                        + "client binder hook skipped");
                return;
            }
            XC_MethodHook swapBinder = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 2
                                || !(param.args[0] instanceof ComponentName)
                                || !(param.args[1] instanceof IBinder)
                                || param.args[1] instanceof AdIdBinder) {
                            return;
                        }
                        ComponentName name = (ComponentName) param.args[0];
                        if (!"com.google.android.gms".equals(name.getPackageName())) return;
                        if (ConfigManager.getGAID() == null) return;
                        IBinder service = (IBinder) param.args[1];
                        // One binder call per GMS service connection. The
                        // service class is GMS's to rename; the descriptor is
                        // the contract every client is compiled against.
                        if (!GAID_AIDL_DESCRIPTOR.equals(service.getInterfaceDescriptor())) {
                            return;
                        }
                        param.args[1] = wrapAdIdBinder(service);
                    } catch (Throwable ignored) {
                        // The connection must reach the app whatever happens here.
                    }
                }
            };
            // doConnected(ComponentName, IBinder, boolean) up to Android 16;
            // Android 17 put an IBinderSession before the boolean. Hooking by
            // name covers both, the callback checks the two leading arguments.
            if (XposedBridge.hookAllMethods(dispatcher, "doConnected", swapBinder).isEmpty()) {
                XposedBridge.log("DeviceSpoofLab-GAID: ServiceDispatcher.doConnected not found, "
                        + "client binder hook skipped");
            }
        } catch (Throwable t) {
            sClientBinderHooked.set(false);
            XposedBridge.log("DeviceSpoofLab-GAID: client binder hook install failed: "
                    + t.getMessage());
        }
    }

    // The adid service gives every bindService() in a process the same binder,
    // and ServiceDispatcher compares binders by identity to recognise a
    // connection it already has, so the same binder gets the same wrapper.
    private static WeakReference<AdIdBinder> sAdIdBinder;

    private static synchronized IBinder wrapAdIdBinder(IBinder service) {
        AdIdBinder wrapper = sAdIdBinder != null ? sAdIdBinder.get() : null;
        if (wrapper == null || wrapper.mRemote != service) {
            wrapper = new AdIdBinder(service);
            sAdIdBinder = new WeakReference<>(wrapper);
            if (ConfigManager.isVerboseLoggingEnabled()) {
                XposedBridge.log("DeviceSpoofLab-GAID: wrapped adid service binder");
            }
        }
        return wrapper;
    }

    // Forwards everything to the real adid service proxy and rewrites the
    // getId() reply on its way back to the caller.
    private static final class AdIdBinder implements IBinder {
        final IBinder mRemote;

        AdIdBinder(IBinder remote) {
            mRemote = remote;
        }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            boolean handled = mRemote.transact(code, data, reply, flags);
            // AIDL: getId() = TX code 1.
            if (handled && code == 1 && reply != null && (flags & FLAG_ONEWAY) == 0) {
                try {
                    rewriteGetIdReply(reply);
                } catch (Throwable ignored) {
                }
            }
            return handled;
        }

        @Override
        public String getInterfaceDescriptor() throws RemoteException {
            return mRemote.getInterfaceDescriptor();
        }

        @Override
        public boolean pingBinder() {
            return mRemote.pingBinder();
        }

        @Override
        public boolean isBinderAlive() {
            return mRemote.isBinderAlive();
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return mRemote.queryLocalInterface(descriptor);
        }

        @Override
        public void dump(FileDescriptor fd, String[] args) throws RemoteException {
            mRemote.dump(fd, args);
        }

        @Override
        public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
            mRemote.dumpAsync(fd, args);
        }

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            mRemote.linkToDeath(recipient, flags);
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return mRemote.unlinkToDeath(recipient, flags);
        }
    }

    private static void rewriteGetIdReply(Parcel reply) {
        String spoof = ConfigManager.getGAID();
        if (spoof == null) return;
        reply.setDataPosition(0);
        try {
            reply.readException();
        } catch (Throwable t) {
            // The call failed; leave the error for the caller to read.
            reply.setDataPosition(0);
            return;
        }
        // A received reply still points into the read-only binder buffer.
        // Emptying it drops that buffer, and the writes below then land in
        // memory the Parcel owns.
        reply.setDataSize(0);
        reply.setDataPosition(0);
        // No-exception header, written by hand: writeNoException() would also
        // flush StrictMode/AppOps state this thread gathered for another call.
        reply.writeInt(0);
        reply.writeString(spoof);
        reply.setDataPosition(0);
    }

    // Catches the adid AIDL stub by its interface descriptor at bind time,
    // independent of how R8 renamed the Stub / Chimera service classes. Runs in
    // the GMS process; rewrites the getId() reply for every client (so even an
    // obfuscated/shaded AdvertisingIdClient in the target app gets the spoof).
    private static void installGmsAdIdAttachInterfaceHook() {
        if (!sAttachIfaceHooked.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(android.os.Binder.class, "attachInterface",
                    android.os.IInterface.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (sStubHookInstalled.get()) return;
                                if (!GAID_AIDL_DESCRIPTOR.equals(param.args[1])) return;
                                Object stub = param.thisObject;
                                if (stub == null) return;
                                Class<?> dispatcher = findOnTransactDeclaringClass(stub.getClass());
                                if (dispatcher != null) {
                                    installStubOnTransactHook(dispatcher);
                                }
                            } catch (Throwable ignored) {
                                // Discovery must never disturb the binder setup.
                            }
                        }
                    });
        } catch (Throwable t) {
            sAttachIfaceHooked.set(false);
            XposedBridge.log("DeviceSpoofLab-GAID: attachInterface discovery install failed: "
                    + t.getMessage());
        }
    }

    private static void hookIAdvertisingIdServiceStub(XC_LoadPackage.LoadPackageParam lpparam) {
        // Direct path: if the Stub class is already on the classpath, hook it now
        // and skip the class-load watcher entirely.
        Class<?> stub = XposedHelpers.findClassIfExists(GAID_STUB_NAME, lpparam.classLoader);
        if (stub != null) {
            installStubOnTransactHook(stub);
            return;
        }
        // Deferred path: the stub / Chimera impl is loaded lazily under a name we
        // can't reference statically. Rather than hooking ClassLoader.loadClass on
        // the base class — which fires for *every* lookup in *every* loader in the
        // process (cache hits and parent-delegation passes included) and risks
        // reentrancy if the callback ever triggers a class load — watch the single
        // narrow choke point that every dex-backed loader funnels real class
        // *definitions* through: BaseDexClassLoader.findClass(String). Standard
        // loaders (PathClassLoader / DexClassLoader / DelegateLastClassLoader) and
        // the Chimera module loaders all inherit it, it fires only on first
        // definition, and the watcher retires itself the instant it lands a
        // getId() hook (see watchForStub / removeWatchers).
        XC_MethodHook watcher = watchForStub();
        sWatcherDeadlineNanos = System.nanoTime() + WATCHER_BUDGET_NANOS;
        try {
            Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader");
            XC_MethodHook.Unhook u = XposedHelpers.findAndHookMethod(
                    baseDex, "findClass", String.class, watcher);
            synchronized (sWatcherUnhooks) {
                sWatcherUnhooks.add(u);
            }
        } catch (Throwable t) {
            XposedBridge.log("DeviceSpoofLab-GAID: class-load watcher install failed: "
                    + t.getMessage());
        }
    }

    private static XC_MethodHook watchForStub() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sWatcherRetired.get()) return;
                // Retire on a time budget so a renamed (never-matching) service
                // can't keep us intercepting class loads for the whole process.
                // Overflow-safe nanoTime comparison.
                if (System.nanoTime() - sWatcherDeadlineNanos > 0) {
                    removeWatchers();
                    return;
                }
                try {
                    Object result = param.getResult();
                    if (!(result instanceof Class)) return;
                    Class<?> cls = (Class<?>) result;
                    // Hot path is String-only (getName + equals): it touches no
                    // uninitialised classes, so it never re-enters findClass().
                    String n = cls.getName();
                    if (GAID_STUB_NAME.equals(n)) {
                        installStubOnTransactHook(cls);
                        if (sStubHookInstalled.get()) removeWatchers();
                        return;
                    }
                    // The actual AIDL impl is AdvertisingIdChimeraService. We
                    // can't hook getId() statically (it's loaded later via
                    // Chimera and named differently), so hook onBind() to grab
                    // the binder instance at runtime.
                    if (CHIMERA_SERVICE_NAME_A.equals(n) || CHIMERA_SERVICE_NAME_B.equals(n)) {
                        installChimeraServiceOnBindHook(cls);
                        if (sChimeraOnBindInstalled.get()) removeWatchers();
                    }
                } catch (Throwable ignored) {
                    // Watcher bookkeeping must never disturb the class load.
                }
            }
        };
    }

    private static void removeWatchers() {
        if (!sWatcherRetired.compareAndSet(false, true)) return;
        synchronized (sWatcherUnhooks) {
            for (XC_MethodHook.Unhook u : sWatcherUnhooks) {
                try { u.unhook(); } catch (Throwable ignored) {}
            }
            sWatcherUnhooks.clear();
        }
    }

    private static void installChimeraServiceOnBindHook(Class<?> cls) {
        if (!sChimeraOnBindInstalled.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(cls, "onBind",
                    android.content.Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object binder = param.getResult();
                            if (binder == null) return;
                            Class<?> bc = binder.getClass();
                            Method m = findGetIdInHierarchy(bc);
                            if (m != null) {
                                hookGetIdMethod(m);
                                return;
                            }
                            // R8 may rename getId(); rewrite the reply on the
                            // AIDL onTransact dispatcher instead.
                            Class<?> dispatcher = findOnTransactDeclaringClass(bc);
                            if (dispatcher != null) {
                                installStubOnTransactHook(dispatcher);
                            }
                        }
                    });
        } catch (Throwable t) {
            sChimeraOnBindInstalled.set(false);
            XposedBridge.log("DeviceSpoofLab-GAID: ChimeraService.onBind hook failed: "
                    + t.getMessage());
        }
    }

    private static Class<?> findOnTransactDeclaringClass(Class<?> cls) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class && walk != android.os.Binder.class) {
            try {
                walk.getDeclaredMethod("onTransact", int.class,
                        android.os.Parcel.class, android.os.Parcel.class, int.class);
                return walk;
            } catch (NoSuchMethodException ignored) {
            }
            walk = walk.getSuperclass();
        }
        return null;
    }

    private static Method findGetIdInHierarchy(Class<?> cls) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            for (Method m : walk.getDeclaredMethods()) {
                if ("getId".equals(m.getName())
                        && m.getReturnType() == String.class
                        && m.getParameterTypes().length == 0
                        && !Modifier.isAbstract(m.getModifiers())) {
                    return m;
                }
            }
            walk = walk.getSuperclass();
        }
        return null;
    }

    private static void hookGetIdMethod(Method m) {
        if (!sGetIdHooked.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String v = ConfigManager.getGAID();
                    if (v != null) param.setResult(v);
                }
            });
        } catch (Throwable t) {
            sGetIdHooked.set(false);
        }
    }

    private static void installStubOnTransactHook(Class<?> stub) {
        if (!sStubHookInstalled.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(stub, "onTransact",
                    int.class, android.os.Parcel.class, android.os.Parcel.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int code = (int) param.args[0];
                            android.os.Parcel reply = (android.os.Parcel) param.args[2];
                            if (reply == null) return;
                            // AIDL: getId() = TX code 1.
                            if (code != 1) return;
                            String spoof = ConfigManager.getGAID();
                            if (spoof == null) return;
                            try {
                                reply.setDataSize(0);
                                reply.setDataPosition(0);
                                reply.writeNoException();
                                reply.writeString(spoof);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            sStubHookInstalled.set(false);
        }
    }
}
