package com.vpeen.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
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

        /** Stay put WITHOUT opening the app: log the
         *  reason, refresh widget + tile to the real
         *  state, finish the pending result. 3.1 fix9: a
         *  slow tunnel, a probe that hasn't had time, or
         *  a transient failure must NEVER open the app —
         *  only a definite failure the app alone can
         *  resolve (VPN permission not granted, Firefox
         *  login lost with no token at all, foreground-
         *  service start refused) falls back. The user
         *  can tap again; the service pushes widget/tile
         *  updates when/if the tunnel comes up. */
        void stayPut(String reason) {
            try { ConnectLog.log(ctx, "direct stay-put: " + reason); }
            catch (Throwable ignored) {}
            refreshTileAndWidget();
            finishPending();
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
            // 3.1 fix8: the tile must show the real state
            // even when the fallback itself fails silently —
            // a failed tap must never leave a stale tile.
            try { ArcVeePNTileService.requestTileUpdate(ctx); } catch (Throwable ignored) {}
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
                // 3.1 fix9: an unexpected exception is not
                // a definite failure — stay put, never open
                // the app for it. The old code fell back
                // here, so any transient throw (a prefs
                // read, a stale list) opened the app.
                stayPut("toggle threw " + t.getClass().getSimpleName());
            }
        }

        void disconnect() {
            try {
                Intent i = new Intent(ctx, VpnTunnelService.class);
                i.setAction(VpnTunnelService.ACTION_DISCONNECT);
                ctx.startService(i);
            } catch (Throwable ignored) {}
            finishPending();
            // 3.1 fix8: wait for the stop to land before
            // refreshing the tile. The old fixed 300 ms
            // sleep raced the async stop, so the tile update
            // could read the OLD running=true and the tile
            // stayed "Connected" after a disconnect tap.
            for (int k = 0; k < 20; k++) {
                if (!VpnTunnelService.running) break;
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
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
                // 3.1 fix9: the outcome is handled inside
                // connectResolved (stay-put, or the
                // definite-failure fallback). A dead server,
                // a slow tunnel, or a transient failure
                // NEVER opens the app here — the old code
                // fell back on CONNECT_FAILED, so the fix8
                // probe (which fires while a fresh tunnel
                // is still warming up) opened the app on
                // every widget tap whose first probe missed.
                connectResolved(server);
            } catch (Throwable t) {
                stayPut("connectHost threw " + t.getClass().getSimpleName());
            }
        }

        void connectFastest() {
            try {
                try { AppSettings.setLastSelection(ctx, AppSettings.SEL_FASTEST); }
                catch (Throwable ignored) {}
                // 3.1 fix9: CACHE ONLY — no network fetch and
                // no ping sweep before the foreground-service
                // start. The old code fetched the network
                // when the cache was empty and swept pings
                // for up to 4 s first; on a strict ROM that
                // delay lets the tap interaction window
                // close, so the service start is refused and
                // the tap falls back to opening the app (the
                // fix8 tile finding, same defect class). The
                // cached pings rank the servers; with none
                // measured, list order decides. An empty
                // cache means an empty widget list too —
                // stay put; the app fetches on open. The
                // ping-test button still sweeps on demand.
                List<Server> list = cachedServers(ctx);
                if (list == null || list.isEmpty()) { stayPut("no cached servers for fastest"); return; }
                Map<String, Long> pings = AppSettings.getWidgetPings(ctx);
                // 3.1 fix8: up to 3 attempts, best-first,
                // skipping servers that just failed the
                // traffic probe — the old code connected to
                // the first untested server (list.get(0))
                // whenever no ping was measured, and a dead
                // pick stayed "Connected" with no internet.
                java.util.Set<String> tried = new java.util.HashSet<>();
                for (int attempt = 0; attempt < 3; attempt++) {
                    Server best = nextBest(list, pings, tried);
                    if (best == null) break;
                    tried.add(best.ip);
                    int st = connectResolved(best);
                    if (st == CONNECT_OK) return;
                    // The app opened (definite failure) or
                    // the old tunnel is still serving or the
                    // tunnel may still come up: stop here,
                    // do NOT try the next server and do NOT
                    // open the app.
                    if (st == CONNECT_FELL_BACK) return;
                    if (st == CONNECT_STOP_TIMEOUT) return;
                    if (st == CONNECT_PENDING) return;
                    // CONNECT_FAILED: the server was dead —
                    // try the next best.
                }
                // 3.1 fix9: all attempts dead — stay put.
                // The old code fell back to the app here, so
                // a tap with three slow/dead servers opened
                // it. The dead tunnels are already stopped;
                // the widget shows the real state.
                stayPut("no working server");
            } catch (Throwable t) {
                stayPut("connectFastest threw " + t.getClass().getSimpleName());
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
                // 3.1 fix9: cache ONLY — the old code called
                // loadServers() here, which fetched the
                // network when the cache was empty, delaying
                // the foreground-service start past the tap
                // interaction window. An empty cache means
                // an empty widget list too, so the row could
                // not have been tapped from a fresh list.
                List<Server> list = cachedServers(ctx);
                List<Server> widget = widgetServers(ctx);
                return resolveServerFromLists(host, proto, list, widget);
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** connectResolved outcomes: OK = tunnel up and
         *  verified; STOP_TIMEOUT = the old tunnel is
         *  still serving (stop did not clear in 5 s) —
         *  stay put, do NOT open the app; FAILED = the
         *  server is dead / a transient failure — the
         *  tunnel is stopped (or never started) and the
         *  tap stays put, the caller may retry the next
         *  server; PENDING = the tunnel did not come up
         *  within the wait — it may still come up, stay
         *  put, the service pushes widget/tile updates
         *  when/if it does; FELL_BACK = the app opened
         *  (definite failure only) — the caller must
         *  stop, nothing more to try. */
        static final int CONNECT_OK = 0;
        static final int CONNECT_STOP_TIMEOUT = 1;
        static final int CONNECT_FAILED = 2;
        static final int CONNECT_PENDING = 3;
        static final int CONNECT_FELL_BACK = 4;

        /** Start the tunnel for a resolved server, directly,
         *  without opening any activity. Returns CONNECT_OK
         *  only when the tunnel is up AND verified to carry
         *  traffic (the through-tunnel probe). A tunnel
         *  that starts but carries nothing is stopped
         *  again, so `running` can never sit true with no
         *  internet behind it. */
        int connectResolved(Server server) {
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
                        // 3.1 fix8: never return silently here
                        // — refresh the tile so it re-reads the
                        // real (durable) state instead of
                        // sitting stale after a dead tap. The
                        // old tunnel keeps serving meanwhile, so
                        // this is STOP_TIMEOUT (stay put), NOT a
                        // failure — the caller must NOT open the
                        // app for it (fix6 scenario E).
                        refreshTileAndWidget();
                        finishPending();
                        return CONNECT_STOP_TIMEOUT;
                    }
                }
                // Firefox servers need a live ProxyPass token.
                String ffxPass = null;
                if ("firefox".equals(server.proto)) {
                    // 3.1 fix9: prefer the STORED pass, even
                    // inside the 60 s early margin — the
                    // service's renewal loop refreshes it
                    // live at expiry - 2 min. The old code
                    // fetched a fresh pass here whenever the
                    // stored one was within 60 s of expiry;
                    // that network fetch delayed the
                    // foreground-service start past the tap
                    // interaction window. Only when there is
                    // NO stored pass at all do we fetch.
                    try { ffxPass = FirefoxAuth.cachedProxyPass(ctx); } catch (Throwable ignored) {}
                    if (ffxPass == null || ffxPass.isEmpty()) {
                        try {
                            ffxPass = FirefoxAuth.ensureProxyPass(ctx);
                        } catch (FirefoxAuth.FfxException e) {
                            // Dead login: the ONLY pass failure
                            // the app alone can resolve (the
                            // login dialog). Anything else is
                            // transient — stay put, never open
                            // the app for a network blip.
                            if (e.reauth) { fallback("firefox reauth"); return CONNECT_FELL_BACK; }
                            stayPut("firefox pass fetch failed");
                            return CONNECT_FAILED;
                        } catch (Throwable ignored) {
                            stayPut("firefox pass threw");
                            return CONNECT_FAILED;
                        }
                    }
                    if (ffxPass == null || ffxPass.isEmpty()) {
                        stayPut("firefox pass null");
                        return CONNECT_FAILED;
                    }
                }
                // VPN permission (proxy mode needs none). A
                // non-null prepare intent means the user has
                // not granted it yet — only the app can ask.
                if (!AppSettings.isProxyMode(ctx)) {
                    try {
                        Intent prep = VpnService.prepare(ctx);
                        if (prep != null) { fallback("vpn prepare needed"); return CONNECT_FELL_BACK; }
                    } catch (Throwable t) {
                        // 3.1 fix9: a throw here is not the
                        // permission itself — stay put.
                        stayPut("vpn prepare threw " + t.getClass().getSimpleName());
                        return CONNECT_FAILED;
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
                    // 3.1 fix9: this stays a fallback — when
                    // the direct start is refused there is no
                    // other way to connect from the widget,
                    // and the app-open is the only recovery.
                    // The pre-start work above is now cache-
                    // only, so the start fires while the tap
                    // interaction is still live and this
                    // should almost never happen.
                    try { ConnectLog.log(ctx, "direct start failed, fallback"); }
                    catch (Throwable ignored) {}
                    fallback("start threw " + t.getClass().getSimpleName());
                    return CONNECT_FELL_BACK;
                }
                finishPending();
                // Wait for the tunnel (up to 15 s), then VERIFY
                // it carries traffic before reporting success.
                // 3.1 fix5: fix4 waited only 5 s and OPENED
                // THE APP on a timeout — on a slow network the
                // tunnel simply takes longer than 5 s, so every
                // widget/tile tap opened the app. A slow tunnel
                // is NOT a failure: the service pushes
                // widget/tile updates when/if it comes up, and
                // the widget still shows Disconnected so the
                // user can tap again. The fallback fires only
                // on a definite failure (start threw, no VPN
                // permission, reauth needed, no servers at
                // all) — see above.
                // 3.1 fix8: `running` only means sing-box
                // started — a dead server starts fine (TUN
                // up, no upstream), so waiting for `running`
                // alone is exactly the reported "Connected,
                // no internet". The probe below is the gate:
                // one retry for a slow start, then the dead
                // tunnel is stopped again and the attempt
                // counts as failed (the caller retries the
                // next server, or stays put).
                // 3.1 fix9: a tunnel that did not come up
                // within the wait is NOT a failure — it may
                // still come up, and the service pushes
                // widget/tile updates when/if it does. The
                // old code returned FAILED here, so the
                // caller opened the app on every slow start.
                if (!awaitTunnel(() -> VpnTunnelService.running, TUNNEL_WAIT_MS)) {
                    stayPut("tunnel not up yet");
                    return CONNECT_PENDING;
                }
                if (!verifyTunnel()) {
                    try { ConnectLog.log(ctx, "direct connect unverified, stopping dead tunnel"); }
                    catch (Throwable ignored) {}
                    stopDeadTunnel();
                    // 3.1 fix9: a dead server is cleaned up
                    // and the tap stays put — the old code
                    // returned FAILED and the caller opened
                    // the app. The service's never-flowed
                    // watch is the backstop for a tunnel
                    // that passes the probe and dies later.
                    stayPut("probe failed");
                    return CONNECT_FAILED;
                }
                if (AppSettings.SEL_FASTEST.equals(AppSettings.getLastSelection(ctx))) {
                    try {
                        AppSettings.saveWidgetBest(ctx, server.ip,
                                server.flag == null ? "" : server.flag);
                    } catch (Throwable ignored) {}
                }
                try { ConnectLog.log(ctx, "direct connect ok host=" + server.ip); }
                catch (Throwable ignored) {}
                try { ArcVeePNWidget.updateAll(ctx); } catch (Throwable ignored) {}
                try { ArcVeePNTileService.requestTileUpdate(ctx); } catch (Throwable ignored) {}
                return CONNECT_OK;
            } catch (Throwable t) {
                // 3.1 fix9: an unexpected exception is not a
                // definite failure — stay put, never open the
                // app for it.
                stayPut("connectResolve threw " + t.getClass().getSimpleName());
                return CONNECT_FAILED;
            }
        }

        /** Through-tunnel probe, patient: up to 3 attempts
         *  2 s apart. True when real traffic flowed through
         *  the tunnel. 3.1 fix9: the old 2 attempts 1 s
         *  apart fired while a fresh tunnel was still
         *  warming up (Firefox hostname DNS, first CONNECT)
         *  and declared a good tunnel dead — then the
         *  caller opened the app. A tunnel that cannot
         *  carry traffic 6 s after coming up is dead; a
         *  slower one gets its chance. */
        boolean verifyTunnel() {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    if (tunnelProbeMs() >= 0) return true;
                } catch (Throwable ignored) {}
                if (attempt < 2) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { return false; }
                }
            }
            return false;
        }

        /** Stop a tunnel that started but carries no traffic,
         *  and wait briefly for `running` to clear so the
         *  tile never reads the stale true. */
        void stopDeadTunnel() {
            try {
                Intent d = new Intent(ctx, VpnTunnelService.class);
                d.setAction(VpnTunnelService.ACTION_DISCONNECT);
                ctx.startService(d);
            } catch (Throwable ignored) {}
            for (int k = 0; k < 30; k++) {
                if (!VpnTunnelService.running) break;
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            refreshTileAndWidget();
        }

        /** Push widget + tile updates so both read the real
         *  (durable) state after a failed attempt. */
        void refreshTileAndWidget() {
            try { ArcVeePNWidget.updateAll(ctx); } catch (Throwable ignored) {}
            try { ArcVeePNTileService.requestTileUpdate(ctx); } catch (Throwable ignored) {}
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

    // ---- through-tunnel verification probe (3.1 fix8) ----

    /** A real through-tunnel check: a minimal HTTP GET to
     *  the generate_204 endpoint, sent through the sing-box
     *  `mixed` inbound on 127.0.0.1:1080, which routes via
     *  the active outbound (the connected server). The
     *  exact mechanism MainActivity.tunnelRttMs uses for
     *  the displayed ping — the mixed inbound listens on
     *  1080 in VPN mode too and in proxy mode. Returns ms
     *  to the response headers, or -1 on any failure.
     *  `running == true` alone never proves traffic flows:
     *  sing-box starts fine with a dead server (TUN up,
     *  no upstream), so a tile connect that only waits for
     *  `running` can report Connected with no internet.
     *  This probe is the gate before any success is
     *  reported — and the service's stall watch uses it
     *  to confirm a stalled tunnel before stopping it. */
    static long tunnelProbeMs() {
        return probeThroughProxy("127.0.0.1", 1080,
                "http://www.gstatic.com/generate_204");
    }

    /** The probe against an explicit proxy + URL (the
     *  test seam for the failure path: a closed port
     *  returns -1). Returns ms, or -1 on any failure. */
    static long probeThroughProxy(String proxyHost, int proxyPort,
            String urlString) {
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(proxyHost, proxyPort));
            HttpURLConnection c = (HttpURLConnection)
                    new URL(urlString).openConnection(proxy);
            return probeConnection(c);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** The probe against an already-opened connection
     *  (the test seam for the success path: tests pass a
     *  stub connection returning 204 / 500 / throwing,
     *  because loopback TCP is denied in this VM and a
     *  real stub proxy cannot run here). Returns ms to
     *  the response headers, or -1 on any failure. */
    static long probeConnection(HttpURLConnection c) {
        try {
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
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** The best server not yet tried this tap (pure over
     *  plain values, unit tested): measured pings
     *  first, lowest first, then the unmeasured in
     *  list order. */
    static Server nextBest(List<Server> list,
            Map<String, Long> pings, java.util.Set<String> tried) {
        List<Server> ranked = WidgetList.sortByPing(list, pings);
        for (Server s : ranked) {
            if (s == null || s.ip == null) continue;
            if (tried != null && tried.contains(s.ip)) continue;
            return s;
        }
        return null;
    }

    // ---- server lists (cache first, network only when empty) ----

    /** The WIDGET's server list: the PERSISTED cache ONLY,
     *  parsed WITHOUT the passwords (the widget only shows
     *  flags/names/pings — see SubscriptionApi.parseCacheJsonLight),
     *  and NEVER the network. 3.1 fix5: fix4's list factory called
     *  loadServers() here, which fetched the network when
     *  the cache was empty — on the main thread
     *  (RemoteViewsFactory callbacks run there), which
     *  froze the app for seconds whenever the cache was
     *  empty (notably the first switch to Firefox) and
     *  left the old list on screen. An empty cache now
     *  means an empty list; the app pushes a widget
     *  update after every successful fetch, so the list
     *  fills in as soon as the servers arrive. Never null. */
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
