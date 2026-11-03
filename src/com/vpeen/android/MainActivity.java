package com.vpeen.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
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
import java.io.OutputStream;
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

    // v1.7 real tunnel ping: tiny HTTP pages, verified live.
    // example.com = 577 bytes, neverssl.com = ~4 KB, both HTTP
    // only (no redirect, no second TLS handshake to the target).
    private static final String[] PING_HOSTS = {"example.com", "neverssl.com"};
    private static final int PING_TIMEOUT_MS = 8000;
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

    private static class CachedServers {
        List<Server> servers; long at;
        CachedServers(List<Server> s, long a) { servers = s; at = a; }
    }
    private final Map<String, CachedServers> serverCache = new ConcurrentHashMap<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://t.me/ProxyArc")));
                        } catch (Exception ignored) {}
                    }
                    return true;
                }
                return false;
            }
        });
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
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
            try { webView.evaluateJavascript(js, null); } catch (Exception ignored) {}
        });
    }

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
                } catch (Exception e) {
                    eval("window.onLocationsError(" + jsString(e.getMessage()) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void connect(final String region, final String display) {
            runOnUiThread(() -> {
                Intent prep = VpnService.prepare(MainActivity.this);
                if (prep != null) {
                    pendingRegion = region;
                    pendingDisplay = display;
                    startActivityForResult(prep, REQ_VPN);
                } else {
                    doConnect(region, display, null);
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            connecting = false;
            lastError = "";
            watchdogStop = true;
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, VpnTunnelService.class);
                i.setAction(VpnTunnelService.ACTION_DISCONNECT);
                startService(i);
                lastIp = "—";
            });
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
                    Intent d = new Intent(MainActivity.this, VpnTunnelService.class);
                    d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                    startService(d);
                    Thread.sleep(1500);
                    // Same region first; if it cannot connect at
                    // all, fall back to Fastest across all regions.
                    if (!connectRegionSync(region, display, exclude)) {
                        doConnectFastest();
                    }
                } catch (Exception ignored) {}
            }).start();
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
                    } catch (Exception ignored) {}
                    if (regions.isEmpty()) {
                        String[] fb = {"fr-prs", "gb-lnd", "nl", "ru-spb", "sg", "us-or", "us-va"};
                        for (String r : fb) regions.add(r);
                    }
                    ExecutorService ex = Executors.newFixedThreadPool(Math.min(regions.size(), 7));
                    for (String region : regions) {
                        final String reg = region;
                        ex.submit(() -> {
                            List<PingResult> res = pingRegion(api, reg, null, PING_HOSTS[0]);
                            if (res.isEmpty()) res = pingRegion(api, reg, null, PING_HOSTS[1]);
                            long best = res.isEmpty() ? -1 : res.get(0).ms;
                            JSONArray arr = new JSONArray();
                            try {
                                JSONObject o = new JSONObject();
                                o.put("code", reg);
                                o.put("pingMs", best);
                                arr.put(o);
                            } catch (Exception ignored) {}
                            eval("window.onPings(" + jsString(arr.toString()) + ")");
                        });
                    }
                    ex.shutdown();
                } catch (Exception ignored) {}
            }).start();
        }

        @JavascriptInterface
        public void connectFastest() {
            runOnUiThread(() -> {
                Intent prep = VpnService.prepare(MainActivity.this);
                if (prep != null) {
                    pendingRegion = "__fastest__";
                    pendingDisplay = "Fastest — Auto";
                    startActivityForResult(prep, REQ_VPN);
                } else {
                    doConnectFastest();
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
                } catch (Exception e) {
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
                    if (lastError == null || lastError.isEmpty()) lastError = "اتصال برقرار نشد.";
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
                o.put("ip", lastIp);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
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
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK && pendingRegion != null) {
                String r = pendingRegion, d = pendingDisplay;
                pendingRegion = null; pendingDisplay = null;
                if ("__fastest__".equals(r)) doConnectFastest();
                else doConnect(r, d, null);
            } else {
                lastError = "مجوز VPN داده نشد.";
                eval("window.onConnectError(" + jsString(lastError) + ")");
            }
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

    /** Real end-to-end ping through one server's tunnel:
     *  timer starts BEFORE CONNECT (so tunnel setup counts),
     *  then TLS to the proxy + CONNECT + auth, then a tiny HTTP
     *  GET through the tunnel. Returns ms, or -1 if the server
     *  cannot carry real traffic at all. */
    private long tunnelPingMs(Server s, String testHost) {
        Socket tunnel = null;
        try {
            long start = System.currentTimeMillis();
            tunnel = Upstream.connectViaServer(s, testHost, 80, null);
            tunnel.setSoTimeout(PING_TIMEOUT_MS);
            OutputStream out = tunnel.getOutputStream();
            String req = "GET / HTTP/1.1\r\n"
                    + "Host: " + testHost + "\r\n"
                    + "User-Agent: ArcVeePN-ping\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = tunnel.getInputStream();
            StringBuilder hdr = new StringBuilder();
            int prev = -1, cur;
            boolean headersDone = false;
            while ((cur = in.read()) != -1) {
                hdr.append((char) cur);
                if (prev == '\n' && cur == '\n') { headersDone = true; break; }
                if (hdr.length() > 4 && hdr.substring(hdr.length() - 4).equals("\r\n\r\n")) {
                    headersDone = true; break;
                }
                prev = cur;
                if (hdr.length() > 8192) return -1;
            }
            if (!headersDone) return -1;
            String firstLine = hdr.toString().split("\r?\n", 2)[0];
            if (!firstLine.contains(" 200")) return -1;
            // Drain the tiny body so the time covers a real response.
            byte[] buf = new byte[4096];
            while (in.read(buf) != -1) {
                if (System.currentTimeMillis() - start > PING_TIMEOUT_MS + 10000) return -1;
            }
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        } finally {
            try { if (tunnel != null) tunnel.close(); } catch (Exception ignored) {}
        }
    }

    /** Tunnel-ping a list of servers in parallel. Only successes,
     *  sorted fastest (lowest ms) first. */
    private List<PingResult> pingTestServers(List<Server> servers, String host) {
        List<PingResult> out = new ArrayList<>();
        if (servers == null || servers.isEmpty()) return out;
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(servers.size(), 20));
        List<Future<Long>> futs = new ArrayList<>();
        for (Server s : servers) {
            final Server sv = s;
            futs.add(ex.submit(() -> tunnelPingMs(sv, host)));
        }
        ex.shutdown();
        try { ex.awaitTermination(30000, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        for (int i = 0; i < servers.size(); i++) {
            try {
                Future<Long> f = futs.get(i);
                if (!f.isDone()) continue;
                Long ms = f.get();
                if (ms != null && ms >= 0) out.add(new PingResult(servers.get(i), servers.get(i).region, ms));
            } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Long.compare(a.ms, b.ms));
        return out;
    }

    /** Tunnel-ping up to PING_MAX_PER_REGION servers of one region.
     *  Empty = no server in this region carried real traffic. */
    private List<PingResult> pingRegion(VeePnApi api, String region, String excludeHost, String host) {
        try {
            List<Server> servers = getServersCached(api, region);
            List<Server> cand = new ArrayList<>();
            for (Server s : servers) {
                if (excludeHost != null && excludeHost.equals(s.host())) continue;
                cand.add(s);
                if (cand.size() >= PING_MAX_PER_REGION) break;
            }
            List<PingResult> res = pingTestServers(cand, host);
            for (PingResult r : res) r.region = region;
            return res;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Tunnel-ping every region (up to 2 servers each, parallel).
     *  Only successes, fastest first. */
    private List<PingResult> pingAllRegions(VeePnApi api, List<String> regions, String host) {
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
            } catch (Exception ignored) {}
        }
        List<PingResult> res = pingTestServers(cand, host);
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
                try { sock.close(); } catch (Exception ignored) {}
            }
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }

    private List<Server> tcpRankedServers(List<Server> servers) {
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
        try { ex.awaitTermination(4000, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}
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
     *  running. Returns true on the first working server. */
    private boolean tryRankedConnect(List<ConnectCandidate> candidates) {
        int tries = 0;
        for (ConnectCandidate c : candidates) {
            if (tries++ >= 3) break;
            try {
                if (startServiceWithServer(c.server, c.display, c.region)) return true;
                try {
                    Intent d = new Intent(this, VpnTunnelService.class);
                    d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                    startService(d);
                    Thread.sleep(800);
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** Full fallback chain for one region, synchronous. Returns
     *  true if a tunnel is up. Sets lastError on failure. */
    private boolean connectRegionSync(String region, String display, String excludeHost) {
        lastError = "";
        connecting = true;
        connectingSince = System.currentTimeMillis();
        currentRegionCode = region;
        currentDisplay = display;
        if (excludeHost == null) optimizeCount = 0;
        try {
            VeePnApi api = new VeePnApi(this);
            List<PingResult> ping = pingRegion(api, region, excludeHost, PING_HOSTS[0]);
            if (ping.isEmpty()) ping = pingRegion(api, region, excludeHost, PING_HOSTS[1]);
            if (!ping.isEmpty()) {
                reportPings(ping, java.util.Collections.singletonList(region));
                if (tryRankedConnect(pingToCandidates(ping, display, region))) return true;
            }
            // Emergency fallback: TCP ranking, then API order.
            List<Server> servers = getServersCached(api, region);
            List<Server> cand = new ArrayList<>();
            for (Server s : servers) {
                if (excludeHost != null && excludeHost.equals(s.host())) continue;
                cand.add(s);
            }
            List<Server> tcp = tcpRankedServers(cand);
            if (!tcp.isEmpty() && tryRankedConnect(serversToCandidates(tcp, display, region))) return true;
            if (!cand.isEmpty() && tryRankedConnect(serversToCandidates(cand.subList(0, Math.min(3, cand.size())), display, region))) return true;
            lastError = "اتصال به سرورهای این منطقه برقرار نشد";
            connecting = false;
            return false;
        } catch (Exception e) {
            connecting = false;
            lastError = e.getMessage();
            return false;
        }
    }

    /** Full fallback chain for Fastest, synchronous. */
    private boolean connectFastestSync() {
        lastError = "";
        connecting = true;
        connectingSince = System.currentTimeMillis();
        optimizeCount = 0;
        try {
            VeePnApi api = new VeePnApi(this);
            List<String> regions = new ArrayList<>();
            try {
                for (Location l : api.locations()) regions.add(l.region);
            } catch (Exception ignored) {}
            if (regions.isEmpty()) {
                String[] fb = {"fr-prs", "gb-lnd", "nl", "ru-spb", "sg", "us-or", "us-va"};
                for (String r : fb) regions.add(r);
            }
            List<PingResult> ping = pingAllRegions(api, regions, PING_HOSTS[0]);
            if (ping.isEmpty()) ping = pingAllRegions(api, regions, PING_HOSTS[1]);
            if (!ping.isEmpty()) {
                reportPings(ping, regions);
                if (tryRankedConnect(pingToCandidates(ping, null, null))) return true;
            }
            // Emergency fallback: TCP best per region, then API order.
            List<ConnectCandidate> tcpCands = new ArrayList<>();
            for (String region : regions) {
                try {
                    List<Server> servers = getServersCached(api, region);
                    List<Server> tcp = tcpRankedServers(servers);
                    if (!tcp.isEmpty()) {
                        Server s = tcp.get(0);
                        String name = (s.regionName != null && !s.regionName.isEmpty()) ? s.regionName : region;
                        tcpCands.add(new ConnectCandidate(s, region, name + " (" + region + ")"));
                    }
                } catch (Exception ignored) {}
            }
            if (!tcpCands.isEmpty() && tryRankedConnect(tcpCands)) return true;
            List<ConnectCandidate> apiCands = new ArrayList<>();
            for (String region : regions) {
                try {
                    List<Server> servers = getServersCached(api, region);
                    if (!servers.isEmpty()) {
                        Server s = servers.get(0);
                        String name = (s.regionName != null && !s.regionName.isEmpty()) ? s.regionName : region;
                        apiCands.add(new ConnectCandidate(s, region, name + " (" + region + ")"));
                    }
                } catch (Exception ignored) {}
            }
            if (!apiCands.isEmpty() && tryRankedConnect(apiCands)) return true;
            lastError = "اتصال به هیچ سروری برقرار نشد";
            connecting = false;
            return false;
        } catch (Exception e) {
            connecting = false;
            lastError = e.getMessage();
            return false;
        }
    }

    private void doConnect(String region, String display, String excludeHost) {
        new Thread(() -> {
            if (!connectRegionSync(region, display, excludeHost)) {
                eval("window.onConnectError(" + jsString(lastError) + ")");
            }
        }).start();
    }

    private void doConnectFastest() {
        new Thread(() -> {
            if (!connectFastestSync()) {
                eval("window.onConnectError(" + jsString(lastError) + ")");
            }
        }).start();
    }

    private boolean startServiceWithServer(Server server, String display, String regionCode) throws Exception {
        currentRegionCode = regionCode;
        currentDisplay = display;
        VpnTunnelService.currentServer = server;
        VpnTunnelService.currentRegion = display;
        Intent i = new Intent(this, VpnTunnelService.class);
        i.setAction(VpnTunnelService.ACTION_CONNECT);
        i.putExtra(VpnTunnelService.EXTRA_REGION, VpnTunnelService.currentRegion);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        for (int k = 0; k < 10; k++) {
            Thread.sleep(500);
            if (VpnTunnelService.running) break;
        }
        if (!VpnTunnelService.running) return false;
        watchdogStop = false;
        startWatchdog();
        new Bridge().checkExitIp();
        return true;
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
                try { Thread.sleep(30000); } catch (Exception e) { break; }
                if (watchdogStop || !VpnTunnelService.running) break;
                try {
                    long[] st = VpnTunnelService.tunnelStats();
                    long total = (st.length > 3 ? st[1] + st[3] : 0);
                    long now = System.currentTimeMillis();
                    if (prevBytes < 0) { prevBytes = total; lastChange = now; continue; }
                    if (total != prevBytes) { prevBytes = total; lastChange = now; continue; }
                    if (VpnTunnelService.connectedAtMs > 0
                            && now - VpnTunnelService.connectedAtMs > 60000
                            && now - lastChange > 90000) {
                        lastChange = now;
                        new Bridge().optimize();
                    }
                } catch (Exception ignored) {}
            }
        }, "arcveepn-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }
}
