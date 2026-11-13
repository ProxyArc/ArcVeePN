package com.vpeen.android;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;


/**
 * Fetches a Shadowsocks subscription and parses it
 * into Server objects. The subscription endpoint serves
 * plain-text ss:// links (one per line), SS-only, with the
 * country flag emoji as the link name.
 *
 * Put your own subscription URL in SUBSCRIPTION_URL below
 * (and a backup one in BACKUP_SUBSCRIPTION_URL). The app
 * reads the links as-is.
 */
public class SubscriptionApi {
    // Your subscription endpoint. Replace with your own URL.
    private static final String SUBSCRIPTION_URL = "https://example.com/subscription";

    private static final String USER_AGENT = "ArcVeePN/1.9";
    private static final int TIMEOUT_MS = 15000;
    private static final long CACHE_TTL_MS = 30L * 60 * 1000;

    private static volatile List<Server> cached = null;
    private static volatile long cachedAtMs = 0;

    // The raw text of the last successful subscription
    // fetch, for the offline cache (AppSettings). On load it
    // is re-parsed exactly like a fresh fetch.
    // kind is "ss" (primary: ss:// lines) or "vmess"
    // (backup: one base64 blob of vmess:// links).
    private static volatile String lastRawText = null;
    private static volatile String lastRawKind = null;
    private static volatile long lastRawAtMs = 0;

    /** v8: the last successful fetch as a cache JSON string
     *  {"fetchedAt":<ms>,"kind":"ss"|"vmess","text":"..."},
     *  or null when nothing was ever fetched. */
    public static String lastFetchForCache() {
        try {
            String t = lastRawText;
            String k = lastRawKind;
            if (t == null || t.isEmpty() || k == null) return null;
            JSONObject o = new JSONObject();
            o.put("fetchedAt", lastRawAtMs);
            o.put("kind", k);
            o.put("text", t);
            return o.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Parse a cached subscription (the JSON from
     *  lastFetchForCache) back into servers. VMess servers
     *  are returned as-is. Returns an empty list on any failure. */
    public static List<Server> parseCacheJson(String cacheJson) {
        List<Server> out = new ArrayList<>();
        try {
            if (cacheJson == null || cacheJson.isEmpty()) return out;
            JSONObject o = new JSONObject(cacheJson);
            long fetchedAt = o.optLong("fetchedAt", 0);
            String kind = o.optString("kind", "");
            String text = o.optString("text", "");
            if (text.isEmpty()) return out;
            if ("vmess".equals(kind)) {
                String b64 = text.trim().replace('-', '+').replace('_', '/');
                int pad = b64.length() % 4;
                if (pad == 1) return out;
                if (pad > 0) {
                    StringBuilder p = new StringBuilder(b64);
                    for (int i = 0; i < 4 - pad; i++) p.append('=');
                    b64 = p.toString();
                }
                byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(decoded), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        Server s = parseVmessLine(line.trim());
                        if (s != null) out.add(s);
                    } catch (Throwable ignored) {}
                }
                return out;
            }
            if (!"ss".equals(kind)) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    Server s = parseLine(line.trim());
                    if (s == null) continue;
                    out.add(s);
                } catch (Throwable ignored) {}
            }
            return out;
        } catch (Throwable ignored) {
            return out;
        }
    }

    private static String endpoint() {
        return SUBSCRIPTION_URL;
    }

    // ---- Backup (emergency) subscription ----
    // A second, standard V2Ray subscription (base64 blob of
    // vmess:// links). Used ONLY when the primary fetch fails,
    // so the app keeps working if the primary goes down.
    // Replace with your own backup URL.
    private static final String BACKUP_SUBSCRIPTION_URL = "https://example.com/backup";

    private static String backupEndpoint() {
        return BACKUP_SUBSCRIPTION_URL;
    }




    /** The endpoint host only, for logging. Never the full URL. */
    public static String endpointHost() {
        try {
            return new URL(endpoint()).getHost();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Force the next fetchServers() to re-fetch. */
    public static void refresh() {
        cachedAtMs = 0;
        cached = null;
    }

    /** The subscription servers, cached in memory for 30 min. */
    public List<Server> fetchServers() throws Exception {
        long now = System.currentTimeMillis();
        List<Server> c = cached;
        if (c != null && now - cachedAtMs < CACHE_TTL_MS) return c;
        List<Server> fresh = fetchFresh();
        cached = fresh;
        cachedAtMs = now;
        return fresh;
    }

    /** Primary first; on ANY primary failure (network, HTTP
     *  non-200, empty list, zero parseable servers) the backup
     *  subscription is fetched instead. The next refresh tries
     *  the primary first again, so it recovers when the
     *  primary is back. */
    private List<Server> fetchFresh() throws Exception {
        Exception primaryErr = null;
        try {
            List<Server> primary = fetchPrimary();
            if (primary != null && !primary.isEmpty()) return primary;
            primaryErr = new Exception("primary subscription empty");
        } catch (Throwable e) {
            primaryErr = new Exception("primary failed: " + e.getMessage());
        }
        try {
            List<Server> backup = fetchBackup();
            if (backup != null && !backup.isEmpty()) return backup;
        } catch (Throwable ignored) {}
        throw (primaryErr != null ? primaryErr : new Exception("subscription empty"));
    }

    private List<Server> fetchPrimary() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/plain");
            int status = conn.getResponseCode();
            if (status != 200) throw new Exception("subscription HTTP " + status);
            InputStream is = conn.getInputStream();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            List<Server> out = new ArrayList<>();
            StringBuilder rawText = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    rawText.append(line).append('\n');
                    Server s = parseLine(line.trim());
                    if (s != null) out.add(s);
                } catch (Throwable ignored) {
                    // Skip malformed lines silently.
                }
            }
            if (out.isEmpty()) throw new Exception("subscription empty");
            // v8: remember the raw text for the offline cache.
            lastRawText = rawText.toString();
            lastRawKind = "ss";
            lastRawAtMs = System.currentTimeMillis();
            return out;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Backup: a standard V2Ray subscription — one base64 blob
     *  that decodes to vmess:// links, one per line. Plain
     *  . Returns only USABLE servers (see
     *  parseVmessLine — loopback / info entries are skipped). */
    private List<Server> fetchBackup() throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(backupEndpoint());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/plain");
            int status = conn.getResponseCode();
            if (status != 200) throw new Exception("backup HTTP " + status);
            // Read the whole body, then base64-decode it.
            InputStream is = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            String raw = sb.toString().trim();
            if (raw.isEmpty()) throw new Exception("backup empty");
            String b64 = raw.replace('-', '+').replace('_', '/');
            int pad = b64.length() % 4;
            if (pad == 1) throw new Exception("backup bad base64");
            if (pad > 0) {
                StringBuilder p = new StringBuilder(b64);
                for (int i = 0; i < 4 - pad; i++) p.append('=');
                b64 = p.toString();
            }
            byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(decoded), StandardCharsets.UTF_8));
            List<Server> out = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    Server s = parseVmessLine(line.trim());
                    if (s != null) out.add(s);
                } catch (Throwable ignored) {
                    // Skip malformed lines silently.
                }
            }
            if (out.isEmpty()) throw new Exception("backup has no usable servers");
            // v8: remember the raw text for the offline cache.
            lastRawText = raw;
            lastRawKind = "vmess";
            lastRawAtMs = System.currentTimeMillis();
            return out;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /** True for loopback addresses (127.0.0.0/8, ::1, localhost)
     *  — these are info entries, not servers. */
    static boolean isLoopback(String addr) {
        if (addr == null) return true;
        String a = addr.trim().toLowerCase();
        if (a.isEmpty()) return true;
        if (a.equals("localhost") || a.equals("::1") || a.equals("[::1]")) return true;
        if (a.startsWith("127.")) return true;
        return false;
    }

    /** True when a vmess name looks like an account-info /
     *  status line rather than a server (e.g. "status: active",
     *  "validity: ..."). Such entries are never shown or
     *  connected. */
    static boolean isInfoName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("status") || n.contains("validity")
                || n.contains("expire") || n.contains("traffic")
                || n.contains("active") || n.contains("account")
                || n.contains("remaining") || n.contains("balance");
    }

    /** Extract a flag emoji (regional-indicator pair) from a
     *  name, or "" when there is none. */
    static String flagFromName(String name) {
        if (name == null) return "";
        int[] cps = name.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i++) {
            int a = cps[i], b = cps[i + 1];
            if (a >= 0x1F1E6 && a <= 0x1F1FF && b >= 0x1F1E6 && b <= 0x1F1FF) {
                return new String(new int[]{a, b}, 0, 2);
            }
        }
        return "";
    }

    /**
     * Parse one vmess:// link (backup sub).
     * vmess://&lt;base64url(JSON)&gt; — JSON: v, ps, add, port,
     * id, aid, net, type, host, path, tls.
     * Returns null for anything malformed, for loopback
     * addresses, and for info/status names. Never logs the uuid.
     */
    static Server parseVmessLine(String line) {
        if (line == null || !line.startsWith("vmess://")) return null;
        String b64 = line.substring("vmess://".length()).trim();
        if (b64.isEmpty()) return null;
        b64 = b64.replace('-', '+').replace('_', '/');
        int pad = b64.length() % 4;
        if (pad == 1) return null;
        if (pad > 0) {
            StringBuilder sb = new StringBuilder(b64);
            for (int i = 0; i < 4 - pad; i++) sb.append('=');
            b64 = sb.toString();
        }
        byte[] decoded;
        try {
            decoded = Base64.decode(b64, Base64.DEFAULT);
        } catch (Throwable e) {
            return null;
        }
        JSONObject j;
        try {
            j = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return null;
        }
        String addr = j.optString("add", "");
        int port = j.optInt("port", 0);
        String uuid = j.optString("id", "");
        String ps = j.optString("ps", "");
        if (addr.isEmpty() || port <= 0 || port > 65535 || uuid.isEmpty()) return null;
        if (isLoopback(addr)) return null;
        if (isInfoName(ps)) return null;
        Server s = new Server();
        s.proto = "vmess";
        s.ip = addr;
        s.port = port;
        s.uuid = uuid;
        s.alterId = j.optInt("aid", 0);
        s.network = j.optString("net", "tcp");
        if (s.network == null || s.network.isEmpty()) s.network = "tcp";
        s.type = j.optString("type", "none");
        s.host = j.optString("host", "");
        s.path = j.optString("path", "");
        s.tls = j.optString("tls", "none");
        if (s.tls == null || s.tls.isEmpty()) s.tls = "none";
        s.name = ps;
        String flag = flagFromName(ps);
        s.flag = flag.isEmpty() ? "\uD83C\uDF10" : flag; // globe when no flag in the name
        s.serverName = (s.host != null && !s.host.isEmpty()) ? s.host : addr;
        return s;
    }

    /**
     * Parse one ss:// link.
     * ss://&lt;base64url(method:password)&gt;@&lt;ip&gt;:&lt;port&gt;#&lt;urlencoded-flag&gt;
     * Returns null for anything malformed. Never logs the password.
     */
    static Server parseLine(String line) {
        if (line == null || !line.startsWith("ss://")) return null;
        String rest = line.substring("ss://".length());
        int hash = rest.indexOf('#');
        String flag = "";
        if (hash >= 0) {
            try {
                flag = URLDecoder.decode(rest.substring(hash + 1), "UTF-8");
            } catch (Throwable ignored) {
                flag = "";
            }
            rest = rest.substring(0, hash);
        }
        int at = rest.lastIndexOf('@');
        if (at <= 0) return null;
        String userinfo = rest.substring(0, at);
        String hostport = rest.substring(at + 1);
        int colon = hostport.lastIndexOf(':');
        if (colon <= 0) return null;
        String ip = hostport.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(hostport.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (ip.isEmpty() || port <= 0 || port > 65535) return null;
        // base64url (no padding) -> standard Base64.
        String b64 = userinfo.replace('-', '+').replace('_', '/');
        int pad = b64.length() % 4;
        if (pad == 1) return null;
        if (pad > 0) {
            StringBuilder sb = new StringBuilder(b64);
            for (int i = 0; i < 4 - pad; i++) sb.append('=');
            b64 = sb.toString();
        }
        byte[] decoded;
        try {
            decoded = Base64.decode(b64, Base64.DEFAULT);
        } catch (Throwable e) {
            return null;
        }
        String cred = new String(decoded, StandardCharsets.UTF_8);
        int sep = cred.indexOf(':');
        if (sep <= 0) return null;
        String method = cred.substring(0, sep);
        String password = cred.substring(sep + 1);
        if (method.isEmpty() || password.isEmpty()) return null;
        Server s = new Server();
        s.proto = "ss";
        s.ip = ip;
        s.port = port;
        s.method = method;
        s.password = password;
        s.flag = flag;
        return s;
    }
}
