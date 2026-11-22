package com.vpeen.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.*;

/**
 * ArcVeePN 1.7 — WebView UI is the user's ProxyArc HTML exactly.
 *
 * ROOT CAUSE of the v1.6 failure (verified live 2026-11-03):
 * v1.6 ranked servers by downloading a 500 KB file from
 * speedtest.tele2.net through each tunnel. That test host itself
 * is unreachable — curl from this VM timed out after 20 s with
 * HTTP 000, size 0, no headers at all. So EVERY server's test
 * failed, the ranked list was empty, and v1.6 refused to connect
 * to anything ("no server passed, do not connect") instead of
 * falling back. It also never reported results, so the UI stayed
 * on "تست…" forever, and the auto-test on launch had been removed.
 * The VeePN servers were not the problem; the test host was.
 *
 * v1.7 — real tunnel ping, with a fallback chain so a test
 * failure can NEVER again leave the user with no VPN at all:
 *
 * 1. PRIMARY — real ping through the tunnel: for each candidate
 *    server, open the real upstream path (TLS to the proxy +
 *    HTTP CONNECT + per-session auth, exactly as real traffic
 *    goes) and fetch a tiny page over HTTP through it
 *    (a tiny public test page, 577 bytes, verified live). The time from
 *    before CONNECT to after the full tiny response is the
 *    real, end-to-end ping. Only servers that complete it count,
 *    and the lowest time wins. This is not a TCP port probe:
 *    a port can answer TCP yet fail CONNECT/auth, and that
 *    server is correctly excluded here.
 * 2. If the test page fails for ALL servers, retry the same test
 *    with a second test page (also verified live, HTTP only, no
 *    redirect), so one test-host outage cannot kill the app.
 * 3. If both tunnel tests fail, fall back to TCP connect ranking
 *    (v1.5 method, used ONLY as an emergency fallback, never
 *    as the primary rank) and try those servers in order.
 * 4. If TCP also fails everywhere, try the API-order servers
 *    in order, with a real "tunnel actually running" check.
 * Only if every level fails to start a tunnel is an error shown.
 *
 * Also restored in v1.7: the auto-test on launch (tiny pages,
 * not 500 KB, so launch data cost is a few KB, not megabytes),
 * and per-region reporting even on failure (best ms, or -1
 * shown as "—"), so the UI never stays stuck on "ping…".
 *
 * Kept from v1.6 (these were not the bug): trying ranked
 * servers in order until the tunnel actually reports running
 * (up to 3), optimize throttled to once per 60 s with fallback
 * to Fastest, and the background watchdog that recovers a
 * stalled tunnel even when the WebView is paused.
 */
public class MainActivity extends Activity {
    private static final int REQ_VPN = 1001;
    private static final int REQ_NOTIF = 1002;

    /** 3.1 fix3: the widget / Quick Settings tile tap opens
     *  MainActivity with this extra (and this action) and the
     *  Activity toggles the connection itself. The Activity
     *  is singleTop, so a tap while the app is already open
     *  delivers the intent to onNewIntent instead of stacking
     *  a second Activity. */
    public static final String EXTRA_AUTO_TOGGLE = "com.vpeen.android.AUTO_TOGGLE";
    public static final String ACTION_WIDGET_TOGGLE = "com.vpeen.android.WIDGET_TOGGLE";

    // sb11 RTT ping (replaces the sb10 CONNECT-time ping): sb10
    // timed connection SETUP (DNS + TCP + TLS handshake + CONNECT
    // = 3-4 RTTs, and carrier DNS inside the timed window for
    // pre-connect probes, since resolvedIp is only set in
    // startTunnel) — so it read ~800 ms where Speedtest showed
    // ~120 ms. Speedtest times a request/response on an
    // ALREADY-ESTABLISHED connection: ~1 RTT. This does the same:
    // the CONNECT tunnel is established once (untimed setup),
    // then a minimal GET is timed to its first response byte on
    // that established tunnel. The Clash API delay is NO LONGER
    // shown as the ping (it measures a full HTTPS fetch — the
    // same multi-RTT error); Clash stays only for sing-box's
    // internal urltest use.

    private WebView webView;
    // Sub (TEST): pending connect while the VPN permission
    // dialog is up, and the last fetched Sub server list
    // (passwords stay in these objects only — never sent to
    // the WebView; the 30-min fetch cache lives in SubscriptionApi).
    private String pendingSubIp;
    private String pendingSubFlag;
    private String pendingSubProto;
    private volatile List<Server> subCache;
    // Sub v2: per-server TCP ping results + Fastest
    // live-switch. subPingMs holds the last sweep's result
    // per IP (ms, or -1 when the server did not answer);
    // subFailedIps are servers a Fastest connect already
    // tried and failed, so the next-best is tried instead.
    private static final int SUB_PING_TIMEOUT_MS = 3000;
    // Local proxy ports (were the removed Java bridge's
    // SOCKS_PORT / HTTP_PORT — sing-box serves these ports
    // itself in both modes now).
    private static final int SOCKS_PORT = 1080;
    private static final int HTTP_PORT = 8080;
    private static final long SUB_FASTEST_MIN_GAIN_MS = 20;
    private final Map<String, Long> subPingMs = new ConcurrentHashMap<>();
    private final java.util.Set<String> subFailedIps =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile Thread subPingThread = null;
    private volatile boolean subFastest = false;
    private volatile long subFastestEpoch = 0;
    private volatile String subBestIp = null;
    private volatile long subBestMs = Long.MAX_VALUE;
    private volatile String subConnectedIp = null;
    private volatile long subConnectedMs = Long.MAX_VALUE;
    private volatile boolean pendingSubFastest = false;
    // v8: cached-list state. ssCacheLoaded is true when the
    // SS list on screen came from the offline cache (the
    // fresh fetch is still running or failed); the same
    // flag is reported to the WebView so the user can see
    // the list is cached, not live.
    private volatile boolean ssCacheLoaded = false;
    private volatile String lastIp = "—";
    private volatile String lastError = "";
    private volatile boolean connecting = false;
    private volatile long connectingSince = 0;
    private volatile String currentRegionCode = null;
    private volatile String currentDisplay = null;
    private volatile int optimizeCount = 0;
    private volatile long lastOptimizeAt = 0;
    private volatile boolean watchdogStop = false;
    private volatile Thread watchdogThread = null;

    // sb14: connect epoch — every user-initiated connect,
    // disconnect, or cancel bumps it. A connect flow captures
    // the epoch at start and aborts SILENTLY (no error shown
    // — the new attempt owns the UI) as soon as it changes,
    // so tapping a different server mid-connect cancels the
    // in-flight attempt and the new one wins. EPOCH_NONE means
    // "never abort" (the launch ping test).
    private volatile long connectEpoch = 0;
    private static final long EPOCH_NONE = -1;
    private boolean epochAlive(long epoch) {
        return epoch == EPOCH_NONE || epoch == connectEpoch;
    }

    // sb9: sing-box's own ping (Clash API delay for the active
    // outbound), polled while connected; -1 = not measured yet.
    private volatile long lastSbPingMs = -1;
    private volatile Thread sbPingThread = null;

    // sb15: Fastest periodic re-test + auto-switch. While
    // connected in VPN mode on Fastest, re-test all regions every
    // FASTEST_RETEST_INTERVAL_MS and switch to a different server
    // ONLY when it is clearly better (at least
    // FASTEST_SWITCH_MIN_GAIN_MS faster AND at least 25% lower
    // ping — both conditions, so noise never flaps the server).
    private static final long FASTEST_RETEST_INTERVAL_MS = 5L * 60 * 1000;
    private static final long FASTEST_SWITCH_MIN_GAIN_MS = 30;
    private volatile Thread fastestRetestThread = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // FIRST: install the crash recorder before anything else
        // can throw, so a launch crash leaves a readable crash.log.
        try { CrashLog.install(this); } catch (Throwable ignored) {}
        // sb7: silently mirror any recorded crash.log from the
        // previous run to the external files dir (Files app can
        // open it) — NO on-screen display of any kind.
        try { CrashLog.mirrorToExternal(this); } catch (Throwable ignored) {}
        // 2.7 fix: restore the emergency toggle — every fetch
        // (including the refresh on open) must follow it.
        try { SubscriptionApi.setEmergencyMode(AppSettings.isEmergencyMode(this)); }
        catch (Throwable ignored) {}
        // 3.1: Firefox mode takes priority over the emergency
        // toggle — the two can never both be on. If the saved
        // settings somehow have both, Firefox wins and the
        // emergency toggle is turned off.
        try {
            if (AppSettings.isFirefoxMode(this) && AppSettings.isEmergencyMode(this)) {
                AppSettings.setEmergencyMode(this, false);
                SubscriptionApi.setEmergencyMode(false);
            }
        } catch (Throwable ignored) {}
        // 2.7 fix2: restore the learned IP->country map before
        // any fetch runs, so flag-less backup servers get their
        // country from the first list shown.
        try { SubscriptionApi.loadIpCc(AppSettings.getIpCc(this)); }
        catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null) return false;
                String str = u.toString();
                if (str.startsWith("tg://") || str.contains("t.me/")) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, u);
                        startActivity(i);
                    } catch (Throwable e) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://t.me/ProxyArc")));
                        } catch (Throwable ignored) {}
                    }
                    return true;
                }
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // sb5: NO on-screen report banners anymore — the user
                // explicitly does not want any report shown in the
                // app. CrashLog and ConnectLog still write their
                // files silently (see CrashLog.install / ConnectLog),
                // but nothing is ever injected into the page.
            }
        });
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
        // 3.1 fix3: a widget / tile tap opens the app to toggle
        // the connection (see handleAutoToggleIntent). The
        // connect itself runs in Java and does not need the
        // page to be loaded — the UI catches up through its
        // 1 s poll once the page is up.
        handleAutoToggleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutoToggleIntent(intent);
    }

    /** 3.1 fix3: handle a widget / Quick Settings tile tap.
     *  The decision (WidgetConnect) is: disconnect when a
     *  tunnel is up; otherwise connect to the last server
     *  (or Fastest); open the Firefox login dialog when the
     *  last server is a Firefox server and the user is
     *  logged out. Every path reuses the existing Bridge
     *  connect/disconnect/login code — including the VPN
     *  permission flow — so the tap behaves exactly like
     *  the same tap inside the app. A failure shows the
     *  same short generic message as an in-app failure, via
     *  the normal status path; nothing extra is shown. */
    private void handleAutoToggleIntent(Intent intent) {
        try {
            if (intent == null || !intent.getBooleanExtra(EXTRA_AUTO_TOGGLE, false)) return;
            WidgetConnect.Decision d = WidgetConnect.decide(
                    VpnTunnelService.running,
                    AppSettings.isFirefoxMode(this),
                    FirefoxAuth.isLoggedIn(this),
                    AppSettings.getLastSelection(this));
            Bridge b = new Bridge();
            switch (d.action) {
                case WidgetConnect.ACTION_DISCONNECT:
                    b.disconnect();
                    break;
                case WidgetConnect.ACTION_OPEN_LOGIN:
                    b.openFirefoxLogin("login");
                    break;
                case WidgetConnect.ACTION_CONNECT_SERVER: {
                    String host = WidgetConnect.hostOf(d.server);
                    // Resolve the protocol from the cached list
                    // when known; null on a cold start matches
                    // by address alone (the connect re-fetches
                    // the list) — the server's own protocol
                    // drives the Firefox-token logic.
                    String proto = null;
                    try {
                        if (subCache != null) {
                            for (Server a : subCache) {
                                if (a != null && host.equals(a.ip)) { proto = a.proto; break; }
                            }
                        }
                    } catch (Throwable ignored) {}
                    b.connectServer(host, "", proto);
                    break;
                }
                case WidgetConnect.ACTION_CONNECT_FASTEST:
                default:
                    b.connectSubFastest();
                    break;
            }
        } catch (Throwable ignored) {}
    }

    // sb12: Back must close the topmost overlay (app list,
    // then the panel) instead of exiting the app. The JS side
    // reports the overlay depth through Bridge.setOverlayDepth
    // whenever the panel or the app list opens or closes; a
    // depth > 0 means Back closes the topmost overlay only.
    private volatile int overlayDepth = 0;

    @Override
    public void onBackPressed() {
        if (overlayDepth > 0) {
            eval("window.__closeTopOverlay && window.__closeTopOverlay()");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    private void eval(String js) {
        if (webView == null) return;
        runOnUiThread(() -> {
            try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        });
    }

    // sb5: the red report banner is GONE at the user's explicit
    // request — no crash log, no connect log is ever shown on
    // screen. The showCrashLogIfAny/showConnectLogIfAny methods
    // were deleted entirely. CrashLog and ConnectLog still write
    // their files silently for later diagnosis; nothing here
    // surfaces them in the UI or in the status message.

    private static String jsString(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    /** Android System WebView major version (0 = unknown).
     *  WebView.getCurrentWebViewPackage() (API 26+) first; the
     *  WebView user-agent ("Chrome/<major>") as a fallback.
     *  Used by the outdated-WebView prompt in the UI (2.7 fix3). */
    private int webViewMajorVersion() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi =
                        android.webkit.WebView.getCurrentWebViewPackage();
                if (pi != null && pi.versionName != null) {
                    String v = pi.versionName.trim();
                    int dot = v.indexOf('.');
                    return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
                }
            }
        } catch (Throwable ignored) {}
        try {
            String ua = android.webkit.WebSettings.getDefaultUserAgent(this);
            return parseChromeMajor(ua);
        } catch (Throwable ignored) {}
        return 0;
    }

    /** Chrome major version from a WebView user-agent string
     *  ("...Chrome/74.0.3729.185..."), or 0 when it cannot be
     *  determined. Pure string logic, unit-testable. */
    static int parseChromeMajor(String ua) {
        try {
            if (ua == null) return 0;
            int i = ua.indexOf("Chrome/");
            if (i < 0) return 0;
            int s = i + 7, e = s;
            while (e < ua.length() && Character.isDigit(ua.charAt(e))) e++;
            if (e <= s) return 0;
            return Integer.parseInt(ua.substring(s, e));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** True when a WebView major version is too old for this
     *  UI (below WEBVIEW_MIN_CHROME in index.html: the oldest
     *  Chromium the UI needs after the old-engine cleanup is
     *  59 — grid place-items — so 60 is the line). 0 (unknown)
     *  never prompts — the app renders best effort instead. */
    static boolean needsWebViewUpdate(int major) {
        return major > 0 && major < 60;
    }

    /** 2.7 fix2: the server-list payload sent to the WebView —
     *  {"servers":[{ip,flag,cc,port,proto,name}], "src":
     *  "primary"|"backup"} — so the UI can show which
     *  subscription the displayed list came from. Passwords /
     *  uuids are never in the payload. */
    private JSONObject serversPayload(List<Server> list) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        for (Server a : list) {
            JSONObject o = new JSONObject();
            o.put("ip", a.ip);
            o.put("flag", a.flag == null ? "" : a.flag);
            o.put("cc", a.countryCode == null ? "" : a.countryCode);
            o.put("port", a.port);
            o.put("proto", a.proto == null ? "ss" : a.proto);
            o.put("name", a.name == null ? "" : a.name);
            arr.put(o);
        }
        out.put("servers", arr);
        // 3.1: the Firefox list reports its own source, so
        // the UI pill shows "Firefox" instead of the stale
        // subscription source.
        out.put("src", AppSettings.isFirefoxMode(MainActivity.this)
                ? "firefox" : SubscriptionApi.getLastSource());
        return out;
    }

    /** 2.7 fix2: persist the learned IP->country map after a
     *  successful fetch, so it survives restarts. */
    private void persistIpCc() {
        try {
            String j = SubscriptionApi.ipCcJson();
            if (j != null && !j.isEmpty()) AppSettings.saveIpCc(MainActivity.this, j);
        } catch (Throwable ignored) {}
    }

    public class Bridge {
        /** SS-only refresh — the single entry point for
         *  app open and the refresh button. Every refresh tries
         *  the PRIMARY subscription first; SubscriptionApi already
         *  falls back to the BACKUP (emergency) subscription
         *  inside fetchServers when the primary fails (network
         *  / HTTP / empty / zero parseable servers), and the
         *  next refresh tries the primary first again. On
         *  success the raw subscription is saved to the
         *  offline cache and the SS list is shown. When BOTH
         *  subscriptions yield 0 usable servers, the WebView
         *  KEEPS the list it already has (the cached list shown
         *  on open) — the dropdown is never emptied by a
         *  failed refresh. The same {ip, flag, port, proto,
         *  name} payload as refreshSub, never the password. */
        /** Android System WebView major version (0 = unknown),
         *  for the JS outdated-WebView prompt. */
        @JavascriptInterface
        public int getWebViewVersion() {
            try { return webViewMajorVersion(); } catch (Throwable ignored) { return 0; }
        }

        /** 3.1 fix3: the Android API level, so the JS shows
         *  the "Add to Quick Settings" button only where the
         *  system add-tile flow exists (API 33+). */
        @JavascriptInterface
        public int getApiLevel() {
            try { return android.os.Build.VERSION.SDK_INT; }
            catch (Throwable ignored) { return 0; }
        }

        /** 3.1 fix3: ask the system to add the ArcVeePN
         *  Quick Settings tile (Android 13+ only — the JS
         *  hides the button below API 33). The system shows
         *  its own add-tile dialog; the result needs no
         *  on-screen reporting. On older Android the user
         *  adds the tile by hand from the QS editor. */
        @JavascriptInterface
        public void requestAddTile() {
            try {
                if (android.os.Build.VERSION.SDK_INT < 33) return;
                android.app.StatusBarManager sbm = (android.app.StatusBarManager)
                        getSystemService(android.content.Context.STATUS_BAR_SERVICE);
                if (sbm == null) return;
                sbm.requestAddTileService(
                        new android.content.ComponentName(MainActivity.this,
                                ArcVeePNTileService.class),
                        "ArcVeePN",
                        android.graphics.drawable.Icon.createWithResource(
                                MainActivity.this, R.drawable.ic_stat_arc),
                        getMainExecutor(),
                        result -> {
                            // The system dialog handles the
                            // outcome; nothing to report.
                        });
            } catch (Throwable ignored) {}
        }

        /** Open the Play Store page for Android System WebView
         *  (market:// first, https:// fallback). Used by the
         *  outdated-WebView prompt in the UI. */
        @JavascriptInterface
        public void openWebViewUpdate() {
            try {
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=com.google.android.webview"));
                        startActivity(i);
                    } catch (Throwable e1) {
                        try {
                            Intent i2 = new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview"));
                            startActivity(i2);
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
        }

        /** Open a URL outside the app (browser / Telegram).
         *  The new UI's "Project on GitHub" button and the
         *  Telegram join link use this — the WebViewClient
         *  only intercepts tg:// and t.me/ URLs, so a plain
         *  https:// link would otherwise load INSIDE the
         *  WebView and navigate away from the app UI. */
        @JavascriptInterface
        public void openExternal(final String url) {
            try {
                if (url == null) return;
                String u = url.trim();
                if (!u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("tg://")) return;
                final android.net.Uri uri = android.net.Uri.parse(u);
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void refreshSubForce() {
            // 3.1: Firefox mode uses ONLY the Firefox servers —
            // the refresh must never touch the subscriptions.
            if (AppSettings.isFirefoxMode(MainActivity.this)) {
                refreshFirefoxServers();
                return;
            }
            new Thread(() -> {
                try {
                    try {
                        SubscriptionApi.refresh();
                        List<Server> list = new SubscriptionApi().fetchServers();
                        subCache = list;
                        ssCacheLoaded = false;
                        // v8: persist the raw subscription for
                        // the offline cache (raw text only).
                        try {
                            String cacheJson = SubscriptionApi.lastFetchForCache();
                            if (cacheJson != null)
                                AppSettings.saveCachedSs(MainActivity.this, cacheJson);
                        } catch (Throwable ignored) {}
                        // 2.7 fix2: persist the learned IP->cc
                        // map after a successful fetch.
                        persistIpCc();
                        eval("window.onServers(" + jsString(serversPayload(list).toString()) + ")");
                        // 3.1 fix5: push the new source's
                        // servers to the widget (header + list)
                        // — fix4 never did this, so after
                        // switching sources the widget kept
                        // showing the previous source's servers
                        // until the next connect/disconnect.
                        pushWidgetAfterFetch();
                    } catch (Throwable e) {
                        // The primary yielded nothing (2.7 fix3:
                        // no fallback to the emergency list) —
                        // keep the cached list on screen.
                        try { ConnectLog.log(MainActivity.this, "servers failed: primary (" + SubscriptionApi.getLastPrimaryError() + ")"); } catch (Throwable ignored) {}
                        eval("window.onServersError("
                                + jsString("Could not load servers.") + ")");
                    }
                } catch (Throwable ignored) {}
            }).start();
        }
        /** 3.1: the Firefox refresh — fresh Remote Settings
         *  list when possible, the cached list when the fetch
         *  fails, the short generic error when there is
         *  nothing at all. Same payload shape as the
         *  subscription refresh, src "firefox". */
        private void refreshFirefoxServers() {
            new Thread(() -> {
                try {
                    try {
                        List<Server> list =
                                FirefoxAuth.getServers(MainActivity.this, true);
                        subCache = list;
                        ssCacheLoaded = false;
                        eval("window.onServers(" + jsString(serversPayload(list).toString()) + ")");
                        // 3.1 fix5: push the Firefox servers to
                        // the widget (header + list) — see
                        // pushWidgetAfterFetch.
                        pushWidgetAfterFetch();
                    } catch (Throwable e) {
                        try { ConnectLog.log(MainActivity.this, "servers failed: firefox list"); } catch (Throwable ignored) {}
                        eval("window.onServersError("
                                + jsString("Could not load servers.") + ")");
                    }
                } catch (Throwable ignored) {}
            }).start();
        }
        /** 3.1 fix5: push the current source's servers to
         *  the home-screen widget (header + list) after a
         *  successful fetch. fix4 never did this — the
         *  widget only refreshed on connect / disconnect —
         *  so after switching sources the widget kept showing
         *  the previous source's servers until the next
         *  connect. Safe from the fetch thread:
         *  AppWidgetManager calls are thread-safe. */
        private void pushWidgetAfterFetch() {
            try { ArcVeePNWidget.updateAll(MainActivity.this); } catch (Throwable ignored) {}
            try { ArcVeePNWidget.notifyDataChanged(MainActivity.this); } catch (Throwable ignored) {}
        }
        /** Manual emergency load (2.5 rebuild): fetch ONLY
         *  the backup subscription (never the primary) and
         *  show those servers. Called by the hamburger-menu
         *  emergency button. The raw backup is persisted to
         *  the offline cache like a normal fetch, so the
         *  emergency servers survive an app restart. */
        @JavascriptInterface
        public void loadBackupServers() {
            new Thread(() -> {
                try {
                    try {
                        List<Server> list = new SubscriptionApi().fetchBackupServers();
                        subCache = list;
                        ssCacheLoaded = false;
                        // Persist the raw backup for the
                        // offline cache (raw text only).
                        try {
                            String cacheJson = SubscriptionApi.lastFetchForCache();
                            if (cacheJson != null)
                                AppSettings.saveCachedSs(MainActivity.this, cacheJson);
                        } catch (Throwable ignored) {}
                        // 2.7 fix2: persist the learned IP->cc map.
                        persistIpCc();
                        eval("window.onServers(" + jsString(serversPayload(list).toString()) + ")");
                        // 3.1 fix5: push the emergency servers
                        // to the widget (header + list) — see
                        // pushWidgetAfterFetch.
                        pushWidgetAfterFetch();
                    } catch (Throwable e) {
                        // The backup yielded nothing — keep
                        // the cached list on screen.
                        eval("window.onServersError("
                                + jsString("Could not load servers.") + ")");
                    }
                } catch (Throwable ignored) {}
            }).start();
        }
        /** 2.7 fix: the emergency TOGGLE (hamburger menu).
         *  ON = persist the mode, point every fetch at the
         *  BACKUP subscription, and load it NOW (the list is
         *  replaced with the emergency servers immediately);
         *  OFF = persist off, point fetches back at the
         *  primary, and reload the primary. A failed backup
         *  fetch keeps the cached list on screen and reports
         *  the short generic "Could not load servers." — the
         *  JS reverts the toggle when it hears that error. */
        @JavascriptInterface
        public void setEmergencyMode(final boolean on) {
            try {
                // 3.1: mutual exclusion — turning the emergency
                // toggle ON turns the Firefox toggle OFF (the
                // Firefox list must never mix with the backup).
                if (on && AppSettings.isFirefoxMode(MainActivity.this)) {
                    AppSettings.setFirefoxMode(MainActivity.this, false);
                }
                AppSettings.setEmergencyMode(MainActivity.this, on);
                SubscriptionApi.setEmergencyMode(on);
                // A mode switch must bypass the 30-min
                // in-memory cache, or the toggle would keep
                // showing the old list until the cache aged
                // out.
                SubscriptionApi.refresh();
                if (on) {
                    loadBackupServers();
                } else {
                    refreshSubForce();
                }
            } catch (Throwable ignored) {}
        }
        /** 3.1: the Firefox TOGGLE (hamburger menu). ON =
         *  persist the mode, turn the emergency toggle OFF,
         *  and load the Firefox list NOW (the list is replaced
         *  with the Firefox servers immediately); OFF =
         *  persist off and reload the primary subscription.
         *  The JS only calls this when the user is signed in
         *  (otherwise it opens the sign-in dialog first). A
         *  failed list fetch keeps the cached list on screen
         *  and reports the short generic "Could not load
         *  servers." — the JS reverts the toggle then. */
        @JavascriptInterface
        public void setFirefoxMode(final boolean on) {
            try {
                AppSettings.setFirefoxMode(MainActivity.this, on);
                if (on) {
                    AppSettings.setEmergencyMode(MainActivity.this, false);
                    SubscriptionApi.setEmergencyMode(false);
                    SubscriptionApi.refresh();
                    refreshFirefoxServers();
                    refreshFirefoxQuota();
                } else {
                    SubscriptionApi.refresh();
                    refreshSubForce();
                }
            } catch (Throwable ignored) {}
        }
        /** 3.1: open the Firefox sign-in dialog (Mozilla's own
         *  page in a WebView). action is "login" or "signup".
         *  The result comes back as window.onFirefoxLogin('ok'
         *  | 'cancelled' | 'failed') — the JS turns the toggle
         *  on only on 'ok'. */
        @JavascriptInterface
        public void openFirefoxLogin(final String action) {
            try {
                FirefoxLoginDialog.show(MainActivity.this,
                        "signup".equals(action) ? "signup" : "login",
                        status -> eval("window.onFirefoxLogin("
                                + jsString(status) + ")"));
            } catch (Throwable ignored) {}
        }
        /** 3.1: refresh the cached quota line in the
         *  background (HEAD, no new token) and push it to the
         *  UI as window.onFirefoxQuota({text}). A failure
         *  leaves the old line in place, silently. */
        @JavascriptInterface
        public void refreshFirefoxQuota() {
            new Thread(() -> {
                try {
                    FirefoxAuth.refreshQuotaCached(MainActivity.this);
                    String line = FirefoxAuth.getQuotaLine(MainActivity.this);
                    JSONObject o = new JSONObject();
                    o.put("text", line);
                    eval("window.onFirefoxQuota(" + jsString(o.toString()) + ")");
                } catch (Throwable ignored) {}
            }).start();
        }
        /** 3.1 fix: LOG OUT of the Firefox account. Clears
         *  the stored tokens (OAuth access/refresh + ProxyPass
         *  + expiries + email/quota), turns the Firefox toggle
         *  OFF, and falls back to the GitHub primary — the
         *  list reload shows Main servers only, never Firefox
         *  ones (the cached Firefox list is only served while
         *  Firefox mode is on). The JS disconnects first when
         *  connected. Shows only the short generic sign-out
         *  state via window.onFirefoxLogout(); no token is
         *  ever shown or logged. */
        @JavascriptInterface
        public void logoutFirefox() {
            try {
                FirefoxAuth.clearAll(MainActivity.this);
                AppSettings.setFirefoxMode(MainActivity.this, false);
                VpnTunnelService.firefoxProxyPass = null;
                SubscriptionApi.refresh();
                refreshSubForce();
                eval("window.onFirefoxLogout()");
            } catch (Throwable ignored) {}
        }
        /** 3.1: the stored login died (refresh rejected) —
         *  clear the tokens, turn Firefox mode off, reload the
         *  primary, and tell the UI to show the sign-in state
         *  again. Called from the connect path, never from the
         *  UI directly. */
        private void firefoxAuthLost() {
            try {
                FirefoxAuth.clearAll(MainActivity.this);
                AppSettings.setFirefoxMode(MainActivity.this, false);
                VpnTunnelService.firefoxProxyPass = null;
                SubscriptionApi.refresh();
                refreshSubForce();
                eval("window.onFirefoxAuthLost()");
            } catch (Throwable ignored) {}
        }
        /** The CACHED Shadowsocks list, shown IMMEDIATELY
         *  on app open while the fresh fetch runs in the
         *  background. Returns {"cached":true|false,
         *  "servers":[{ip,flag,port,proto,name}]} — the same
         *  payload shape as onServers. "cached" is false
         *  (and servers empty) when the app has never fetched
         *  successfully. The SS cache is re-parsed exactly
         *  like a fresh fetch. */
        @JavascriptInterface
        public String getCachedServers() {
            try {
                // 3.1: Firefox mode serves the cached Firefox
                // list (src "firefox") — never the subscription
                // cache, which belongs to another source.
                if (AppSettings.isFirefoxMode(MainActivity.this)) {
                    List<Server> fx = FirefoxAuth.cachedServerList(MainActivity.this);
                    JSONObject out = new JSONObject();
                    out.put("cached", !fx.isEmpty());
                    out.put("src", fx.isEmpty() ? "" : "firefox");
                    JSONArray arr = new JSONArray();
                    for (Server a : fx) {
                        JSONObject o = new JSONObject();
                        o.put("ip", a.ip);
                        o.put("flag", a.flag == null ? "" : a.flag);
                        o.put("cc", a.countryCode == null ? "" : a.countryCode);
                        o.put("port", a.port);
                        o.put("proto", a.proto == null ? "ss" : a.proto);
                        o.put("name", a.name == null ? "" : a.name);
                        arr.put(o);
                    }
                    out.put("servers", arr);
                    if (!fx.isEmpty()) {
                        subCache = fx;
                        ssCacheLoaded = true;
                    }
                    return out.toString();
                }
                JSONObject out = new JSONObject();
                List<Server> list = loadSsCache();
                // 2.7 fix3: the toggle alone decides the source.
                // A cached emergency list must never be shown
                // while the toggle is OFF — it is stale data
                // from an earlier emergency session, and showing
                // it would auto-select the emergency source.
                // The fresh primary refresh running on boot
                // replaces it with the primary list.
                if (!list.isEmpty() && !SubscriptionApi.isEmergencyMode()
                        && "backup".equals(SubscriptionApi.getLastSource())) {
                    list = new ArrayList<>();
                }
                out.put("cached", !list.isEmpty());
                // 2.7 fix2: which subscription the cached list
                // came from (set by parseCacheJson from the
                // cache kind), so the UI can show the source.
                out.put("src", list.isEmpty() ? "" : SubscriptionApi.getLastSource());
                JSONArray arr = new JSONArray();
                for (Server a : list) {
                    JSONObject o = new JSONObject();
                    o.put("ip", a.ip);
                    o.put("flag", a.flag == null ? "" : a.flag);
                    o.put("cc", a.countryCode == null ? "" : a.countryCode);
                    o.put("port", a.port);
                    o.put("proto", a.proto == null ? "ss" : a.proto);
                    o.put("name", a.name == null ? "" : a.name);
                    arr.put(o);
                }
                out.put("servers", arr);
                if (!list.isEmpty()) {
                    subCache = list;
                    ssCacheLoaded = true;
                    // 2.7 fix2: the cache re-parse may have
                    // taught the IP->cc map — persist it.
                    persistIpCc();
                }
                return out.toString();
            } catch (Throwable ignored) {
                return "{\"cached\":false,\"servers\":[]}";
            }
        }

        /** Sub v2: start a TCP-connect ping sweep over the
         *  Sub servers. Results stream to the WebView live
         *  (onSubPing per server, onSubPingDone at the
         *  end); a sweep already running is not started twice. */
        @JavascriptInterface
        public void pingSub() {
            startSubPingSweep();
        }

        /** Connect to one subscription server by IP + protocol
         *  ("ss" primary, "vmess" backup). Mirrors connect():
         *  proxy mode needs no VPN permission, otherwise the
         *  VpnService.prepare flow with pendingSubIp/
         *  pendingSubFlag/pendingSubProto (handled in
         *  onActivityResult, like pendingRegion/pendingDisplay).
         *  The actual connect runs on a background thread in
         *  doConnectSub. */
        @JavascriptInterface
        public void connectServer(final String ip, final String flag, final String proto) {
            runOnUiThread(() -> {
                try {
                    try { ConnectLog.clear(MainActivity.this); } catch (Throwable ignored) {}
                    if (AppSettings.isProxyMode(MainActivity.this)) {
                        doConnectSub(ip, flag, proto);
                        return;
                    }
                    Intent prep = VpnService.prepare(MainActivity.this);
                    if (prep != null) {
                        pendingSubIp = ip;
                        pendingSubFlag = flag;
                        pendingSubProto = proto;
                        startActivityForResult(prep, REQ_VPN);
                    } else {
                        doConnectSub(ip, flag, proto);
                    }
                } catch (Throwable t) {
                    lastError = "Connection failed.";
                    connecting = false;
                    eval("window.onConnectError(" + jsString(lastError) + ")");
                }
            });
        }

        /** Connect to one Shadowsocks server by IP (primary).
         *  Kept for compatibility; delegates to connectServer. */
        @JavascriptInterface
        public void connectSub(final String ip, final String flag) {
            connectServer(ip, flag, "ss");
        }

        /** Sub v2: Fastest mode — ping-test all servers and
         *  switch live to the best found so far. Same VPN
         *  permission flow as connectSub (pendingSubFastest
         *  is resumed in onActivityResult). */
        @JavascriptInterface
        public void connectSubFastest() {
            runOnUiThread(() -> {
                try {
                    try { ConnectLog.clear(MainActivity.this); } catch (Throwable ignored) {}
                    if (AppSettings.isProxyMode(MainActivity.this)) {
                        doConnectSubFastest();
                        return;
                    }
                    Intent prep = VpnService.prepare(MainActivity.this);
                    if (prep != null) {
                        pendingSubFastest = true;
                        startActivityForResult(prep, REQ_VPN);
                    } else {
                        doConnectSubFastest();
                    }
                } catch (Throwable t) {
                    lastError = "Connection failed.";
                    connecting = false;
                    eval("window.onConnectError(" + jsString(lastError) + ")");
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            // sb14: bump the epoch so an in-flight connect
            // aborts silently when the user taps Disconnect.
            connectEpoch++;
            connecting = false;
            lastError = "";
            watchdogStop = true;
            // Sub v2: leaving Fastest mode + forget the
            // connected Sub server (bumping the fastest
            // epoch stops any sweep from switching afterwards).
            subFastest = false;
            subFastestEpoch++;
            subConnectedIp = null;
            subConnectedMs = Long.MAX_VALUE;
            runOnUiThread(() -> {
                // sb15 HARDENING: startService is guarded — an
                // IllegalStateException here (app backgrounded)
                // would otherwise be uncaught on the main thread.
                try {
                    Intent i = new Intent(MainActivity.this, VpnTunnelService.class);
                    i.setAction(VpnTunnelService.ACTION_DISCONNECT);
                    startService(i);
                } catch (Throwable ignored) {}
                lastIp = "—";
            });
        }

        /** sb14: abort the in-flight connect attempt — the
         *  epoch bump makes every phase check fail — and stop
         *  a service that may already be starting. The caller
         *  (tapping a different server mid-connect) starts a
         *  new connect right after this. */
        @JavascriptInterface
        public void cancelConnect() {
            connectEpoch++;
            connecting = false;
            try {
                Intent d = new Intent(MainActivity.this, VpnTunnelService.class);
                d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                startService(d);
            } catch (Throwable ignored) {}
        }
        @JavascriptInterface
        public void optimize() {
            // SS-only: a Shadowsocks tunnel is never
            // "optimized" — the old v1.7 region re-test only
            // made sense for the removed HTTP Proxy protocol.
            // The watchdog still calls this; it is a no-op.
        }

        /** sb15: called from the JS 1 s poll while connected.
         *  Consumes a debounced underlying-network change (see
         *  VpnTunnelService.consumeNetChangeIfDue) and
         *  re-establishes the tunnel. Guards: connected only,
         *  never while already connecting. last_selection is
         *  untouched.
         *  v1.9-proxyfix: proxy mode is included now. It used to
         *  return early ("the proxy is per-connection") — but the
         *  proxy's outbound sockets and DNS state are bound to the
         *  network the service started on, so when another VPN
         *  (Nord) comes up or goes down the running proxy keeps
         *  its dead state forever. Re-establishing (full stop +
         *  fresh start, same server / Fastest re-test) is what
         *  revives it — exactly like VPN mode. */
        @JavascriptInterface
        public void pollNetworkChange() {
            try {
                if (!VpnTunnelService.running) return;
                if (connecting) return;
                // 3.1 fix: the Firefox renewal loop flags a
                // terminal refresh failure here (dead login /
                // spent quota) — run the existing generic
                // auth-lost path once, then continue. This is
                // the only place the service's flag is consumed.
                if (VpnTunnelService.consumeFirefoxAuthLostIfDue()) {
                    new Bridge().firefoxAuthLost();
                    return;
                }
                if (!VpnTunnelService.consumeNetChangeIfDue()) return;
                ConnectLog.log(MainActivity.this,
                        "netchange: reconnecting after network change");
                reconnectAfterNetworkChange();
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void checkExitIp() {
            new Thread(() -> {
                try {
                    String ip;
                    if (VpnTunnelService.running) {
                        Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                                new InetSocketAddress("127.0.0.1", SOCKS_PORT));
                        HttpURLConnection c = (HttpURLConnection)
                                new URL("https://api.ipify.org?format=json").openConnection(proxy);
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        ip = readIp(c);
                    } else {
                        HttpURLConnection c = (HttpURLConnection)
                                new URL("https://api.ipify.org?format=json").openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        ip = readIp(c);
                    }
                    lastIp = ip;
                    eval("window.onExitIp(" + jsString(ip) + ")");
                } catch (Throwable e) {
                    // Keep the previous IP; the UI poll shows the state.
                }
            }).start();
        }

        /** 3.1 fix7: the exit-IP box's server identity, as a pure
         *  function (unit-testable). subConnectedIp is set only
         *  by the IN-APP connect flow (startServiceWithServer);
         *  a widget/tile-started connection never sets it, so
         *  getStatusJson used to report subIp="" and the JS
         *  paintConn() — which shows the IP only when it can
         *  find the connected server in the list — showed
         *  nothing, even with a live tunnel and a known exit
         *  IP. Fall back to the service's running server,
         *  which the widget path DOES set (DirectConnect sets
         *  currentServer before starting the service; the
         *  service copies it to runningServer). */
        static String resolveSubIpForStatus(String subConnectedIp,
                Server runningServer) {
            if (subConnectedIp != null && !subConnectedIp.isEmpty()) {
                return subConnectedIp;
            }
            if (runningServer != null && runningServer.ip != null
                    && !runningServer.ip.isEmpty()) {
                return runningServer.ip;
            }
            return "";
        }

        @JavascriptInterface
        public String getStatusJson() {
            try {
                JSONObject o = new JSONObject();
                boolean connected = VpnTunnelService.running;
                if (connected) connecting = false;
                // Sub v2: while a Fastest sweep is still
                // testing, never auto-fail the connect — the
                // sweep owns the outcome and reports it
                // (onConnectError) when it ends.
                boolean subSweeping = subPingThread != null && subPingThread.isAlive();
                if (connecting && !subSweeping && System.currentTimeMillis() - connectingSince > 20000 && !connected) {
                    connecting = false;
                    if (lastError == null || lastError.isEmpty()) lastError = "Connection failed.";
                }
                o.put("connected", connected);
                String state;
                if (connected) state = "connected";
                else if (connecting) state = "connecting";
                else if (lastError != null && !lastError.isEmpty()) state = "error";
                else state = "disconnected";
                o.put("state", state);
                o.put("message", lastError == null ? "" : lastError);
                o.put("region", VpnTunnelService.currentRegion);
                o.put("regionCode", currentRegionCode == null ? "" : currentRegionCode);
                o.put("connectedAt", VpnTunnelService.connectedAtMs);
                long[] st = VpnTunnelService.tunnelStats();
                long tx = st.length > 1 ? st[1] : 0;
                long rx = st.length > 3 ? st[3] : 0;
                o.put("txBytes", tx);
                o.put("rxBytes", rx);
                // sb9: statsLive = the sing-box status stream has
                // actually delivered a non-zero byte total (the JS
                // stall watchdog must only fire on real stats);
                // sbPingMs = the live ping of the connected server,
                // -1 if not measured yet.
                o.put("statsLive", VpnTunnelService.statsEverNonZero);
                o.put("sbPingMs", lastSbPingMs);
                // sb10: current mode + local proxy ports, so the
                // side panel can reflect the persisted mode and
                // show the proxy address while proxy mode is on.
                // v1.9-ipfix: while connected the tag must show
                // the RUNNING service's mode, not the persisted
                // setting — the two can diverge (mode flipped
                // without the service restarting), and the UI
                // then claimed "Proxy" while a VPN tunnel was
                // up (or vice versa). Disconnected, the
                // persisted mode is the selected one.
                o.put("mode", connected
                        ? (VpnTunnelService.proxyMode ? "proxy" : "vpn")
                        : AppSettings.getMode(MainActivity.this));
                o.put("socksPort", SOCKS_PORT);
                o.put("httpPort", HTTP_PORT);
                o.put("ip", lastIp);
                // Sub v2: connected Sub server IP ("" when
                // none), Fastest live-switch state, and whether
                // a ping sweep is still testing.
                o.put("subIp", resolveSubIpForStatus(subConnectedIp,
                        VpnTunnelService.runningServer));
                o.put("subFastest", subFastest);
                o.put("subTesting", subSweeping);
                o.put("ssCached", ssCacheLoaded);
                return o.toString();
            } catch (Throwable e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public long singBoxPingMs() {
            return lastSbPingMs;
        }

        // ---------- sb10: mode + split tunneling (side panel) ----------

        @JavascriptInterface
        public String getMode() {
            try { return AppSettings.getMode(MainActivity.this); }
            catch (Throwable e) { return "vpn"; }
        }

        @JavascriptInterface
        public void setMode(final String mode) {
            try {
                // sb14: a mode switch aborts any in-flight
                // connect — the service is disconnected below
                // anyway, and the old attempt must not come back
                // and claim the tunnel afterwards.
                connectEpoch++;
                AppSettings.setMode(MainActivity.this, mode);
                // sb13: mutual exclusivity, enforced at the
                // choke point — turning proxy mode ON also
                // turns split tunneling OFF in the persisted
                // settings, so no code path can ever leave
                // both enabled at once.
                if (AppSettings.isProxyMode(MainActivity.this)) {
                    AppSettings.setSplitMode(MainActivity.this, AppSettings.SPLIT_OFF);
                }
                // Belt and braces: the UI disconnects first on a
                // mode switch, but never let a live connection
                // survive one from any path.
                if (VpnTunnelService.running) {
                    try {
                        Intent d = new Intent(MainActivity.this, VpnTunnelService.class);
                        d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                        startService(d);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        // ---------- sb12: overlay depth for the Back button ----------

        /** JS reports how many overlays are open (panel = 1,
         *  app list on top = 2) so onBackPressed can close the
         *  topmost one instead of exiting the app. */
        @JavascriptInterface
        public void setOverlayDepth(final int depth) {
            overlayDepth = Math.max(0, depth);
        }

        /** All settings the side panel needs on load, in one call. */
        @JavascriptInterface
        public String getSettings() {
            try {
                JSONObject o = new JSONObject();
                o.put("mode", AppSettings.getMode(MainActivity.this));
                o.put("splitMode", AppSettings.getSplitMode(MainActivity.this));
                o.put("splitLastMode", AppSettings.getSplitLastMode(MainActivity.this));
                // sb14: the last server the user picked, so the
                // app can reopen on it (first launch = Fastest).
                o.put("lastSelection", AppSettings.getLastSelection(MainActivity.this));
                // 2.7 fix: the emergency toggle state, so the
                // hamburger menu paints the switch correctly.
                o.put("emergencyMode", AppSettings.isEmergencyMode(MainActivity.this));
                // 3.1: the Firefox toggle + sign-in state, so
                // the hamburger menu paints the switch, the
                // account line and the quota line correctly.
                o.put("firefoxMode", AppSettings.isFirefoxMode(MainActivity.this));
                o.put("firefoxLoggedIn", FirefoxAuth.isLoggedIn(MainActivity.this));
                o.put("firefoxEmail", FirefoxAuth.getEmail(MainActivity.this));
                o.put("firefoxQuota", FirefoxAuth.getQuotaLine(MainActivity.this));
                JSONArray arr = new JSONArray();
                for (String p : AppSettings.getSplitPackages(MainActivity.this)) arr.put(p);
                o.put("splitPackages", arr);
                return o.toString();
            } catch (Throwable e) {
                return "{\"mode\":\"vpn\",\"splitMode\":\"off\",\"splitPackages\":[]}";
            }
        }

        /** sb14: remember the last server the user picked
         *  ("fastest" or a region code) so the app reopens on
         *  it. Called from JS on every selection change and
         *  from Java on every successful user-initiated
         *  connect. */
        @JavascriptInterface
        public void saveLastSelection(final String code) {
            try { AppSettings.setLastSelection(MainActivity.this, code); }
            catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public String getSplitTunnel() {
            try {
                JSONObject o = new JSONObject();
                o.put("mode", AppSettings.getSplitMode(MainActivity.this));
                JSONArray arr = new JSONArray();
                for (String p : AppSettings.getSplitPackages(MainActivity.this)) arr.put(p);
                o.put("packages", arr);
                return o.toString();
            } catch (Throwable e) {
                return "{\"mode\":\"off\",\"packages\":[]}";
            }
        }

        @JavascriptInterface
        public void saveSplitTunnel(final String mode, final String packagesJson) {
            try {
                AppSettings.setSplitMode(MainActivity.this, mode);
                // sb13: mutual exclusivity, enforced at the
                // choke point — saving a non-off split mode
                // also turns proxy mode OFF in the persisted
                // settings, so no code path can ever leave
                // both enabled at once.
                if (!AppSettings.SPLIT_OFF.equals(AppSettings.getSplitMode(MainActivity.this))) {
                    AppSettings.setMode(MainActivity.this, AppSettings.MODE_VPN);
                }
                java.util.Set<String> pkgs = new java.util.HashSet<>();
                JSONArray arr = new JSONArray(packagesJson);
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i, "");
                    if (p != null && !p.isEmpty()) pkgs.add(p);
                }
                AppSettings.setSplitPackages(MainActivity.this, pkgs);
            } catch (Throwable ignored) {}
        }

        /** Installed launchable apps, sorted by label:
         *  [{package, label, system}]. Our own package is left
         *  out — it is always kept off the TUN and can never be
         *  part of a split-tunnel set. */
        @JavascriptInterface
        public String getInstalledApps() {
            try {
                PackageManager pm = getPackageManager();
                String own = getPackageName();
                Intent main = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER);
                List<android.content.pm.ResolveInfo> list =
                        pm.queryIntentActivities(main, 0);
                java.util.Map<String, JSONObject> byPkg = new java.util.HashMap<>();
                for (android.content.pm.ResolveInfo ri : list) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.isEmpty() || pkg.equals(own)) continue;
                    if (byPkg.containsKey(pkg)) continue;
                    CharSequence label = null;
                    try { label = ri.loadLabel(pm); } catch (Throwable ignored) {}
                    String lab = (label != null && label.length() > 0)
                            ? label.toString() : pkg;
                    boolean system = false;
                    try {
                        android.content.pm.ApplicationInfo ai =
                                ri.activityInfo.applicationInfo;
                        system = ai != null && (ai.flags
                                & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                    } catch (Throwable ignored) {}
                    JSONObject o = new JSONObject();
                    o.put("package", pkg);
                    o.put("label", lab);
                    o.put("system", system);
                    byPkg.put(pkg, o);
                }
                List<JSONObject> out = new ArrayList<>(byPkg.values());
                out.sort((a, b) -> a.optString("label", "")
                        .compareToIgnoreCase(b.optString("label", "")));
                JSONArray arr = new JSONArray();
                for (JSONObject o : out) arr.put(o);
                return arr.toString();
            } catch (Throwable e) {
                return "[]";
            }
        }

        /** sb11: one app's real launcher icon as a Base64 PNG
         *  data URI (96x96 px — crisp at ~48dp), for the
         *  split-tunnel list. "" on any failure. Called lazily
         *  per row from the JS side, so the list never blocks
         *  on icon loading. */
        @JavascriptInterface
        public String getAppIcon(final String pkg) {
            try {
                PackageManager pm = getPackageManager();
                android.graphics.drawable.Drawable d = pm.getApplicationIcon(pkg);
                if (d == null) return "";
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        96, 96, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                d.setBounds(0, 0, 96, 96);
                d.draw(c);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos);
                try { bmp.recycle(); } catch (Throwable ignored) {}
                return "data:image/png;base64," + android.util.Base64.encodeToString(
                        baos.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Throwable e) {
                return "";
            }
        }

        @JavascriptInterface
        public String getCrashLog() {
            try { return CrashLog.read(MainActivity.this); } catch (Throwable ignored) { return ""; }
        }

        /** DIAG (sb3d): step-by-step log of the last Connect attempt. */
        @JavascriptInterface
        public String getConnectLog() {
            try { return ConnectLog.read(MainActivity.this); } catch (Throwable ignored) { return ""; }
        }

        private String readIp(HttpURLConnection c) throws Exception {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            c.disconnect();
            return new JSONObject(sb.toString()).optString("ip", "?");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK && pendingSubIp != null) {
                // Sub: the VPN permission flow.
                String ip = pendingSubIp, fl = pendingSubFlag, pr = pendingSubProto;
                pendingSubIp = null; pendingSubFlag = null; pendingSubProto = null;
                doConnectSub(ip, fl, pr);
            } else if (resultCode == RESULT_OK && pendingSubFastest) {
                // Sub v2: Fastest was waiting on permission.
                pendingSubFastest = false;
                doConnectSubFastest();
            } else {
                // Permission denied (or cancelled): drop any
                // stale pending Sub request so a later grant
                // cannot resume the wrong one.
                pendingSubIp = null; pendingSubFlag = null; pendingSubProto = null;
                pendingSubFastest = false;
                lastError = "VPN permission was not granted.";
                eval("window.onConnectError(" + jsString(lastError) + ")");
            }
        }
        } catch (Throwable t) {
            // sb15 (no-logs audit): never raw exception text in
            // the status message — the generic error only. The
            // detail is in logcat, not in the UI.
            lastError = "Connection failed.";
        }
    }

    /** v5: through-tunnel RTT for the CONNECTED server — the
     *  number Speedtest shows. A minimal HTTP GET to the
     *  generate_204 endpoint (empty 204, no TLS inside the
     *  tunnel) sent through the sing-box `mixed` inbound on
     *  127.0.0.1:1080, which routes via the active outbound
     *  (the connected server). The tunnel is already
     *  established, so this is ~1 RTT through it — unlike
     *  the pre-connect SsProbe, which also pays the SS
     *  handshake + relay setup (~2-3 RTTs) and so reads
     *  ~400 ms where Speedtest reads ~140 ms. Returns ms
     *  to the response headers, or -1 on any failure.
     *  Works in both modes: the mixed inbound listens on
     *  1080 in VPN mode too (v4 fix) and in proxy mode. */
    private long tunnelRttMs() {
        HttpURLConnection c = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress("127.0.0.1", SOCKS_PORT));
            c = (HttpURLConnection) new URL("http://www.gstatic.com/generate_204")
                    .openConnection(proxy);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            long t0 = System.currentTimeMillis();
            int code = c.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            if (code == 204 || (code >= 200 && code < 300)) return ms;
            return -1;
        } catch (Throwable ignored) {
            return -1;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
        }
    }

    /** Sub (TEST): user-initiated connect to one Sub
     *  Shadowsocks server. Same epoch discipline as doConnect:
     *  a newer attempt supersedes this one silently.
     *  Sub v2: picking a server by hand cancels Fastest
     *  mode (bumping the fastest epoch stops any sweep from
     *  switching afterwards). */
    private void doConnectSub(String ip, String flag, String proto) {
        subFastest = false;
        subFastestEpoch++;
        doConnectSubInternal(ip, flag, proto);
    }

    /** Sub v2: the shared connect entry for a single Sub
     *  server — used by the user path and by the Fastest
     *  live-switch. On failure while Fastest is on, the next
     *  best server is tried instead of erroring right away. */
    private void doConnectSubInternal(String ip, String flag, String proto) {
        final long epoch = ++connectEpoch;
        new Thread(() -> {
            boolean ok = connectSubSync(ip, flag, proto, epoch);
            if (!ok && epoch == connectEpoch) {
                if (subFastest) {
                    subFailedIps.add(ip);
                    if (!maybeSwitchSubFastest(subFastestEpoch)) {
                        if (!VpnTunnelService.running && !connecting) {
                            eval("window.onConnectError(" + jsString(lastError) + ")");
                        }
                    }
                } else {
                    eval("window.onConnectError(" + jsString(lastError) + ")");
                }
            }
        }).start();
    }

    /** Sub v2: Fastest mode — start a ping sweep and switch
     *  live to the best server as results arrive. No server is
     *  picked up front; the sweep's first good result triggers
     *  the first connect (see maybeSwitchSubFastest). */
    private void doConnectSubFastest() {
        subFastest = true;
        subFastestEpoch++;
        subBestIp = null;
        subBestMs = Long.MAX_VALUE;
        subFailedIps.clear();
        try { AppSettings.setLastSelection(MainActivity.this, AppSettings.SEL_FASTEST); } catch (Throwable ignored) {}
        // NOTE: `connecting` is deliberately NOT set here —
        // maybeSwitchSubFastest refuses to switch while it is
        // set, and the sweep must be free to start the first
        // connect. The WebView stays on "Connecting…" from
        // its own state + the subTesting status flag (see
        // poll); `connecting` is set by the switch itself.
        startSubPingSweep();
        // v7: if a background sweep is already running, the
        // start above is a no-op (the guard) — try an
        // immediate switch to the best server measured so
        // far, so a Fastest tap mid-sweep still connects at
        // once instead of waiting for the running sweep to
        // end. No-op when nothing is measured yet (the
        // running sweep's next result then switches, via
        // the live subFastest check in the sweep thread).
        maybeSwitchSubFastest(subFastestEpoch);
    }

    /** One TCP-connect probe to an Sub VMess server
     *  (ip:port): the time to establish the TCP connection,
     *  or -1 when it does not answer within the timeout.
     *  VMess-only — Shadowsocks servers use the real
     *  handshake probe (SsProbe) instead, because a port
     *  that answers TCP is not proof the SS service behind
     *  it works. */
    private long tcpProbeSub(String ip, int port) {
        Socket sock = null;
        try {
            long t0 = System.currentTimeMillis();
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, port), SUB_PING_TIMEOUT_MS);
            return System.currentTimeMillis() - t0;
        } catch (Throwable ignored) {
            return -1;
        } finally {
            try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        }
    }

    /** Sub v2: ping sweep over the subscription servers
     *  (20 parallel probes). Shadowsocks servers get a REAL
     *  handshake probe (SsProbe — full AEAD handshake +
     *  relay, so a dead SS service reads TIMEOUT instead of
     *  a fake ~60 ms TCP connect); VMess servers keep the
     *  TCP-connect probe. Every result is streamed to the
     *  WebView as it arrives (onSubPing) so the list
     *  lights up live; in Fastest mode each result is also a
     *  chance to switch (maybeSwitchSubFastest). A sweep
     *  already running is not started twice. At the end, a
     *  Fastest sweep that connected to nothing and found no
     *  usable server reports the error — a sweep that is
     *  mid-connect, or that already connected, stays silent.
     *  v7: the sweep checks the LIVE subFastest state
     *  (not a flag captured at start) — a Fastest tap while
     *  a background sweep is already running must adopt
     *  that sweep, so its results switch live instead of
     *  the tap silently doing nothing. */
    private synchronized void startSubPingSweep() {
        if (subPingThread != null && subPingThread.isAlive()) return;
        subPingThread = new Thread(() -> {
            try {
                List<Server> list = subCache;
                if (list == null || list.isEmpty()) {
                    try { list = fetchCurrentServers(); subCache = list; }
                    catch (Throwable ignored) { list = new ArrayList<>(); }
                }
                if (list.isEmpty()) {
                    if (subFastest
                            && !VpnTunnelService.running && !connecting) {
                        eval("window.onConnectError("
                                + jsString("Could not load servers.") + ")");
                    }
                    return;
                }
                ExecutorService ex = Executors.newFixedThreadPool(Math.min(list.size(), 20));
                for (Server a : list) {
                    final Server sv = a;
                    ex.submit(() -> {
                        long ms;
                        if ("vmess".equals(sv.proto) || "firefox".equals(sv.proto)) {
                            // TCP-connect probe: VMess has no
                            // handshake probe, and a Firefox
                            // server answers CONNECT only with a
                            // valid Bearer token — a bare TCP
                            // connect is the only pre-connect
                            // signal that the host is alive.
                            ms = tcpProbeSub(sv.ip, sv.port);
                        } else {
                            // Real SS handshake probe: validates
                            // the whole path (handshake + relay).
                            // -1 = TIMEOUT (dead server).
                            ms = SsProbe.probe(sv.ip, sv.port, sv.method,
                                    sv.password, SUB_PING_TIMEOUT_MS);
                        }
                        subPingMs.put(sv.ip, ms);
                        try {
                            JSONObject o = new JSONObject();
                            o.put("ip", sv.ip);
                            // v11: send the port too, so the JS
                            // keys the ping by ip:port (a unique
                            // server identity).
                            o.put("port", sv.port);
                            o.put("pingMs", ms);
                            eval("window.onSubPing(" + jsString(o.toString()) + ")");
                        } catch (Throwable ignored) {}
                        // v7: live subFastest — a Fastest tap
                        // mid-sweep adopts this running sweep.
                        if (subFastest) maybeSwitchSubFastest(subFastestEpoch);
                    });
                }
                ex.shutdown();
                try {
                    int slices = 0;
                    while (!ex.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                        if (++slices >= 40) { ex.shutdownNow(); break; }
                    }
                } catch (InterruptedException e) {
                    ex.shutdownNow();
                }
                if (subFastest
                        && !VpnTunnelService.running && !connecting
                        && computeSubBest() == null) {
                    eval("window.onConnectError(" + jsString("Could not connect to any server.") + ")");
                }
            } catch (Throwable ignored) {
            } finally {
                // 3.1 fix4: persist the sweep's ping table +
                // best server for the home-screen widget.
                saveWidgetPingState();
                eval("window.onSubPingDone()");
            }
        }, "arcveepn-sub-ping");
        subPingThread.setDaemon(true);
        subPingThread.start();
    }

    /** 3.1 fix4: persist the sweep's ping table + best
     *  server for the home-screen widget, which ranks its
     *  list and shows the best server's flag from the
     *  persisted data (it has no access to the app's
     *  in-memory sweep results). */
    private void saveWidgetPingState() {
        try {
            AppSettings.saveWidgetPings(this, new java.util.HashMap<>(subPingMs));
            String bestIp = computeSubBest();
            if (bestIp != null && subCache != null) {
                String flag = "";
                for (Server a : subCache) {
                    if (bestIp.equals(a.ip)) { flag = a.flag == null ? "" : a.flag; break; }
                }
                AppSettings.saveWidgetBest(this, bestIp, flag);
            }
        } catch (Throwable ignored) {}
    }

    /** Best (lowest-ms) Sub server from the last ping sweep,
     *  skipping failures and servers a Fastest connect already
     *  tried and failed. Updates subBestIp/subBestMs.
     *  Null = no usable server measured yet. */
    private synchronized String computeSubBest() {
        String bestIp = null;
        long bestMs = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : subPingMs.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) continue;
            if (subFailedIps.contains(e.getKey())) continue;
            if (e.getValue() < bestMs) { bestMs = e.getValue(); bestIp = e.getKey(); }
        }
        subBestIp = bestIp;
        subBestMs = bestIp == null ? Long.MAX_VALUE : bestMs;
        return bestIp;
    }

    /** Sub v2 Fastest live-switch: while a Fastest sweep is
     *  running, switch to the best server found SO FAR — as
     *  results arrive, not only when the sweep ends. Only
     *  switches when the new best is at least
     *  SUB_FASTEST_MIN_GAIN_MS better than the server we
     *  are on, so near-equal results never flap the tunnel.
     *  `connecting` is set synchronously here, so two results
     *  landing at once cannot start two connects. Returns
     *  true when a connect was started. */
    private synchronized boolean maybeSwitchSubFastest(long fEpoch) {
        if (!subFastest || fEpoch != subFastestEpoch) return false;
        if (connecting) return false;
        String bestIp = computeSubBest();
        if (bestIp == null) return false;
        if (bestIp.equals(subConnectedIp)) return false;
        if (subConnectedIp != null
                && subBestMs >= subConnectedMs - SUB_FASTEST_MIN_GAIN_MS) return false;
        String flag = "";
        String proto = "ss";
        List<Server> list = subCache;
        if (list != null) {
            for (Server a : list) {
                if (a != null && bestIp.equals(a.ip)) {
                    flag = a.flag == null ? "" : a.flag;
                    proto = a.proto == null ? "ss" : a.proto;
                    break;
                }
            }
        }
        connecting = true;
        connectingSince = System.currentTimeMillis();
        doConnectSubInternal(bestIp, flag, proto);
        return true;
    }


    /** 3.1: the server list for the CURRENT source — the
     *  Firefox list when Firefox mode is on, the
     *  subscription fetch otherwise. Used by the connect
     *  and ping-sweep fallbacks, so they never fetch the
     *  wrong source when the cache is empty. */
    private List<Server> fetchCurrentServers() throws Exception {
        if (AppSettings.isFirefoxMode(this)) {
            return FirefoxAuth.getServers(this, true);
        }
        return new SubscriptionApi().fetchServers();
    }

    /** v8: the cached SS list (empty when never fetched).
     *  Re-parsed exactly like a fresh fetch. */
    private List<Server> loadSsCache() {
        try {
            return SubscriptionApi.parseCacheJson(AppSettings.getCachedSs(this));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** Sub (TEST): single-server connect, synchronous. No
     *  ping/ranking fallbacks (one server, one attempt) — the
     *  server comes from the cached subscription list,
     *  re-fetched fresh if the cache is empty. Returns true
     *  only when the tunnel is actually running for THIS
     *  server. Sets the Sub statics AFTER the stop-and-wait:
     *  stopTunnel() clears currentServer, so setting it
     *  before the stop would leave startTunnel with nothing
     *  to build. */
    private boolean connectSubSync(String ip, String flag, String proto, long epoch) {
        lastError = "";
        connecting = true;
        connectingSince = System.currentTimeMillis();
        currentRegionCode = "sub";
        optimizeCount = 0;
        try {
            Server server = null;
            List<Server> list = subCache;
            if (list != null) {
                for (Server a : list) {
                    if (a != null && ip.equals(a.ip)
                            && (proto == null || proto.equals(a.proto))) { server = a; break; }
                }
            }
            if (server == null) {
                try {
                    List<Server> fresh = fetchCurrentServers();
                    subCache = fresh;
                    for (Server a : fresh) {
                        if (a != null && ip.equals(a.ip)
                                && (proto == null || proto.equals(a.proto))) { server = a; break; }
                    }
                } catch (Throwable ignored) {}
            }
            if (server == null) {
                if (epoch == connectEpoch) {
                    connecting = false;
                    lastError = "Could not connect to this server.";
                }
                return false;
            }
            if (!epochAlive(epoch)) return false;
            // Display for the notification / status: flag only
            // (never a provider name — nothing user-visible may
            // name the subscription source).
            String display = (flag != null && !flag.isEmpty() ? flag : ip);
            currentDisplay = display;
            if (VpnTunnelService.running) {
                Intent d = new Intent(this, VpnTunnelService.class);
                d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                startService(d);
                for (int k = 0; k < 20; k++) {
                    Thread.sleep(100);
                    if (!epochAlive(epoch)) return false;
                    if (!VpnTunnelService.running) break;
                }
                if (VpnTunnelService.running) return false;
            }
            if (!epochAlive(epoch)) return false;
            // 3.1: a Firefox server needs a fresh ProxyPass JWT
            // for the CONNECT (Bearer). Fetched here, on the
            // connect thread, in memory only — a failure (or a
            // dead login) fails this connect with the generic
            // message, exactly like an unreachable server.
            // Any other protocol clears the slot so a stale
            // token can never leak into its config.
            String ffxPass = null;
            if ("firefox".equals(server.proto)) {
                try {
                    ffxPass = FirefoxAuth.ensureProxyPass(MainActivity.this);
                } catch (FirefoxAuth.FfxException e) {
                    if (e.reauth) {
                        try { new Bridge().firefoxAuthLost(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                if (ffxPass == null || ffxPass.isEmpty()) {
                    if (epoch == connectEpoch) {
                        connecting = false;
                        lastError = "Could not connect to this server.";
                    }
                    return false;
                }
            }
            VpnTunnelService.firefoxProxyPass = ffxPass;
            VpnTunnelService.currentServer = server;
            VpnTunnelService.currentRegion = display;
            VpnTunnelService.proxyMode = AppSettings.isProxyMode(this);
            VpnTunnelService.splitMode = AppSettings.getSplitMode(this);
            VpnTunnelService.splitPackages = AppSettings.getSplitPackages(this);
            Intent i = new Intent(this, VpnTunnelService.class);
            i.setAction(VpnTunnelService.ACTION_CONNECT);
            i.putExtra(VpnTunnelService.EXTRA_REGION, display);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            for (int k = 0; k < 10; k++) {
                Thread.sleep(500);
                if (!epochAlive(epoch)) return false;
                if (VpnTunnelService.running) break;
            }
            if (!VpnTunnelService.running) {
                if (epoch == connectEpoch) {
                    connecting = false;
                    lastError = "Could not connect to this server.";
                }
                return false;
            }
            if (!epochAlive(epoch)) return false;
            if (VpnTunnelService.runningServer != server) {
                if (epoch == connectEpoch) {
                    connecting = false;
                    lastError = "Could not connect to this server.";
                }
                return false;
            }
            watchdogStop = false;
            startWatchdog();
            // v5: record the connected server BEFORE starting
            // the ping poller, so its first iteration already
            // measures the through-tunnel RTT for this server
            // (otherwise the first RTT would wait a full
            // 30 s sleep, because subConnectedIp was null).
            subConnectedIp = ip;
            subConnectedMs = subPingMs.getOrDefault(ip, Long.MAX_VALUE);
            // 3.1 fix4: remember the Fastest pick for the
            // small widget's flag.
            if (subFastest) {
                try { AppSettings.saveWidgetBest(MainActivity.this, ip, flag == null ? "" : flag); }
                catch (Throwable ignored) {}
            }
            startSbPingPoller();
            new Bridge().checkExitIp();
            // Sub v2: re-evaluate Fastest — a better server
            // may have been measured while this connect was in
            // flight, so switch to it now instead of waiting
            // for the next sweep.
            connecting = false;
            if (subFastest) maybeSwitchSubFastest(subFastestEpoch);
            return true;
        } catch (Throwable e) {
            if (epoch == connectEpoch) {
                connecting = false;
                lastError = "Could not connect to this server.";
            }
            return false;
        }
    }

    /** sb11: live ping of the connected server, re-measured
     *  while connected. The DISPLAYED ping is the RTT ping
     *  (a request/response on an established CONNECT
     *  tunnel to the live server, over the direct path — the
     *  app is excluded from the TUN, so this works while
     *  connected, in both modes). The Clash API delay is NO
     *  LONGER used for the display at all (it measures a full
     *  HTTPS fetch — the same multi-RTT error as the old ping);
     *  Clash stays only for sing-box's internal urltest use. A
     *  failed probe leaves lastSbPingMs at -1 and the JS keeps
     *  the previous displayed value.
     *  v5: while an Sub server is connected, the poller
     *  measures the through-tunnel RTT (tunnelRttMs, best of
     *  two) and publishes it as lastSbPingMs, so the JS shows
     *  the Speedtest-like value instead of the pre-connect
     *  SsProbe value (~400 ms while Speedtest showed ~140 ms).
     *  A failed probe keeps the last good value (never -1
     *  while the tunnel is up). Refreshes every 10 s. */
    private synchronized void startSbPingPoller() {
        if (sbPingThread != null && sbPingThread.isAlive()) return;
        lastSbPingMs = -1;
        sbPingThread = new Thread(() -> {
            while (!watchdogStop && VpnTunnelService.running) {
                boolean ok = false;
                try {
                    if (subConnectedIp != null) {
                        long a = tunnelRttMs();
                        long b = tunnelRttMs();
                        long best = -1;
                        if (a > 0 && b > 0) best = Math.min(a, b);
                        else if (a > 0) best = a;
                        else if (b > 0) best = b;
                        if (best > 0) { lastSbPingMs = best; ok = true; }
                    }
                } catch (Throwable ignored) {
                    // keep the last good value
                }
                // v6: if the RTT measurement just failed (tunnel
                // still warming up), retry in 2 s instead of
                // waiting the full 10 s — so the accurate
                // through-tunnel ping appears quickly after
                // connect instead of showing the pre-connect
                // SsProbe value for a whole interval.
                long sleepMs;
                if (subConnectedIp != null) sleepMs = ok ? 10000 : 2000;
                else sleepMs = 30000;
                try {
                    Thread.sleep(sleepMs);
                } catch (Throwable e) { break; }
            }
        }, "arcveepn-sb-ping");
        sbPingThread.setDaemon(true);
        sbPingThread.start();
    }
    private void reconnectAfterNetworkChange() {
        final long epoch = ++connectEpoch;
        final String region = currentRegionCode;
        final String display = currentDisplay;
        // Sub v2: the same snapshot for an Sub tunnel.
        final boolean aFast = subFastest;
        final String aIp = subConnectedIp;
        String aFl = "";
        String aPr = "ss";
        if (aIp != null && subCache != null) {
            for (Server a : subCache) {
                if (a != null && aIp.equals(a.ip)) {
                    aFl = a.flag == null ? "" : a.flag;
                    aPr = a.proto == null ? "ss" : a.proto;
                    break;
                }
            }
        }
        final String aFlag = aFl;
        final String aProto = aPr;
        ConnectLog.log(this, "netchange reconnect region=" + region);
        new Thread(() -> {
            try {
                // Stop the old tunnel (same disconnect + wait as
                // optimize) — its sockets are on the old network.
                Intent d = new Intent(MainActivity.this, VpnTunnelService.class);
                d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                startService(d);
                for (int k = 0; k < 30; k++) {
                    Thread.sleep(100);
                    if (!epochAlive(epoch)) return;
                    if (!VpnTunnelService.running) break;
                }
                if (!epochAlive(epoch)) return;
                if (VpnTunnelService.running) {
                    ConnectLog.log(MainActivity.this,
                            "netchange: old tunnel still up, abort");
                    return;
                }
                // Network change on an Sub tunnel — Fastest
                // re-tests from scratch, a fixed server
                // re-establishes the SAME server.
                if ("sub".equals(region)) {
                    if (aFast) { doConnectSubFastest(); return; }
                    if (aIp != null) { doConnectSubInternal(aIp, aFlag, aProto); return; }
                    ConnectLog.log(MainActivity.this,
                            "netchange: no sub server, abort");
                    return;
                }
                ConnectLog.log(MainActivity.this,
                        "netchange: no current server, abort");
            } catch (Throwable ignored) {}
        }, "arcveepn-netchange").start();
    }

    /** Background watchdog: while connected, check tunnel bytes
     *  every 30 s; on a 90 s stall (and >60 s since connect) trigger
     *  the same optimize path, even if the WebView is paused. */
    private synchronized void startWatchdog() {
        if (watchdogThread != null && watchdogThread.isAlive()) return;
        watchdogThread = new Thread(() -> {
            long prevBytes = -1;
            long lastChange = System.currentTimeMillis();
            while (!watchdogStop && VpnTunnelService.running) {
                try { Thread.sleep(30000); } catch (Throwable e) { break; }
                if (watchdogStop || !VpnTunnelService.running) break;
                try {
                    long[] st = VpnTunnelService.tunnelStats();
                    long total = (st.length > 3 ? st[1] + st[3] : 0);
                    long now = System.currentTimeMillis();
                    // sb9: NEVER auto-optimize on a dead stats
                    // stream. The sing-box byte counters stay 0
                    // forever if the stats thread died at birth
                    // (running-flag race, fixed in SingBoxRunner)
                    // or the CommandClient stream never delivers
                    // — firing optimize on that is exactly the
                    // reported "server disconnects by itself".
                    // Only a stall AFTER real traffic was seen
                    // (statsEverNonZero) counts as a stall.
                    if (!VpnTunnelService.statsEverNonZero) {
                        prevBytes = total; lastChange = now; continue;
                    }
                    if (prevBytes < 0) { prevBytes = total; lastChange = now; continue; }
                    if (total != prevBytes) { prevBytes = total; lastChange = now; continue; }
                    if (VpnTunnelService.connectedAtMs > 0
                            && now - VpnTunnelService.connectedAtMs > 60000
                            && now - lastChange > 90000) {
                        lastChange = now;
                        new Bridge().optimize();
                    }
                } catch (Throwable ignored) {}
            }
        }, "arcveepn-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }
}
