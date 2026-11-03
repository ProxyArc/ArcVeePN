package com.vpeen.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import hev.htproxy.TProxyService;

/**
 * Android port of VPeeN's tunnel mode (desktop: tun2socks + wintun/utun).
 * Chain: app traffic -> TUN (hev-socks5-tunnel, prebuilt .so from
 * heiher/hev-socks5-tunnel 2.19.3) -> local SOCKS5 127.0.0.1:1080
 * (LocalSocksServer) -> VeePN HTTPS CONNECT upstream.
 * DNS uses hev's mapdns, like Orbot: queries to 198.18.0.2 are
 * mapped to fake 240/0 IPs, then sent as domain CONNECTs, so no
 * UDP DNS relay is needed for normal browsing.
 */
public class VpnTunnelService extends VpnService {
    public static final String ACTION_CONNECT = "com.vpeen.android.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vpeen.android.DISCONNECT";
    public static final String EXTRA_REGION = "region";

    public static volatile Server currentServer;
    public static volatile String currentRegion = "";
    public static volatile boolean running = false;
    public static volatile String status = "قطع";
    public static volatile long connectedAtMs = 0;

    public static long[] tunnelStats() {
        try {
            long[] s = TProxyService.TProxyGetStats();
            return s != null ? s : new long[]{0, 0, 0, 0};
        } catch (Throwable t) {
            return new long[]{0, 0, 0, 0};
        }
    }

    private static final String TAG = "ArcVeePN";
    private static final String VIRTUAL_IPV4 = "198.18.0.1";
    private static final String VIRTUAL_IPV6 = "fc00::1";
    private static final String FAKE_DNS = "198.18.0.2";

    private ParcelFileDescriptor tunFd;
    private LocalSocksServer localProxy;
    private Thread hevThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopTunnel();
            stopSelf();
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
            } catch (Exception e) {
                Log.e(TAG, "startTunnel failed", e);
                status = "خطا: " + e.getMessage();
                running = false;
                stopTunnel();
            }
        }, "vpeen-start").start();
    }

    private synchronized void startTunnel() throws Exception {
        if (running) return;
        Server server = currentServer;
        if (server == null) throw new Exception("سروری انتخاب نشده");
        status = "در حال اتصال…";
        // 1. Resolve upstream hostnames BEFORE the TUN exists.
        for (String addr : server.addresses) {
            try {
                InetAddress ip = InetAddress.getByName(addr);
                server.resolvedIp = ip.getHostAddress();
                break;
            } catch (Exception ignored) {}
        }
        if (server.resolvedIp == null) {
            Log.w(TAG, "could not pre-resolve upstream, will try hostname (may loop)");
        }
        // 2. Local SOCKS5/HTTP bridge.
        Upstream.Protector protector = s -> {
            try { protect(s); } catch (Exception ignored) {}
        };
        localProxy = new LocalSocksServer(server, protector);
        localProxy.start();
        // 3. TUN interface.
        Builder b = new Builder();
        b.setSession("ArcVeePN");
        b.addAddress(VIRTUAL_IPV4, 32);
        b.addAddress(VIRTUAL_IPV6, 128);
        b.addRoute("0.0.0.0", 0);
        b.addRoute("::", 0);
        b.addDnsServer(FAKE_DNS);
        b.setMtu(1400);
        try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false);
            b.allowFamily(OsConstants.AF_INET);
            b.allowFamily(OsConstants.AF_INET6);
        }
        tunFd = b.establish();
        if (tunFd == null) throw new Exception("ساخت TUN ناموفق بود (مجوز VPN؟)");
        // 4. Foreground notification (required for a VPN service).
        startForeground(1, buildNotification());
        // 5. hev tun2socks config + start (blocks in its own thread).
        File conf = new File(getCacheDir(), "tproxy.conf");
        String cfg = "misc:\n"
                + "  log-level: warn\n"
                + "  task-stack-size: 81920\n"
                + "tunnel:\n"
                + "  ipv4: " + VIRTUAL_IPV4 + "\n"
                + "  ipv6: '" + VIRTUAL_IPV6 + "'\n"
                + "  mtu: 1400\n"
                + "socks5:\n"
                + "  port: " + LocalSocksServer.SOCKS_PORT + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "mapdns:\n"
                + "  address: " + FAKE_DNS + "\n"
                + "  port: 53\n"
                + "  network: 240.0.0.0\n"
                + "  netmask: 240.0.0.0\n"
                + "  cache-size: 10000\n";
        try (FileOutputStream fos = new FileOutputStream(conf, false)) {
            fos.write(cfg.getBytes(StandardCharsets.UTF_8));
        }
        final int fd = tunFd.getFd();
        final String confPath = conf.getAbsolutePath();
        hevThread = new Thread(() -> {
            try {
                TProxyService.TProxyStartService(confPath, fd);
            } catch (Throwable t) {
                Log.e(TAG, "hev failed", t);
            }
        }, "vpeen-hev");
        hevThread.setDaemon(true);
        hevThread.start();
        running = true;
        connectedAtMs = System.currentTimeMillis();
        status = "متصل به " + currentRegion;
        Log.i(TAG, "tunnel up, region=" + currentRegion + " server=" + server);
    }

    private Notification buildNotification() {
        String channelId = "vpeen_vpn";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, "ArcVeePN VPN", NotificationManager.IMPORTANCE_LOW);
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
        return nb.setContentTitle("ArcVeePN متصل است")
                .setContentText("منطقه: " + currentRegion + " — برای قطع، برنامه را باز کنید")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public synchronized void stopTunnel() {
        running = false;
        connectedAtMs = 0;
        status = "قطع";
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
        try { if (tunFd != null) tunFd.close(); } catch (Exception ignored) {}
        tunFd = null;
        try { if (localProxy != null) localProxy.stop(); } catch (Exception ignored) {}
        localProxy = null;
        try { stopForeground(true); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        super.onRevoke();
    }

    /** Protect a raw socket so upstream traffic bypasses the TUN. */
    public static void protectSocket(Socket s, VpnService svc) {
        try { svc.protect(s); } catch (Exception ignored) {}
    }
}
