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
 * plain-text ss:// links (one per line), with the
 * country flag emoji as the link name. Multi-protocol
 * lines (ss / vmess / vless / trojan) are routed by
 * scheme.
 *
 * Server addresses are not included in this public source.
 */
public class SubscriptionApi {
    private static final String SUBSCRIPTION_URL = "";

    private static final String USER_AGENT = "ArcVeePN/3.1";
    private static final int TIMEOUT_MS = 15000;
    private static final long CACHE_TTL_MS = 30L * 60 * 1000;

    private static volatile List<Server> cached = null;
    private static volatile long cachedAtMs = 0;

    // v8: the raw text of the last successful subscription
    // fetch, for the offline cache (AppSettings). The raw
    // text is stored: on load it is re-parsed exactly like
    // a fresh fetch.
    // kind is "ss" (primary: ss:// lines), "vmess"
    // (backup: a vmess-only base64 blob) or "backup"
    // (backup: a base64 blob of ss:// + vmess:// links,
    // stored PLAIN).
    private static volatile String lastRawText = null;
    private static volatile String lastRawKind = null;
    private static volatile long lastRawAtMs = 0;

    // 2.7 fix: the emergency toggle (hamburger menu).
    // When ON, fetchServers() serves the BACKUP
    // subscription instead of the primary — every caller
    // (refresh on open, refresh button, connect fallback)
    // follows the toggle, so the app never silently falls
    // back to the primary while emergency is on. Set from
    // MainActivity (persisted in AppSettings) and restored
    // on launch.
    private static volatile boolean emergencyMode = false;

    public static void setEmergencyMode(boolean on) {
        emergencyMode = on;
    }

    public static boolean isEmergencyMode() {
        return emergencyMode;
    }

    // 2.7 fix2: which subscription served the last list
    // ("primary" | "backup" | "") and why the primary failed
    // (short reason, for the silent file log only — never shown
    // on screen). The fallback must never hide a broken primary:
    // the UI shows the source, and the failure reason is logged.
    private static volatile String lastSource = "";
    private static volatile String lastPrimaryError = "";

    public static String getLastSource() { return lastSource; }
    public static String getLastPrimaryError() { return lastPrimaryError; }

    // 2.7 fix2: IP -> country code, learned from every server
    // whose name carried a flag. The backup worker's names are
    // sometimes flag-less ("Auto-1" with no emoji) — then there
    // is no country in the name at all, and the ONLY way to show
    // the country is the IP: the backup servers are the same
    // physical servers as the primary (95% of backup IPs are in
    // the primary list), so a backup server inherits the country
    // its IP had on a fetch that DID carry a flag. The map is
    // persisted (AppSettings) so it survives restarts; it is
    // only a fallback — a flag in the name always wins, and a
    // fresh flag always refreshes the map.
    private static final java.util.Map<String, String> ipCc =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Country code for one server: the flag in its name first,
     *  then the learned IP map. Records a fresh flag in the map. */
    static String resolveCountry(String ip, String flag) {
        String cc = codeFromFlag(flag);
        if (cc != null && !cc.isEmpty()) {
            if (ip != null && !ip.isEmpty()) ipCc.put(ip, cc);
            return cc;
        }
        if (ip != null) {
            String known = ipCc.get(ip);
            if (known != null && !known.isEmpty()) return known;
        }
        return "";
    }

    /** The learned IP->cc map as a compact JSON object, or "" when empty. */
    public static String ipCcJson() {
        try {
            if (ipCc.isEmpty()) return "";
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<String, String> e : ipCc.entrySet()) o.put(e.getKey(), e.getValue());
            return o.toString();
        } catch (Throwable ignored) { return ""; }
    }

    /** Restore a persisted IP->cc map (called on launch, before any fetch). */
    public static void loadIpCc(String json) {
        try {
            if (json == null || json.isEmpty()) return;
            JSONObject o = new JSONObject(json);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String ip = keys.next();
                String cc = o.optString(ip, "");
                if (ip != null && !ip.isEmpty() && cc != null && cc.length() == 2) ipCc.put(ip, cc);
            }
        } catch (Throwable ignored) {}
    }

    
    /** Short, safe failure reason (no URL, no credentials) for the silent log. */
    static String shortErr(Throwable e) {
        if (e == null) return "unknown";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        if (m.length() > 80) m = m.substring(0, 80);
        return m;
    }

    
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

    /** v8: parse a cached subscription (the JSON from
     *  lastFetchForCache) back into servers. Passwords are
     *  used as-is, exactly like a fresh fetch. Returns an
     *  empty list on any failure. */
    public static List<Server> parseCacheJson(String cacheJson) {
        return parseCacheJsonImpl(cacheJson);
    }

    /** The widget's DISPLAY-ONLY parse of the persisted
     *  cache — same servers (ip, flag, port, proto, name,
     *  country). The widget never connects from this list,
     *  so the password is never needed — the light parse
     *  keeps every widget update cheap on the main thread.
     *  The connect path always uses the full parseCacheJson.
     *  Never null, never throws. */
    public static List<Server> parseCacheJsonLight(String cacheJson) {
        return parseCacheJson(cacheJson);
    }

    private static List<Server> parseCacheJsonImpl(String cacheJson) {
        List<Server> out = new ArrayList<>();
        try {
            if (cacheJson == null || cacheJson.isEmpty()) return out;
            JSONObject o = new JSONObject(cacheJson);
            String kind = o.optString("kind", "");
            String text = o.optString("text", "");
            if (text.isEmpty()) return out;
            if ("backup".equals(kind)) {
                // Backup/emergency subscription: a base64 blob
                // of ss:// + vmess:// links. Re-parse both kinds
                // exactly as fetchBackup() does.
                lastSource = "backup";
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
                        String t = line.trim();
                        if (t.isEmpty()) continue;
                        // 2.7 fix: route by scheme (ss /
                        // vmess / vless / trojan), exactly as
                        // fetchBackup() does.
                        Server s = parseAnyLine(t);
                        if (s == null || isLoopback(s.ip)) continue;
                        out.add(s);
                    } catch (Throwable ignored) {}
                }
                return out;
            }
            if ("vmess".equals(kind)) {
                lastSource = "backup";
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
                        // 2.7 fix: route by scheme.
                        Server s = parseAnyLine(line.trim());
                        if (s != null) out.add(s);
                    } catch (Throwable ignored) {}
                }
                return out;
            }
            if (!"ss".equals(kind)) return out;
            lastSource = "primary";
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    // 2.7 fix: route by scheme, so a non-ss
                    // line in a primary cache is parsed too.
                    Server s = parseAnyLine(line.trim());
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
    // A second, standard V2Ray subscription (base64 blob,
    // mostly ss:// links plus a few vmess:// placeholders).
    // Used ONLY when the user turns the emergency toggle ON
    // — never as an automatic fallback: with the toggle OFF
    // the app uses the primary only, and a primary failure
    // shows "Could not load servers."
    private static final String BACKUP_SUBSCRIPTION_URL = "";

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

    /** The subscription servers, cached in memory for 30 min.
     *  2.7 fix: when the emergency toggle is ON, this serves
     *  the BACKUP subscription instead of the primary — every
     *  caller (refresh on open, refresh button, connect
     *  fallback) follows the toggle. */
    public List<Server> fetchServers() throws Exception {
        long now = System.currentTimeMillis();
        List<Server> c = cached;
        if (c != null && now - cachedAtMs < CACHE_TTL_MS) return c;
        List<Server> fresh = emergencyMode ? fetchBackup() : fetchFresh();
        if (emergencyMode) lastSource = "backup";
        cached = fresh;
        cachedAtMs = now;
        return fresh;
    }

    /** Manual emergency load (2.5 rebuild): fetch ONLY the
     *  backup subscription, never the primary. Used by the
     *  hamburger-menu emergency button. The result is
     *  cached like a normal fetch (30 min); a following
     *  normal refresh still tries the primary first (it
     *  clears the cache). */
    public List<Server> fetchBackupServers() throws Exception {
        List<Server> b = fetchBackup();
        lastSource = "backup";
        cached = b;
        cachedAtMs = System.currentTimeMillis();
        return b;
    }

    /** Primary ONLY (2.7 fix3) — up to 2 attempts against a
     *  single transient failure, then the primary error is
     *  thrown. There is NO fallback to the backup here: the
     *  emergency subscription is used only when the user
     *  turns the emergency toggle ON (fetchBackup via
     *  fetchBackupServers), never automatically. The failure
     *  reason is recorded (getLastPrimaryError) for the
     *  silent file log, and the UI shows the short generic
     *  "Could not load servers." The next refresh tries the
     *  primary again, so it recovers when the primary is back. */
    private List<Server> fetchFresh() throws Exception {
        return fetchPrimaryWithRetry(() -> fetchPrimary());
    }

    /** Testable core of fetchFresh: try the primary up to twice,
     *  then throw its error. The fetcher is injected so the
     *  retry / source-tracking logic is unit-testable without
     *  any network (this VM's sandbox intercepts ALL of the
     *  JVM's TCP, even to 127.0.0.1, so no local-server test
     *  can run here). There is deliberately NO backup parameter
     *  anymore — the backup must never be reached from here. */
    static List<Server> fetchPrimaryWithRetry(
            java.util.concurrent.Callable<List<Server>> tryPrimary) throws Exception {
        Exception primaryErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                List<Server> primary = tryPrimary.call();
                if (primary != null && !primary.isEmpty()) {
                    lastSource = "primary";
                    lastPrimaryError = "";
                    return primary;
                }
                primaryErr = new Exception("primary subscription empty");
            } catch (Throwable e) {
                primaryErr = new Exception("primary failed: " + shortErr(e));
            }
            if (attempt == 1) {
                try { Thread.sleep(1000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        lastPrimaryError = primaryErr == null ? "subscription empty" : primaryErr.getMessage();
        throw (primaryErr != null ? primaryErr : new Exception("subscription empty"));
    }

    /** Decode one subscription body to plain link text. The
     *  primary comes in two shapes: plain ss:// lines or
     *  ONE base64 blob (a standard V2Ray subscription).
     *  A body whose first non-empty line is already a link is
     *  used as-is; anything else is base64-decoded first.
     *  Throws when the body decodes to no links at all. */
    static String decodeBodyToText(String body) throws Exception {
        if (body == null) throw new Exception("subscription empty");
        String raw = body.trim();
        if (raw.isEmpty()) throw new Exception("subscription empty");
        String first = "";
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) { first = t; break; }
        }
        if (first.startsWith("ss://") || first.startsWith("vmess://")
                || first.startsWith("vless://") || first.startsWith("trojan://")) {
            return raw;
        }
        String b64 = raw.replace('-', '+').replace('_', '/');
        int pad = b64.length() % 4;
        if (pad == 1) throw new Exception("subscription bad base64");
        if (pad > 0) {
            StringBuilder p = new StringBuilder(b64);
            for (int i = 0; i < 4 - pad; i++) p.append('=');
            b64 = p.toString();
        }
        byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
        String text = new String(decoded, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) throw new Exception("subscription empty");
        return text;
    }

    /** Parse plain subscription text (one link per line) into
     *  servers, routing every line by scheme (ss / vmess /
     *  vless / trojan). Throws when no usable server parses. */
    static List<Server> parseSubscriptionLines(String text) throws Exception {
        List<Server> out = new ArrayList<>();
        if (text == null) throw new Exception("subscription empty");
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            try {
                Server s = parseAnyLine(line.trim());
                if (s != null && !isLoopback(s.ip)) out.add(s);
            } catch (Throwable ignored) {
                // Skip malformed lines silently.
            }
        }
        if (out.isEmpty()) throw new Exception("subscription empty");
        return out;
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
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            // The primary is one base64 blob — decode first,
            // then parse. The DECODED text is cached (kind
            // "ss") so the offline re-parse reads plain links,
            // never a blob.
            String text = decodeBodyToText(sb.toString());
            List<Server> out = parseSubscriptionLines(text);
            lastRawText = text;
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
     *  that decodes to ss:// links (mostly) and vmess://
     *  links (a few placeholders), one per line. Returns
     *  only USABLE servers (loopback / info entries are
     *  skipped). */
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
            boolean sawOther = false;
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    // The backup is a multi-protocol
                    // subscription — route every line by scheme
                    // (ss / vmess / vless / trojan) instead of
                    // only ss + vmess.
                    Server s = parseAnyLine(t);
                    if (s == null || isLoopback(s.ip)) continue;
                    out.add(s);
                    if (!"vmess".equals(s.proto)) sawOther = true;
                } catch (Throwable ignored) {
                    // Skip malformed lines silently.
                }
            }
            if (out.isEmpty()) throw new Exception("backup has no usable servers");
            // v8: remember the raw text for the offline cache.
            // A mixed backup gets its own kind so the offline
            // re-parse reads every scheme.
            lastRawText = raw;
            lastRawKind = sawOther ? "backup" : "vmess";
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

    /** Country code (e.g. "FR") from a flag emoji
     *  (regional-indicator pair), or "" when there is none.
     *  The pair is scanned for ANYWHERE in the string: the
     *  backup subscription names its servers "Auto-N 🇫🇷",
     *  so the flag is not at the start. The UI groups by
     *  this plain-ASCII code, so the country never depends
     *  on the emoji surviving the trip to the WebView — the
     *  flag itself is only decoration. */
    static String codeFromFlag(String flag) {
        if (flag == null) return "";
        String f = flagFromName(flag);
        if (f.isEmpty()) return "";
        int[] cps = f.codePoints().toArray();
        if (cps.length < 2) return "";
        int a = cps[0] - 0x1F1E6, b = cps[1] - 0x1F1E6;
        if (a < 0 || a > 25 || b < 0 || b > 25) return "";
        return "" + (char) ('A' + a) + (char) ('A' + b);
    }

    /** URL-decode a link fragment / query value, "" on failure. */
    static String urlDecode(String s) {
        if (s == null) return "";
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Parse one query string ("a=1&b=2") into a map. Keys and
     *  values are URL-decoded; a missing value is "". */
    static java.util.Map<String, String> parseQuery(String q) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) m.put(urlDecode(pair), "");
            else m.put(urlDecode(pair.substring(0, eq)),
                    urlDecode(pair.substring(eq + 1)));
        }
        return m;
    }

    /** Split a link into [before-# fragment, fragment] and
     *  [before-? query, query] parts. Returns {main, query,
     *  fragment} with "" defaults. */
    static String[] splitLink(String line) {
        String main = line, query = "", frag = "";
        int hash = main.indexOf('#');
        if (hash >= 0) {
            frag = main.substring(hash + 1);
            main = main.substring(0, hash);
        }
        int q = main.indexOf('?');
        if (q >= 0) {
            query = main.substring(q + 1);
            main = main.substring(0, q);
        }
        return new String[]{main, query, frag};
    }

    /**
     * Parse one vless:// link (emergency sub).
     * vless://&lt;uuid&gt;@&lt;host&gt;:&lt;port&gt;?encryption=none&amp;security=tls&amp;type=ws&amp;host=..&amp;path=..&amp;sni=..&amp;flow=..#&lt;name&gt;
     * Returns null for anything malformed, for loopback
     * addresses, and for info/status names. Never logs the uuid.
     */
    static Server parseVlessLine(String line) {
        if (line == null || !line.startsWith("vless://")) return null;
        String rest = line.substring("vless://".length()).trim();
        if (rest.isEmpty()) return null;
        String[] parts = splitLink(rest);
        String main = parts[0];
        java.util.Map<String, String> q = parseQuery(parts[1]);
        String name = urlDecode(parts[2]);
        int at = main.lastIndexOf('@');
        if (at <= 0) return null;
        String uuid = main.substring(0, at);
        String hostport = main.substring(at + 1);
        int colon = hostport.lastIndexOf(':');
        if (colon <= 0) return null;
        String addr = hostport.substring(0, colon);
        // IPv6 literals arrive as [::1] — strip the brackets.
        if (addr.startsWith("[") && addr.endsWith("]"))
            addr = addr.substring(1, addr.length() - 1);
        int port;
        try {
            port = Integer.parseInt(hostport.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (uuid.isEmpty() || addr.isEmpty() || port <= 0 || port > 65535) return null;
        if (isLoopback(addr)) return null;
        if (isInfoName(name)) return null;
        Server s = new Server();
        s.proto = "vless";
        s.ip = addr;
        s.port = port;
        s.uuid = uuid;
        s.encryption = q.get("encryption");
        if (s.encryption == null || s.encryption.isEmpty()) s.encryption = "none";
        s.flow = q.get("flow");
        if (s.flow == null) s.flow = "";
        s.network = q.get("type");
        if (s.network == null || s.network.isEmpty()) s.network = "tcp";
        s.type = "none";
        s.host = q.get("host");
        if (s.host == null) s.host = "";
        s.path = q.get("path");
        if (s.path == null) s.path = "";
        String security = q.get("security");
        s.tls = ("tls".equals(security) || "reality".equals(security)) ? "tls" : "none";
        s.name = name;
        String flag = flagFromName(name);
        s.flag = flag.isEmpty() ? "\uD83C\uDF10" : flag;
        s.countryCode = resolveCountry(addr, flag);
        String sni = q.get("sni");
        s.serverName = (sni != null && !sni.isEmpty()) ? sni
                : (s.host != null && !s.host.isEmpty()) ? s.host : addr;
        return s;
    }

    /**
     * Parse one trojan:// link (emergency sub).
     * trojan://&lt;password&gt;@&lt;host&gt;:&lt;port&gt;?sni=..&amp;type=ws&amp;host=..&amp;path=..#&lt;name&gt;
     * The password is URL-decoded (Trojan links percent-encode
     * it). Returns null for anything malformed, for loopback
     * addresses, and for info/status names. Never logs the
     * password.
     */
    static Server parseTrojanLine(String line) {
        if (line == null || !line.startsWith("trojan://")) return null;
        String rest = line.substring("trojan://".length()).trim();
        if (rest.isEmpty()) return null;
        String[] parts = splitLink(rest);
        String main = parts[0];
        java.util.Map<String, String> q = parseQuery(parts[1]);
        String name = urlDecode(parts[2]);
        int at = main.lastIndexOf('@');
        if (at <= 0) return null;
        String password = urlDecode(main.substring(0, at));
        String hostport = main.substring(at + 1);
        int colon = hostport.lastIndexOf(':');
        if (colon <= 0) return null;
        String addr = hostport.substring(0, colon);
        if (addr.startsWith("[") && addr.endsWith("]"))
            addr = addr.substring(1, addr.length() - 1);
        int port;
        try {
            port = Integer.parseInt(hostport.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (password.isEmpty() || addr.isEmpty() || port <= 0 || port > 65535) return null;
        if (isLoopback(addr)) return null;
        if (isInfoName(name)) return null;
        Server s = new Server();
        s.proto = "trojan";
        s.ip = addr;
        s.port = port;
        s.password = password;
        s.network = q.get("type");
        if (s.network == null || s.network.isEmpty()) s.network = "tcp";
        s.type = "none";
        s.host = q.get("host");
        if (s.host == null) s.host = "";
        s.path = q.get("path");
        if (s.path == null) s.path = "";
        s.tls = "tls"; // Trojan always runs over TLS.
        s.name = name;
        String flag = flagFromName(name);
        s.flag = flag.isEmpty() ? "\uD83C\uDF10" : flag;
        s.countryCode = resolveCountry(addr, flag);
        String sni = q.get("sni");
        s.serverName = (sni != null && !sni.isEmpty()) ? sni
                : (s.host != null && !s.host.isEmpty()) ? s.host : addr;
        return s;
    }

    /** Route one subscription line to the right parser by
     *  scheme — ss / vmess / vless / trojan — or null when
     *  the scheme is unknown. One dispatcher serves the
     *  primary, the backup and the offline cache, so a new
     *  protocol works everywhere at once.
     */
    static Server parseAnyLine(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.startsWith("ss://")) return parseLine(t);
        if (t.startsWith("vmess://")) return parseVmessLine(t);
        if (t.startsWith("vless://")) return parseVlessLine(t);
        if (t.startsWith("trojan://")) return parseTrojanLine(t);
        return null;
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
        s.countryCode = resolveCountry(addr, flag);
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
        s.countryCode = resolveCountry(ip, flag);
        return s;
    }
}
