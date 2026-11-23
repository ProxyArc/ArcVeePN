package com.vpeen.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

// sb15: the manifest package is com.vpeen.android again (the
// original package, so this build updates stable v1.7 in place),
// which is also this file's Java package — the generated R class
// lives in com.vpeen.android, so no R import is needed at all.
// (sb3–sb14 the manifest package was com.vpeen.android.singbox
// and R had to be imported from there.)

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * sing-box build (ArcVeePN SB).
 * Chain: app traffic -> TUN (Android VpnService, fd handed to
 * sing-box via PlatformInterface.openTun) -> sing-box
 * `shadowsocks` outbound (or `vmess` for backup-subscription
 * servers). In proxy mode there is no TUN: sing-box serves a
 * local SOCKS5/HTTP proxy on 127.0.0.1:1080/8080 instead, which
 * MainActivity also uses for the exit-IP check.
 *
 * LAUNCH-CRASH FIX (sb1 -> sb2): this class references NO libbox
 * type at all — no imports, no fields, no method signatures.
 * All libbox work lives in SingBoxRunner, loaded reflectively
 * ONLY on Connect (Class.forName). In sb1 this class imported
 * 13 libbox types and MainActivity touched VpnTunnelService at
 * launch via getStatusJson(), so ART could pull the 46 MB Go
 * .so (go/Seq static initializer) at launch, and any failure
 * there (VerifyError / NoClassDefFoundError /
 * UnsatisfiedLinkError — all java.lang.Error) escaped every
 * catch (Exception) and killed the process instantly.
 * Every catch here is now Throwable, so a core failure becomes
 * a visible status string, never an uncaught kill.
 */
public class VpnTunnelService extends VpnService {
    public static final String ACTION_CONNECT = "com.vpeen.android.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vpeen.android.DISCONNECT";
    public static final String EXTRA_REGION = "region";

    public static volatile String currentRegion = "";
    // The Shadowsocks server this tunnel is for.
    public static volatile Server currentServer;
    public static volatile Server runningServer;
    // 3.1: the Firefox ProxyPass JWT for the tunnel being
    // built, in memory only. MainActivity sets it (fresh,
    // from FirefoxAuth.ensureProxyPass) right before
    // ACTION_CONNECT when the server is a Firefox one, and
    // null otherwise. Never logged, never persisted.
    public static volatile String firefoxProxyPass;
    public static volatile boolean running = false;
    public static volatile String status = "Disconnected";
    public static volatile long connectedAtMs = 0;

    // Traffic totals from the sing-box status stream (cumulative
    // bytes since the service started). Used by MainActivity's
    // watchdog exactly like hev's TProxyGetStats in stable v1.7.
    private static volatile long upTotalBytes = 0;
    private static volatile long downTotalBytes = 0;

    // sb9: the stall watchdogs (Java + JS) must NEVER fire on a
    // dead stats stream. statsEverNonZero is set only when the
    // sing-box status stream actually delivers a NON-ZERO byte
    // total — proof real traffic flowed. If the stream never
    // delivers (stats thread died at birth from the running-flag
    // race, or the CommandClient stream never connects), the
    // counters stay 0 and the watchdogs must stay silent, or the
    // app "optimizes" (disconnect + reconnect) by itself ~1–2.5
    // min after every connect — the reported auto-disconnect.
    public static volatile boolean statsEverNonZero = false;
    public static volatile long lastStatsAtMs = 0;

    // 3.1 fix: the renewal loop sets this when a token
    // refresh fails terminally (dead login / spent quota).
    // MainActivity's 1 s poll consumes it (see
    // pollNetworkChange) and runs the existing generic
    // auth-lost path — never a retry storm, never a token
    // detail on screen.
    private static volatile boolean firefoxAuthLostPending = false;

    public static boolean consumeFirefoxAuthLostIfDue() {
        try {
            if (!firefoxAuthLostPending) return false;
            firefoxAuthLostPending = false;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // 3.1 fix: the ProxyPass renewal loop (Arman model, see
    // FirefoxAuth PROXY_PASS_RENEW_LEAD_MS). Runs only while
    // a Firefox tunnel is up. Started in startTunnel, stopped
    // in stopTunnel.
    // sb10: set by MainActivity before Connect — connection mode
    // (VPN with TUN, or proxy with local SOCKS5/HTTP inbounds and
    // no TUN at all) and the split-tunneling settings (VPN mode
    // only; applied by SingBoxPlatform.openTun before establish).
    public static volatile boolean proxyMode = false;
    public static volatile String splitMode = "off";
    public static volatile java.util.Set<String> splitPackages =
            new java.util.HashSet<>();

    // sb15: underlying-network-change detection. The tunnel's
    // sockets live on the real Wi-Fi / cellular network, so when
    // THAT network changes (Wi-Fi -> mobile, reconnect, …) the
    // tunnel must be re-established. Registered when the tunnel
    // comes up (VPN mode only — the proxy is per-connection, so
    // new proxy connections use the new network automatically)
    // and unregistered on disconnect. The VPN/TUN network itself
    // is IGNORED — it is not an underlying network. A change is
    // recorded as PENDING with a timestamp; MainActivity's 1 s
    // poll consumes it after a ~3 s debounce, so flapping (several
    // callbacks in quick succession) causes a single reconnect
    // to the final network.
    private ConnectivityManager.NetworkCallback netCallback;
    private volatile String lastUnderlyingKey = null;
    private static volatile String pendingNetKey = null;
    private static volatile long pendingNetAtMs = 0;
    private static final long NET_CHANGE_DEBOUNCE_MS = 3000;

    public static long[] tunnelStats() {
        try {
            return new long[]{0, upTotalBytes, 0, downTotalBytes};
        } catch (Throwable ignored) {
            return new long[]{0, 0, 0, 0};
        }
    }

    public static void setTraffic(long up, long down) {
        upTotalBytes = up;
        downTotalBytes = down;
        if (up > 0 || down > 0) statsEverNonZero = true;
        lastStatsAtMs = System.currentTimeMillis();
    }

    public static void resetTraffic() {
        upTotalBytes = 0;
        downTotalBytes = 0;
        statsEverNonZero = false;
        lastStatsAtMs = 0;
    }

    private static final String TAG = "ArcVeePN";

    // The sing-box runner: interface only, loaded reflectively on
    // Connect. Must stay an interface reference — never a
    // SingBoxRunner field, or the class would link libbox at load.
    private TunnelRunner runner;

    @Override
    public void onCreate() {
        super.onCreate();
        try { CrashLog.install(this); } catch (Throwable ignored) {}
        // sb7: silently mirror any recorded crash.log from the
        // previous run to the external files dir — NO on-screen
        // display of any kind.
        try { CrashLog.mirrorToExternal(this); } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            try { stopTunnel(); } catch (Throwable ignored) {}
            try { stopSelf(); } catch (Throwable ignored) {}
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(action)) {
            String region = intent.getStringExtra(EXTRA_REGION);
            if (region != null) currentRegion = region;
            startTunnelAsync();
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startTunnelAsync() {
        new Thread(() -> {
            try {
                startTunnel();
            } catch (Throwable t) {
                Log.e(TAG, "startTunnel failed", t);
                try { ConnectLog.log(this, "startTunnel FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage()); } catch (Throwable ignored) {}
                // sb15 (no-logs audit): the status string is a
                // user-facing state — never raw exception text.
                // The detail stays in the silent ConnectLog above.
                status = "Connection failed.";
                running = false;
                try { stopTunnel(); } catch (Throwable ignored) {}
            }
        }, "vpeen-sb-start").start();
    }

    /** Reflective load: the ONLY place a libbox class is touched. */
    private TunnelRunner loadRunner() throws Throwable {
        try {
            Class<?> c = Class.forName("com.vpeen.android.SingBoxRunner");
            Object o = c.getDeclaredConstructor(VpnTunnelService.class).newInstance(this);
            return (TunnelRunner) o;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            throw new Exception("sing-box failed to load: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }
    }

    private synchronized void startTunnel() throws Throwable {
        if (running) return;
        // Shadowsocks path — single server, one attempt.
        // The password comes from the subscription link; it
        // is never logged (the full config is NOT logged on
        // this path — it contains the password).
        Server sub = currentServer;
        if (sub != null) {
            runningServer = sub;
            status = "Connecting…";
            ConnectLog.log(this, "startTunnel begin sub server=" + sub.ip
                    + ":" + sub.port + " flag="
                    + (sub.flag == null ? "" : sub.flag));
            boolean proxy = proxyMode;
            // No hostname pre-resolve (the server is an IP
            // literal) and no LocalSocksServer: Shadowsocks
            // needs no HTTP-proxy bridge. In proxy mode
            // sing-box owns 127.0.0.1:1080/8080; in VPN mode
            // the TUN feeds sing-box directly.
            ConnectLog.log(this, "sub: no local bridge, mode="
                    + (proxy ? "proxy" : "vpn"));
            startForeground(1, buildNotification());
            ConnectLog.log(this, "foreground OK mode=" + (proxy ? "proxy" : "vpn"));
            // 2.7 fix: multi-protocol — the config builder
            // dispatches on the server's proto (ss / vmess /
            // vless / trojan), so emergency-subscription
            // servers of any protocol connect.
            // 3.1: a Firefox server's config also carries the
            // in-memory ProxyPass JWT (firefoxProxyPass, set
            // by MainActivity just before this start); any
            // other protocol ignores it.
            String config = SingBoxConfig.build(sub, getPackageName(), proxy,
                    firefoxProxyPass);
            ConnectLog.log(this, "config built len=" + config.length());
            ConnectLog.log(this, "config inbounds proxy=" + proxy
                    + " mixed=" + config.contains("\"mixed-in\"")
                    + " http=" + config.contains("\"http-in\"")
                    + " tun=" + config.contains("\"tun-in\"")
                    + " splitMode=" + splitMode
                    + " splitPkgs=" + (splitPackages == null ? 0 : splitPackages.size()));
            TunnelRunner r = loadRunner();
            runner = r;
            ConnectLog.log(this, "runner loaded OK");
            try {
                ConnectLog.log(this, "runner.start begin");
                r.start(config);
                ConnectLog.log(this, "runner.start OK");
            } catch (Throwable t) {
                runner = null;
                throw t;
            }
            running = true;
            connectedAtMs = System.currentTimeMillis();
            String fl = (sub.flag != null && !sub.flag.isEmpty())
                    ? sub.flag : sub.ip;
            status = "Connected to " + fl;
            ConnectLog.log(this, "running=true");
            // 3.1 fix8: persist the tunnel state for the tile
            // (durable source of truth) and start the
            // heartbeat/stall watch — the tile path has no
            // MainActivity watchdog, so a tunnel that later
            // goes silent must be caught here, not only in
            // the activity.
            try { AppSettings.setTunnelState(this, true, fl); } catch (Throwable ignored) {}
            try { startTunnelWatch(); } catch (Throwable ignored) {}
            Log.i(TAG, "sing-box tunnel up (sub), server=" + sub);
            try { registerNetCallback(); } catch (Throwable ignored) {}
            // 3.1 fix: a Firefox tunnel's ProxyPass JWT expires
            // 10 min after it was minted — start the renewal
            // loop so the live session gets a fresh token.
            try { startFirefoxRenewalLoop(); } catch (Throwable ignored) {}
            // 3.1 fix3: push the connected state to the
            // home-screen widget and the Quick Settings tile.
            try { ArcVeePNWidget.updateAll(this); } catch (Throwable ignored) {}
            try { ArcVeePNTileService.requestTileUpdate(this); } catch (Throwable ignored) {}
            return;
        }
    }

    /** Reload the live tunnel with a new config (renewal
     *  path only). False when there is no runner to reload
     *  (tunnel already down) — the caller must not treat
     *  that as a renewal failure. */
    public synchronized boolean reloadTunnelConfig(String config) {
        try {
            if (runner == null) return false;
            runner.reload(config);
            return true;
        } catch (Throwable t) {
            try { ConnectLog.log(this, "reloadTunnelConfig FAILED: "
                    + t.getClass().getSimpleName()); } catch (Throwable ignored) {}
            return false;
        }
    }

    private volatile Thread ffxRenewThread = null;
    private volatile boolean ffxRenewStop = false;
    // 3.1 fix3: wall-clock time of the last successful live
    // reload. The renewal loop must not reload more often
    // than MIN_RELOAD_INTERVAL_MS (see shouldReloadForRenewal
    // in FirefoxAuth) — each reload is a full tunnel teardown.
    private volatile long ffxLastReloadAtMs = 0;

    /** 3.1 fix — the Arman renewal model, adapted to this
     *  app (Arman core/internal/proxy/renewal.go
     *  RunRenewalLoop + adoptPass):
     *  - Proactive: every RENEW_CHECK_INTERVAL_MS (30 s)
     *    the WALL clock is read (never one long sleep — a
     *    sleeping phone's timers stall while the pass
     *    expires by wall clock, Arman's wallCheckInterval
     *    is 15 s). When now passes the stored pass expiry
     *    minus the 2-minute lead, a fresh pass is minted
     *    (forceRefreshProxyPass — the plain ensure would
     *    return the still-valid stored token) and the live
     *    service is reloaded with the rebuilt config, the
     *    session retained (Arman: updateCurrentSessionToken
     *    -> "session=retained"; here: startOrReloadService
     *    on the live CommandServer — the TUN is
     *    re-established, the VpnService stays up, see
     *    SingBoxRunner.reload).
     *  - Reactive backstop (Firefox only): when real traffic
     *    has flowed (statsEverNonZero, the sb9 guard) and
     *    then NOTHING flows for STALL_WINDOW_MS (90 s) while
     *    the stored pass has already EXPIRED, the same
     *    refresh+reload runs. The expiry gate matters: an
     *    idle user with a fresh pass must NOT trigger a
     *    reload — no-flow alone is idle, not a stall.
     *  - Retry: a transient refresh failure retries after
     *    RENEW_RETRY_DELAY_MS (30 s, the loop's own sleep).
     *    A terminal failure (reauth=true: dead login, spent
     *    quota) stops the loop at once and flags
     *    firefoxAuthLostPending — MainActivity surfaces the
     *    existing generic auth-lost path, nothing more.
     *  - 3.1 fix3 — no-gain guard: a reload happens ONLY
     *    when the freshly fetched pass actually buys lifetime
     *    (FirefoxAuth.shouldReloadForRenewal: new expiry
     *    later than the old one, not already dead, and at
     *    least MIN_RELOAD_INTERVAL_MS since the last reload).
     *    The fix build reloaded whenever the pass was due,
     *    without checking the fetch result — when Guardian
     *    returns the same cached token, the expiry never
     *    advances and the loop reloaded every 30 s forever,
     *    each reload a full tunnel teardown (the ~10-minute
     *    speed drop). A skipped reload keeps the tunnel and
     *    its flows alive; the fetch is retried 30 s later.
     *    The stored expiry is also clock-anchored at fetch
     *    time (FirefoxAuth.anchorOffsetSeconds), so a skewed
     *    phone clock cannot misjudge when the pass is due.
     *  - Firefox-gated throughout: non-Firefox tunnels never
     *    start this loop, and the sb9 watchdog guard
     *    (statsEverNonZero) is untouched. */
    private void startFirefoxRenewalLoop() {
        try {
            Server s = runningServer;
            if (s == null || !"firefox".equals(s.proto)) return;
            if (ffxRenewThread != null && ffxRenewThread.isAlive()) return;
            ffxRenewStop = false;
            // 3.1 fix3: a new tunnel has no reload yet — the
            // min-interval guard starts from zero.
            ffxLastReloadAtMs = 0;
            ffxRenewThread = new Thread(() -> {
                long lastFlowTotal = -1;
                long lastFlowAtMs = System.currentTimeMillis();
                while (!ffxRenewStop && running) {
                    try { Thread.sleep(FirefoxAuth.RENEW_CHECK_INTERVAL_MS); }
                    catch (InterruptedException e) { break; }
                    if (ffxRenewStop || !running) break;
                    try {
                        Server cur = runningServer;
                        if (cur == null || !"firefox".equals(cur.proto)) break;
                        long now = System.currentTimeMillis();
                        long[] st = tunnelStats();
                        long total = (st.length > 3 ? st[1] + st[3] : 0);
                        if (lastFlowTotal < 0) { lastFlowTotal = total; lastFlowAtMs = now; }
                        else if (total != lastFlowTotal) { lastFlowTotal = total; lastFlowAtMs = now; }
                        long passExp = FirefoxAuth.getProxyPassExpiresAtMs(this);
                        boolean due = FirefoxAuth.shouldRenew(now, passExp,
                                FirefoxAuth.PROXY_PASS_RENEW_LEAD_MS);
                        boolean stalled = statsEverNonZero
                                && now - lastFlowAtMs > FirefoxAuth.STALL_WINDOW_MS
                                && passExp > 0 && now > passExp;
                        if (!due && !stalled) continue;
                        ConnectLog.log(this, "ffx renew: "
                                + (stalled ? "stall+expired" : "expiry-2min"));
                        String tok;
                        try {
                            tok = FirefoxAuth.forceRefreshProxyPass(this);
                        } catch (FirefoxAuth.FfxException e) {
                            if (e.reauth) {
                                ConnectLog.log(this, "ffx renew: login dead, auth lost");
                                firefoxAuthLostPending = true;
                                break;
                            }
                            ConnectLog.log(this, "ffx renew: transient failure, retry in 30s");
                            continue;
                        } catch (Throwable t) {
                            ConnectLog.log(this, "ffx renew: transient failure, retry in 30s");
                            continue;
                        }
                        if (tok == null || tok.isEmpty()) continue;
                        // 3.1 fix3: reload ONLY when the fresh
                        // pass actually buys lifetime (see
                        // FirefoxAuth.shouldReloadForRenewal).
                        // The fix-build loop reloaded whenever
                        // the pass was due, without checking what
                        // the fetch returned — when Guardian
                        // answers with the SAME cached token, the
                        // stored expiry never advances, the pass
                        // stays due, and the loop reloaded every
                        // 30 s forever; each reload is a full
                        // tunnel teardown, which is exactly the
                        // "speed drops after ~10 min" symptom.
                        // Skipping the reload keeps the tunnel
                        // (and its flows) alive; the loop retries
                        // the fetch 30 s later.
                        long newExp = FirefoxAuth.getProxyPassExpiresAtMs(this);
                        if (!FirefoxAuth.shouldReloadForRenewal(now, passExp,
                                newExp, ffxLastReloadAtMs)) {
                            ConnectLog.log(this, "ffx renew: no gain, skip reload");
                            continue;
                        }
                        firefoxProxyPass = tok;
                        Server srv = runningServer;
                        if (srv == null) break;
                        String cfg = SingBoxConfig.build(srv, getPackageName(), proxyMode, tok);
                        if (!reloadTunnelConfig(cfg)) break;
                        ffxLastReloadAtMs = System.currentTimeMillis();
                        // Fresh token in place: the stall clock
                        // restarts from here, so a slow recovery
                        // cannot re-trigger immediately.
                        lastFlowAtMs = System.currentTimeMillis();
                        lastFlowTotal = total;
                        ConnectLog.log(this, "ffx renew: reloaded with fresh pass");
                    } catch (Throwable ignored) {}
                }
            }, "vpeen-ffx-renew");
            ffxRenewThread.setDaemon(true);
            ffxRenewThread.start();
        } catch (Throwable ignored) {}
    }

    private void stopFirefoxRenewalLoop() {
        try {
            ffxRenewStop = true;
            Thread t = ffxRenewThread;
            ffxRenewThread = null;
            if (t != null) t.interrupt();
        } catch (Throwable ignored) {}
    }

    private volatile Thread tunnelWatchThread = null;
    private volatile boolean tunnelWatchStop = false;

    /** 3.1 fix8 — heartbeat + stall watch for the tile
     *  path. The MainActivity watchdog (30 s checks, 90 s
     *  stall -> optimize) only runs while the activity is
     *  alive; a tile/widget connect with the app closed
     *  had NO watchdog at all, so a tunnel that went
     *  silent stayed "Connected" forever. This daemon
     *  thread runs while the tunnel is up: every 30 s it
     *  touches the durable heartbeat (so the tile can
     *  tell a live tunnel from a dead process) and, with
     *  the SAME sb9 guard as the activity watchdog (only
     *  after real traffic was once seen, >60 s since
     *  connect, no byte change for 90 s), verifies the
     *  tunnel with a through-tunnel probe — a confirmed
     *  dead tunnel is stopped, which clears running,
     *  persists disconnected, and refreshes widget/tile.
     *  Never fires on a never-flowed stats stream: that
     *  case is the direct connect's post-connect probe. */
    private void startTunnelWatch() {
        try {
            if (tunnelWatchThread != null && tunnelWatchThread.isAlive()) return;
            tunnelWatchStop = false;
            tunnelWatchThread = new Thread(() -> {
                long prevBytes = -1;
                long lastChange = System.currentTimeMillis();
                // 3.1 fix9: whether the never-flowed probe
                // below already ran for this tunnel.
                boolean neverFlowedProbed = false;
                while (!tunnelWatchStop && running) {
                    try { Thread.sleep(30000); } catch (Throwable e) { break; }
                    if (tunnelWatchStop || !running) break;
                    try { AppSettings.touchTunnelHeartbeat(this); } catch (Throwable ignored) {}
                    try {
                        long[] st = tunnelStats();
                        long total = (st.length > 3 ? st[1] + st[3] : 0);
                        long now = System.currentTimeMillis();
                        // sb9: NEVER treat a dead stats stream
                        // as a stall — only a stall AFTER real
                        // traffic was seen counts.
                        if (!statsEverNonZero) {
                            prevBytes = total; lastChange = now;
                            // 3.1 fix9: the never-flowed case
                            // is no longer the tap's
                            // post-connect probe (that probe
                            // must never open the app) — so
                            // the service itself must catch a
                            // stillborn tunnel. After 60 s
                            // with zero bytes, probe once: a
                            // failed probe stops the tunnel
                            // (clearing running, persisting
                            // disconnected, refreshing
                            // widget/tile). A successful probe
                            // means the tunnel works and the
                            // user is just idle — the probe's
                            // own traffic makes the stats
                            // non-zero, so this fires at most
                            // once per tunnel.
                            if (!neverFlowedProbed && connectedAtMs > 0
                                    && now - connectedAtMs > 60000) {
                                neverFlowedProbed = true;
                                long probe = DirectConnect.tunnelProbeMs();
                                if (probe < 0) {
                                    try { ConnectLog.log(this, "tunnel watch: never flowed, probe failed, stopping"); } catch (Throwable ignored) {}
                                    try { stopTunnel(); } catch (Throwable ignored) {}
                                }
                            }
                            continue;
                        }
                        if (prevBytes < 0) { prevBytes = total; lastChange = now; continue; }
                        if (total != prevBytes) { prevBytes = total; lastChange = now; continue; }
                        if (connectedAtMs > 0 && now - connectedAtMs > 60000
                                && now - lastChange > 90000) {
                            // Stall after real traffic: verify
                            // with a real through-tunnel probe
                            // before stopping anything.
                            lastChange = now;
                            long probe = DirectConnect.tunnelProbeMs();
                            if (probe < 0) {
                                try { ConnectLog.log(this, "tunnel watch: stall confirmed by probe, stopping"); } catch (Throwable ignored) {}
                                try { stopTunnel(); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }, "vpeen-tunnel-watch");
            tunnelWatchThread.setDaemon(true);
            tunnelWatchThread.start();
        } catch (Throwable ignored) {}
    }

    private void stopTunnelWatch() {
        try {
            tunnelWatchStop = true;
            Thread t = tunnelWatchThread;
            tunnelWatchThread = null;
            if (t != null && t != Thread.currentThread()) t.interrupt();
        } catch (Throwable ignored) {}
    }

    // ---------- sb15: underlying-network-change detection ----------

    /** Register the network callback (both modes). The baseline
     *  is the underlying network we are already on — registering
     *  must NOT fire for the current network.
     *  v1.9-proxyfix: proxy mode used to return early here, so a
     *  proxy never learned that the underlying network changed —
     *  e.g. another VPN app (Nord) coming up or going down. The
     *  proxy's outbound sockets and DNS state are bound to the
     *  network the service started on, so without this the proxy
     *  kept its dead state forever after Nord went away, and only
     *  a full stop + fresh start (which a VPN-mode connect forces
     *  via the mode-switch disconnect) could revive it. */
    private void registerNetCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            lastUnderlyingKey = underlyingNetworkKey(cm);
            pendingNetKey = null;
            pendingNetAtMs = 0;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    evaluateUnderlyingNetwork();
                }
                @Override public void onLost(Network network) {
                    evaluateUnderlyingNetwork();
                }
                @Override public void onCapabilitiesChanged(Network network,
                        NetworkCapabilities caps) {
                    evaluateUnderlyingNetwork();
                }
                @Override public void onLinkPropertiesChanged(Network network,
                        LinkProperties lp) {
                    evaluateUnderlyingNetwork();
                }
            };
            cm.registerNetworkCallback(req, netCallback);
            ConnectLog.log(this, "netchange: watching underlying network key="
                    + lastUnderlyingKey);
        } catch (Throwable ignored) {
            netCallback = null;
        }
    }

    private void unregisterNetCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && netCallback != null) {
                cm.unregisterNetworkCallback(netCallback);
            }
        } catch (Throwable ignored) {}
        netCallback = null;
        lastUnderlyingKey = null;
        pendingNetKey = null;
        pendingNetAtMs = 0;
    }

    /** Recompute the underlying network and record a pending
     *  change when it actually changed (transport type or network
     *  identity). Called from the NetworkCallback on a system
     *  binder thread — never throws, never touches the UI. */
    private void evaluateUnderlyingNetwork() {
        try {
            if (!running) return;
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            String key = underlyingNetworkKey(cm);
            if (key == null) return; // no underlying network yet — wait for one
            if (key.equals(lastUnderlyingKey)) return;
            lastUnderlyingKey = key;
            pendingNetKey = key;
            pendingNetAtMs = System.currentTimeMillis();
            ConnectLog.log(this, "netchange: underlying network changed key="
                    + key + " (debounce)");
        } catch (Throwable ignored) {}
    }

    /** Key of the current UNDERLYING network (the real Wi-Fi /
     *  cellular network the tunnel's sockets live on). The VPN/TUN
     *  network itself is skipped — it is not an underlying network.
     *  Same selection as SingBoxPlatform.firstUsableNetwork:
     *  prefer a VALIDATED non-VPN network with INTERNET, else the
     *  first unvalidated one. null = no underlying network now. */
    private String underlyingNetworkKey(ConnectivityManager cm) {
        try {
            if (cm == null) return null;
            List<Network> ordered = new ArrayList<>();
            try {
                Network active = cm.getActiveNetwork();
                if (active != null) ordered.add(active);
            } catch (Throwable ignored) {}
            try {
                Network[] all = cm.getAllNetworks();
                if (all != null) {
                    for (Network n : all) {
                        if (n != null && !ordered.contains(n)) ordered.add(n);
                    }
                }
            } catch (Throwable ignored) {}
            Network unvalidated = null;
            String base = null;
            for (Network n : ordered) {
                NetworkCapabilities caps;
                try { caps = cm.getNetworkCapabilities(n); } catch (Throwable ignored) { continue; }
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    base = networkKey(n, caps);
                    break;
                }
                if (unvalidated == null) unvalidated = n;
            }
            if (base == null) {
                if (unvalidated == null) return null;
                NetworkCapabilities caps;
                try { caps = cm.getNetworkCapabilities(unvalidated); } catch (Throwable ignored) { return null; }
                if (caps == null) return null;
                base = networkKey(unvalidated, caps);
            }
            // v1.9-proxyfix: in proxy mode ArcVeePN has no TUN, so
            // ANY TRANSPORT_VPN network is ANOTHER app's VPN (e.g.
            // Nord). Its appearance/disappearance changes the path
            // the proxy's outbound sockets take, so it must change
            // this key — the underlying Wi-Fi/cellular key alone
            // never changes when another VPN comes or goes, so
            // Nord on/off was invisible to this detector and the
            // proxy never re-established. In VPN mode our own TUN
            // is a VPN network, so the suffix is proxy-only (our
            // own TUN coming/going is handled by onRevoke and the
            // start/stop lifecycle, not by this detector).
            if (proxyMode) base += vpnPresenceSuffix(cm);
            return base;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** "|V<handle>" while another VPN network is up, "|V-" when
     *  none. The handle identifies the VPN network, so a
     *  different VPN — or the same one re-established — also
     *  changes the key. */
    private static String vpnPresenceSuffix(ConnectivityManager cm) {
        try {
            if (cm == null) return "|V-";
            Network[] all = cm.getAllNetworks();
            if (all != null) {
                for (Network n : all) {
                    if (n == null) continue;
                    NetworkCapabilities caps;
                    try { caps = cm.getNetworkCapabilities(n); } catch (Throwable ignored) { continue; }
                    if (caps == null) continue;
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                    return "|V" + n.getNetworkHandle();
                }
            }
        } catch (Throwable ignored) {}
        return "|V-";
    }

    /** Network identity + transport type: the key changes when
     *  the phone moves to a different network (new handle) or a
     *  different transport (Wi-Fi <-> cellular). */
    private static String networkKey(Network n, NetworkCapabilities caps) {
        StringBuilder sb = new StringBuilder();
        try { sb.append(n.getNetworkHandle()); } catch (Throwable ignored) { sb.append("?"); }
        sb.append(':');
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) sb.append('W');
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) sb.append('C');
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) sb.append('E');
        return sb.toString();
    }

    /** sb15: true (and the pending change CONSUMED) when a real
     *  underlying-network change was recorded at least
     *  NET_CHANGE_DEBOUNCE_MS ago. Flapping updates the pending
     *  entry's timestamp, so consumption always happens 3 s after
     *  the LAST change — one reconnect to the final network.
     *  Called from MainActivity's 1 s poll, VPN + connected only. */
    public static boolean consumeNetChangeIfDue() {
        try {
            if (pendingNetAtMs == 0 || pendingNetKey == null) return false;
            if (System.currentTimeMillis() - pendingNetAtMs < NET_CHANGE_DEBOUNCE_MS) return false;
            pendingNetAtMs = 0;
            pendingNetKey = null;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Called by the sing-box core (CommandServerHandler.serviceStop). */
    public void requestStopFromCore() {
        new Thread(() -> {
            try { stopTunnel(); } catch (Throwable ignored) {}
            try { stopSelf(); } catch (Throwable ignored) {}
        }, "vpeen-sb-corestop").start();
    }

    private Notification buildNotification() {
        // sb10: proxy mode gets its own channel + text (no VPN,
        // no key icon — the user must be able to tell the modes
        // apart in the notification shade).
        boolean proxy = proxyMode;
        String channelId = proxy ? "vpeen_proxy_sb" : "vpeen_vpn_sb";
        String channelName = proxy ? "ArcVeePN Proxy" : "ArcVeePN VPN";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, channelName, NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, channelId);
        } else {
            nb = new Notification.Builder(this);
        }
        String title = proxy
                ? "ArcVeePN — Proxy Mode"
                : "ArcVeePN connected";
        String text = proxy
                ? "Local proxy: 127.0.0.1:1080 and 8080 — open the app to disconnect"
                : "Region: " + currentRegion + " — open the app to disconnect";
        // sb12: the small icon is what the status bar shows. It
        // was android.R.drawable.ic_dialog_info — the "!"
        // exclamation mark the user reported. It is now the app's
        // own mark: a white arc + dot silhouette on transparency
        // (res/drawable*/ic_stat_arc.png, derived from the
        // supplied ArcVeePN logo — never the JPG itself, which
        // has no alpha and a dark background, so Android would
        // render it as a solid block). The large icon stays the
        // full-color launcher icon (it was already correct when
        // the notification is pulled down).
        return nb.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_arc)
                .setLargeIcon(BitmapFactory.decodeResource(
                        getResources(), R.mipmap.ic_launcher))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public synchronized void stopTunnel() {
        // DIAG (v1.9-fix): entry line — pairs with the
        // runner.stop lines, so the log shows whether a previous
        // tunnel was fully torn down before the next connect.
        try { ConnectLog.log(this, "stopTunnel begin running=" + running); } catch (Throwable ignored) {}
        // 3.1 fix: stop the Firefox renewal loop first — it
        // must never refresh a token for a dead tunnel — and
        // drop the in-memory ProxyPass with the tunnel.
        try { stopFirefoxRenewalLoop(); } catch (Throwable ignored) {}
        firefoxProxyPass = null;
        firefoxAuthLostPending = false;
        // sb15: stop watching the network first — no event may
        // fire while the tunnel is being torn down.
        try { unregisterNetCallback(); } catch (Throwable ignored) {}
        running = false;
        runningServer = null;
        currentServer = null;
        connectedAtMs = 0;
        status = "Disconnected";
        resetTraffic();
        // 3.1 fix8: persist the disconnect (the tile reads
        // this durable state) and stop the watch thread.
        try { AppSettings.setTunnelState(this, false, ""); } catch (Throwable ignored) {}
        try { stopTunnelWatch(); } catch (Throwable ignored) {}
        try { if (runner != null) runner.stop(); } catch (Throwable ignored) {}
        runner = null;
        try { stopForeground(true); } catch (Throwable ignored) {}
        // 3.1 fix3: push the disconnected state to the
        // home-screen widget and the Quick Settings tile.
        try { ArcVeePNWidget.updateAll(this); } catch (Throwable ignored) {}
        try { ArcVeePNTileService.requestTileUpdate(this); } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        try { stopTunnel(); } catch (Throwable ignored) {}
        try { super.onDestroy(); } catch (Throwable ignored) {}
    }

    @Override
    public void onRevoke() {
        try { stopTunnel(); } catch (Throwable ignored) {}
        try { super.onRevoke(); } catch (Throwable ignored) {}
    }

    /** Protect a raw socket so upstream traffic bypasses the TUN. */
    public static void protectSocket(java.net.Socket s, VpnService svc) {
        try { svc.protect(s); } catch (Throwable ignored) {}
    }
}
