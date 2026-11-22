package com.vpeen.android;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * sing-box (libbox) config for the ArcVeePN SB build.
 * One outbound per connect — one server, one attempt, no
 * urltest group, no extra-servers outbounds. TUN inbound
 * (platform openTun) in VPN mode; a `mixed` inbound on
 * 127.0.0.1:1080 exists in VPN mode too (the v4 fix) — the
 * app's exit-IP check goes through it. DoH DNS through the
 * tunnel. No gvisor stack is requested (Vauth's build has no
 * with_gvisor tag — the default stack applies, exactly like
 * FoxyVPN).
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
 *
 * 2.7 fix: multi-protocol. The config skeleton is shared by
 * every protocol; only the single "proxy-out" outbound
 * differs. build() dispatches on the server's proto:
 * "ss" -> shadowsocks, "vmess" -> vmess, "vless" -> vless,
 * "trojan" -> trojan. buildSub/buildVmess stay as thin
 * wrappers so old call sites still compile.
 */
public final class SingBoxConfig {
    private SingBoxConfig() {}

    /**
     * The whole config around ONE outbound. Identical for
     * every protocol (log debug, local-dns + DoH remote-dns
     * 1.1.1.1 detour proxy-out, TUN in VPN mode / mixed 1080
     * + http 8080 in proxy mode, same route rules, same
     * clash_api). The outbound is the only part that differs.
     */
    private static String skeleton(String ownPackage, boolean proxyMode,
            JSONObject out) throws Exception {
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

        JSONArray outbounds = new JSONArray();
        outbounds.put(new JSONObject()
                .put("type", "direct")
                .put("tag", "direct-out"));
        outbounds.put(out);

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
     * V2Ray transport shared by vmess / vless / trojan.
     * sing-box accepts ONLY http / ws / quic / grpc /
     * httpupgrade as a transport "type". Plain TCP has NO
     * transport object at all — a "tcp" type is rejected with
     * "unknown transport type: tcp" and the config fails to
     * load. So: tcp (and anything unknown) -> null (no
     * transport); ws/http/grpc/quic/httpupgrade -> the
     * matching transport object. (h2 is the V2Ray name for
     * HTTP/2 -> sing-box "http".)
     */
    private static JSONObject transportFor(Server s) throws Exception {
        String net = (s.network == null || s.network.isEmpty())
                ? "tcp" : s.network;
        if ("ws".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "ws");
            if (s.path != null && !s.path.isEmpty()) {
                transport.put("path", s.path);
            }
            if (s.host != null && !s.host.isEmpty()) {
                transport.put("headers", new JSONObject()
                        .put("Host", s.host));
            }
            return transport;
        } else if ("http".equals(net) || "h2".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "http");
            if (s.host != null && !s.host.isEmpty()) {
                transport.put("host", new JSONArray().put(s.host));
            }
            if (s.path != null && !s.path.isEmpty()) {
                transport.put("path", s.path);
            }
            return transport;
        } else if ("grpc".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "grpc");
            if (s.path != null && !s.path.isEmpty()) {
                transport.put("service_name", s.path);
            }
            return transport;
        } else if ("quic".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "quic");
            return transport;
        } else if ("httpupgrade".equals(net)) {
            JSONObject transport = new JSONObject();
            transport.put("type", "httpupgrade");
            if (s.host != null && !s.host.isEmpty()) {
                transport.put("host", s.host);
            }
            if (s.path != null && !s.path.isEmpty()) {
                transport.put("path", s.path);
            }
            return transport;
        }
        // net == "tcp" (the common case) -> no transport object:
        // plain TCP, which is sing-box's default.
        return null;
    }

    /** TLS object with the server's SNI (serverName, else the
     *  address). null when the server has no TLS. */
    private static JSONObject tlsFor(Server s) throws Exception {
        if (!"tls".equals(s.tls)) return null;
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        String sni = (s.serverName != null && !s.serverName.isEmpty())
                ? s.serverName : s.ip;
        tls.put("server_name", sni);
        return tls;
    }

    /** Shadowsocks outbound (primary subscription). */
    private static JSONObject ssOut(Server s) throws Exception {
        JSONObject out = new JSONObject();
        out.put("type", "shadowsocks");
        out.put("tag", "proxy-out");
        out.put("server", s.ip);
        out.put("server_port", s.port);
        out.put("method", s.method);
        out.put("password", s.password);
        return out;
    }

    /** VMess outbound (backup subscription). */
    private static JSONObject vmessOut(Server s) throws Exception {
        JSONObject out = new JSONObject();
        out.put("type", "vmess");
        out.put("tag", "proxy-out");
        out.put("server", s.ip);
        out.put("server_port", s.port);
        out.put("uuid", s.uuid);
        out.put("alter_id", s.alterId);
        out.put("security", "auto");
        JSONObject transport = transportFor(s);
        if (transport != null) out.put("transport", transport);
        JSONObject tls = tlsFor(s);
        if (tls != null) out.put("tls", tls);
        return out;
    }

    /** VLESS outbound (backup subscription, 2.7 fix).
     *  sing-box has NO "encryption" field on a VLESS
     *  outbound — the link's encryption=none is a V2Ray
     *  concept (VLESS is unencrypted below TLS), so the
     *  parsed value is simply not emitted. "flow" is only
     *  set when TLS is on (xtls-rprx-vision requires TLS). */
    private static JSONObject vlessOut(Server s) throws Exception {
        JSONObject out = new JSONObject();
        out.put("type", "vless");
        out.put("tag", "proxy-out");
        out.put("server", s.ip);
        out.put("server_port", s.port);
        out.put("uuid", s.uuid);
        JSONObject tls = tlsFor(s);
        if (tls != null) {
            if (s.flow != null && !s.flow.isEmpty()) {
                out.put("flow", s.flow);
            }
            out.put("tls", tls);
        }
        JSONObject transport = transportFor(s);
        if (transport != null) out.put("transport", transport);
        return out;
    }

    /** Trojan outbound (backup subscription, 2.7 fix).
     *  Trojan always runs over TLS. */
    private static JSONObject trojanOut(Server s) throws Exception {
        JSONObject out = new JSONObject();
        out.put("type", "trojan");
        out.put("tag", "proxy-out");
        out.put("server", s.ip);
        out.put("server_port", s.port);
        out.put("password", s.password);
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        String sni = (s.serverName != null && !s.serverName.isEmpty())
                ? s.serverName : s.ip;
        tls.put("server_name", sni);
        out.put("tls", tls);
        JSONObject transport = transportFor(s);
        if (transport != null) out.put("transport", transport);
        return out;
    }

    /** Firefox outbound (3.1, Mozilla VPN on Fastly).
     *  HTTP CONNECT with a Bearer ProxyPass JWT — sing-box's
     *  `http` outbound sends exactly this when the username
     *  is empty and the header is set (sing library
     *  protocol/http/client.go: custom `headers` are copied
     *  onto the CONNECT request, and `Proxy-Authorization:
     *  Basic ...` is added ONLY when username != ""). The
     *  TLS handshake uses a real Firefox fingerprint (uTLS
     *  "firefox"), like Arman. No local relay is needed.
     *  The token is passed in memory only, at connect time —
     *  it is never written to a file or a log. */
    private static JSONObject firefoxOut(Server s, String token) throws Exception {
        if (token == null || token.isEmpty())
            throw new Exception("no Firefox token");
        JSONObject out = new JSONObject();
        out.put("type", "http");
        out.put("tag", "proxy-out");
        out.put("server", s.ip);
        out.put("server_port", s.port);
        JSONObject headers = new JSONObject();
        headers.put("Proxy-Authorization", "Bearer " + token);
        headers.put("User-Agent", FirefoxAuth.FIREFOX_UA);
        out.put("headers", headers);
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", s.ip);
        tls.put("utls", new JSONObject()
                .put("enabled", true)
                .put("fingerprint", "firefox"));
        out.put("tls", tls);
        return out;
    }

    /**
     * 2.7 fix: build the config for ONE server of ANY
     * protocol — ss / vmess / vless / trojan — chosen by
     * the server's proto field. Unknown protos fall back to
     * Shadowsocks (the primary's protocol).
     * 3.1: "firefox" -> the HTTP outbound above; the
     * ProxyPass JWT comes from firefoxToken (in memory
     * only, fetched fresh at connect time).
     */
    public static String build(Server s, String ownPackage,
            boolean proxyMode) throws Exception {
        return build(s, ownPackage, proxyMode, null);
    }

    public static String build(Server s, String ownPackage,
            boolean proxyMode, String firefoxToken) throws Exception {
        String proto = (s.proto == null || s.proto.isEmpty()) ? "ss" : s.proto;
        JSONObject out;
        if ("vmess".equals(proto)) {
            out = vmessOut(s);
        } else if ("vless".equals(proto)) {
            out = vlessOut(s);
        } else if ("trojan".equals(proto)) {
            out = trojanOut(s);
        } else if ("firefox".equals(proto)) {
            out = firefoxOut(s, firefoxToken);
        } else {
            out = ssOut(s);
        }
        return skeleton(ownPackage, proxyMode, out);
    }

    /**
     * Sub (TEST): sing-box config for ONE Sub Shadowsocks
     * server. See the class doc — the single outbound
     * "proxy-out" is type "shadowsocks" (server = IP literal,
     * so no TLS object, no username, no domain_resolver). One
     * server, one attempt.
     */
    public static String buildSub(Server sub, String ownPackage,
            boolean proxyMode) throws Exception {
        return build(sub, ownPackage, proxyMode);
    }

    /**
     * Backup (VMess): sing-box config for ONE VMess server.
     * Same shape as buildSub — the single outbound
     * "proxy-out" is type "vmess" (server = address, uuid,
     * alter_id, security auto, transport from net/type/host/
     * path, TLS when tls is "tls"). One server, one attempt.
     */
    public static String buildVmess(Server vmess, String ownPackage,
            boolean proxyMode) throws Exception {
        return build(vmess, ownPackage, proxyMode);
    }
}
