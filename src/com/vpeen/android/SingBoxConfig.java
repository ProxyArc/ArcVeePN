package com.vpeen.android;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * sing-box (libbox) config for the experimental ArcVeePN SB build.
 * Mirrors the shape FoxyVPN uses with this same Vauth libbox.aar:
 * TUN inbound (platform openTun), one plain `http` outbound with
 * TLS + Basic auth to the VeePN proxy, DoH DNS through the tunnel.
 * No gvisor stack is requested (Vauth's build has no with_gvisor
 * tag — the default stack applies, exactly like FoxyVPN).
 * No mixed inbound (the Java LocalSocksServer on
 * 127.0.0.1:1080 stays in charge of the exit-IP / tunnel tests).
 *
 * sb9: Clash API enabled (experimental.clash_api, localhost
 * 127.0.0.1:9090, no secret) so the app can read sing-box's OWN
 * measurements (delay endpoint per outbound, urltest results).
 * One `http` outbound per distinct free region (selected server
 * stays `proxy-out`, extras become `proxy-<region>`), plus a
 * `urltest` group `proxy-fastest` over them (generate_204 probe,
 * 1m interval, tolerance 50). Region pick routes to that
 * region's outbound; Fastest routes to the urltest group, so
 * sing-box itself picks and re-picks the lowest-latency server.
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

    public static String build(Server server, String ownPackage,
            String regionCode, boolean fastestMode,
            java.util.List<Server> extraServers, boolean proxyMode) throws Exception {
        String host = server.host();
        // Prefer the pre-resolved IP (set before the TUN starts) so
        // sing-box never needs DNS through the tunnel to reach the
        // upstream itself; TLS SNI stays the real hostname.
        String upstream = (server.resolvedIp != null && !server.resolvedIp.isEmpty())
                ? server.resolvedIp : host;

        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();
        // sb5 FIX: sb4 still hung — the log ended at
        // "startOrReloadService begin" with no OK/FAILED and no
        // "openTun called": startOrReloadService hangs inside
        // sing-box's pre-TUN DNS start, because the DoH remote-dns
        // must resolve cloudflare-dns.com via UDP 8.8.8.8 local-dns
        // and that UDP path hangs on the user's mobile network
        // (also why speedtest would not open: DNS broken).
        // FoxyVPN (same libbox AAR) uses the SYSTEM resolver:
        // {"type": "local"} — no 8.8.8.8, no detour. Our
        // SingBoxPlatform.localDNSTransport() already implements
        // exactly that (active-network getAllByName), so local-dns
        // becomes type "local" here. (sb4's fix — removing the
        // detour to the bare direct outbound — stays in effect:
        // there is still no detour on local-dns.)
        dnsServers.put(new JSONObject()
                .put("tag", "local-dns")
                .put("type", "local"));
        // sb8 FIX: sb7 connects but in-tunnel DNS is still dead
        // (Speedtest finds no server). remote-dns was
        // "cloudflare-dns.com" — a HOSTNAME — so before any
        // in-tunnel DNS could work, sing-box had to resolve it via
        // local-dns, and the user's carrier (SamanTel) filters that
        // bootstrap DNS path (same reason the UDP 8.8.8.8 path hung
        // in sb4). Point the DoH server at the IP literal 1.1.1.1
        // so NO bootstrap DNS is needed at all (SYBbox precedent:
        // "Default remote resolver is https://1.1.1.1/dns-query —
        // an address so nothing has to resolve the resolver first").
        // An IP needs no domain_resolver, so that line is removed.
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
            // sb10 PROXY MODE: no TUN at all. sing-box serves the
            // local proxy itself — mixed (SOCKS5 + HTTP CONNECT)
            // on 1080 and plain HTTP proxy on 8080, loopback only.
            // The Java LocalSocksServer is NOT started in proxy
            // mode (VpnTunnelService), so sing-box owns both ports
            // with no second listener on them.
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
        }

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", host);
        tls.put("utls", new JSONObject()
                .put("enabled", true)
                .put("fingerprint", "chrome"));

        JSONObject httpOut = new JSONObject();
        httpOut.put("type", "http");
        httpOut.put("tag", "proxy-out");
        httpOut.put("server", upstream);
        httpOut.put("server_port", server.port);
        httpOut.put("username", server.username);
        httpOut.put("password", server.password);
        httpOut.put("domain_resolver", "local-dns");
        httpOut.put("tls", tls);

        JSONArray outbounds = new JSONArray();
        outbounds.put(new JSONObject()
                .put("type", "direct")
                .put("tag", "direct-out"));
        outbounds.put(httpOut);

        // sb9: one http outbound per distinct free region, for the
        // urltest group and for Clash API delay per outbound. The
        // selected server stays proxy-out; each extra region gets
        // proxy-<region>. Dedup by region, first server wins; the
        // extras come from MainActivity's ranked candidates + warm
        // server cache (no new API calls at connect time), so the
        // group has 2–7 members depending on cache warmth.
        java.util.List<String> groupMembers = new java.util.ArrayList<>();
        groupMembers.add("proxy-out");
        if (extraServers != null) {
            java.util.Set<String> seenRegions = new java.util.HashSet<>();
            if (regionCode != null && !regionCode.isEmpty()) seenRegions.add(regionCode);
            if (server.region != null && !server.region.isEmpty()) seenRegions.add(server.region);
            for (Server s : extraServers) {
                if (s == null) continue;
                String r = (s.region != null && !s.region.isEmpty()) ? s.region : null;
                if (r == null || seenRegions.contains(r)) continue;
                String sh = s.host();
                if (sh == null || sh.isEmpty()) continue;
                String sup = (s.resolvedIp != null && !s.resolvedIp.isEmpty()) ? s.resolvedIp : sh;
                JSONObject stls = new JSONObject();
                stls.put("enabled", true);
                stls.put("server_name", sh);
                stls.put("utls", new JSONObject()
                        .put("enabled", true)
                        .put("fingerprint", "chrome"));
                JSONObject o = new JSONObject();
                o.put("type", "http");
                o.put("tag", "proxy-" + r);
                o.put("server", sup);
                o.put("server_port", s.port);
                o.put("username", s.username);
                o.put("password", s.password);
                o.put("domain_resolver", "local-dns");
                o.put("tls", stls);
                outbounds.put(o);
                groupMembers.add("proxy-" + r);
                seenRegions.add(r);
            }
        }

        // sb9: urltest group over the per-region outbounds. Only
        // built with 2+ members; with a single member there is
        // nothing to choose between, so Fastest falls back to
        // proxy-out. sing-box probes each member with a
        // generate_204 GET every 1m and picks the lowest delay
        // (tolerance 50 ms: it only switches when the new best is
        // 50 ms better, so it does not flap between servers).
        boolean useGroup = fastestMode && groupMembers.size() >= 2;
        if (groupMembers.size() >= 2) {
            JSONArray members = new JSONArray();
            for (String t : groupMembers) members.put(t);
            JSONObject urltest = new JSONObject();
            urltest.put("type", "urltest");
            urltest.put("tag", "proxy-fastest");
            urltest.put("outbounds", members);
            urltest.put("url", "https://www.gstatic.com/generate_204");
            urltest.put("interval", "1m");
            urltest.put("tolerance", 50);
            outbounds.put(urltest);
        }
        // Region pick -> that region's outbound (proxy-out is the
        // selected server). Fastest -> the urltest group, so
        // sing-box itself picks the lowest-latency server.
        String finalOut = useGroup ? "proxy-fastest" : "proxy-out";

        JSONArray rules = new JSONArray();
        if (!proxyMode) {
            // VPN mode only: hijack TUN DNS to the DoH resolver.
            // In proxy mode there is no TUN traffic to hijack.
            rules.put(new JSONObject()
                    .put("protocol", "dns")
                    .put("action", "hijack-dns"));
            rules.put(new JSONObject()
                    .put("port", 53)
                    .put("action", "hijack-dns"));
        }
        rules.put(new JSONObject().put("outbound", finalOut));

        JSONObject route = new JSONObject();
        route.put("rules", rules);
        route.put("final", finalOut);
        route.put("auto_detect_interface", true);
        route.put("find_process", false);
        route.put("default_domain_resolver", "local-dns");

        JSONObject root = new JSONObject();
        // sb5: debug level so Go-side startup steps are recorded in
        // ConnectLog via SingBoxPlatform.writeDebugMessage(). The log
        // stays a silent file only — nothing is shown on screen.
        root.put("log", new JSONObject().put("level", "debug"));
        root.put("dns", dns);
        root.put("inbounds", inbounds);
        root.put("outbounds", outbounds);
        root.put("route", route);
        root.put("experimental", new JSONObject()
                .put("cache_file", new JSONObject().put("enabled", false))
                // sb9: Clash API on localhost only (no secret, no
                // external exposure). The app reads sing-box's own
                // delay measurement for the active outbound from
                // GET /proxies/proxy-out/delay — see MainActivity.
                .put("clash_api", new JSONObject()
                        .put("external_controller", "127.0.0.1:9090")));
        return root.toString(2);
    }
}
