package com.vpeen.android;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * sing-box (libbox) config for the ArcVeePN SB build.
 * SS-only: one `shadowsocks` outbound (buildServer) or one
 * `vmess` outbound (buildVmess, for backup-subscription
 * servers) per connect — one server, one attempt, no urltest
 * group, no extra-servers outbounds. TUN inbound (platform
 * openTun) in VPN mode; a `mixed` inbound on 127.0.0.1:1080
 * exists in VPN mode too (the v4 fix) — the app's exit-IP
 * check goes through it. DoH DNS through the tunnel. No
 * gvisor stack is requested (Vauth's build has no with_gvisor
 * tag — the default stack applies, exactly like FoxyVPN).
 *
 * sb9: Clash API enabled (experimental.clash_api, localhost
 * 127.0.0.1:9090, no secret) so the app can read sing-box's OWN
 * measurements (delay endpoint per outbound, urltest results).
 *
 * sb10: proxy mode. When proxyMode is true there is NO tun
 * inbound at all — instead a `mixed` inbound (SOCKS5 + HTTP
 * CONNECT) on 127.0.0.1:1080 and an `http` inbound on
 * 127.0.0.1:8080 serve the local proxy. Both route final to the
 * selected outbound, exactly like the TUN did. No hijack-dns
 * rules in proxy mode (there is no TUN traffic to hijack); the
 * DNS section is kept identical because the outbounds and the
 * route still reference local-dns as their domain resolver.
 */
public final class SingBoxConfig {
    private SingBoxConfig() {}

    /**
     * Server (TEST): sing-box config for ONE Server Shadowsocks
     * server. Log debug, local-dns + DoH remote-dns
     * (1.1.1.1, detour proxy-out), TUN in VPN mode / mixed
     * 1080 + http 8080 in proxy mode, same route rules, same
     * clash_api — the single outbound "proxy-out" is type
     * "shadowsocks" (server = IP literal, so no TLS object,
     * no username, no domain_resolver). No extra-servers
     * outbounds, no urltest group: one server, one attempt.
     */
    public static String buildServer(Server server, String ownPackage,
            boolean proxyMode) throws Exception {
        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();
        dnsServers.put(new JSONObject()
                .put("tag", "local-dns")
                .put("type", "local"));
        dnsServers.put(new JSONObject()
                .put("tag", "remote-dns")
                .put("type", "https")
                .put("server", "1.1.1.1")
                .put("server_port", 443)
                .put("detour", "proxy-out"));
        dns.put("servers", dnsServers);
        dns.put("final", "remote-dns");
        dns.put("strategy", "ipv4_only");
        dns.put("disable_cache", false);

        JSONObject tun = new JSONObject();
        tun.put("type", "tun");
        tun.put("tag", "tun-in");
        tun.put("mtu", 1400);
        tun.put("address", new JSONArray()
                .put("198.18.0.1/30")
                .put("fc00::1/126"));
        tun.put("dns_address", new JSONArray()
                .put("198.18.0.2")
                .put("fc00::2"));
        tun.put("auto_route", true);
        tun.put("strict_route", true);
        tun.put("exclude_package", new JSONArray().put(ownPackage));
        tun.put("platform", new JSONObject()
                .put("http_proxy", new JSONObject().put("enabled", false)));

        JSONArray inbounds = new JSONArray();
        if (proxyMode) {
            JSONObject mixed = new JSONObject();
            mixed.put("type", "mixed");
            mixed.put("tag", "mixed-in");
            mixed.put("listen", "127.0.0.1");
            mixed.put("listen_port", 1080);
            inbounds.put(mixed);
            JSONObject httpIn = new JSONObject();
            httpIn.put("type", "http");
            httpIn.put("tag", "http-in");
            httpIn.put("listen", "127.0.0.1");
            httpIn.put("listen_port", 8080);
            inbounds.put(httpIn);
        } else {
            inbounds.put(tun);
            // v4: a mixed inbound in VPN mode too. The app's
            // own exit-IP check (checkExitIp) goes through the
            // local SOCKS proxy on 127.0.0.1:1080, and for
            // these servers the Java LocalSocksServer is
            // skipped — so in VPN mode nothing listened on
            // 1080 and the IP box stayed "Not connected".
            // A direct fetch is NOT the answer: the TUN
            // excludes the app's own package, so a direct
            // fetch would return the phone's REAL public IP.
            // This mixed inbound (routed out via proxy-out)
            // makes the SOCKS check return the TRUE exit IP
            // in both modes. No bind conflict: nothing else
            // listens on 1080 for these servers in VPN mode.
            JSONObject mixed = new JSONObject();
            mixed.put("type", "mixed");
            mixed.put("tag", "mixed-in");
            mixed.put("listen", "127.0.0.1");
            mixed.put("listen_port", 1080);
            inbounds.put(mixed);
        }

        JSONObject ssOut = new JSONObject();
        ssOut.put("type", "shadowsocks");
        ssOut.put("tag", "proxy-out");
        ssOut.put("server", server.ip);
        ssOut.put("server_port", server.port);
        ssOut.put("method", server.method);
        ssOut.put("password", server.password);

        JSONArray outbounds = new JSONArray();
        outbounds.put(new JSONObject()
                .put("type", "direct")
                .put("tag", "direct-out"));
        outbounds.put(ssOut);

        JSONArray rules = new JSONArray();
        if (!proxyMode) {
            rules.put(new JSONObject()
                    .put("protocol", "dns")
                    .put("action", "hijack-dns"));
            rules.put(new JSONObject()
                    .put("port", 53)
                    .put("action", "hijack-dns"));
        }
        rules.put(new JSONObject().put("outbound", "proxy-out"));

        JSONObject route = new JSONObject();
        route.put("rules", rules);
        route.put("final", "proxy-out");
        route.put("auto_detect_interface", true);
        route.put("find_process", false);
        route.put("default_domain_resolver", "local-dns");

        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("level", "debug"));
        root.put("dns", dns);
        root.put("inbounds", inbounds);
        root.put("outbounds", outbounds);
        root.put("route", route);
        root.put("experimental", new JSONObject()
                .put("cache_file", new JSONObject().put("enabled", false))
                .put("clash_api", new JSONObject()
                        .put("external_controller", "127.0.0.1:9090")));
        return root.toString(2);
    }

    /**
     * Backup (VMess): sing-box config for ONE VMess server.
     * Same shape as buildServer — log debug, local-dns + DoH
     * remote-dns (1.1.1.1, detour proxy-out), TUN in VPN mode
     * / mixed 1080 + http 8080 in proxy mode, same route rules,
     * same clash_api — but the single outbound "proxy-out" is
     * type "vmess" (server = address, uuid, alter_id, security
     * auto, transport from net/type/host/path, TLS when tls is
     * "tls"). One server, one attempt.
     */
    public static String buildVmess(Server vmess, String ownPackage,
            boolean proxyMode) throws Exception {
        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();
        dnsServers.put(new JSONObject()
                .put("tag", "local-dns")
                .put("type", "local"));
        dnsServers.put(new JSONObject()
                .put("tag", "remote-dns")
                .put("type", "https")
                .put("server", "1.1.1.1")
                .put("server_port", 443)
                .put("detour", "proxy-out"));
        dns.put("servers", dnsServers);
        dns.put("final", "remote-dns");
        dns.put("strategy", "ipv4_only");
        dns.put("disable_cache", false);

        JSONObject tun = new JSONObject();
        tun.put("type", "tun");
        tun.put("tag", "tun-in");
        tun.put("mtu", 1400);
        tun.put("address", new JSONArray()
                .put("198.18.0.1/30")
                .put("fc00::1/126"));
        tun.put("dns_address", new JSONArray()
                .put("198.18.0.2")
                .put("fc00::2"));
        tun.put("auto_route", true);
        tun.put("strict_route", true);
        tun.put("exclude_package", new JSONArray().put(ownPackage));
        tun.put("platform", new JSONObject()
                .put("http_proxy", new JSONObject().put("enabled", false)));

        JSONArray inbounds = new JSONArray();
        if (proxyMode) {
            JSONObject mixed = new JSONObject();
            mixed.put("type", "mixed");
            mixed.put("tag", "mixed-in");
            mixed.put("listen", "127.0.0.1");
            mixed.put("listen_port", 1080);
            inbounds.put(mixed);
            JSONObject httpIn = new JSONObject();
            httpIn.put("type", "http");
            httpIn.put("tag", "http-in");
            httpIn.put("listen", "127.0.0.1");
            httpIn.put("listen_port", 8080);
            inbounds.put(httpIn);
        } else {
            inbounds.put(tun);
            // v4: same mixed inbound in VPN mode as buildServer
            // (see the comment there) — the exit-IP check needs
            // a SOCKS listener on 1080, and a direct fetch
            // would show the phone's real IP (TUN excludes the
            // app's own package).
            JSONObject mixed = new JSONObject();
            mixed.put("type", "mixed");
            mixed.put("tag", "mixed-in");
            mixed.put("listen", "127.0.0.1");
            mixed.put("listen_port", 1080);
            inbounds.put(mixed);
        }

        JSONObject vmOut = new JSONObject();
        vmOut.put("type", "vmess");
        vmOut.put("tag", "proxy-out");
        vmOut.put("server", vmess.ip);
        vmOut.put("server_port", vmess.port);
        vmOut.put("uuid", vmess.uuid);
        vmOut.put("alter_id", vmess.alterId);
        vmOut.put("security", "auto");
        // V2Ray transport: sing-box accepts ONLY http / ws / quic /
        // grpc / httpupgrade as a transport "type". Plain TCP has NO
        // transport object at all — a "tcp" type is rejected with
        // "unknown transport type: tcp" and the config fails to load.
        // So: tcp (and anything unknown) -> no transport; ws/http/
        // grpc/quic/httpupgrade -> the matching transport object.
        // (h2 is the V2Ray name for HTTP/2 -> sing-box "http".)
        String net = (vmess.network == null || vmess.network.isEmpty())
                ? "tcp" : vmess.network;
        if ("ws".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "ws");
            if (vmess.path != null && !vmess.path.isEmpty()) {
                transport.put("path", vmess.path);
            }
            if (vmess.host != null && !vmess.host.isEmpty()) {
                transport.put("headers", new JSONObject()
                        .put("Host", vmess.host));
            }
            vmOut.put("transport", transport);
        } else if ("http".equals(net) || "h2".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "http");
            if (vmess.host != null && !vmess.host.isEmpty()) {
                transport.put("host", new JSONArray().put(vmess.host));
            }
            if (vmess.path != null && !vmess.path.isEmpty()) {
                transport.put("path", vmess.path);
            }
            vmOut.put("transport", transport);
        } else if ("grpc".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "grpc");
            if (vmess.path != null && !vmess.path.isEmpty()) {
                transport.put("service_name", vmess.path);
            }
            vmOut.put("transport", transport);
        } else if ("quic".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "quic");
            vmOut.put("transport", transport);
        } else if ("httpupgrade".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "httpupgrade");
            if (vmess.host != null && !vmess.host.isEmpty()) {
                transport.put("host", vmess.host);
            }
            if (vmess.path != null && !vmess.path.isEmpty()) {
                transport.put("path", vmess.path);
            }
            vmOut.put("transport", transport);
        }
        // net == "tcp" (the common case) -> no transport object:
        // plain TCP, which is sing-box's default for VMess.
        if ("tls".equals(vmess.tls)) {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            String sni = (vmess.serverName != null && !vmess.serverName.isEmpty())
                    ? vmess.serverName : vmess.ip;
            tls.put("server_name", sni);
            vmOut.put("tls", tls);
        }

        JSONArray outbounds = new JSONArray();
        outbounds.put(new JSONObject()
                .put("type", "direct")
                .put("tag", "direct-out"));
        outbounds.put(vmOut);

        JSONArray rules = new JSONArray();
        if (!proxyMode) {
            rules.put(new JSONObject()
                    .put("protocol", "dns")
                    .put("action", "hijack-dns"));
            rules.put(new JSONObject()
                    .put("port", 53)
                    .put("action", "hijack-dns"));
        }
        rules.put(new JSONObject().put("outbound", "proxy-out"));

        JSONObject route = new JSONObject();
        route.put("rules", rules);
        route.put("final", "proxy-out");
        route.put("auto_detect_interface", true);
        route.put("find_process", false);
        route.put("default_domain_resolver", "local-dns");

        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("level", "debug"));
        root.put("dns", dns);
        root.put("inbounds", inbounds);
        root.put("outbounds", outbounds);
        root.put("route", route);
        root.put("experimental", new JSONObject()
                .put("cache_file", new JSONObject().put("enabled", false))
                .put("clash_api", new JSONObject()
                        .put("external_controller", "127.0.0.1:9090")));
        return root.toString(2);
    }
}
