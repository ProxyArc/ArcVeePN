package com.vpeen.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 3.1 fix4 — the HEADLESS connect/disconnect path for the
 *  home-screen widget and the Quick Settings tile.
 *
 *  fix3's tap opened MainActivity; the user explicitly wants
 *  the tap to connect DIRECTLY, from the widget/tile itself,
 *  without opening the app.
 *
 *  WHY the direct start is allowed: the Android
 *  foreground-service background-start rules list as an
 *  explicit exemption "the user performs an action on a UI
 *  element related to your app. For example, they might
 *  interact with a bubble, notification, widget, or
 *  activity." A widget/tile tap is exactly that. Arman
 *  VPN's QuickTile does the same — a direct service start
 *  from onClick, with the app-open as the fallback.
 *
 *  FALLBACK: if the direct start is refused
 *  (ForegroundServiceStartNotAllowedException) or anything
 *  in the chain fails — no VPN permission yet, Firefox
 *  login lost, no servers at all — the app opens with the
 *  auto-toggle extra, so the tap behaves exactly like the
 *  same tap inside the app (including the VPN-permission
 *  flow, the Firefox login dialog, and the generic error
 *  message). The fallback is the only case where the app
 *  opens; a successful direct tap never opens it.
 *
 *  All entry points return immediately and do the work on
 *  a background thread. A widget tap's BroadcastReceiver
 *  PendingResult (goAsync) is finished as soon as the
 *  foreground service has been started (or the fallback
 *  fired) — the tunnel itself is then watched by the
 *  service, and the service pushes widget/tile updates on
 *  connect/disconnect.
 *
 *  Server resolution prefers the PERSISTED cache (the app
 *  refreshes it on every open), so a tap is fast; the
 *  network is fetched only when the cache is empty. */
public final class DirectConnect {

    private DirectConnect() {}

    private static final int PING_TIMEOUT_MS = 3000;
    private static final int FASTEST_SWEEP_BUDGET_MS = 4000;
    private static final int MAX_LIST_ROWS = 200;
    /** 3.1 fix5: how long a direct connect waits for the
     *  tunnel to come up before giving up (without opening
     *  the app — see connectResolved). 5 s was too short
     *  on a slow network. */
    static final long TUNNEL_WAIT_MS = 15000;

    /** Opens the app with the auto-toggle extra (the
     *  fallback). Implementations decide HOW: the widget
     *  uses a plain startActivity; the tile collapses the
     *  QS panel as it opens. MainActivity routes a
     *  logged-out Firefox choice to the login dialog. */
    public interface Opener {
        void openApp(Context ctx);
    }

    /** The widget's opener: plain startActivity. */
    public static Opener activityOpener() {
        return new Opener() {
            @Override public void openApp(Context ctx) {
                openMainToggle(ctx);
            }
        };
    }

    /** Open MainActivity with the auto-toggle extra. */
    static void openMainToggle(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.setAction(MainActivity.ACTION_WIDGET_TOGGLE);
            i.putExtra(MainActivity.EXTRA_AUTO_TOGGLE, true);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    // ---- entry points (return immediately) ----

    /** Widget/tile tap: connect to the last server (or the
     *  best, when Fastest is selected), or disconnect when
     *  already connected. */
    public static void toggle(
            Context ctx, BroadcastReceiver.PendingResult pending, Opener opener) {
        Task t = new Task(ctx, pending, opener == null ? activityOpener() : opener);
        new Thread(t::toggle, "arcveepn-direct").start();
    }

    /** Widget row tap: connect to one specific server. */
    public static void connectHost(
            Context ctx, String host, String proto,
            BroadcastReceiver.PendingResult pending, Opener opener) {
        Task t = new Task(ctx, pending, opener == null ? activityOpener() : opener);
        final String h = host;
        final String p = proto;
        new Thread(() -> t.connectHost(h, p), "arcveepn-direct").start();
    }

    /** The large widget's ping-test button: re-rank the list
     *  by ping and refresh the widget. */
    public static void runPingSweep(
            Context ctx, BroadcastReceiver.PendingResult pending) {
        new Thread(() -> {
            try {
                runPingSweepSync(ctx.getApplicationContext(),
                        loadServers(ctx.getApplicationContext()),
                        FASTEST_SWEEP_BUDGET_MS);
                try { ArcVeePNWidget.notifyDataChanged(ctx.getApplicationContext()); }
                catch (Throwable ignored) {}
                try { ArcVeePNWidget.updateAll(ctx.getApplicationContext()); }
                catch (Throwable ignored) {}
            } catch (Throwable ignored) {
            } finally {
                try { if (pending != null) pending.finish(); } catch (Throwable ignored) {}
            }
        }, "arcveepn-widget-ping").start();
    }

    // ---- the task (runs on a background thread) ----

    private static final class Task {
        private final Context ctx;
        private final BroadcastReceiver.PendingResult pending;
        private final Opener opener;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        Task(Context ctx, BroadcastReceiver.PendingResult pending, Opener opener) {
            this.ctx = ctx.getApplicationContext();
            this.pending = pending;
            this.opener = opener;
        }

        void finishPending() {
            if (finished.compareAndSet(false, true)) {
                try { if (pending != null) pending.finish(); } catch (Throwable ignored) {}
            }
        }

        /** Open the app — ONLY on a definite failure (no VPN
         *  permission yet, Firefox login lost, no servers at
         *  all). 3.1 fix6: every app-open is logged with its
         *  reason in the silent connect.log, so a future
         *  "it opened the app" report names the exact branch.
         *  A slow tunnel, a slow stop, or a stale row NEVER
         *  opens the app (see below). */
        void fallback(String reason) {
            try { ConnectLog.log(ctx, "direct fallback: " + reason); }
            catch (Throwable ignored) {}
            try { opener.openApp(ctx); } catch (Throwable ignored) {}
            finishPending();
        }

        void toggle() {
            try {
                WidgetConnect.Decision d = WidgetConnect.decide(
                        VpnTunnelService.running,
                        AppSettings.isFirefoxMode(ctx),
                        FirefoxAuth.isLoggedIn(ctx),
                        AppSettings.getLastSelection(ctx));
                switch (d.action) {
                    case WidgetConnect.ACTION_DISCONNECT:
                        disconnect();
                        return;
                    case WidgetConnect.ACTION_OPEN_LOGIN:
                        fallback("firefox login lost");
                        return;
                    case WidgetConnect.ACTION_CONNECT_SERVER:
                        connectHost(WidgetConnect.hostOf(d.server), null);
                        return;
                    case WidgetConnect.ACTION_CONNECT_FASTEST:
                    default:
                        connectFastest();
                        return;
                }
            } catch (Throwable t) {
                fallback("toggle threw " + t.getClass().getSimpleName());
            }
        }

        void disconnect() {
            try {
                Intent i = new Intent(ctx, VpnTunnelService.class);
                i.setAction(VpnTunnelService.ACTION_DISCONNECT);
                ctx.startService(i);
            } catch (Throwable ignored) {}
            finishPending();
            try { Thread.sleep(300); } catch (InterruptedException e) { }
            try { ArcVeePNWidget.updateAll(ctx); } catch (Throwable ignored) {}
            try { ArcVeePNTileService.requestTileUpdate(ctx); } catch (Throwable ignored) {}
        }

        void connectHost(String host, String proto) {
            try {
                // 3.1 fix6: a row tap resolves from the widget's
                // OWN list first (the exact rows the user
                // tapped), so a stale full cache can never make
                // the tap miss. A resolve miss is NOT a reason
                // to open the app: the list may have rotated
                // between the widget build and the tap — refresh
                // the widget so the next tap sees fresh rows,
                // and stay put.
                Server server = resolveServer(host, proto);
                if (server == null) {
                    try {
                        ConnectLog.log(ctx, "direct resolve miss host=" + host
                                + " proto=" + proto + " (widget refresh)");
                    } catch (Throwable ignored) {}
                    try { ArcVeePNWidget.updateAll(ctx); } catch (Throwable ignored) {}
                    finishPending();
                    return;
                }
                try { AppSettings.setLastSelection(ctx, "sub:" + server.ip); }
                catch (Throwable ignored) {}
                connectResolved(server);
            } catch (Throwable t) {
                fallback("connectHost threw " + t.getClass().getSimpleName());
            }
        }

        void connectFastest() {
            try {
                try { AppSettings.setLastSelection(ctx, AppSettings.SEL_FASTEST); }
                catch (Throwable ignored) {}
                List<Server> list = loadServers(ctx);
                if (list == null || list.isEmpty()) { fallback("no servers for fastest"); return; }
                Map<String, Long> pings = AppSettings.getWidgetPings(ctx);
                Server best = WidgetList.bestOf(list, pings);
                if (best == null) {
                    runPingSweepSync(ctx, list, FASTEST_SWEEP_BUDGET_MS);
                    pings = AppSettings.getWidgetPings(ctx);
                    best = WidgetList.bestOf(list, pings);
                }
                if (best == null) best = list.get(0);
                connectResolved(best);
            } catch (Throwable t) {
                fallback("connectFastest threw " + t.getClass().getSimpleName());
            }
        }

        /** The server for a host (ip or Firefox hostname),
         *  from the current source's list. 3.1 fix6: the
         *  widget's OWN list is tried first (see
         *  resolveServerFromLists below) — the rows the
         *  user tapped — then the full list, so a cache
         *  that rotated between the widget build and the
         *  tap still resolves. */
        Server resolveServer(String host, String proto) {
            try {
                if (host == null || host.isEmpty()) return null;
                List<Server> list = loadServers(ctx);
                List<Server> widget = widgetServers(ctx);
                return resolveServerFromLists(host, proto, list, widget);
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** Start the tunnel for a resolved server, directly,
         *  without opening any activity. */
        void connectResolved(Server server) {
            try {
                try { ConnectLog.clear(ctx); } catch (Throwable ignored) {}
                try { ConnectLog.log(ctx, "direct connect begin host=" + server.ip); }
                catch (Throwable ignored) {}
                // Stop a running tunnel first (same as the app's
                // own connect path). 3.1 fix6: the wait is 5 s
                // (was 2 s — stopTunnel() is synchronized and
                // can sit behind an in-flight start/reload, so
                // 2 s was not enough when the user tapped a row
                // while connected, and the timeout OPENED THE
                // APP). A stop that is still running is NOT a
                // reason to open the app: log it and stay put —
                // the user can tap again, and the old tunnel
                // keeps serving meanwhile.
                if (VpnTunnelService.running) {
                    try {
                        Intent d = new Intent(ctx, VpnTunnelService.class);
                        d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                        ctx.startService(d);
                    } catch (Throwable ignored) {}
                    for (int k = 0; k < 50; k++) {
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                        if (!VpnTunnelService.running) break;
                    }
                    if (VpnTunnelService.running) {
                        try { ConnectLog.log(ctx, "direct stop timeout, still running"); }
                        catch (Throwable ignored) {}
                        finishPending();
                        return;
                    }
                }
                // Firefox servers need a live ProxyPass token.
                String ffxPass = null;
                if ("firefox".equals(server.proto)) {
                    try {
                        ffxPass = FirefoxAuth.ensureProxyPass(ctx);
                    } catch (FirefoxAuth.FfxException e) {
                        if (e.reauth) { fallback("firefox reauth"); return; }
                    } catch (Throwable ignored) {}
                    if (ffxPass == null || ffxPass.isEmpty()) { fallback("firefox pass null"); return; }
                }
                // VPN permission (proxy mode needs none). A
                // non-null prepare intent means the user has
                // not granted it yet — only the app can ask.
                if (!AppSettings.isProxyMode(ctx)) {
                    try {
                        Intent prep = VpnService.prepare(ctx);
                        if (prep != null) { fallback("vpn prepare needed"); return; }
                    } catch (Throwable t) {
                        fallback("vpn prepare threw " + t.getClass().getSimpleName());
                        return;
                    }
                }
                String display = (server.flag != null && !server.flag.isEmpty())
                        ? server.flag : server.ip;
                VpnTunnelService.firefoxProxyPass = ffxPass;
                VpnTunnelService.currentServer = server;
                VpnTunnelService.currentRegion = display;
                VpnTunnelService.proxyMode = AppSettings.isProxyMode(ctx);
                VpnTunnelService.splitMode = AppSettings.getSplitMode(ctx);
                VpnTunnelService.splitPackages = AppSettings.getSplitPackages(ctx);
                Intent i = new Intent(ctx, VpnTunnelService.class);
                i.setAction(VpnTunnelService.ACTION_CONNECT);
                i.putExtra(VpnTunnelService.EXTRA_REGION, display);
                try {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                    else ctx.startService(i);
                } catch (Throwable t) {
                    // ForegroundServiceStartNotAllowedException
                    // or anything else: fall back to the app.
                    try { ConnectLog.log(ctx, "direct start failed, fallback"); }
                    catch (Throwable ignored) {}
                    fallback("start threw " + t.getClass().getSimpleName());
                    return;
                }
                finishPending();
                // Wait for the tunnel (up to 15 s), then push
                // widget/tile updates. 3.1 fix5: fix4 waited
                // only 5 s and OPENED THE APP on a timeout —
                // on a slow network the tunnel simply takes
                // longer than 5 s, so every widget/tile tap
                // opened the app. A slow tunnel is NOT a
                // failure: the service pushes widget/tile
                // updates when/if it comes up, and the widget
                // still shows Disconnected so the user can tap
                // again. The fallback fires only on a definite
                // failure (start threw, no VPN permission,
                // reauth needed, no servers at all) — see above.
                if (awaitTunnel(() -> VpnTunnelService.running, TUNNEL_WAIT_MS)) {
                    if (AppSettings.SEL_FASTEST.equals(AppSettings.getLastSelection(ctx))) {
                        try {
                            AppSettings.saveWidgetBest(ctx, server.ip,
                                    server.flag == null ? "" : server.flag);
                        } catch (Throwable ignored) {}
                    }
                    try { ArcVeePNWidget.updateAll(ctx); } catch (Throwable ignored) {}
                    try { ArcVeePNTileService.requestTileUpdate(ctx); } catch (Throwable ignored) {}
                } else {
                    try { ConnectLog.log(ctx, "direct connect timeout, tunnel not up"); }
                    catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                fallback("connectResolved threw " + t.getClass().getSimpleName());
            }
        }
    }

    /** Wait up to timeoutMs for the tunnel to come up,
     *  polling every 500 ms. True when running became true.
     *  A slow tunnel is NOT a failure (see above). The
     *  supplier makes the wait unit-testable: a tunnel that
     *  comes up after 5 s must return true, one that never
     *  comes up must return false after ~timeoutMs. */
    static boolean awaitTunnel(java.util.function.BooleanSupplier isRunning, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try { if (isRunning.getAsBoolean()) return true; }
            catch (Throwable ignored) { return false; }
            if (System.currentTimeMillis() >= deadline) return false;
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
    }

    // ---- server lists (cache first, network only when empty) ----

    /** The WIDGET's server list: the PERSISTED cache ONLY,
     *  parsed WITHOUT the passwords (the widget only shows
     *  flags/names/pings — see SubscriptionApi.parseCacheJsonLight),
     *  and NEVER the network. An empty cache means an empty
     *  list; the app pushes a widget update after every
     *  successful fetch, so the list fills in as soon as the
     *  servers arrive. Never null. */
    static List<Server> widgetServers(Context ctx) {
        try {
            return widgetServersFromCaches(AppSettings.isFirefoxMode(ctx),
                    AppSettings.getCachedSs(ctx),
                    FirefoxAuth.cachedListJson(ctx));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** Pure core of widgetServers: the cached Firefox list
     *  when Firefox mode is on, the light-parsed SS cache
     *  otherwise. Never null. Unit-testable without Android. */
    static List<Server> widgetServersFromCaches(
            boolean firefoxMode, String ssCacheJson, String fxListJson) {
        try {
            if (firefoxMode) {
                if (fxListJson == null || fxListJson.isEmpty()) return new ArrayList<>();
                return FirefoxAuth.parseServerList(fxListJson);
            }
            return SubscriptionApi.parseCacheJsonLight(ssCacheJson);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** Pure resolution (no Context, no network): try the
     *  widget list first (the exact rows on screen), then
     *  the full list. Each list is tried proto-exact
     *  first, then host-only — a row whose proto the
     *  widget recorded as null (or that differs after a
     *  source switch) still resolves by host. Returns
     *  null only when the host is in NEITHER list. Pure
     *  function, unit tested (Fix6WidgetTest). */
    static Server resolveServerFromLists(String host, String proto,
            List<Server> full, List<Server> widget) {
        if (host == null || host.isEmpty()) return null;
        Server s = matchInList(host, proto, widget);
        if (s != null) return s;
        s = matchInList(host, proto, full);
        if (s != null) return s;
        s = matchInList(host, null, widget);
        if (s != null) return s;
        return matchInList(host, null, full);
    }

    /** First server in the list with this host, and with
     *  this proto when proto is not null. */
    static Server matchInList(String host, String proto,
            List<Server> list) {
        if (list == null) return null;
        for (Server a : list) {
            if (a != null && host.equals(a.ip)
                    && (proto == null || proto.equals(a.proto))) return a;
        }
        return null;
    }

    /** The current source's list, preferring the persisted
     *  cache (the app refreshes it on every open) and
     *  fetching only when the cache is empty. Never null.
     *  CONNECT path only (background thread) — the widget
     *  list must use widgetServers() instead. */
    static List<Server> loadServers(Context ctx) {
        try {
            if (AppSettings.isFirefoxMode(ctx)) {
                List<Server> c = FirefoxAuth.cachedServerList(ctx);
                if (c != null && !c.isEmpty()) return c;
                return FirefoxAuth.getServers(ctx, false);
            }
            List<Server> c = cachedServers(ctx);
            if (c != null && !c.isEmpty()) return c;
            return new SubscriptionApi().fetchServers();
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** The persisted cache only — no network. Never null. */
    static List<Server> cachedServers(Context ctx) {
        try {
            if (AppSettings.isFirefoxMode(ctx)) {
                List<Server> c = FirefoxAuth.cachedServerList(ctx);
                return c == null ? new ArrayList<Server>() : c;
            }
            List<Server> c = SubscriptionApi.parseCacheJson(AppSettings.getCachedSs(ctx));
            return c == null ? new ArrayList<Server>() : c;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    // ---- ping sweep (bounded) ----

    /** Probe every server in the list (20 threads, same
     *  probes as the app's sweep) and persist the pings
     *  and the best server. Waits at most budgetMs. */
    static void runPingSweepSync(Context ctx, List<Server> list, long budgetMs) {
        if (list == null || list.isEmpty()) return;
        // 3.1 fix6: in Firefox mode, warm the ProxyPass while
        // the sweep is running — ensureProxyPass returns
        // instantly when the pass is still valid and fetches
        // only when it is near expiry, so the row tap after
        // the sweep does no slow pre-start work. Best effort:
        // a failure here changes nothing (the tap fetches it
        // itself), and the sweep must never be blocked by it.
        if (AppSettings.isFirefoxMode(ctx)) {
            try { FirefoxAuth.ensureProxyPass(ctx); } catch (Throwable ignored) {}
        }
        final Map<String, Long> pings = new ConcurrentHashMap<>(AppSettings.getWidgetPings(ctx));
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(list.size(), 20));
        for (Server a : list) {
            final Server sv = a;
            ex.submit(() -> {
                long ms;
                if ("vmess".equals(sv.proto) || "firefox".equals(sv.proto)) {
                    ms = tcpProbe(sv.ip, sv.port, PING_TIMEOUT_MS);
                } else {
                    ms = SsProbe.probe(sv.ip, sv.port, sv.method, sv.password, PING_TIMEOUT_MS);
                }
                pings.put(sv.ip, ms);
            });
        }
        ex.shutdown();
        try { ex.awaitTermination(budgetMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { }
        ex.shutdownNow();
        try { AppSettings.saveWidgetPings(ctx, pings); } catch (Throwable ignored) {}
        Server best = WidgetList.bestOf(list, pings);
        if (best != null) {
            try {
                AppSettings.saveWidgetBest(ctx, best.ip,
                        best.flag == null ? "" : best.flag);
            } catch (Throwable ignored) {}
        }
    }

    /** A plain TCP-connect probe (vmess/firefox servers),
     *  in ms, or -1. Same as MainActivity.tcpProbeSub. */
    static long tcpProbe(String ip, int port, int timeoutMs) {
        Socket sock = null;
        try {
            long t0 = System.currentTimeMillis();
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, port), timeoutMs);
            return System.currentTimeMillis() - t0;
        } catch (Throwable ignored) {
            return -1;
        } finally {
            try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        }
    }
}
