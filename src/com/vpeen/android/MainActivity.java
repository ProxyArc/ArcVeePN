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
 *    (example.com, 577 bytes, verified live). The time from
 *    before CONNECT to after the full tiny response is the
 *    real, end-to-end ping. Only servers that complete it count,
 *    and the lowest time wins. This is not a TCP port probe:
 *    a port can answer TCP yet fail CONNECT/auth, and that
 *    server is correctly excluded here.
 * 2. If example.com fails for ALL servers, retry the same test
 *    with neverssl.com (also verified live, HTTP only, no
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
    // Server (TEST): pending connect while the VPN permission
    // dialog is up, and the last fetched Server server list
    // (passwords stay in these objects only — never sent to
    // the WebView; the 30-min fetch cache lives in SubscriptionApi).
    private String pendingIp;
    private String pendingFlag;
    private String pendingProto;
    private volatile List<Server> serverCache;
    // Server v2: per-server TCP ping results + Fastest
    // live-switch. serverPingMs holds the last sweep's result
    // per IP (ms, or -1 when the server did not answer);
    // failedIps are servers a Fastest connect already
    // tried and failed, so the next-best is tried instead.
    private static final int PING_TIMEOUT_MS = 3000;
    // Local proxy ports (were the removed Java bridge's
    // SOCKS_PORT / HTTP_PORT — sing-box serves these ports
    // itself in both modes now).
    private static final int SOCKS_PORT = 1080;
    private static final int HTTP_PORT = 8080;
    private static final long FASTEST_MIN_GAIN_MS = 20;
    private final Map<String, Long> serverPingMs = new ConcurrentHashMap<>();
    private final java.util.Set<String> failedIps =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile Thread pingThread = null;
    private volatile boolean fastestServer = false;
    private volatile long fastestServerEpoch = 0;
    private volatile String serverBestIp = null;
    private volatile long serverBestMs = Long.MAX_VALUE;
    private volatile String connectedServerIp = null;
    private volatile long connectedServerMs = Long.MAX_VALUE;
    private volatile boolean pendingFastest = false;
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
         *  name} payload as refreshServers, never the password. */
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
        public void refreshServersForce() {
            new Thread(() -> {
                try {
                    try {
                        SubscriptionApi.refresh();
                        List<Server> list = new SubscriptionApi().fetchServers();
                        serverCache = list;
                        ssCacheLoaded = false;
                        // v8: persist the raw subscription for
                        // the offline cache (raw text, not the
                        // password).
                        try {
                            String cacheJson = SubscriptionApi.lastFetchForCache();
                            if (cacheJson != null)
                                AppSettings.saveCachedSs(MainActivity.this, cacheJson);
                        } catch (Throwable ignored) {}
                        JSONArray arr = new JSONArray();
                        for (Server a : list) {
                            JSONObject o = new JSONObject();
                            o.put("ip", a.ip);
                            o.put("flag", a.flag == null ? "" : a.flag);
                            o.put("port", a.port);
                            o.put("proto", a.proto == null ? "ss" : a.proto);
                            o.put("name", a.name == null ? "" : a.name);
                            arr.put(o);
                        }
                        eval("window.onServers(" + jsString(arr.toString()) + ")");
                    } catch (Throwable e) {
                        // Both subscriptions yielded nothing —
                        // keep the cached list on screen.
                        eval("window.onServersError("
                                + jsString("Could not load servers.") + ")");
                    }
                } catch (Throwable ignored) {}
            }).start();
        }
        /** The CACHED Shadowsocks list, shown IMMEDIATELY
         *  on app open while the fresh fetch runs in the
         *  background. Returns {"cached":true|false,
         *  "servers":[{ip,flag,port,proto,name}]} — the same
         *  payload shape as onServers. "cached" is false
         *  (and servers empty) when the app has never fetched
         *  successfully. The SS cache is re-parsed with the
         *  subscription bucket-roll refresh, so a cached SS server
         *  stays valid after the credential bucket rolls. */
        @JavascriptInterface
        public String getCachedServers() {
            try {
                JSONObject out = new JSONObject();
                List<Server> list = loadSsCache();
                out.put("cached", !list.isEmpty());
                JSONArray arr = new JSONArray();
                for (Server a : list) {
                    JSONObject o = new JSONObject();
                    o.put("ip", a.ip);
                    o.put("flag", a.flag == null ? "" : a.flag);
                    o.put("port", a.port);
                    o.put("proto", a.proto == null ? "ss" : a.proto);
                    o.put("name", a.name == null ? "" : a.name);
                    arr.put(o);
                }
                out.put("servers", arr);
                if (!list.isEmpty()) {
                    serverCache = list;
                    ssCacheLoaded = true;
                }
                return out.toString();
            } catch (Throwable ignored) {
                return "{\"cached\":false,\"servers\":[]}";
            }
        }

        /** Server v2: start a TCP-connect ping sweep over the
         *  Server servers. Results stream to the WebView live
         *  (onServerPing per server, onServerPingDone at the
         *  end); a sweep already running is not started twice. */
        @JavascriptInterface
        public void pingServers() {
            startServerPingSweep();
        }

        /** Connect to one subscription server by IP + protocol
         *  ("ss" primary, "vmess" backup). Mirrors connect():
         *  proxy mode needs no VPN permission, otherwise the
         *  VpnService.prepare flow with pendingIp/
         *  pendingFlag/pendingProto (handled in
         *  onActivityResult, like pendingRegion/pendingDisplay).
         *  The actual connect runs on a background thread in
         *  doConnect. */
        @JavascriptInterface
        public void connectServer(final String ip, final String flag, final String proto) {
            runOnUiThread(() -> {
                try {
                    try { ConnectLog.clear(MainActivity.this); } catch (Throwable ignored) {}
                    if (AppSettings.isProxyMode(MainActivity.this)) {
                        doConnect(ip, flag, proto);
                        return;
                    }
                    Intent prep = VpnService.prepare(MainActivity.this);
                    if (prep != null) {
                        pendingIp = ip;
                        pendingFlag = flag;
                        pendingProto = proto;
                        startActivityForResult(prep, REQ_VPN);
                    } else {
                        doConnect(ip, flag, proto);
                    }
                } catch (Throwable t) {
                    lastError = "Connection failed.";
                    connecting = false;
                    eval("window.onConnectError(" + jsString(lastError) + ")");
                }
            });
        }

        /** Server v2: Fastest mode — ping-test all servers and
         *  switch live to the best found so far. Same VPN
         *  permission flow as connectServer (pendingFastest
         *  is resumed in onActivityResult). */
        @JavascriptInterface
        public void connectFastest() {
            runOnUiThread(() -> {
                try {
                    try { ConnectLog.clear(MainActivity.this); } catch (Throwable ignored) {}
                    if (AppSettings.isProxyMode(MainActivity.this)) {
                        doConnectFastest();
                        return;
                    }
                    Intent prep = VpnService.prepare(MainActivity.this);
                    if (prep != null) {
                        pendingFastest = true;
                        startActivityForResult(prep, REQ_VPN);
                    } else {
                        doConnectFastest();
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
            // Server v2: leaving Fastest mode + forget the
            // connected Server server (bumping the fastest
            // epoch stops any sweep from switching afterwards).
            fastestServer = false;
            fastestServerEpoch++;
            connectedServerIp = null;
            connectedServerMs = Long.MAX_VALUE;
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

        @JavascriptInterface
        public String getStatusJson() {
            try {
                JSONObject o = new JSONObject();
                boolean connected = VpnTunnelService.running;
                if (connected) connecting = false;
                // Server v2: while a Fastest sweep is still
                // testing, never auto-fail the connect — the
                // sweep owns the outcome and reports it
                // (onConnectError) when it ends.
                boolean sweeping = pingThread != null && pingThread.isAlive();
                if (connecting && !sweeping && System.currentTimeMillis() - connectingSince > 20000 && !connected) {
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
                // Server v2: connected Server server IP ("" when
                // none), Fastest live-switch state, and whether
                // a ping sweep is still testing.
                o.put("serverIp", connectedServerIp == null ? "" : connectedServerIp);
                o.put("fastestServer", fastestServer);
                o.put("serverTesting", sweeping);
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
            if (resultCode == RESULT_OK && pendingIp != null) {
                // Server: the VPN permission flow.
                String ip = pendingIp, fl = pendingFlag, pr = pendingProto;
                pendingIp = null; pendingFlag = null; pendingProto = null;
                doConnect(ip, fl, pr);
            } else if (resultCode == RESULT_OK && pendingFastest) {
                // Server v2: Fastest was waiting on permission.
                pendingFastest = false;
                doConnectFastest();
            } else {
                // Permission denied (or cancelled): drop any
                // stale pending Server request so a later grant
                // cannot resume the wrong one.
                pendingIp = null; pendingFlag = null; pendingProto = null;
                pendingFastest = false;
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

    /** Server (TEST): user-initiated connect to one Server
     *  Shadowsocks server. Same epoch discipline as doConnect:
     *  a newer attempt supersedes this one silently.
     *  Server v2: picking a server by hand cancels Fastest
     *  mode (bumping the fastest epoch stops any sweep from
     *  switching afterwards). */
    private void doConnect(String ip, String flag, String proto) {
        fastestServer = false;
        fastestServerEpoch++;
        doConnectInternal(ip, flag, proto);
    }

    /** Server v2: the shared connect entry for a single Server
     *  server — used by the user path and by the Fastest
     *  live-switch. On failure while Fastest is on, the next
     *  best server is tried instead of erroring right away. */
    private void doConnectInternal(String ip, String flag, String proto) {
        final long epoch = ++connectEpoch;
        new Thread(() -> {
            boolean ok = connectServerSync(ip, flag, proto, epoch);
            if (!ok && epoch == connectEpoch) {
                if (fastestServer) {
                    failedIps.add(ip);
                    if (!maybeSwitchFastest(fastestServerEpoch)) {
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

    /** Server v2: Fastest mode — start a ping sweep and switch
     *  live to the best server as results arrive. No server is
     *  picked up front; the sweep's first good result triggers
     *  the first connect (see maybeSwitchFastest). */
    private void doConnectFastest() {
        fastestServer = true;
        fastestServerEpoch++;
        serverBestIp = null;
        serverBestMs = Long.MAX_VALUE;
        failedIps.clear();
        try { AppSettings.setLastSelection(MainActivity.this, AppSettings.SEL_FASTEST); } catch (Throwable ignored) {}
        // NOTE: `connecting` is deliberately NOT set here —
        // maybeSwitchFastest refuses to switch while it is
        // set, and the sweep must be free to start the first
        // connect. The WebView stays on "Connecting…" from
        // its own state + the serverTesting status flag (see
        // poll); `connecting` is set by the switch itself.
        startServerPingSweep();
        // v7: if a background sweep is already running, the
        // start above is a no-op (the guard) — try an
        // immediate switch to the best server measured so
        // far, so a Fastest tap mid-sweep still connects at
        // once instead of waiting for the running sweep to
        // end. No-op when nothing is measured yet (the
        // running sweep's next result then switches, via
        // the live fastestServer check in the sweep thread).
        maybeSwitchFastest(fastestServerEpoch);
    }

    /** One TCP-connect probe to an Server VMess server
     *  (ip:port): the time to establish the TCP connection,
     *  or -1 when it does not answer within the timeout.
     *  VMess-only — Shadowsocks servers use the real
     *  handshake probe (SsProbe) instead, because a port
     *  that answers TCP is not proof the SS service behind
     *  it works. */
    private long tcpProbeServer(String ip, int port) {
        Socket sock = null;
        try {
            long t0 = System.currentTimeMillis();
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, port), PING_TIMEOUT_MS);
            return System.currentTimeMillis() - t0;
        } catch (Throwable ignored) {
            return -1;
        } finally {
            try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        }
    }

    /** Server v2: ping sweep over the subscription servers
     *  (20 parallel probes). Shadowsocks servers get a REAL
     *  handshake probe (SsProbe — full AEAD handshake +
     *  relay, so a dead SS service reads TIMEOUT instead of
     *  a fake ~60 ms TCP connect); VMess servers keep the
     *  TCP-connect probe. Every result is streamed to the
     *  WebView as it arrives (onServerPing) so the list
     *  lights up live; in Fastest mode each result is also a
     *  chance to switch (maybeSwitchFastest). A sweep
     *  already running is not started twice. At the end, a
     *  Fastest sweep that connected to nothing and found no
     *  usable server reports the error — a sweep that is
     *  mid-connect, or that already connected, stays silent.
     *  v7: the sweep checks the LIVE fastestServer state
     *  (not a flag captured at start) — a Fastest tap while
     *  a background sweep is already running must adopt
     *  that sweep, so its results switch live instead of
     *  the tap silently doing nothing. */
    private synchronized void startServerPingSweep() {
        if (pingThread != null && pingThread.isAlive()) return;
        pingThread = new Thread(() -> {
            try {
                List<Server> list = serverCache;
                if (list == null || list.isEmpty()) {
                    try { list = new SubscriptionApi().fetchServers(); serverCache = list; }
                    catch (Throwable ignored) { list = new ArrayList<>(); }
                }
                if (list.isEmpty()) {
                    if (fastestServer
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
                        if ("vmess".equals(sv.proto)) {
                            ms = tcpProbeServer(sv.ip, sv.port);
                        } else {
                            // Real SS handshake probe: validates
                            // the whole path (handshake + relay).
                            // -1 = TIMEOUT (dead server).
                            ms = SsProbe.probe(sv.ip, sv.port, sv.method,
                                    sv.password, PING_TIMEOUT_MS);
                        }
                        serverPingMs.put(sv.ip, ms);
                        try {
                            JSONObject o = new JSONObject();
                            o.put("ip", sv.ip);
                            // v11: send the port too, so the JS
                            // keys the ping by ip:port (a unique
                            // server identity).
                            o.put("port", sv.port);
                            o.put("pingMs", ms);
                            eval("window.onServerPing(" + jsString(o.toString()) + ")");
                        } catch (Throwable ignored) {}
                        // v7: live fastestServer — a Fastest tap
                        // mid-sweep adopts this running sweep.
                        if (fastestServer) maybeSwitchFastest(fastestServerEpoch);
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
                if (fastestServer
                        && !VpnTunnelService.running && !connecting
                        && computeServerBest() == null) {
                    eval("window.onConnectError(" + jsString("Could not connect to any server.") + ")");
                }
            } catch (Throwable ignored) {
            } finally {
                eval("window.onServerPingDone()");
            }
        }, "arcveepn-server-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    /** Best (lowest-ms) Server server from the last ping sweep,
     *  skipping failures and servers a Fastest connect already
     *  tried and failed. Updates serverBestIp/serverBestMs.
     *  Null = no usable server measured yet. */
    private synchronized String computeServerBest() {
        String bestIp = null;
        long bestMs = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : serverPingMs.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) continue;
            if (failedIps.contains(e.getKey())) continue;
            if (e.getValue() < bestMs) { bestMs = e.getValue(); bestIp = e.getKey(); }
        }
        serverBestIp = bestIp;
        serverBestMs = bestIp == null ? Long.MAX_VALUE : bestMs;
        return bestIp;
    }

    /** Server v2 Fastest live-switch: while a Fastest sweep is
     *  running, switch to the best server found SO FAR — as
     *  results arrive, not only when the sweep ends. Only
     *  switches when the new best is at least
     *  FASTEST_MIN_GAIN_MS better than the server we
     *  are on, so near-equal results never flap the tunnel.
     *  `connecting` is set synchronously here, so two results
     *  landing at once cannot start two connects. Returns
     *  true when a connect was started. */
    private synchronized boolean maybeSwitchFastest(long fEpoch) {
        if (!fastestServer || fEpoch != fastestServerEpoch) return false;
        if (connecting) return false;
        String bestIp = computeServerBest();
        if (bestIp == null) return false;
        if (bestIp.equals(connectedServerIp)) return false;
        if (connectedServerIp != null
                && serverBestMs >= connectedServerMs - FASTEST_MIN_GAIN_MS) return false;
        String flag = "";
        String proto = "ss";
        List<Server> list = serverCache;
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
        doConnectInternal(bestIp, flag, proto);
        return true;
    }


    /** v8: the cached SS list (empty when never fetched).
     *  Re-parsed  exactly like a fresh fetch,
     *  with the subscription bucket-roll refresh applied. */
    private List<Server> loadSsCache() {
        try {
            return SubscriptionApi.parseCacheJson(AppSettings.getCachedSs(this));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** Server (TEST): single-server connect, synchronous. No
     *  ping/ranking fallbacks (one server, one attempt) — the
     *  server comes from the cached subscription list,
     *  re-fetched fresh if the cache is empty. Returns true
     *  only when the tunnel is actually running for THIS
     *  server. Sets the Server statics AFTER the stop-and-wait:
     *  stopTunnel() clears currentServer, so setting it
     *  before the stop would leave startTunnel with nothing
     *  to build. */
    private boolean connectServerSync(String ip, String flag, String proto, long epoch) {
        lastError = "";
        connecting = true;
        connectingSince = System.currentTimeMillis();
        currentRegionCode = "server";
        optimizeCount = 0;
        try {
            Server server = null;
            List<Server> list = serverCache;
            if (list != null) {
                for (Server a : list) {
                    if (a != null && ip.equals(a.ip)
                            && (proto == null || proto.equals(a.proto))) { server = a; break; }
                }
            }
            if (server == null) {
                try {
                    List<Server> fresh = new SubscriptionApi().fetchServers();
                    serverCache = fresh;
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
            // 30 s sleep, because connectedServerIp was null).
            connectedServerIp = ip;
            connectedServerMs = serverPingMs.getOrDefault(ip, Long.MAX_VALUE);
            startSbPingPoller();
            new Bridge().checkExitIp();
            // Server v2: re-evaluate Fastest — a better server
            // may have been measured while this connect was in
            // flight, so switch to it now instead of waiting
            // for the next sweep.
            connecting = false;
            if (fastestServer) maybeSwitchFastest(fastestServerEpoch);
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
     *  v5: while an Server server is connected, the poller
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
                    if (connectedServerIp != null) {
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
                if (connectedServerIp != null) sleepMs = ok ? 10000 : 2000;
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
        // Server v2: the same snapshot for an Server tunnel.
        final boolean aFast = fastestServer;
        final String aIp = connectedServerIp;
        String aFl = "";
        String aPr = "ss";
        if (aIp != null && serverCache != null) {
            for (Server a : serverCache) {
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
                // Network change on an Server tunnel — Fastest
                // re-tests from scratch, a fixed server
                // re-establishes the SAME server.
                if ("server".equals(region)) {
                    if (aFast) { doConnectFastest(); return; }
                    if (aIp != null) { doConnectInternal(aIp, aFlag, aProto); return; }
                    ConnectLog.log(MainActivity.this,
                            "netchange: no server server, abort");
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
