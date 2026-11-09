package com.vpeen.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.libbox.AutoRedirectHandler;
import io.nekohasekai.libbox.AutoRedirectSession;
import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.ExchangeContext;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.SystemProxyStatus;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

/**
 * libbox PlatformInterface + CommandServerHandler for ArcVeePN SB.
 * Ported from FoxyVPN's FoxyPlatformInterface (same Vauth libbox.aar),
 * trimmed to what this app needs: openTun via VpnService.Builder,
 * protect() for every outbound socket fd, and no-op/error stubs for
 * the features we do not use (shell, bridge, auto-redirect, SFTP…).
 */
public class SingBoxPlatform implements PlatformInterface, CommandServerHandler {
    private static final String TAG = "ArcVeePN";

    private final VpnTunnelService service;
    private volatile ParcelFileDescriptor tunFd;
    private volatile InterfaceUpdateListener defaultInterfaceListener;

    public SingBoxPlatform(VpnTunnelService service) {
        this.service = service;
    }

    // ---------- PlatformInterface: the two that matter ----------

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public void autoDetectInterfaceControl(int fd) throws Exception {
        // sing-box calls this for every outbound socket: keep it off the TUN.
        if (!service.protect(fd)) {
            throw new IOException("VPN refused native socket protection");
        }
    }

    @Override
    public int openTun(TunOptions options) throws Exception {
        Log.i(TAG, "openTun MTU=" + options.getMTU());
        ConnectLog.log(service, "openTun called mtu=" + options.getMTU());
        VpnService.Builder b = service.new Builder().setSession("ArcVeePN")
                .setMtu(options.getMTU());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false);
            b.setBlocking(true);
        }
        boolean hasIpv4 = false, hasIpv6 = false;
        RoutePrefixIterator inet4 = options.getInet4Address();
        if (inet4 != null) {
            while (inet4.hasNext()) {
                RoutePrefix p = inet4.next();
                b.addAddress(p.address(), p.prefix());
                hasIpv4 = true;
            }
        }
        RoutePrefixIterator inet6 = options.getInet6Address();
        if (inet6 != null) {
            while (inet6.hasNext()) {
                RoutePrefix p = inet6.next();
                b.addAddress(p.address(), p.prefix());
                hasIpv6 = true;
            }
        }
        if (!hasIpv4 && !hasIpv6) {
            b.addAddress("198.18.0.1", 30);
            hasIpv4 = true;
        }
        if (options.getAutoRoute()) {
            boolean anyRoute = false;
            RoutePrefixIterator r4 = options.getInet4RouteRange();
            if (r4 != null) {
                while (r4.hasNext()) {
                    RoutePrefix p = r4.next();
                    b.addRoute(p.address(), p.prefix());
                    anyRoute = true;
                }
            }
            RoutePrefixIterator r6 = options.getInet6RouteRange();
            if (r6 != null) {
                while (r6.hasNext()) {
                    RoutePrefix p = r6.next();
                    b.addRoute(p.address(), p.prefix());
                    anyRoute = true;
                }
            }
            if (!anyRoute) {
                if (hasIpv4) b.addRoute("0.0.0.0", 0);
                if (hasIpv6) b.addRoute("::", 0);
            }
            boolean addedDns = false;
            try {
                StringIterator dnsIter = options.getDNSServerAddress();
                if (dnsIter != null) {
                    while (dnsIter.hasNext()) {
                        String dns = dnsIter.next();
                        if (dns != null && !dns.isEmpty()) {
                            b.addDnsServer(dns);
                            b.addRoute(dns, dns.contains(":") ? 128 : 32);
                            addedDns = true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (!addedDns) {
                if (hasIpv4) {
                    b.addDnsServer("198.18.0.2");
                    b.addRoute("198.18.0.2", 32);
                } else if (hasIpv6) {
                    b.addDnsServer("fc00::2");
                    b.addRoute("fc00::2", 128);
                }
            }
        }
        // sb11 SPLIT TUNNELING FIX — NEVER mix allow and disallow
        // lists on one Builder. Android's VpnService.Builder docs:
        // "A Builder may have only a set of allowed applications
        // OR a set of disallowed ones, but not both. Calling this
        // method after addDisallowedApplication has already been
        // called, or vice versa, will throw an
        // UnsupportedOperationException." sb10 called
        // addDisallowedApplication(own package) FIRST, then
        // addAllowedApplication(selected) in "only" mode — every
        // allow call threw, the per-call catch swallowed it, and
        // the TUN came up with only the app itself disallowed,
        // so ALL apps were tunneled — exactly the reported
        // symptom ("Only" + Telegram, yet everything tunneled).
        // The settings themselves DO flow (MainActivity hands
        // splitMode/splitPackages to the service before Connect);
        // only the application was broken.
        // - "only" (non-empty set): ONLY addAllowedApplication
        //   for the selected packages. The VPN app itself is
        //   never in the list, and per the API docs only listed
        //   apps use the VPN — so the app's own traffic goes
        //   direct automatically; do NOT also disallow self here
        //   (that would be the forbidden mix).
        // - "bypass" (non-empty set): ONLY addDisallowedApplication
        //   — self + selected packages (same list type, no mix).
        // - "off" or empty set: only self disallowed.
        // The config's exclude_package holds only our own package
        // (SingBoxConfig) and is skipped below, so it can never
        // mix either. Applies on connect; a change takes effect
        // on the NEXT connect (the UI says so).
        String splitMode = VpnTunnelService.splitMode;
        java.util.Set<String> splitPkgs = VpnTunnelService.splitPackages;
        boolean onlyMode = "only".equals(splitMode)
                && splitPkgs != null && !splitPkgs.isEmpty();
        try {
            if (onlyMode) {
                for (String pkg : splitPkgs) {
                    if (pkg == null || pkg.isEmpty()
                            || pkg.equals(service.getPackageName())) continue;
                    try {
                        b.addAllowedApplication(pkg);
                    } catch (Throwable e) {
                        ConnectLog.log(service, "split allow FAILED " + pkg
                                + ": " + e.getClass().getSimpleName());
                    }
                }
            } else {
                // Always keep our own app off the TUN (exit-IP
                // check + API calls in MainActivity must stay
                // direct), plus anything sing-box lists in
                // exclude_package (today: only our own package,
                // skipped here — it can never be disallowed in
                // "only" mode, see above).
                try { b.addDisallowedApplication(service.getPackageName()); } catch (Throwable ignored) {}
                try {
                    StringIterator ex = options.getExcludePackage();
                    if (ex != null) {
                        while (ex.hasNext()) {
                            String pkg = ex.next();
                            if (pkg == null || pkg.isEmpty()
                                    || pkg.equals(service.getPackageName())) continue;
                            try { b.addDisallowedApplication(pkg); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
                if ("bypass".equals(splitMode) && splitPkgs != null) {
                    for (String pkg : splitPkgs) {
                        if (pkg == null || pkg.isEmpty()
                                || pkg.equals(service.getPackageName())) continue;
                        try {
                            b.addDisallowedApplication(pkg);
                        } catch (Throwable e) {
                            ConnectLog.log(service, "split disallow FAILED " + pkg
                                    + ": " + e.getClass().getSimpleName());
                        }
                    }
                }
            }
            ConnectLog.log(service, "split applied mode=" + splitMode
                    + " only=" + onlyMode
                    + " pkgs=" + (splitPkgs == null ? 0 : splitPkgs.size()));
        } catch (Throwable e) {
            ConnectLog.log(service, "split FAILED: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        closeTun();
        ParcelFileDescriptor pfd;
        try {
            pfd = b.establish();
        } catch (Throwable t) {
            ConnectLog.log(service, "establish FAILED: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            throw t;
        }
        if (pfd == null) {
            ConnectLog.log(service, "establish returned null");
            throw new IOException("Builder.establish() returned null");
        }
        tunFd = pfd;
        ConnectLog.log(service, "establish OK fd=" + pfd.getFd());
        notifyDefaultInterface();
        return pfd.getFd();
    }

    public void closeTun() {
        try { if (tunFd != null) tunFd.close(); } catch (Throwable ignored) {}
        tunFd = null;
    }

    // ---------- PlatformInterface: interface monitoring ----------

    @Override
    public boolean useProcFS() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        defaultInterfaceListener = listener;
        notifyDefaultInterface();
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        if (defaultInterfaceListener == listener) defaultInterfaceListener = null;
    }

    public void notifyDefaultInterface() {
        InterfaceUpdateListener l = defaultInterfaceListener;
        if (l == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    service.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) { l.updateDefaultInterface("", -1, false, false); return; }
            Network net = cm.getActiveNetwork();
            if (net == null) { l.updateDefaultInterface("", -1, false, false); return; }
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                l.updateDefaultInterface("", -1, false, false);
                return;
            }
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null || lp.getInterfaceName() == null) {
                l.updateDefaultInterface("", -1, false, false);
                return;
            }
            java.net.NetworkInterface nif = java.net.NetworkInterface.getByName(lp.getInterfaceName());
            int index = nif != null ? nif.getIndex() : -1;
            boolean expensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            boolean constrained = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
            l.updateDefaultInterface(lp.getInterfaceName(), index, expensive, constrained);
        } catch (Throwable e) {
            Log.w(TAG, "notifyDefaultInterface failed", e);
        }
    }

    @Override
    public io.nekohasekai.libbox.NetworkInterfaceIterator getInterfaces() {
        List<io.nekohasekai.libbox.NetworkInterface> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    service.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (lp == null || caps == null) continue;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    String name = lp.getInterfaceName();
                    if (name == null) continue;
                    java.net.NetworkInterface nif = java.net.NetworkInterface.getByName(name);
                    if (nif == null) continue;
                    io.nekohasekai.libbox.NetworkInterface ni =
                            new io.nekohasekai.libbox.NetworkInterface();
                    ni.setName(name);
                    ni.setIndex(nif.getIndex());
                    ni.setMTU(nif.getMTU());
                    List<String> addrs = new ArrayList<>();
                    for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        // v1.9 ROOT-CAUSE FIX: getHostAddress() appends
                        // a %scope suffix to scoped IPv6 addresses
                        // (e.g. fe80::...%wlan0, ::1%lo). Go's
                        // netip.MustParsePrefix rejects IPv6 zones
                        // and PANICS inside service start (network
                        // manager -> UpdateInterfaces -> getInterfaces),
                        // before openTun — the rare connect-time
                        // crash (log died inside startOrReloadService,
                        // no FAILED, no openTun, empty crash.log).
                        // Strip the zone id; the prefix is identical.
                        String host = ia.getAddress().getHostAddress();
                        int pct = host.indexOf('%');
                        if (pct >= 0) host = host.substring(0, pct);
                        addrs.add(host + "/" + ia.getNetworkPrefixLength());
                    }
                    ni.setAddresses(new StrIter(addrs));
                    List<String> dns = new ArrayList<>();
                    for (InetAddress a : lp.getDnsServers()) dns.add(a.getHostAddress());
                    ni.setDNSServer(new StrIter(dns));
                    List<String> gw = new ArrayList<>();
                    for (android.net.RouteInfo r : lp.getRoutes()) {
                        if (r.getDestination() != null
                                && r.getDestination().getPrefixLength() == 0
                                && r.getGateway() != null
                                && !r.getGateway().isAnyLocalAddress()) {
                            gw.add(r.getGateway().getHostAddress());
                        }
                    }
                    ni.setGateway(new StrIter(gw));
                    int type = Libbox.InterfaceTypeOther;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = Libbox.InterfaceTypeWIFI;
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = Libbox.InterfaceTypeCellular;
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = Libbox.InterfaceTypeEthernet;
                    ni.setType(type);
                    int flags = 0;
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        flags |= 0x1 | 0x40; // IFF_UP | IFF_RUNNING
                    }
                    if (nif.isLoopback()) flags |= 0x8;
                    if (nif.isPointToPoint()) flags |= 0x10;
                    try { if (nif.supportsMulticast()) flags |= 0x1000; } catch (Throwable ignored) {}
                    ni.setFlags(flags);
                    ni.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                    out.add(ni);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "getInterfaces failed", e);
        }
        final java.util.Iterator<io.nekohasekai.libbox.NetworkInterface> it = out.iterator();
        return new NetworkInterfaceIterator() {
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public io.nekohasekai.libbox.NetworkInterface next() { return it.next(); }
        };
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress,
            int sourcePort, String destinationAddress, int destinationPort) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ConnectivityManager cm = (ConnectivityManager)
                    service.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                int uid = Process.INVALID_UID;
                try {
                    uid = cm.getConnectionOwnerUid(ipProtocol,
                            new InetSocketAddress(sourceAddress, sourcePort),
                            new InetSocketAddress(destinationAddress, destinationPort));
                } catch (Throwable ignored) {}
                if (uid != Process.INVALID_UID) {
                    String[] pkgs = null;
                    try { pkgs = service.getPackageManager().getPackagesForUid(uid); } catch (Throwable ignored) {}
                    ConnectionOwner owner = new ConnectionOwner();
                    owner.setUserId(uid);
                    owner.setUserName(pkgs != null && pkgs.length > 0 ? pkgs[0] : "");
                    return owner;
                }
            }
        }
        throw new FileNotFoundException("connection owner not found");
    }

    @Override
    public boolean underNetworkExtension() { return false; }

    @Override
    public boolean includeAllNetworks() { return false; }

    @Override
    public WIFIState readWIFIState() {
        try {
            WifiManager wm = (WifiManager) service.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            String ssid = info != null && info.getSSID() != null ? info.getSSID() : "";
            if ("<unknown ssid>".equals(ssid)) ssid = "";
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return new WIFIState(ssid, info != null && info.getBSSID() != null ? info.getBSSID() : "");
        } catch (Throwable e) {
            return new WIFIState("", "");
        }
    }

    @Override public void clearDNSCache() {}
    @Override public void sendNotification(Notification notification) {}
    @Override public void cancelNotification(String identifier, int typeID) {}
    @Override public void startNeighborMonitor(NeighborUpdateListener listener) {}
    @Override public void closeNeighborMonitor(NeighborUpdateListener listener) {}
    @Override public void registerMyInterface(String name) {}
    @Override public boolean usePlatformShell() { return false; }
    @Override public void checkPlatformShell() {}
    @Override public ShellSession openShellSession(PlatformUser user, String command,
            StringIterator args, String workingDir, int width, int height) throws Exception {
        throw new UnsupportedOperationException("shell not supported");
    }
    @Override public PlatformUser lookupUser(String username) throws Exception {
        throw new UnsupportedOperationException("user lookup not supported");
    }
    @Override public String lookupSFTPServer() throws Exception {
        throw new UnsupportedOperationException("sftp not supported");
    }
    @Override public String readSystemSSHHostKey() throws Exception {
        throw new UnsupportedOperationException("ssh not supported");
    }
    @Override public String tailscaleHostname() { return ""; }
    @Override public boolean usePlatformBridge() { return false; }
    @Override public BridgeSession createBridge(BridgeOptions options) throws Exception {
        throw new UnsupportedOperationException("bridge not supported");
    }
    @Override public boolean usePlatformAutoRedirect() { return false; }
    @Override public AutoRedirectSession createAutoRedirect(byte[] options,
            AutoRedirectHandler handler) throws Exception {
        throw new UnsupportedOperationException("auto-redirect not supported");
    }

    // sb6 FIX — firstUsableNetwork(): mirror of FoxyVPN's
    // FoxyVpnService.firstUsableNetwork(). Once the TUN is up,
    // ConnectivityManager.getActiveNetwork() returns the VPN network
    // itself (whose DNS is our fake 198.18.0.2), so a lookup through
    // it loops back into the TUN -> hijack-dns -> remote-dns ->
    // local-dns -> VPN network again, and in-tunnel DNS is dead
    // (Speedtest stuck on "Finding provider"). Instead, pick a
    // REAL underlying network: candidates are the active network
    // first, then every network; skip any TRANSPORT_VPN network,
    // require NET_CAPABILITY_INTERNET, prefer a VALIDATED network,
    // else take the first unvalidated non-VPN one, else null.
    private Network firstUsableNetwork(ConnectivityManager cm) {
        if (cm == null) return null;
        Network[] all;
        try { all = cm.getAllNetworks(); } catch (Throwable ignored) { all = null; }
        List<Network> ordered = new ArrayList<>();
        try {
            Network active = cm.getActiveNetwork();
            if (active != null) ordered.add(active);
        } catch (Throwable ignored) {}
        if (all != null) {
            for (Network n : all) {
                if (n != null && !ordered.contains(n)) ordered.add(n);
            }
        }
        Network unvalidated = null;
        for (Network n : ordered) {
            NetworkCapabilities caps;
            try { caps = cm.getNetworkCapabilities(n); } catch (Throwable ignored) { continue; }
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n;
            if (unvalidated == null) unvalidated = n;
        }
        return unvalidated;
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return new LocalDNSTransport() {
            @Override public boolean raw() { return false; }
            @Override public void exchange(ExchangeContext ctx, byte[] message) {
                // sb15 HARDENING: this is called from native Go
                // (libbox DNS transport). If ctx.errorCode itself
                // throws (context already closed/timed out on the
                // Go side), the Throwable would escape a
                // native-invoked callback — nothing here may ever
                // throw, so the call is guarded too.
                try { ctx.errorCode(4); } catch (Throwable ignored) {}
            }
            @Override public void lookup(ExchangeContext ctx, String network, String domain) {
                // sb15 HARDENING: lookup() is called from native
                // Go, so NO Throwable may ever escape it — a Java
                // exception crossing into the Go runtime kills
                // the process. Every ctx call (success and both
                // errorCode paths, including the one inside the
                // catch) is guarded, so the worst case is a
                // failed lookup, never a crash.
                try {
                    lookupGuarded(ctx, network, domain);
                } catch (Throwable ignored) {
                    try { ctx.errorCode(2); } catch (Throwable ignored2) {}
                }
            }
            private void lookupGuarded(ExchangeContext ctx, String network, String domain) {
                String clean = domain.endsWith(".")
                        ? domain.substring(0, domain.length() - 1) : domain;
                try {
                    ConnectivityManager cm = (ConnectivityManager)
                            service.getSystemService(Context.CONNECTIVITY_SERVICE);
                    // sb6: NOT getActiveNetwork() — once the TUN is up
                    // that returns the VPN network itself (DNS loop).
                    Network net = firstUsableNetwork(cm);
                    if (net == null) {
                        try { ctx.errorCode(2); } catch (Throwable ignored) {}
                        return;
                    }
                    InetAddress[] addrs = net.getAllByName(clean);
                    List<String> out = new ArrayList<>();
                    for (InetAddress a : addrs) {
                        if ("ip4".equals(network) && a instanceof Inet6Address) continue;
                        if ("ip6".equals(network) && !(a instanceof Inet6Address)) continue;
                        // v1.9 DEFENSIVE: same %scope strip as
                        // getInterfaces() — a scoped IPv6 DNS answer
                        // must never reach ctx.success()'s Unwrap().
                        String host = a.getHostAddress();
                        int pct = host.indexOf('%');
                        if (pct >= 0) host = host.substring(0, pct);
                        out.add(host);
                    }
                    // sb7: never hand the Go side an EMPTY success —
                    // an empty answer string must be an error (code 2),
                    // not a success with no addresses.
                    if (out.isEmpty()) {
                        try { ctx.errorCode(2); } catch (Throwable ignored) {}
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < out.size(); i++) {
                        if (i > 0) sb.append('\n');
                        sb.append(out.get(i));
                    }
                    try { ctx.success(sb.toString()); } catch (Throwable ignored) {}
                } catch (Throwable e) {
                    Log.w(TAG, "localDNSTransport lookup failed: " + e.getMessage());
                    try { ctx.errorCode(2); } catch (Throwable ignored) {}
                }
            }
        };
    }

    // ---------- CommandServerHandler ----------

    @Override public int connectSSHAgent() { return -1; }
    @Override public SystemProxyStatus getSystemProxyStatus() { return new SystemProxyStatus(); }
    @Override public void serviceReload() {
        throw new UnsupportedOperationException("Reconnect through ArcVeePN to reload");
    }
    @Override public void serviceStop() {
        service.requestStopFromCore();
    }
    @Override public void setSystemProxyEnabled(boolean enabled) {}
    @Override public void triggerNativeCrash() {}
    @Override public void writeDebugMessage(String message) {
        if (message == null) return;
        String m = message.toLowerCase();
        if (m.contains("error") || m.contains("fatal")) Log.e(TAG, "sing-box: " + message);
        else if (m.contains("warn")) Log.w(TAG, "sing-box: " + message);
        else Log.i(TAG, "sing-box: " + message);
        // sb5: also record Go-side messages in the SILENT connect
        // log file (no on-screen display). Best-effort, never throws.
        try { ConnectLog.log(service, "sb: " + message); } catch (Throwable ignored) {}
    }

    // ---------- small helper ----------

    static final class StrIter implements StringIterator {
        private final List<String> list;
        private int pos;
        StrIter(List<String> list) { this.list = list; }
        @Override public int len() { return list.size(); }
        @Override public boolean hasNext() { return pos < list.size(); }
        @Override public String next() {
            return pos < list.size() ? list.get(pos++) : "";
        }
    }
}
