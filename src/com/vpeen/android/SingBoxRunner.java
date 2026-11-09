package com.vpeen.android;

import android.util.Log;

import java.io.File;
import java.util.concurrent.TimeUnit;

import io.nekohasekai.libbox.CommandClient;
import io.nekohasekai.libbox.CommandClientHandler;
import io.nekohasekai.libbox.CommandClientOptions;
import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.ConnectionEvents;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LogIterator;
import io.nekohasekai.libbox.OutboundGroupItemIterator;
import io.nekohasekai.libbox.OutboundGroupIterator;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SetupOptions;
import io.nekohasekai.libbox.StatusMessage;
import io.nekohasekai.libbox.StringIterator;

/**
 * sing-box core runner. This is the ONLY app class (besides
 * SingBoxPlatform, which it instantiates) that references any
 * io.nekohasekai.libbox type.
 *
 * It is loaded reflectively by VpnTunnelService ONLY on Connect,
 * so touching any libbox class — whose go/Seq static initializer
 * dlopens the 46 MB libbox.so — can never happen at app launch.
 * If loading or starting fails, the Throwable propagates to
 * VpnTunnelService, which turns it into a visible status string
 * instead of an uncaught process kill.
 *
 * Ordering fix vs sb1: Libbox.setup() (which does the native
 * load) now runs BEFORE Libbox.checkConfig(), not after it.
 */
public class SingBoxRunner implements TunnelRunner {
    private static final String TAG = "ArcVeePN";
    private static volatile boolean libboxSetupDone = false;

    private final VpnTunnelService service;
    private SingBoxPlatform platform;
    private CommandServer commandServer;
    private CommandClient statsClient;
    private Thread statsThread;

    public SingBoxRunner(VpnTunnelService service) {
        this.service = service;
    }

    private static synchronized void ensureLibboxSetup(VpnTunnelService svc) throws Throwable {
        if (libboxSetupDone) {
            ConnectLog.log(svc, "setup already done");
            return;
        }
        ConnectLog.log(svc, "setup begin");
        try {
            System.loadLibrary("box");
            SetupOptions options = new SetupOptions();
            options.setBasePath(svc.getFilesDir().getAbsolutePath());
            options.setWorkingPath(svc.getFilesDir().getAbsolutePath());
            options.setTempPath(svc.getCacheDir().getAbsolutePath());
            options.setLogMaxLines(5000);
            Libbox.setup(options);
            Log.i(TAG, "libbox setup done, sing-box version=" + Libbox.version()
                    + " go=" + Libbox.goVersion());
            ConnectLog.log(svc, "setup OK sing-box=" + Libbox.version()
                    + " go=" + Libbox.goVersion());
            libboxSetupDone = true;
        } catch (Throwable t) {
            ConnectLog.log(svc, "setup FAILED: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            throw new Exception("sing-box failed to load: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    @Override
    public synchronized void start(String config) throws Throwable {
        // 1. Native setup FIRST (sb1 called checkConfig before this).
        ensureLibboxSetup(service);
        // 2. Validate the generated config.
        ConnectLog.log(service, "checkConfig begin");
        try {
            Libbox.checkConfig(config);
        } catch (Throwable t) {
            ConnectLog.log(service, "checkConfig FAILED: " + t.getMessage());
            throw new Exception("sing-box config rejected: " + t.getMessage(), t);
        }
        ConnectLog.log(service, "checkConfig OK");
        // 3. Command server (FoxyVPN pattern): it owns the sing-box
        // service; openTun() is called by the core during start.
        try { new File(service.getFilesDir(), "command.sock").delete(); } catch (Throwable ignored) {}
        try { new File(service.getFilesDir(), "cache.db").delete(); } catch (Throwable ignored) {}
        platform = new SingBoxPlatform(service);
        commandServer = new CommandServer(platform, platform);
        ConnectLog.log(service, "commandServer created");
        try {
            commandServer.start();
        } catch (Throwable t) {
            ConnectLog.log(service, "commandServer.start FAILED: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            throw t;
        }
        ConnectLog.log(service, "commandServer.start OK");
        ConnectLog.log(service, "startOrReloadService begin");
        try {
            commandServer.startOrReloadService(config, new OverrideOptions());
        } catch (Throwable t) {
            ConnectLog.log(service, "startOrReloadService FAILED: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            throw t;
        }
        ConnectLog.log(service, "startOrReloadService OK");
        // 4. Traffic stats via the CommandClient status stream.
        VpnTunnelService.resetTraffic();
        startStatsClient();
        ConnectLog.log(service, "stats client started");
    }

    // sb9: the stats thread used to loop on
    // VpnTunnelService.running — but it is spawned HERE, before
    // startTunnel() sets running=true, so if the OS scheduled it
    // first it exited on its first check and no byte count ever
    // arrived (watchdogs then "optimized" ~1–2.5 min after every
    // connect). The thread now loops on its own flag, set true
    // before start and false in stop().
    private volatile boolean statsActive = false;
    private volatile boolean statsFirstOkLogged = false;
    private volatile boolean statsFirstFailLogged = false;

    private void startStatsClient() {
        statsActive = true;
        statsFirstOkLogged = false;
        statsFirstFailLogged = false;
        statsThread = new Thread(() -> {
            while (statsActive) {
                CommandClientHandler handler = new CommandClientHandler() {
                    @Override public void connected() {}
                    @Override public void disconnected(String message) {
                        Log.w(TAG, "stats stream disconnected: " + message);
                    }
                    @Override public void writeStatus(StatusMessage m) {
                        if (m != null && m.getTrafficAvailable()) {
                            VpnTunnelService.setTraffic(m.getUplinkTotal(), m.getDownlinkTotal());
                            if (!statsFirstOkLogged) {
                                statsFirstOkLogged = true;
                                ConnectLog.log(service, "stats stream OK up="
                                        + m.getUplinkTotal() + " down=" + m.getDownlinkTotal());
                            }
                        }
                    }
                    @Override public void clearLogs() {}
                    @Override public void setDefaultLogLevel(int level) {}
                    @Override public void writeLogs(LogIterator messages) {}
                    @Override public void writeGroups(OutboundGroupIterator groups) {}
                    @Override public void writeOutbounds(OutboundGroupItemIterator outbounds) {}
                    @Override public void initializeClashMode(StringIterator modes, String current) {}
                    @Override public void updateClashMode(String mode) {}
                    @Override public void writeConnectionEvents(ConnectionEvents events) {}
                };
                try {
                    CommandClientOptions opts = new CommandClientOptions();
                    opts.addCommand(Libbox.CommandStatus);
                    opts.setStatusInterval(TimeUnit.SECONDS.toNanos(2));
                    statsClient = new CommandClient(handler, opts);
                    statsClient.connect();
                    // connect() returns after the stream ends; loop
                    // reconnects while the tunnel is up.
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    Log.w(TAG, "stats client unavailable: " + t.getMessage());
                    if (!statsFirstFailLogged) {
                        statsFirstFailLogged = true;
                        ConnectLog.log(service, "stats client FAILED: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                } finally {
                    try { if (statsClient != null) statsClient.disconnect(); } catch (Throwable ignored) {}
                    statsClient = null;
                }
            }
        }, "vpeen-sb-stats");
        statsThread.setDaemon(true);
        statsThread.start();
    }

    @Override
    public synchronized void stop() {
        statsActive = false;
        try { if (statsClient != null) statsClient.disconnect(); } catch (Throwable ignored) {}
        statsClient = null;
        try { if (statsThread != null) statsThread.interrupt(); } catch (Throwable ignored) {}
        statsThread = null;
        // DIAG (v1.9-fix): log whether the previous service
        // actually stopped cleanly — the 12:36 crash came after
        // proxy/split tests in the same process, so a half-dead
        // previous service is a live hypothesis for the next
        // start. Silent file only.
        try { ConnectLog.log(service, "runner.stop begin"); } catch (Throwable ignored) {}
        try {
            if (commandServer != null) commandServer.closeService();
            try { ConnectLog.log(service, "runner.stop closeService OK"); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            try { ConnectLog.log(service, "runner.stop closeService FAILED: "
                    + e.getClass().getSimpleName()); } catch (Throwable ignored) {}
        }
        try {
            if (commandServer != null) commandServer.close();
            try { ConnectLog.log(service, "runner.stop close OK"); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            try { ConnectLog.log(service, "runner.stop close FAILED: "
                    + e.getClass().getSimpleName()); } catch (Throwable ignored) {}
        }
        commandServer = null;
        try { if (platform != null) platform.closeTun(); } catch (Throwable ignored) {}
        platform = null;
    }
}
