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
    private static final long CACHE_TTL_MS = 60000;

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
    private static final int RTT_TIMEOUT_MS = 8000;
    private static final int PING_MAX_PER_REGION = 2;
    private static final int TCP_TIMEOUT_MS = 2500;

    private WebView webView;
    private String pendingRegion;
    private String pendingDisplay;
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
    // sb9: true while the current connect flow is Fastest, so the
    // service routes through the sing-box urltest group.
    private volatile boolean fastestModeFlag = false;

    // sb15: Fastest periodic re-test + auto-switch. While
    // connected in VPN mode on Fastest, re-test all regions every
    // FASTEST_RETEST_INTERVAL_MS and switch to a different server
    // ONLY when it is clearly better (at least
    // FASTEST_SWITCH_MIN_GAIN_MS faster AND at least 25% lower
    // ping — both conditions, so noise never flaps the server).
    private static final long FASTEST_RETEST_INTERVAL_MS = 5L * 60 * 1000;
    private static final long FASTEST_SWITCH_MIN_GAIN_MS = 30;
    private volatile Thread fastestRetestThread = null;

    private static class CachedServers {
        List<Server> servers; long at;
        CachedServers(List<Server> s, long a) { servers = s; at = a; }
    }
    private final Map<String, CachedServers> serverCache = new ConcurrentHashMap<>();

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

    private static String flagFor(String code) {
        if (code == null || code.length() < 2) return "";
        String c = code.toUpperCase();
        return new String(Character.toChars(127397 + c.charAt(0)))
                + new String(Character.toChars(127397 + c.charAt(1)));
    }

    public class Bridge {
        @JavascriptInterface
        public void refreshLocations() {
            new Thread(() -> {
                try {
                    VeePnApi api = new VeePnApi(MainActivity.this);
                    List<Location> list = api.locations();
                    JSONArray arr = new JSONArray();
                    for (Location l : list) {
                        JSONObject o = new JSONObject();
                        o.put("region", l.region);
                        o.put("countryCode", l.countryCode);
                        o.put("name", l.name);
                        o.put("id", l.id);
                        o.put("flag", flagFor(l.countryCode));
                        arr.put(o);
                    }
                    eval("window.onLocations(" + jsString(arr.toString()) + ")");
                } catch (Throwable e) {
                    // sb15 (no-logs audit): the locations handler
                    // does not exist in the page (no-op), and raw
                    // exception text must never reach the UI.
                    eval("window.onLocationsError("
                            + jsString("Could not load server list.") + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void connect(final String region, final String display) {
            runOnUiThread(() -> {
                // sb15 HARDENING: this runs on the UI thread — an
                // exception here (VpnService.prepare /
                // startActivityForResult) would be uncaught on the
                // main thread and kill the process. Everything is
                // guarded; the failure shows only the generic error.
                try {
                    // DIAG (sb3d): fresh connect log for this attempt.
                    try { ConnectLog.clear(MainActivity.this); } catch (Throwable ignored) {}
                    // sb10: proxy mode runs no TUN at all — no VPN
                    // permission is needed (and no VPN key icon shows).
                    if (AppSettings.isProxyMode(MainActivity.this)) {
                        doConnect(region, display, null);
                        return;
                    }
                    Intent prep = VpnService.prepare(MainActivity.this);
                    if (prep != null) {
                        pendingRegion = region;
                        pendingDisplay = display;
                        startActivityForResult(prep, REQ_VPN);
                    } else {
                        doConnect(region, display, null);
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
            if (!VpnTunnelService.running) return;
            long now = System.currentTimeMillis();
            if (now - lastOptimizeAt < 60000) return;
            lastOptimizeAt = now;
            optimizeCount++;
            if (currentRegionCode == null) return;
            final String region = currentRegionCode;
            final String display = currentDisplay;
            final String exclude = VpnTunnelService.currentServer != null
                    ? VpnTunnelService.currentServer.host() : null;
            new Thread(() -> {
                try {
                    // sb14: observe the epoch, never bump it — a
                    // user connect must always supersede this
                    // auto-reconnect, and its fallback must not
                    // run after being superseded.
                    final long epoch = connectEpoch;
                    Intent d = new Intent(MainActivity.this, VpnTunnelService.class);
                    d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                    startService(d);
                    Thread.sleep(1500);
                    if (!epochAlive(epoch)) return;
                    // Same region first; if it cannot connect at
                    // all, fall back to Fastest across all regions.
                    if (!connectRegionSync(region, display, exclude, epoch)) {
                        if (!epochAlive(epoch)) return;
                        doConnectFastest(false);
                    }
                } catch (Throwable ignored) {}
            }).start();
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
        public void pingServers() {
            // v1.7: real tunnel ping on launch again (tiny pages,
            // not 500 KB), reported per region as it completes,
            // with -1 for a region whose servers all fail — so
            // the UI updates to ms or "—", never stuck on "ping…".
            new Thread(() -> {
                try {
                    VeePnApi api = new VeePnApi(MainActivity.this);
                    List<String> regions = new ArrayList<>();
                    try {
                        for (Location l : api.locations()) regions.add(l.region);
                    } catch (Throwable ignored) {}
                    if (regions.isEmpty()) {
                        String[] fb = {"fr-prs", "gb-lnd", "nl", "ru-spb", "sg", "us-or", "us-va"};
                        for (String r : fb) regions.add(r);
                    }
                    ExecutorService ex = Executors.newFixedThreadPool(Math.min(regions.size(), 7));
                    for (String region : regions) {
                        final String reg = region;
                        ex.submit(() -> {
                            // sb10: CONNECT-time ping only (no test
                            // host anymore); the per-server retry
                            // lives inside pingTestServers.
                            // sb14: the launch ping test never
                            // aborts (EPOCH_NONE).
                            List<PingResult> res = pingRegion(api, reg, null, EPOCH_NONE);
                            long best = res.isEmpty() ? -1 : res.get(0).ms;
                            JSONArray arr = new JSONArray();
                            try {
                                JSONObject o = new JSONObject();
                                o.put("code", reg);
                                o.put("pingMs", best);
                                arr.put(o);
                            } catch (Throwable ignored) {}
                            eval("window.onPings(" + jsString(arr.toString()) + ")");
                        });
                    }
                    ex.shutdown();
                } catch (Throwable ignored) {}
            }).start();
        }

        @JavascriptInterface
        public void connectFastest() {
            runOnUiThread(() -> {
                // sb15 HARDENING: same guard as connect() — this
                // runs on the UI thread and must never throw.
                try {
                    // DIAG (sb3d): fresh connect log for this attempt.
                    try { ConnectLog.clear(MainActivity.this); } catch (Throwable ignored) {}
                    // sb10: proxy mode needs no VPN permission.
                    if (AppSettings.isProxyMode(MainActivity.this)) {
                        doConnectFastest();
                        return;
                    }
                    Intent prep = VpnService.prepare(MainActivity.this);
                    if (prep != null) {
                        pendingRegion = "__fastest__";
                        pendingDisplay = "Fastest — Auto";
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
        public void checkExitIp() {
            new Thread(() -> {
                try {
                    String ip;
                    if (VpnTunnelService.running) {
                        Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                                new InetSocketAddress("127.0.0.1", LocalSocksServer.SOCKS_PORT));
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
                if (connecting && System.currentTimeMillis() - connectingSince > 20000 && !connected) {
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
                o.put("socksPort", LocalSocksServer.SOCKS_PORT);
                o.put("httpPort", LocalSocksServer.HTTP_PORT);
                o.put("ip", lastIp);
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
            if (resultCode == RESULT_OK && pendingRegion != null) {
                String r = pendingRegion, d = pendingDisplay;
                pendingRegion = null; pendingDisplay = null;
                if ("__fastest__".equals(r)) doConnectFastest();
                else doConnect(r, d, null);
            } else {
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

    private List<Server> getServersCached(VeePnApi api, String region) throws Exception {
        CachedServers c = serverCache.get(region);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at < CACHE_TTL_MS) return c.servers;
        List<Server> servers = api.serverList(region);
        serverCache.put(region, new CachedServers(servers, now));
        return servers;
    }

    // ---------- v1.7 real tunnel ping ----------

    private static class PingResult {
        Server server; String region; long ms;
        PingResult(Server s, String r, long m) { server = s; region = r; ms = m; }
    }

    private static class ConnectCandidate {
        Server server; String region; String display;
        ConnectCandidate(Server s, String r, String d) { server = s; region = r; display = d; }
    }

    /** sb11 RTT ping through one server's upstream — measures
     *  what Speedtest measures. The CONNECT tunnel is
     *  established ONCE (untimed setup: DNS + TCP + TLS +
     *  CONNECT 200, which already proves the proxy carries
     *  traffic), then a minimal GET is timed to its first
     *  response byte on that established tunnel: ~1 RTT through
     *  the proxy, like Speedtest's latency. Returns ms, or -1
     *  if the server cannot carry real traffic at all. The
     *  overall timeout is enforced with a Future because
     *  Upstream uses 10 s socket timeouts internally; the probe
     *  runs on a daemon thread so an abandoned attempt can never
     *  block the pool. */
    private long rttPingMs(Server s) {
        ExecutorService one = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "arcveepn-rtt-ping");
            t.setDaemon(true);
            return t;
        });
        Future<Long> f = one.submit(() -> {
            Socket sock = null;
            try {
                // Setup (untimed): DNS + TCP + TLS + CONNECT 200.
                sock = Upstream.connectViaServer(s, "1.1.1.1", 80, null);
                sock.setSoTimeout(3000);
                java.io.InputStream in = sock.getInputStream();
                java.io.OutputStream out = sock.getOutputStream();
                String req = "GET / HTTP/1.0\r\n"
                        + "Host: 1.1.1.1\r\n"
                        + "Connection: close\r\n\r\n";
                long t0 = System.currentTimeMillis();
                out.write(req.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                // First response byte = one round trip through
                // the proxy. (The rest of the tiny response is
                // never read — the socket is closed right away.)
                int b = in.read();
                if (b < 0) return -1L;
                return System.currentTimeMillis() - t0;
            } catch (Throwable e) {
                return -1L;
            } finally {
                try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
            }
        });
        try {
            Long ms = f.get(RTT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return ms == null ? -1 : ms;
        } catch (Throwable e) {
            try { f.cancel(true); } catch (Throwable ignored) {}
            return -1;
        } finally {
            one.shutdownNow();
        }
    }

    /** RTT ping a list of servers in parallel, two probes each,
     *  best wins. Only successes, sorted fastest (lowest ms)
     *  first. sb14: `epoch` is the connect epoch that owns
     *  this probe (EPOCH_NONE = never abort); a superseded
     *  probe aborts FAST — the pool is shut down hard instead
     *  of waiting out the 30 s awaitTermination. */
    private List<PingResult> pingTestServers(List<Server> servers, long epoch) {
        List<PingResult> out = new ArrayList<>();
        if (servers == null || servers.isEmpty()) return out;
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(servers.size(), 20));
        List<Future<Long>> futs = new ArrayList<>();
        for (Server s : servers) {
            final Server sv = s;
            futs.add(ex.submit(() -> {
                // Two independent RTT probes, best wins — a
                // single sample can spike; the min is the stable
                // latency, like Speedtest's best sample.
                long a = rttPingMs(sv);
                long b = rttPingMs(sv);
                if (a < 0) return b;
                if (b < 0) return a;
                return Math.min(a, b);
            }));
        }
        ex.shutdown();
        // sb14: poll termination in short slices so a
        // superseded connect aborts within ~500 ms instead of
        // waiting out the full 30 s; the results are discarded.
        // The 60-slice cap preserves the old 30 s ceiling.
        int slices = 0;
        try {
            while (!ex.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                if (!epochAlive(epoch)) { ex.shutdownNow(); return new ArrayList<>(); }
                if (++slices >= 60) { ex.shutdownNow(); break; }
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            return new ArrayList<>();
        } catch (Throwable ignored) {}
        for (int i = 0; i < servers.size(); i++) {
            try {
                Future<Long> f = futs.get(i);
                if (!f.isDone()) continue;
                Long ms = f.get();
                if (ms != null && ms >= 0) out.add(new PingResult(servers.get(i), servers.get(i).region, ms));
            } catch (Throwable ignored) {}
        }
        out.sort((a, b) -> Long.compare(a.ms, b.ms));
        return out;
    }

    /** RTT ping up to PING_MAX_PER_REGION servers of one
     *  region. Empty = no server in this region carried traffic. */
    private List<PingResult> pingRegion(VeePnApi api, String region, String excludeHost, long epoch) {
        try {
            List<Server> servers = getServersCached(api, region);
            List<Server> cand = new ArrayList<>();
            for (Server s : servers) {
                if (excludeHost != null && excludeHost.equals(s.host())) continue;
                cand.add(s);
                if (cand.size() >= PING_MAX_PER_REGION) break;
            }
            if (!epochAlive(epoch)) return new ArrayList<>();
            List<PingResult> res = pingTestServers(cand, epoch);
            for (PingResult r : res) r.region = region;
            return res;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    /** RTT ping every region (up to 2 servers each,
     *  parallel). Only successes, fastest first. */
    private List<PingResult> pingAllRegions(VeePnApi api, List<String> regions, long epoch) {
        List<Server> cand = new ArrayList<>();
        Map<Server, String> serverRegion = new ConcurrentHashMap<>();
        for (String region : regions) {
            try {
                List<Server> servers = getServersCached(api, region);
                int n = 0;
                for (Server s : servers) {
                    cand.add(s); serverRegion.put(s, region);
                    if (++n >= PING_MAX_PER_REGION) break;
                }
            } catch (Throwable ignored) {}
        }
        if (!epochAlive(epoch)) return new ArrayList<>();
        List<PingResult> res = pingTestServers(cand, epoch);
        for (PingResult r : res) r.region = serverRegion.getOrDefault(r.server, r.server.region);
        return res;
    }

    // ---------- emergency fallback only: TCP connect (v1.5 method) ----------

    private long tcpProbeMs(Server s) {
        try {
            String host = s.host();
            if (host.isEmpty()) return -1;
            String ip = InetAddress.getByName(host).getHostAddress();
            long start = System.currentTimeMillis();
            Socket sock = new Socket();
            try {
                sock.connect(new InetSocketAddress(ip, s.port), TCP_TIMEOUT_MS);
            } finally {
                try { sock.close(); } catch (Throwable ignored) {}
            }
            return System.currentTimeMillis() - start;
        } catch (Throwable e) {
            return -1;
        }
    }

    private List<Server> tcpRankedServers(List<Server> servers, long epoch) {
        List<Server> out = new ArrayList<>();
        if (servers == null || servers.isEmpty()) return out;
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(servers.size(), 20));
        Map<Server, Long> probes = new ConcurrentHashMap<>();
        List<Future<?>> futs = new ArrayList<>();
        for (Server s : servers) {
            final Server sv = s;
            futs.add(ex.submit(() -> probes.put(sv, tcpProbeMs(sv))));
        }
        ex.shutdown();
        // sb14: same fast-abort polling as pingTestServers
        // (8 slices x 500 ms preserves the old 4 s ceiling).
        int slices = 0;
        try {
            while (!ex.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                if (!epochAlive(epoch)) { ex.shutdownNow(); return new ArrayList<>(); }
                if (++slices >= 8) { ex.shutdownNow(); break; }
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            return new ArrayList<>();
        } catch (Throwable ignored) {}
        List<Server> cand = new ArrayList<>();
        for (Server s : servers) {
            Long ms = probes.get(s);
            if (ms != null && ms >= 0) cand.add(s);
        }
        cand.sort((a, b) -> Long.compare(
                probes.getOrDefault(a, Long.MAX_VALUE), probes.getOrDefault(b, Long.MAX_VALUE)));
        return cand;
    }

    private void reportPings(List<PingResult> results, List<String> allRegions) {
        try {
            Map<String, Long> best = new HashMap<>();
            for (PingResult r : results) {
                Long cur = best.get(r.region);
                if (cur == null || r.ms < cur) best.put(r.region, r.ms);
            }
            JSONArray arr = new JSONArray();
            if (allRegions != null) {
                for (String region : allRegions) {
                    JSONObject o = new JSONObject();
                    o.put("code", region);
                    o.put("pingMs", best.containsKey(region) ? best.get(region) : -1);
                    arr.put(o);
                }
            } else {
                for (Map.Entry<String, Long> e : best.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("code", e.getKey());
                    o.put("pingMs", e.getValue());
                    arr.put(o);
                }
            }
            eval("window.onPings(" + jsString(arr.toString()) + ")");
        } catch (Throwable ignored) {}
    }

    private List<ConnectCandidate> pingToCandidates(List<PingResult> results, String display, String fallbackRegion) {
        List<ConnectCandidate> out = new ArrayList<>();
        for (PingResult r : results) {
            String disp = display;
            if (display == null) {
                String name = (r.server.regionName != null && !r.server.regionName.isEmpty())
                        ? r.server.regionName : r.region;
                disp = name + " (" + r.region + ")";
            }
            out.add(new ConnectCandidate(r.server, r.region, disp));
        }
        return out;
    }

    private List<ConnectCandidate> serversToCandidates(List<Server> servers, String display, String region) {
        List<ConnectCandidate> out = new ArrayList<>();
        for (Server s : servers) {
            String disp = display;
            if (display == null) {
                String name = (s.regionName != null && !s.regionName.isEmpty()) ? s.regionName : region;
                disp = name + " (" + (s.region != null && !s.region.isEmpty() ? s.region : region) + ")";
            }
            out.add(new ConnectCandidate(s, region, disp));
        }
        return out;
    }

    /** Try candidates in order until the tunnel actually reports
     *  running. Returns true on the first working server. sb14:
     *  a superseded attempt stops here — it must never start a
     *  server for an attempt the user replaced. */
    private boolean tryRankedConnect(List<ConnectCandidate> candidates, long epoch) {
        int tries = 0;
        for (ConnectCandidate c : candidates) {
            if (!epochAlive(epoch)) return false;
            if (tries++ >= 3) break;
            try {
                if (startServiceWithServer(c.server, c.display, c.region, candidates, epoch)) return true;
                if (!epochAlive(epoch)) return false;
                try {
                    Intent d = new Intent(this, VpnTunnelService.class);
                    d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                    startService(d);
                    Thread.sleep(800);
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** Full fallback chain for one region, synchronous. Returns
     *  true if a tunnel is up. Sets lastError on failure. sb14:
     *  every phase boundary checks the epoch — a superseded
     *  attempt returns false SILENTLY (it must NOT touch
     *  connecting/lastError: the new attempt owns them, and
     *  the doConnect wrapper only shows an error for the
     *  current attempt). */
    private boolean connectRegionSync(String region, String display, String excludeHost, long epoch) {
        lastError = "";
        connecting = true;
        connectingSince = System.currentTimeMillis();
        currentRegionCode = region;
        currentDisplay = display;
        fastestModeFlag = false;
        if (excludeHost == null) optimizeCount = 0;
        try {
            VeePnApi api = new VeePnApi(this);
            List<PingResult> ping = pingRegion(api, region, excludeHost, epoch);
            if (!epochAlive(epoch)) return false;
            if (!ping.isEmpty()) {
                reportPings(ping, java.util.Collections.singletonList(region));
                if (tryRankedConnect(pingToCandidates(ping, display, region), epoch)) return true;
            }
            if (!epochAlive(epoch)) return false;
            // Emergency fallback: TCP ranking, then API order.
            List<Server> servers = getServersCached(api, region);
            List<Server> cand = new ArrayList<>();
            for (Server s : servers) {
                if (excludeHost != null && excludeHost.equals(s.host())) continue;
                cand.add(s);
            }
            List<Server> tcp = tcpRankedServers(cand, epoch);
            if (!epochAlive(epoch)) return false;
            if (!tcp.isEmpty() && tryRankedConnect(serversToCandidates(tcp, display, region), epoch)) return true;
            if (!epochAlive(epoch)) return false;
            if (!cand.isEmpty() && tryRankedConnect(serversToCandidates(cand.subList(0, Math.min(3, cand.size())), display, region), epoch)) return true;
            lastError = "Could not connect to servers in this region";
            connecting = false;
            return false;
        } catch (Throwable e) {
            // sb14: only report the failure if this attempt is
            // still current — a superseded one stays silent.
            // sb15 (no-logs audit): the generic message only —
            // never e.getMessage() (raw exception text) in the
            // user-visible status.
            if (epoch == connectEpoch) {
                connecting = false;
                lastError = "Could not connect to servers in this region";
            }
            return false;
        }
    }

    /** Full fallback chain for Fastest, synchronous. sb14:
     *  same epoch checks as connectRegionSync. */
    private boolean connectFastestSync(long epoch) {
        lastError = "";
        connecting = true;
        connectingSince = System.currentTimeMillis();
        optimizeCount = 0;
        fastestModeFlag = true;
        try {
            VeePnApi api = new VeePnApi(this);
            List<String> regions = new ArrayList<>();
            try {
                for (Location l : api.locations()) regions.add(l.region);
            } catch (Throwable ignored) {}
            if (regions.isEmpty()) {
                String[] fb = {"fr-prs", "gb-lnd", "nl", "ru-spb", "sg", "us-or", "us-va"};
                for (String r : fb) regions.add(r);
            }
            if (!epochAlive(epoch)) return false;
            List<PingResult> ping = pingAllRegions(api, regions, epoch);
            if (!epochAlive(epoch)) return false;
            if (!ping.isEmpty()) {
                reportPings(ping, regions);
                if (tryRankedConnect(pingToCandidates(ping, null, null), epoch)) return true;
            }
            if (!epochAlive(epoch)) return false;
            // Emergency fallback: TCP best per region, then API order.
            List<ConnectCandidate> tcpCands = new ArrayList<>();
            for (String region : regions) {
                if (!epochAlive(epoch)) return false;
                try {
                    List<Server> servers = getServersCached(api, region);
                    List<Server> tcp = tcpRankedServers(servers, epoch);
                    if (!epochAlive(epoch)) return false;
                    if (!tcp.isEmpty()) {
                        Server s = tcp.get(0);
                        String name = (s.regionName != null && !s.regionName.isEmpty()) ? s.regionName : region;
                        tcpCands.add(new ConnectCandidate(s, region, name + " (" + region + ")"));
                    }
                } catch (Throwable ignored) {}
            }
            if (!tcpCands.isEmpty() && tryRankedConnect(tcpCands, epoch)) return true;
            if (!epochAlive(epoch)) return false;
            List<ConnectCandidate> apiCands = new ArrayList<>();
            for (String region : regions) {
                try {
                    List<Server> servers = getServersCached(api, region);
                    if (!servers.isEmpty()) {
                        Server s = servers.get(0);
                        String name = (s.regionName != null && !s.regionName.isEmpty()) ? s.regionName : region;
                        apiCands.add(new ConnectCandidate(s, region, name + " (" + region + ")"));
                    }
                } catch (Throwable ignored) {}
            }
            if (!apiCands.isEmpty() && tryRankedConnect(apiCands, epoch)) return true;
            lastError = "Could not connect to any server";
            connecting = false;
            return false;
        } catch (Throwable e) {
            // sb15 (no-logs audit): the generic message only —
            // never e.getMessage() (raw exception text) in the
            // user-visible status.
            if (epoch == connectEpoch) {
                connecting = false;
                lastError = "Could not connect to any server";
            }
            return false;
        }
    }

    private void doConnect(String region, String display, String excludeHost) {
        doConnect(region, display, excludeHost, true);
    }

    /** sb14: every user-initiated connect gets a fresh epoch
     *  (the flow aborts silently if a newer attempt supersedes
     *  it). saveSelection = persist the pick as the last
     *  server on success — false for optimize's
     *  auto-reconnects, which must never overwrite the user's
     *  choice. */
    private void doConnect(String region, String display, String excludeHost, boolean saveSelection) {
        final long epoch = ++connectEpoch;
        new Thread(() -> {
            boolean ok = connectRegionSync(region, display, excludeHost, epoch);
            if (ok && saveSelection) {
                try { AppSettings.setLastSelection(MainActivity.this, region); } catch (Throwable ignored) {}
            }
            // sb14: only the CURRENT attempt may report an
            // error — a superseded one stays silent.
            if (!ok && epoch == connectEpoch) {
                eval("window.onConnectError(" + jsString(lastError) + ")");
            }
        }).start();
    }

    private void doConnectFastest() {
        doConnectFastest(true);
    }

    private void doConnectFastest(boolean saveSelection) {
        final long epoch = ++connectEpoch;
        new Thread(() -> {
            boolean ok = connectFastestSync(epoch);
            if (ok && saveSelection) {
                try { AppSettings.setLastSelection(MainActivity.this, AppSettings.SEL_FASTEST); } catch (Throwable ignored) {}
            }
            if (!ok && epoch == connectEpoch) {
                eval("window.onConnectError(" + jsString(lastError) + ")");
            }
        }).start();
    }

    private boolean startServiceWithServer(Server server, String display, String regionCode,
            List<ConnectCandidate> candidates, long epoch) throws Exception {
        // sb14: never start for a superseded attempt.
        if (!epochAlive(epoch)) return false;
        currentRegionCode = regionCode;
        currentDisplay = display;
        VpnTunnelService.currentServer = server;
        VpnTunnelService.currentRegion = display;
        // sb9: hand the service the region code, the Fastest flag,
        // and extra servers (ranked candidates + warm cache) so
        // SingBoxConfig can build the per-region outbounds and
        // the urltest group. No new network calls here: the cache
        // is whatever the launch auto-test fetched (60 s TTL).
        VpnTunnelService.connectRegionCode = regionCode == null ? "" : regionCode;
        VpnTunnelService.fastestMode = fastestModeFlag;
        // sb10: hand the service the connection mode and the
        // split-tunneling settings for this connect.
        VpnTunnelService.proxyMode = AppSettings.isProxyMode(this);
        VpnTunnelService.splitMode = AppSettings.getSplitMode(this);
        VpnTunnelService.splitPackages = AppSettings.getSplitPackages(this);
        List<Server> extras = new ArrayList<>();
        if (candidates != null) {
            for (ConnectCandidate c : candidates) {
                if (c != null && c.server != null) extras.add(c.server);
            }
        }
        try {
            for (Map.Entry<String, CachedServers> e : serverCache.entrySet()) {
                if (e.getValue() != null && e.getValue().servers != null
                        && !e.getValue().servers.isEmpty()) {
                    extras.add(e.getValue().servers.get(0));
                }
            }
        } catch (Throwable ignored) {}
        VpnTunnelService.extraServers = extras;
        // sb14: if a tunnel is already up, stop it first and wait
        // for it to go down — startTunnel() is a no-op while
        // running, so starting over it would silently keep the
        // old server.
        // v1.9-proxyfix: this used to apply only when the running
        // server was DIFFERENT; the same-server case returned
        // success without restarting ("Already up for this exact
        // server — nothing to start"). A Connect on a stale
        // running service — e.g. the proxy after another VPN
        // (Nord) came and went, whose outbound/DNS state is dead
        // — then never restarted sing-box, so the dead proxy
        // stayed dead however often the user tapped Connect. A
        // Connect now always restarts the service (stop + fresh
        // start). No automatic path depends on the old no-op:
        // optimize / reconnectAfterNetworkChange disconnect first,
        // and the Fastest re-test only ever switches to a
        // DIFFERENT server — so the only caller that reaches here
        // with the service already up is a (re)connect to the
        // same server, where a restart is exactly what is wanted.
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
        Intent i = new Intent(this, VpnTunnelService.class);
        i.setAction(VpnTunnelService.ACTION_CONNECT);
        i.putExtra(VpnTunnelService.EXTRA_REGION, VpnTunnelService.currentRegion);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        for (int k = 0; k < 10; k++) {
            Thread.sleep(500);
            if (!epochAlive(epoch)) return false;
            if (VpnTunnelService.running) break;
        }
        if (!VpnTunnelService.running) return false;
        // sb14: the tunnel that came up may belong to a
        // superseded attempt (its start finished after this
        // attempt began) — only claim success for THIS server
        // and THIS epoch. runningServer is what startTunnel()
        // actually built, not the intended currentServer.
        if (!epochAlive(epoch)) return false;
        if (VpnTunnelService.runningServer != server) return false;
        watchdogStop = false;
        startWatchdog();
        startSbPingPoller();
        // sb15: Fastest re-test only while on Fastest in VPN mode.
        if (fastestModeFlag && !VpnTunnelService.proxyMode) startFastestRetest();
        new Bridge().checkExitIp();
        return true;
    }

    /** sb11: live ping of the connected server, re-measured
     *  every 30 s while connected. The DISPLAYED ping is the
     *  RTT ping (a request/response on an established CONNECT
     *  tunnel to the live server, over the direct path — the
     *  app is excluded from the TUN, so this works while
     *  connected, in both modes). The Clash API delay is NO
     *  LONGER used for the display at all (it measures a full
     *  HTTPS fetch — the same multi-RTT error as the old ping);
     *  Clash stays only for sing-box's internal urltest use. A
     *  failed probe leaves lastSbPingMs at -1 and the JS keeps
     *  the previous displayed value. */
    private synchronized void startSbPingPoller() {
        if (sbPingThread != null && sbPingThread.isAlive()) return;
        lastSbPingMs = -1;
        sbPingThread = new Thread(() -> {
            while (!watchdogStop && VpnTunnelService.running) {
                try {
                    long ms = -1;
                    Server cur = VpnTunnelService.currentServer;
                    if (cur != null) ms = rttPingMs(cur);
                    if (ms < 0 && cur != null) ms = rttPingMs(cur);
                    lastSbPingMs = ms;
                } catch (Throwable ignored) {
                    lastSbPingMs = -1;
                }
                try { Thread.sleep(30000); } catch (Throwable e) { break; }
            }
        }, "arcveepn-sb-ping");
        sbPingThread.setDaemon(true);
        sbPingThread.start();
    }

    /** sb15: Fastest periodic re-test + auto-switch. One round:
     *  re-test ALL regions with the same RTT ping as the ranking
     *  (2 probes per server, best wins), INCLUDING a fresh
     *  measurement of the currently connected server in the same
     *  round, then switch ONLY if a DIFFERENT server is clearly
     *  better — at least FASTEST_SWITCH_MIN_GAIN_MS faster AND at
     *  least 25% lower ping (both conditions, so noise never
     *  flaps the server). The switch goes through the existing
     *  disconnect + reconnect path (doConnectFastest(false)), so
     *  the selection stays "fastest", last_selection is untouched,
     *  and future re-tests keep working. If the current server is
     *  still best, or the best is within the margin, stay — no
     *  switch, no reconnect. Never more than one switch per
     *  interval: a round runs, then the thread sleeps the full
     *  interval. Runs on a background thread, in parallel like
     *  the ranking, and never blocks the UI. Every result and
     *  decision is logged to the silent ConnectLog only. */
    private synchronized void startFastestRetest() {
        if (fastestRetestThread != null && fastestRetestThread.isAlive()) return;
        fastestRetestThread = new Thread(() -> {
            while (!watchdogStop && VpnTunnelService.running
                    && fastestModeFlag && !VpnTunnelService.proxyMode) {
                try { Thread.sleep(FASTEST_RETEST_INTERVAL_MS); } catch (Throwable e) { break; }
                if (watchdogStop || !VpnTunnelService.running) break;
                if (!fastestModeFlag || VpnTunnelService.proxyMode) break;
                if (connecting) continue; // user mid-connect — skip this round
                try {
                    retestFastestOnce();
                } catch (Throwable ignored) {}
            }
        }, "arcveepn-fastest-retest");
        fastestRetestThread.setDaemon(true);
        fastestRetestThread.start();
    }

    private void retestFastestOnce() {
        Server cur = VpnTunnelService.currentServer;
        if (cur == null) return;
        try {
            VeePnApi api = new VeePnApi(this);
            List<String> regions = new ArrayList<>();
            try {
                for (Location l : api.locations()) regions.add(l.region);
            } catch (Throwable ignored) {}
            if (regions.isEmpty()) {
                String[] fb = {"fr-prs", "gb-lnd", "nl", "ru-spb", "sg", "us-or", "us-va"};
                for (String r : fb) regions.add(r);
            }
            List<PingResult> ping = pingAllRegions(api, regions, EPOCH_NONE);
            // Guards again after the (slow) round: the user may
            // have disconnected, switched region, or started a
            // connect while the round was running.
            if (!VpnTunnelService.running || !fastestModeFlag
                    || VpnTunnelService.proxyMode || connecting) return;
            if (ping.isEmpty()) {
                ConnectLog.log(this, "fastest retest: no server answered, staying");
                return;
            }
            // Fresh measurement of the currently connected server
            // in the same round (it may not be among the ranked
            // candidates — the cache is re-fetched after 60 s, so
            // the Server objects are new; compare by host+port).
            long curMs = -1;
            for (PingResult r : ping) {
                if (sameServer(r.server, cur)) { curMs = r.ms; break; }
            }
            if (curMs < 0) {
                List<PingResult> one = pingTestServers(
                        java.util.Collections.singletonList(cur), EPOCH_NONE);
                if (!one.isEmpty()) curMs = one.get(0).ms;
            }
            if (curMs < 0) {
                ConnectLog.log(this, "fastest retest: current server unmeasurable, staying");
                return;
            }
            PingResult best = ping.get(0); // sorted fastest first
            ConnectLog.log(this, "fastest retest: best=" + best.server.host()
                    + " " + best.ms + "ms current=" + cur.host() + " " + curMs + "ms");
            if (sameServer(best.server, cur)) {
                ConnectLog.log(this, "fastest retest: current still best, staying");
                return;
            }
            boolean clearlyBetter = best.ms <= curMs - FASTEST_SWITCH_MIN_GAIN_MS
                    && best.ms * 4 <= curMs * 3; // at least 25% lower
            if (!clearlyBetter) {
                ConnectLog.log(this, "fastest retest: best within margin, staying");
                return;
            }
            ConnectLog.log(this, "fastest retest: switching to " + best.server.host()
                    + " (" + best.ms + "ms vs " + curMs + "ms)");
            // Switch via the existing path. doConnectFastest(false)
            // does NOT touch last_selection (stays "fastest") and
            // is not treated as a user-initiated connect.
            doConnectFastest(false);
        } catch (Throwable ignored) {}
    }

    /** Same upstream server (host + port). The ping cache is
     *  re-fetched after 60 s, so the Server objects are new
     *  instances — identity (==) cannot be used across rounds. */
    private static boolean sameServer(Server a, Server b) {
        if (a == null || b == null) return false;
        String ha = a.host(), hb = b.host();
        return ha != null && ha.equals(hb) && a.port == b.port;
    }

    /** sb15: re-establish the tunnel after a real underlying-
     *  network change (debounced, both modes, connected only).
     *  v1.9-proxyfix: proxy mode included — a change of the
     *  underlying network, or of another VPN's presence (see
     *  VpnTunnelService.vpnPresenceSuffix), re-establishes the
     *  proxy the same way as the VPN. The old tunnel's sockets
     *  are on the old network, so it must be re-established
     *  either way — but the server only ever changes in Fastest
     *  mode:
     *  - Fastest: full fresh re-test of all regions + connect to
     *    the overall best server (no margin needed — a reconnect
     *    is required anyway; if the best is the same server it
     *    still reconnects). last_selection stays "fastest".
     *  - Fixed region: re-establish the SAME selected server —
     *    no re-test, no ranking, no switch. The server never
     *    changes without the user choosing it. last_selection
     *    stays that region.
     *  Runs on a background thread; the decision is logged to the
     *  silent ConnectLog only. */
    private void reconnectAfterNetworkChange() {
        final long epoch = ++connectEpoch;
        final boolean fastest = fastestModeFlag;
        final String region = currentRegionCode;
        final String display = currentDisplay;
        final Server cur = VpnTunnelService.currentServer;
        ConnectLog.log(this, "netchange reconnect fastest=" + fastest
                + " region=" + region);
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
                if (fastest) {
                    // Full fresh re-test + best server. The (false)
                    // means: do NOT touch last_selection.
                    doConnectFastest(false);
                    return;
                }
                if (cur == null || region == null) {
                    ConnectLog.log(MainActivity.this,
                            "netchange: no current server, abort");
                    return;
                }
                connecting = true;
                connectingSince = System.currentTimeMillis();
                currentRegionCode = region;
                currentDisplay = display;
                fastestModeFlag = false;
                lastError = "";
                try {
                    boolean ok = startServiceWithServer(cur, display, region, null, epoch);
                    if (!ok && epoch == connectEpoch) {
                        connecting = false;
                        lastError = "Could not connect to servers in this region";
                        eval("window.onConnectError(" + jsString(lastError) + ")");
                    }
                } catch (Throwable e) {
                    if (epoch == connectEpoch) {
                        connecting = false;
                        lastError = "Could not connect to servers in this region";
                        eval("window.onConnectError(" + jsString(lastError) + ")");
                    }
                }
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
