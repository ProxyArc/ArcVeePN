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
 * EXPERIMENTAL sing-box build (ArcVeePN SB) — v1.8-sb2.
 * Same chain as stable v1.7 except the TUN core: app traffic ->
 * TUN (Android VpnService, fd handed to sing-box via
 * PlatformInterface.openTun) -> sing-box `http` outbound
 * (TLS + Basic auth to the VeePN proxy, SNI = VeePN host).
 * The Java LocalSocksServer (127.0.0.1:1080/8080) is KEPT: it is
 * what MainActivity uses for the exit-IP check and the tiny-GET
 * tunnel test, and it dials the upstream directly with protect().
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

    public static volatile Server currentServer;
    public static volatile String currentRegion = "";
    // sb14: the server the RUNNING tunnel was actually built
    // for. MainActivity uses this — not currentServer, which
    // is the *intended* server and is overwritten by the next
    // attempt before the service reads it — to verify which
    // tunnel came up when two connects race. Set inside
    // startTunnel() from its local server, cleared in
    // stopTunnel().
    public static volatile Server runningServer = null;
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

    // sb9: set by MainActivity before Connect — the region code,
    // whether this is a Fastest connect, and extra servers (ranked
    // candidates + warm cache) for the per-region outbounds and
    // the urltest group in SingBoxConfig.
    public static volatile String connectRegionCode = "";
    public static volatile boolean fastestMode = false;
    public static volatile java.util.List<Server> extraServers = null;

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

    private LocalSocksServer localProxy;
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
        Server server = currentServer;
        if (server == null) throw new Exception("No server selected");
        // sb14: record which server this tunnel is actually
        // built for, BEFORE running=true (running is set at
        // the end of this method, so runningServer is always
        // correct by the time running is true).
        runningServer = server;
        status = "Connecting…";
        // DIAG (sb3d): every step is logged to connect.log so a
        // hung Connect shows exactly where it stopped. Hostnames
        // only — never username/password.
        ConnectLog.log(this, "startTunnel begin region=" + currentRegion
                + " server=" + server.host() + ":" + server.port);
        // 1. Resolve upstream hostname BEFORE the TUN exists.
        for (String addr : server.addresses) {
            try {
                InetAddress ip = InetAddress.getByName(addr);
                server.resolvedIp = ip.getHostAddress();
                break;
            } catch (Throwable ignored) {}
        }
        if (server.resolvedIp == null) {
            Log.w(TAG, "could not pre-resolve upstream, sing-box will resolve via local-dns");
            ConnectLog.log(this, "resolve FAILED, will let sing-box resolve");
        } else {
            ConnectLog.log(this, "resolve OK ip=" + server.resolvedIp);
        }
        // 2. Local SOCKS5/HTTP bridge (kept from v1.7: exit-IP check
        // and tunnel test in MainActivity use it). VPN MODE ONLY:
        // in proxy mode sing-box itself serves 127.0.0.1:1080
        // (mixed) and 127.0.0.1:8080 (http) — starting the Java
        // bridge too would be a second listener on the same ports
        // (bind failure). The exit-IP check still works in proxy
        // mode: it talks SOCKS5 to 127.0.0.1:1080, which is then
        // sing-box's mixed inbound.
        boolean proxy = proxyMode;
        if (!proxy) {
            Upstream.Protector protector = s -> {
                try { protect(s); } catch (Throwable ignored) {}
            };
            localProxy = new LocalSocksServer(server, protector);
            localProxy.start();
            ConnectLog.log(this, "localProxy started");
        } else {
            ConnectLog.log(this, "proxy mode: LocalSocksServer skipped, sing-box owns 1080/8080");
        }
        // 3. Foreground notification (required for a long-running service).
        startForeground(1, buildNotification());
        ConnectLog.log(this, "foreground OK mode=" + (proxy ? "proxy" : "vpn"));
        // 4. sing-box config + lazy core load (Connect only).
        String config = SingBoxConfig.build(server, getPackageName(),
                connectRegionCode, fastestMode, extraServers, proxy);
        Log.i(TAG, "sing-box config:\n" + config);
        ConnectLog.log(this, "config built len=" + config.length());
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
        // 5. Up.
        running = true;
        connectedAtMs = System.currentTimeMillis();
        status = "Connected to " + currentRegion;
        ConnectLog.log(this, "running=true");
        Log.i(TAG, "sing-box tunnel up, region=" + currentRegion + " server=" + server);
        // sb15: watch the underlying network from now on (VPN
        // mode only) — a change re-establishes the tunnel.
        try { registerNetCallback(); } catch (Throwable ignored) {}
    }

    // ---------- sb15: underlying-network-change detection ----------

    /** Register the network callback (VPN mode only). The baseline
     *  is the underlying network we are already on — registering
     *  must NOT fire for the current network. */
    private void registerNetCallback() {
        if (proxyMode) return;
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
            for (Network n : ordered) {
                NetworkCapabilities caps;
                try { caps = cm.getNetworkCapabilities(n); } catch (Throwable ignored) { continue; }
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return networkKey(n, caps);
                }
                if (unvalidated == null) unvalidated = n;
            }
            if (unvalidated == null) return null;
            NetworkCapabilities caps;
            try { caps = cm.getNetworkCapabilities(unvalidated); } catch (Throwable ignored) { return null; }
            if (caps == null) return null;
            return networkKey(unvalidated, caps);
        } catch (Throwable ignored) {
            return null;
        }
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
        // sb15: stop watching the network first — no event may
        // fire while the tunnel is being torn down.
        try { unregisterNetCallback(); } catch (Throwable ignored) {}
        running = false;
        runningServer = null;
        connectedAtMs = 0;
        status = "Disconnected";
        resetTraffic();
        try { if (runner != null) runner.stop(); } catch (Throwable ignored) {}
        runner = null;
        try { if (localProxy != null) localProxy.stop(); } catch (Throwable ignored) {}
        localProxy = null;
        try { stopForeground(true); } catch (Throwable ignored) {}
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
