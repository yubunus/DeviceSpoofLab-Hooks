package com.devicespooflab.hooks.hooks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.ClientCertRequest;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.devicespooflab.hooks.utils.ConfigManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WebViewHooks {

    private static final String TAG = "DeviceSpoofLab-WebView";

    // "(Linux; Android 14; Pixel 7 Pro Build/UQ1A.240205.004; wv) AppleWebKit/..."
    // A model can hold parentheses ("moto g(7) power"), so the segment ends
    // where the engine part starts, not at the first ")".
    private static final Pattern UA_PLATFORM = Pattern.compile(
            "\\(Linux; Android ([^;)]*); (.*?)( Build/[^;)]*)?(; wv)?\\)(?= AppleWebKit/)");

    private static volatile String sRealDefaultUserAgent;

    // WebViews already set up, and those that inject the script the old way.
    private static final Set<WebView> sInitialized =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<WebView, Boolean>()));
    private static final Set<WebView> sLegacyInjected =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<WebView, Boolean>()));

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookDefaultUserAgent();
            hookWebViewConstructors();
            hookLoadUrl();
            hookTimeZoneReceiver(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init failed: " + t);
        }
    }

    // Re-run after a live config refresh. The user agent and the script of a
    // WebView are fixed when it is created; the time zone can follow.
    public static void onConfigChanged() {
        scheduleTimeZonePush();
    }

    // ---- User agent ----

    // WebSettings.getDefaultUserAgent() is where apps and SDKs read the UA
    // without a WebView, and where the per-WebView default below comes from.
    private static void hookDefaultUserAgent() {
        try {
            XposedHelpers.findAndHookMethod(WebSettings.class, "getDefaultUserAgent",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.getResult() instanceof String)) return;
                            String real = (String) param.getResult();
                            sRealDefaultUserAgent = real;
                            String spoofed = spoofUserAgent(real);
                            if (spoofed != null) param.setResult(spoofed);
                        }
                    });
        } catch (Throwable t) {
            logFail("WebSettings.getDefaultUserAgent", t);
        }
    }

    // A configured UA wins. Otherwise keep the real WebView UA (its Chrome version
    // must match the engine JS can feature-detect) and swap only the platform
    // segment for the spoofed release, model and build ID. Null = leave it.
    private static String spoofUserAgent(String real) {
        String configured = ConfigManager.getWebViewUserAgent();
        if (configured != null) return configured;
        if (real == null) return null;
        try {
            Matcher m = UA_PLATFORM.matcher(real);
            if (!m.find()) return null;
            // The reduced UA ("Android 10; K") names no device, and it is what
            // every phone sends for an app that gets it.
            if (m.group(3) == null && "10".equals(m.group(1)) && "K".equals(m.group(2))) {
                return null;
            }
            String model = ConfigManager.getBuildModel();
            String release = ConfigManager.getBuildVersionRelease();
            if (model == null || release == null) return null;
            StringBuilder platform = new StringBuilder("(Linux; Android ")
                    .append(release).append("; ").append(model);
            if (m.group(3) != null) {
                String buildId = ConfigManager.getBuildId();
                platform.append(buildId != null ? " Build/" + buildId : m.group(3));
            }
            if (m.group(4) != null) platform.append("; wv");
            platform.append(')');
            return real.substring(0, m.start()) + platform + real.substring(m.end());
        } catch (Throwable t) {
            return null;
        }
    }

    // The UA a new WebView should start with; null = its own default.
    private static String resolveUserAgent(Context context) {
        try {
            // Hooked above, so this is already the spoofed one. Spoofing it
            // again changes nothing and covers a hook that didn't install.
            String ua = WebSettings.getDefaultUserAgent(context);
            String spoofed = spoofUserAgent(ua);
            return spoofed != null ? spoofed : ua;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Set<Class<?>> sSettingsClasses =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    // setUserAgentString(null) means "back to the default", which inside the
    // WebView is the real one. An app's own non-empty UA passes untouched.
    private static void hookSettingsClass(Class<?> settingsClass) {
        if (!sSettingsClasses.add(settingsClass)) return;
        for (Class<?> c = settingsClass; c != null && c != WebSettings.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("setUserAgentString", String.class);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String ua = (String) param.args[0];
                        if (ua != null && !ua.isEmpty()) return;
                        String spoofed = spoofUserAgent(sRealDefaultUserAgent);
                        if (spoofed != null) param.args[0] = spoofed;
                    }
                });
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                logFail("WebSettings.setUserAgentString", t);
                return;
            }
        }
    }

    // ---- Per-WebView setup ----

    private static void hookWebViewConstructors() {
        // Every public constructor chains to one protected one, whose shape
        // changed over the years. Hook them all; the innermost returns first
        // and sets the WebView up, the outer ones find it done.
        try {
            XposedBridge.hookAllConstructors(WebView.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.thisObject instanceof WebView)) return;
                    WebView webView = (WebView) param.thisObject;
                    try {
                        // The set asks the app's own hashCode() / equals().
                        if (!sInitialized.add(webView)) return;
                        initWebView(webView);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": WebView setup failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            logFail("WebView constructors", t);
        }
    }

    private static void initWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        if (settings != null) {
            hookSettingsClass(settings.getClass());
            String ua = resolveUserAgent(webView.getContext());
            if (ua != null && !ua.equals(settings.getUserAgentString())) {
                settings.setUserAgentString(ua);
            }
        }

        SupportLibrary lib = SupportLibrary.get();
        boolean nativeHints = lib != null && settings != null && lib.setClientHints(settings);
        boolean documentStart = lib != null
                && lib.addDocumentStartScript(webView, buildScript(nativeHints, false));
        if (!documentStart) {
            // Older WebView: inject with evaluateJavascript around each load,
            // which can lose the race against the page's first inline script.
            installLegacyInjection();
            sLegacyInjected.add(webView);
            webView.setWebViewClient(new SpoofingWebViewClient(null));
        }
        if (ConfigManager.isVerboseLoggingEnabled()) {
            XposedBridge.log(TAG + ": WebView set up (client hints "
                    + (nativeHints ? "native" : "script") + ", script at "
                    + (documentStart ? "document start" : "page start") + ")");
        }
        scheduleTimeZonePush();
    }

    // The door AndroidX WebKit uses, without the library: the WebView package
    // hands out its support-library objects as InvocationHandlers, and a call
    // names a method of the org.chromium.support_lib_boundary interface the
    // object implements. Method names and feature strings are a compatibility
    // contract (old AndroidX releases keep working against new WebViews).
    private static final class SupportLibrary {
        private static final String BOUNDARY = "org.chromium.support_lib_boundary.";
        private static final String FACTORY = "WebViewProviderFactoryBoundaryInterface";

        private static volatile SupportLibrary sInstance;
        private static volatile boolean sUnavailable;

        private final ClassLoader loader;
        private final InvocationHandler factory;
        private final Set<String> features;

        private SupportLibrary(ClassLoader loader, InvocationHandler factory, String[] features) {
            this.loader = loader;
            this.factory = factory;
            this.features = new HashSet<>(Arrays.asList(features));
        }

        static SupportLibrary get() {
            SupportLibrary lib = sInstance;
            if (lib != null || sUnavailable) return lib;
            synchronized (SupportLibrary.class) {
                if (sInstance != null || sUnavailable) return sInstance;
                try {
                    ClassLoader loader = webViewClassLoader();
                    if (loader == null) {
                        // Only reached with a WebView in hand, so it is loaded.
                        loader = (ClassLoader) XposedHelpers.callStaticMethod(
                                WebView.class, "getWebViewClassLoader");
                        sWebViewLoader = loader;
                    }
                    Class<?> glue = Class.forName(
                            "org.chromium.support_lib_glue.SupportLibReflectionUtil", false, loader);
                    Method create = glue.getDeclaredMethod("createWebViewProviderFactory");
                    create.setAccessible(true);
                    InvocationHandler factory = (InvocationHandler) create.invoke(null);
                    String[] features = (String[]) call(loader, factory, FACTORY,
                            "getSupportedFeatures", new Class<?>[0]);
                    sInstance = new SupportLibrary(loader, factory, features);
                } catch (Throwable t) {
                    // A WebView too old to have the glue layer.
                    sUnavailable = true;
                    if (ConfigManager.isVerboseLoggingEnabled()) {
                        XposedBridge.log(TAG + ": no WebView support library: " + t);
                    }
                }
                return sInstance;
            }
        }

        private static Object call(ClassLoader loader, InvocationHandler target, String iface,
                                   String method, Class<?>[] types, Object... args) throws Throwable {
            Method m = Class.forName(BOUNDARY + iface, false, loader).getMethod(method, types);
            return target.invoke(null, m, args);
        }

        // Client hints (the Sec-CH-UA-* headers and navigator.userAgentData)
        // come from here, not from the UA string. Only the model and the
        // platform version are overridden; the keys left out keep the engine's
        // own values, and so do the brands.
        boolean setClientHints(WebSettings settings) {
            if (!features.contains("USER_AGENT_METADATA")) return false;
            String model = ConfigManager.getBuildModel();
            String release = ConfigManager.getBuildVersionRelease();
            if (model == null || release == null) return false;
            try {
                InvocationHandler converter = (InvocationHandler) call(loader, factory, FACTORY,
                        "getWebkitToCompatConverter", new Class<?>[0]);
                InvocationHandler adapter = (InvocationHandler) call(loader, converter,
                        "WebkitToCompatConverterBoundaryInterface", "convertSettings",
                        new Class<?>[]{WebSettings.class}, settings);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("MODEL", model);
                metadata.put("PLATFORM_VERSION", platformVersion(release));
                call(loader, adapter, "WebSettingsBoundaryInterface", "setUserAgentMetadataFromMap",
                        new Class<?>[]{Map.class}, metadata);
                return true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": client hints override failed: " + t);
                return false;
            }
        }

        // Runs in every frame before any script of the page.
        boolean addDocumentStartScript(WebView webView, String script) {
            if (!features.contains("DOCUMENT_START_SCRIPT:1")) return false;
            try {
                InvocationHandler provider = (InvocationHandler) call(loader, factory, FACTORY,
                        "createWebView", new Class<?>[]{WebView.class}, webView);
                call(loader, provider, "WebViewProviderBoundaryInterface",
                        "addDocumentStartJavaScript", new Class<?>[]{String.class, String[].class},
                        script, new String[]{"*"});
                return true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": document-start script failed: " + t);
                return false;
            }
        }
    }

    private static volatile ClassLoader sWebViewLoader;

    // The loaded WebView package's classloader; null while none is loaded.
    // Reads the factory's field so asking never loads the WebView by itself.
    private static ClassLoader webViewClassLoader() {
        ClassLoader loader = sWebViewLoader;
        if (loader != null) return loader;
        try {
            Object provider = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass("android.webkit.WebViewFactory", null),
                    "sProviderInstance");
            if (provider != null) {
                loader = provider.getClass().getClassLoader();
                sWebViewLoader = loader;
            }
        } catch (Throwable ignored) {
        }
        return loader;
    }

    // "17" -> "17.0.0", "8.1.0" stays: the form Chromium reports.
    private static String platformVersion(String release) {
        String[] parts = release.split("\\.");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) out.append('.');
            String part = i < parts.length ? parts[i].trim() : "";
            out.append(part.matches("\\d+") ? part : "0");
        }
        return out.toString();
    }

    // ---- JavaScript time zone ----

    private static final String EXTRA_TIME_ZONE = "time-zone";
    private static final long[] TIME_ZONE_PUSH_DELAYS_MS =
            {0L, 16L, 50L, 120L, 250L, 600L, 1500L, 3000L};

    private static final Set<Class<?>> sTimeZoneReceiverClasses =
            Collections.synchronizedSet(new HashSet<Class<?>>());
    private static volatile WeakReference<BroadcastReceiver> sTimeZoneReceiver;
    private static volatile WeakReference<Context> sTimeZoneContext;
    private static Handler sMainHandler;

    // The renderer is a separate, unhooked process and starts in the phone's
    // own zone. Chromium keeps it current through a receiver for
    // ACTION_TIMEZONE_CHANGED that lives in the app's process: find it as it
    // registers and hand it the spoofed zone, the way the system would after
    // a zone change. Date and Intl then follow natively.
    private static void hookTimeZoneReceiver(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> contextImpl = XposedHelpers.findClassIfExists(
                "android.app.ContextImpl", lpparam.classLoader);
        if (contextImpl == null) return;
        try {
            XposedBridge.hookAllMethods(contextImpl, "registerReceiverInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        BroadcastReceiver receiver = null;
                        IntentFilter filter = null;
                        Context context = null;
                        for (Object arg : param.args) {
                            if (arg instanceof BroadcastReceiver) receiver = (BroadcastReceiver) arg;
                            else if (arg instanceof IntentFilter) filter = (IntentFilter) arg;
                            else if (arg instanceof Context) context = (Context) arg;
                        }
                        if (receiver == null || filter == null
                                || !filter.hasAction(Intent.ACTION_TIMEZONE_CHANGED)) {
                            return;
                        }
                        ClassLoader webView = webViewClassLoader();
                        if (webView == null || receiver.getClass().getClassLoader() != webView) {
                            return;
                        }
                        hookTimeZoneReceiverClass(receiver.getClass());
                        sTimeZoneReceiver = new WeakReference<>(receiver);
                        sTimeZoneContext = new WeakReference<>(context);
                        if (ConfigManager.isVerboseLoggingEnabled()) {
                            XposedBridge.log(TAG + ": time zone receiver found: "
                                    + receiver.getClass().getName());
                        }
                        scheduleTimeZonePush();
                    } catch (Throwable ignored) {
                        // The registration itself must never be disturbed.
                    }
                }
            });
        } catch (Throwable t) {
            logFail("ContextImpl.registerReceiverInternal", t);
        }

        // Once unregistered the receiver has no engine behind it any more.
        try {
            XposedHelpers.findAndHookMethod(contextImpl, "unregisterReceiver",
                    BroadcastReceiver.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            WeakReference<BroadcastReceiver> ref = sTimeZoneReceiver;
                            if (ref != null && param.args[0] != null && ref.get() == param.args[0]) {
                                sTimeZoneReceiver = null;
                            }
                        }
                    });
        } catch (Throwable t) {
            logFail("ContextImpl.unregisterReceiver", t);
        }
    }

    // A real zone change on the phone reaches the same receiver; it gets the
    // spoofed zone there too.
    private static void hookTimeZoneReceiverClass(Class<?> receiverClass) {
        if (!sTimeZoneReceiverClasses.add(receiverClass)) return;
        try {
            XposedHelpers.findAndHookMethod(receiverClass, "onReceive",
                    Context.class, Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String zone = LocaleHooks.getSpoofedTimeZoneId();
                            Intent intent = (Intent) param.args[1];
                            if (zone == null || intent == null
                                    || !Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())
                                    || zone.equals(intent.getStringExtra(EXTRA_TIME_ZONE))) {
                                return;
                            }
                            param.args[1] = new Intent(intent).putExtra(EXTRA_TIME_ZONE, zone);
                        }
                    });
        } catch (Throwable t) {
            logFail("time zone receiver", t);
        }
    }

    private static final Runnable sTimeZonePush = new Runnable() {
        @Override
        public void run() {
            try {
                String zone = LocaleHooks.getSpoofedTimeZoneId();
                WeakReference<BroadcastReceiver> ref = sTimeZoneReceiver;
                BroadcastReceiver receiver = ref == null ? null : ref.get();
                if (zone == null || receiver == null) return;
                WeakReference<Context> contextRef = sTimeZoneContext;
                receiver.onReceive(contextRef == null ? null : contextRef.get(),
                        new Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(EXTRA_TIME_ZONE, zone));
            } catch (Throwable ignored) {
            }
        }
    };

    // A renderer only hears about the zone once it is connected, and nothing
    // tells us when that is: repeat over the first seconds after the events
    // that can start one (the receiver registering, a new WebView, a load),
    // densely at first so it lands before the page's own scripts run.
    private static synchronized void scheduleTimeZonePush() {
        if (LocaleHooks.getSpoofedTimeZoneId() == null || sTimeZoneReceiver == null) return;
        try {
            if (sMainHandler == null) {
                sMainHandler = new Handler(Looper.getMainLooper());
            }
            sMainHandler.removeCallbacks(sTimeZonePush);
            for (long delay : TIME_ZONE_PUSH_DELAYS_MS) {
                sMainHandler.postDelayed(sTimeZonePush, delay);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- Loads ----

    private static void hookLoadUrl() {
        XC_MethodHook loadHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (sLegacyInjected.contains(param.thisObject)) {
                        injectAsync(param.thisObject);
                    }
                } catch (Throwable ignored) {
                }
                // loadUrl("javascript:...") runs a script, it loads nothing.
                if (param.args.length > 0 && param.args[0] instanceof String
                        && ((String) param.args[0]).regionMatches(true, 0, "javascript:", 0, 11)) {
                    return;
                }
                scheduleTimeZonePush();
            }
        };

        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl",
                    String.class, loadHook);
        } catch (Throwable t) { logFail("WebView.loadUrl(String)", t); }

        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl",
                    String.class, java.util.Map.class, loadHook);
        } catch (Throwable t) { /* overload */ }

        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadData",
                    String.class, String.class, String.class, loadHook);
        } catch (Throwable t) { /* overload */ }

        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadDataWithBaseURL",
                    String.class, String.class, String.class, String.class, String.class,
                    loadHook);
        } catch (Throwable t) { /* overload */ }

        try {
            XposedHelpers.findAndHookMethod(WebView.class, "postUrl",
                    String.class, byte[].class, loadHook);
        } catch (Throwable t) { /* overload */ }
    }

    // ---- Script injection for WebViews without document-start scripts ----

    private static final AtomicBoolean sLegacyInstalled = new AtomicBoolean(false);

    private static void installLegacyInjection() {
        if (!sLegacyInstalled.compareAndSet(false, true)) return;

        try {
            XposedHelpers.findAndHookMethod(WebView.class, "setWebViewClient",
                    WebViewClient.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!sLegacyInjected.contains(param.thisObject)) return;
                            } catch (Throwable t) {
                                return;
                            }
                            WebViewClient orig = (WebViewClient) param.args[0];
                            if (orig instanceof SpoofingWebViewClient) return;
                            param.args[0] = new SpoofingWebViewClient(orig);
                        }
                    });
        } catch (Throwable t) { logFail("WebView.setWebViewClient", t); }

        try {
            XposedHelpers.findAndHookMethod(WebView.class, "getWebViewClient",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object res = param.getResult();
                            if (res instanceof SpoofingWebViewClient) {
                                WebViewClient delegate = ((SpoofingWebViewClient) res).delegate;
                                param.setResult(delegate != null ? delegate : new WebViewClient());
                            }
                        }
                    });
        } catch (Throwable t) {
            // getWebViewClient() only exists on API 26+; ignore on older platforms.
        }
    }

    private static void injectAsync(Object webView) {
        if (!(webView instanceof WebView)) return;
        final WebView wv = (WebView) webView;
        try {
            wv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        wv.evaluateJavascript(buildScript(false, true), null);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void logFail(String what, Throwable t) {
        XposedBridge.log(TAG + ": failed to hook " + what + ": " + t);
    }

    // Forwards every WebViewClient callback to the original (or the platform
    // default) and injects the spoof script in onPageStarted/onPageFinished.
    static class SpoofingWebViewClient extends WebViewClient {
        private final WebViewClient delegate;

        SpoofingWebViewClient(WebViewClient delegate) {
            this.delegate = delegate;
        }

        // ---- Page lifecycle (also injects our spoof script) ----

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            inject(view);
            if (delegate != null) delegate.onPageStarted(view, url, favicon);
            else super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            inject(view);
            if (delegate != null) delegate.onPageFinished(view, url);
            else super.onPageFinished(view, url);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (delegate != null) delegate.onPageCommitVisible(view, url);
            else super.onPageCommitVisible(view, url);
        }

        // ---- Navigation ----

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return delegate != null
                    ? delegate.shouldOverrideUrlLoading(view, url)
                    : super.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return delegate != null
                    ? delegate.shouldOverrideUrlLoading(view, request)
                    : super.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (delegate != null) delegate.doUpdateVisitedHistory(view, url, isReload);
            else super.doUpdateVisitedHistory(view, url, isReload);
        }

        @Override
        public void onFormResubmission(WebView view, Message dontResend, Message resend) {
            if (delegate != null) delegate.onFormResubmission(view, dontResend, resend);
            else super.onFormResubmission(view, dontResend, resend);
        }

        // ---- Resource loading / interception ----

        @Override
        public void onLoadResource(WebView view, String url) {
            if (delegate != null) delegate.onLoadResource(view, url);
            else super.onLoadResource(view, url);
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return delegate != null
                    ? delegate.shouldInterceptRequest(view, url)
                    : super.shouldInterceptRequest(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return delegate != null
                    ? delegate.shouldInterceptRequest(view, request)
                    : super.shouldInterceptRequest(view, request);
        }

        // ---- Error reporting ----

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (delegate != null) delegate.onReceivedError(view, errorCode, description, failingUrl);
            else super.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (delegate != null) delegate.onReceivedError(view, request, error);
            else super.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (delegate != null) delegate.onReceivedHttpError(view, request, errorResponse);
            else super.onReceivedHttpError(view, request, errorResponse);
        }

        // ---- Security / authentication ----

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            if (delegate != null) delegate.onReceivedSslError(view, handler, error);
            else super.onReceivedSslError(view, handler, error);
        }

        @Override
        public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
            if (delegate != null) delegate.onReceivedClientCertRequest(view, request);
            else super.onReceivedClientCertRequest(view, request);
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            if (delegate != null) delegate.onReceivedHttpAuthRequest(view, handler, host, realm);
            else super.onReceivedHttpAuthRequest(view, handler, host, realm);
        }

        @Override
        public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
            if (delegate != null) delegate.onReceivedLoginRequest(view, realm, account, args);
            else super.onReceivedLoginRequest(view, realm, account, args);
        }

        @Override
        public void onSafeBrowsingHit(WebView view, WebResourceRequest request,
                                      int threatType, SafeBrowsingResponse callback) {
            if (delegate != null) delegate.onSafeBrowsingHit(view, request, threatType, callback);
            else super.onSafeBrowsingHit(view, request, threatType, callback);
        }

        // ---- Renderer / input / scale ----

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            return delegate != null
                    ? delegate.onRenderProcessGone(view, detail)
                    : super.onRenderProcessGone(view, detail);
        }

        @Override
        public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
            return delegate != null
                    ? delegate.shouldOverrideKeyEvent(view, event)
                    : super.shouldOverrideKeyEvent(view, event);
        }

        @Override
        public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
            if (delegate != null) delegate.onUnhandledKeyEvent(view, event);
            else super.onUnhandledKeyEvent(view, event);
        }

        @Override
        public void onScaleChanged(WebView view, float oldScale, float newScale) {
            if (delegate != null) delegate.onScaleChanged(view, oldScale, newScale);
            else super.onScaleChanged(view, oldScale, newScale);
        }

        private void inject(WebView view) {
            try {
                view.evaluateJavascript(buildScript(false, true), null);
            } catch (Throwable ignored) {}
        }
    }

    // ---- The script ----

    // Marks a page the legacy path has already patched. Random per process, so
    // a page can't look for a known name.
    private static final String LEGACY_MARKER =
            "__" + UUID.randomUUID().toString().replace("-", "");

    // What the script's Function.prototype.toString stand-in answers to, and
    // the engine's own toString doesn't: the way one copy of the script knows
    // a window another copy has patched. A stand-in reads as native, so the
    // source text can't tell. Random per process and in no property a page
    // can reach.
    private static final String SCRIPT_KEY = UUID.randomUUID().toString();

    // nativeHints: client hints are overridden in the engine, the script leaves
    // navigator.userAgentData alone. reinjected: the legacy path runs the
    // script more than once per page and needs a guard; a document-start
    // script runs exactly once per document and leaves no trace of that kind.
    private static String buildScript(boolean nativeHints, boolean reinjected) {
        long seed = ConfigManager.getFingerprintSeed();
        int seedLow = (int) (seed & 0xffffffffL);
        String release = ConfigManager.getBuildVersionRelease();
        if (release == null || release.isEmpty()) release = "15";
        String language = ConfigManager.getLocaleLanguage();
        String country = ConfigManager.getLocaleCountry();
        String tag = country.isEmpty() ? language : language + "-" + country;
        String guard = reinjected
                ? "if (window[\"" + LEGACY_MARKER + "\"]) return;\n"
                + "  Object.defineProperty(window, \"" + LEGACY_MARKER + "\", {value:true});"
                : "";
        return SCRIPT_TEMPLATE
                .replace("__DS_GUARD__", guard)
                .replace("__DS_KEY__", SCRIPT_KEY)
                .replace("__DS_SEED__", Integer.toString(seedLow))
                .replace("__DS_NOISE__",
                        Boolean.toString(ConfigManager.isIdentifierEnabled("fingerprint_seed")))
                .replace("__DS_GPU_VENDOR__", esc(ConfigManager.getGpuVendor()))
                .replace("__DS_GPU_RENDERER__", esc(ConfigManager.getGpuRenderer()))
                .replace("__DS_CORES__", Integer.toString(ConfigManager.getCpuCoreCount()))
                .replace("__DS_MEMORY__", deviceMemoryGb(ConfigManager.getMemoryTotalBytes()))
                .replace("__DS_UACH_MODEL__", nativeHints ? "" : esc(ConfigManager.getBuildModel()))
                .replace("__DS_UACH_PLATFORM_VERSION__", esc(platformVersion(release)))
                .replace("__DS_SCREEN_W__", Integer.toString(ConfigManager.getScreenWidth()))
                .replace("__DS_SCREEN_H__", Integer.toString(ConfigManager.getScreenHeight()))
                .replace("__DS_DPR__",
                        Float.toString(ConfigManager.getScreenDensity() / 160.0f))
                .replace("__DS_SPOOF_SCREEN__",
                        Boolean.toString(ConfigManager.isDisplaySpoofEnabled()))
                .replace("__DS_LANGUAGE__", esc(tag));
    }

    // navigator.deviceMemory the way Chromium buckets it: RAM rounded to the
    // nearest power of two, between 0.25 and 8 GB.
    private static String deviceMemoryGb(long totalBytes) {
        long mb = totalBytes / (1024L * 1024L);
        if (mb <= 0) return "0";
        long lower = Long.highestOneBit(mb);
        long upper = lower << 1;
        double gb = (mb - lower <= upper - mb ? lower : upper) / 1024.0;
        gb = Math.max(0.25, Math.min(8.0, gb));
        return gb == Math.rint(gb) ? Long.toString((long) gb) : String.format(Locale.US, "%s", gb);
    }

    // For a value inside a double-quoted JavaScript string. A raw line break
    // there (U+2028 / U+2029 count as one in older engines) is a syntax error,
    // and with it the whole script is gone.
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    // Leaves alone what the engine already reports correctly: the masked GL
    // strings, navigator.platform / vendor / maxTouchPoints, the client-hint
    // brands. A value is only replaced where it differs from the profile, the
    // replacement sits on the prototype like the original, and a patched
    // function reports the native source text and name of the one it stands
    // in for. install(w) patches one window; a same-origin frame that hasn't
    // run the script itself (the initial about:blank document of a new
    // iframe) is patched the moment the page reaches for it.
    private static final String SCRIPT_TEMPLATE =
            "(function(){\n" +
            "  __DS_GUARD__\n" +
            "  var KEY = \"__DS_KEY__\";\n" +
            "  var SEED = __DS_SEED__|0;\n" +
            "  var NOISE = __DS_NOISE__;\n" +
            "  var GPU_VENDOR = \"__DS_GPU_VENDOR__\";\n" +
            "  var GPU_RENDERER = \"__DS_GPU_RENDERER__\";\n" +
            "  var CORES = __DS_CORES__;\n" +
            "  var MEMORY = __DS_MEMORY__;\n" +
            "  var UACH_MODEL = \"__DS_UACH_MODEL__\";\n" +
            "  var UACH_PLATFORM_VERSION = \"__DS_UACH_PLATFORM_VERSION__\";\n" +
            "  var SCREEN_W = __DS_SCREEN_W__;\n" +
            "  var SCREEN_H = __DS_SCREEN_H__;\n" +
            "  var DPR = __DS_DPR__;\n" +
            "  var SPOOF_SCREEN = __DS_SPOOF_SCREEN__;\n" +
            "  var LANGUAGE = \"__DS_LANGUAGE__\";\n" +
            "\n" +
            "  function makeRng(s){ s = s|0; if(s===0) s = 0x9e3779b9|0; return function(){ s ^= s<<13; s ^= s>>17; s ^= s<<5; return ((s>>>0)/4294967296); }; }\n" +
            "\n" +
            "  // Patched functions read as native: source text by function. One\n" +
            "  // made for a frame also has to be a function of that frame, like the\n" +
            "  // original it stands in for (fn instanceof frame.Function).\n" +
            "  var nativeToString = Function.prototype.toString;\n" +
            "  var masks = new WeakMap();\n" +
            "  function mask(fn, original){\n" +
            "    try {\n" +
            "      masks.set(fn, nativeToString.call(original));\n" +
            "      var proto = Object.getPrototypeOf(original);\n" +
            "      if (Object.getPrototypeOf(fn) !== proto) Object.setPrototypeOf(fn, proto);\n" +
            "    } catch(e){}\n" +
            "    return fn;\n" +
            "  }\n" +
            "  // Replaces a prototype method, keeping its name and property flags.\n" +
            "  function patchMethod(proto, name, make){\n" +
            "    try {\n" +
            "      var d = Object.getOwnPropertyDescriptor(proto, name);\n" +
            "      if (!d || typeof d.value !== 'function') return;\n" +
            "      var replacement = mask(make(d.value), d.value);\n" +
            "      Object.defineProperty(proto, name, { value: replacement, writable: d.writable, enumerable: d.enumerable, configurable: d.configurable });\n" +
            "    } catch(e){}\n" +
            "  }\n" +
            "  // Replaces a prototype getter. The original runs first, so a call on\n" +
            "  // the wrong receiver still throws the native error; then() maps the\n" +
            "  // real value to the reported one.\n" +
            "  function patchGetter(proto, name, then){\n" +
            "    try {\n" +
            "      var d = Object.getOwnPropertyDescriptor(proto, name);\n" +
            "      if (!d || !d.get) return;\n" +
            "      var original = d.get;\n" +
            "      var holder = { get [name](){ return then(original.call(this)); } };\n" +
            "      var getter = mask(Object.getOwnPropertyDescriptor(holder, name).get, original);\n" +
            "      Object.defineProperty(proto, name, { get: getter, set: d.set, enumerable: d.enumerable, configurable: d.configurable });\n" +
            "    } catch(e){}\n" +
            "  }\n" +
            "  function constant(value){ return function(){ return value; }; }\n" +
            "\n" +
            "  // Frames of another origin: nothing to do there, and no need to find\n" +
            "  // that out again on every access.\n" +
            "  var foreign = new WeakSet();\n" +
            "\n" +
            "  function install(w){\n" +
            "    try {\n" +
            "      if (!w || foreign.has(w)) return;\n" +
            "      // Already done, by this script or by another copy of it: only a\n" +
            "      // stand-in answers the key, the engine's toString ignores it. The\n" +
            "      // source text alone can't tell. A frame navigated from its initial\n" +
            "      // about:blank to a same-origin page keeps the window the parent has\n" +
            "      // patched, and to the copy that then runs in it, nativeToString is\n" +
            "      // the parent's stand-in, which reads as native.\n" +
            "      var current = w.Function.prototype.toString;\n" +
            "      if (current.call(current, KEY) === KEY) return;\n" +
            "      if (nativeToString.call(current).indexOf('[native code]') < 0) return;\n" +
            "    } catch(e){\n" +
            "      try { foreign.add(w); } catch(e2){}\n" +
            "      return;\n" +
            "    }\n" +
            "\n" +
            "    try {\n" +
            "      var realToString = w.Function.prototype.toString;\n" +
            "      var shim = ({ toString(){\n" +
            "        if (arguments[0] === KEY) return KEY;\n" +
            "        var text = masks.get(this);\n" +
            "        return text !== undefined ? text : realToString.call(this);\n" +
            "      } }).toString;\n" +
            "      mask(shim, realToString);\n" +
            "      Object.defineProperty(w.Function.prototype, 'toString', { value: shim, writable: true, enumerable: false, configurable: true });\n" +
            "    } catch(e){ return; }\n" +
            "\n" +
            "    // ---- Canvas: deterministic per-pixel noise ----\n" +
            "    if (NOISE) try {\n" +
            "      var origGetImage = w.CanvasRenderingContext2D.prototype.getImageData;\n" +
            "      var addNoise = function(d, rng){\n" +
            "        for (var i=0; i<d.length; i+=4){\n" +
            "          var n = ((rng()*3)|0) - 1;\n" +
            "          d[i]   = Math.max(0, Math.min(255, d[i]   + n));\n" +
            "          d[i+1] = Math.max(0, Math.min(255, d[i+1] + n));\n" +
            "          d[i+2] = Math.max(0, Math.min(255, d[i+2] + n));\n" +
            "        }\n" +
            "      };\n" +
            "      // Works on a copy: the page's canvas keeps its pixels, and a canvas\n" +
            "      // without a context yet (or a WebGL one) is never given a 2d context.\n" +
            "      var noisyCopy = function(canvas){\n" +
            "        try {\n" +
            "          var cw = canvas.width, ch = canvas.height;\n" +
            "          if (!cw || !ch) return null;\n" +
            "          var copy = w.document.createElement('canvas');\n" +
            "          copy.width = cw; copy.height = ch;\n" +
            "          var ctx = copy.getContext('2d');\n" +
            "          ctx.drawImage(canvas, 0, 0);\n" +
            "          var img = origGetImage.call(ctx, 0, 0, cw, ch);\n" +
            "          addNoise(img.data, makeRng(SEED ^ (cw*131) ^ (ch*17)));\n" +
            "          ctx.putImageData(img, 0, 0);\n" +
            "          return copy;\n" +
            "        } catch(e){ return null; }\n" +
            "      };\n" +
            "      patchMethod(w.HTMLCanvasElement.prototype, 'toDataURL', function(orig){\n" +
            "        return ({ toDataURL(){ return orig.apply((this instanceof w.HTMLCanvasElement && noisyCopy(this)) || this, arguments); } }).toDataURL;\n" +
            "      });\n" +
            "      patchMethod(w.HTMLCanvasElement.prototype, 'toBlob', function(orig){\n" +
            "        return ({ toBlob(callback){ return orig.apply((this instanceof w.HTMLCanvasElement && noisyCopy(this)) || this, arguments); } }).toBlob;\n" +
            "      });\n" +
            "      patchMethod(w.CanvasRenderingContext2D.prototype, 'getImageData', function(orig){\n" +
            "        return ({ getImageData(sx, sy, sw, sh){\n" +
            "          var img = orig.apply(this, arguments);\n" +
            "          try { addNoise(img.data, makeRng(SEED ^ (sw*131) ^ (sh*17) ^ (sx*7) ^ (sy*23))); } catch(e){}\n" +
            "          return img;\n" +
            "        } }).getImageData;\n" +
            "      });\n" +
            "    } catch(e){}\n" +
            "\n" +
            "    // ---- WebGL: the unmasked vendor / renderer (WEBGL_debug_renderer_info) ----\n" +
            "    if (GPU_VENDOR && GPU_RENDERER) try {\n" +
            "      var patchGL = function(proto){\n" +
            "        patchMethod(proto, 'getParameter', function(orig){\n" +
            "          return ({ getParameter(pname){\n" +
            "            var real = orig.apply(this, arguments);\n" +
            "            // Still null without the extension.\n" +
            "            if (typeof real === 'string') {\n" +
            "              if (pname === 37445) return GPU_VENDOR;\n" +
            "              if (pname === 37446) return GPU_RENDERER;\n" +
            "            }\n" +
            "            return real;\n" +
            "          } }).getParameter;\n" +
            "        });\n" +
            "      };\n" +
            "      if (w.WebGLRenderingContext)  patchGL(w.WebGLRenderingContext.prototype);\n" +
            "      if (w.WebGL2RenderingContext) patchGL(w.WebGL2RenderingContext.prototype);\n" +
            "    } catch(e){}\n" +
            "\n" +
            "    // ---- AudioContext: tiny offsets in channel data, once per channel ----\n" +
            "    if (NOISE) try {\n" +
            "      if (w.AudioBuffer) {\n" +
            "        var noised = new WeakMap();\n" +
            "        patchMethod(w.AudioBuffer.prototype, 'getChannelData', function(orig){\n" +
            "          return ({ getChannelData(channel){\n" +
            "            var data = orig.apply(this, arguments);\n" +
            "            try {\n" +
            "              var done = noised.get(this);\n" +
            "              if (!done) { done = {}; noised.set(this, done); }\n" +
            "              if (!done[channel]) {\n" +
            "                done[channel] = true;\n" +
            "                var rng = makeRng(SEED ^ (data.length|0));\n" +
            "                for (var i=0; i<data.length; i+=500) data[i] += (rng()-0.5) * 1e-7;\n" +
            "              }\n" +
            "            } catch(e){}\n" +
            "            return data;\n" +
            "          } }).getChannelData;\n" +
            "        });\n" +
            "      }\n" +
            "    } catch(e){}\n" +
            "\n" +
            "    // ---- navigator: only what differs from the profile ----\n" +
            "    try {\n" +
            "      var nav = w.navigator, navProto = w.Navigator.prototype;\n" +
            "      if (CORES > 0 && nav.hardwareConcurrency !== CORES) patchGetter(navProto, 'hardwareConcurrency', constant(CORES));\n" +
            "      if (MEMORY > 0 && nav.deviceMemory !== undefined && nav.deviceMemory !== MEMORY) patchGetter(navProto, 'deviceMemory', constant(MEMORY));\n" +
            "      // The WebView takes its languages from the app's locale, which is\n" +
            "      // already spoofed; this only covers a WebView that didn't follow.\n" +
            "      if (LANGUAGE && nav.language !== LANGUAGE) {\n" +
            "        patchGetter(navProto, 'language', constant(LANGUAGE));\n" +
            "        patchGetter(navProto, 'languages', constant(Object.freeze([LANGUAGE])));\n" +
            "      }\n" +
            "      // Only for a WebView whose client hints can't be overridden natively.\n" +
            "      if (UACH_MODEL && w.NavigatorUAData) {\n" +
            "        patchMethod(w.NavigatorUAData.prototype, 'getHighEntropyValues', function(orig){\n" +
            "          return ({ getHighEntropyValues(hints){\n" +
            "            return orig.apply(this, arguments).then(function(values){\n" +
            "              try {\n" +
            "                if ('model' in values) values.model = UACH_MODEL;\n" +
            "                if ('platformVersion' in values) values.platformVersion = UACH_PLATFORM_VERSION;\n" +
            "              } catch(e){}\n" +
            "              return values;\n" +
            "            });\n" +
            "          } }).getHighEntropyValues;\n" +
            "        });\n" +
            "      }\n" +
            "    } catch(e){}\n" +
            "\n" +
            "    // ---- screen + devicePixelRatio (opt-in, hooks.spoof_display) ----\n" +
            "    if (SPOOF_SCREEN) try {\n" +
            "      var statusH = 80;\n" +
            "      patchGetter(w.Screen.prototype, 'width',  constant(Math.floor(SCREEN_W / DPR)));\n" +
            "      patchGetter(w.Screen.prototype, 'height', constant(Math.floor(SCREEN_H / DPR)));\n" +
            "      patchGetter(w.Screen.prototype, 'availWidth',  constant(Math.floor(SCREEN_W / DPR)));\n" +
            "      patchGetter(w.Screen.prototype, 'availHeight', constant(Math.floor((SCREEN_H - statusH) / DPR)));\n" +
            "      patchGetter(w, 'devicePixelRatio', constant(DPR));\n" +
            "    } catch(e){}\n" +
            "\n" +
            "    // ---- frames: patched before the page can read from them ----\n" +
            "    try {\n" +
            "      var reach = function(frameWindow){ try { install(frameWindow); } catch(e){} return frameWindow; };\n" +
            "      ['HTMLIFrameElement', 'HTMLFrameElement', 'HTMLObjectElement', 'HTMLEmbedElement'].forEach(function(cls){\n" +
            "        if (!w[cls]) return;\n" +
            "        patchGetter(w[cls].prototype, 'contentWindow', reach);\n" +
            "        patchGetter(w[cls].prototype, 'contentDocument', function(doc){ try { if (doc) install(doc.defaultView); } catch(e){} return doc; });\n" +
            "      });\n" +
            "    } catch(e){}\n" +
            "  }\n" +
            "\n" +
            "  install(window);\n" +
            "})();";
}
