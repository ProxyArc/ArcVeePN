package com.vpeen.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Port of VPeeN's vpeen/api.py (pure stdlib there, HttpURLConnection here).
 * Flow: guest token via POST /v3/launch/, free locations via
 * GET /v3/location/extension/ (proxyType==0), servers via
 * POST /v3/server/list/ {protocol:"https", region, type:0}.
 * Servers are NOT hardcoded: they are fetched live and their
 * credentials are per-session.
 */
public class VeePnApi {
    public static final String APP_VERSION = "5.0.2";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private static final String[] DEFAULT_DOMAINS = {
            "https://antpeak.com", "https://hibchr.com", "https://hisball.com",
            "https://bitphox.com", "https://freloop.com", "https://tronlit.com",
            "https://tronyza.com"
    };
    private static final String[] RESERVE_BUCKETS = {
            "https://s3-oregon-1.s3-us-west-2.amazonaws.com/api.json",
            "https://proigor.com/payload.json"
    };
    private static final Pattern DOMAIN_RE = Pattern.compile(
            "^https://(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+"
            + "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?::\\d{1,5})?$");
    private static final long TOKEN_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final long RESERVE_TTL_MS = 24L * 3600 * 1000;

    private final SharedPreferences prefs;

    public VeePnApi(Context ctx) {
        prefs = ctx.getSharedPreferences("vpeen", Context.MODE_PRIVATE);
    }

    public static class ApiException extends Exception {
        public ApiException(String m) { super(m); }
    }

    private static class HttpResult {
        int status; Object json; String raw;
    }

    public String udid() {
        String u = prefs.getString("udid", null);
        if (u == null) {
            u = UUID.randomUUID().toString();
            prefs.edit().putString("udid", u).apply();
        }
        return u;
    }

    private static boolean validDomain(String u) {
        if (u == null) return false;
        u = u.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!DOMAIN_RE.matcher(u).matches()) return false;
        if (u.contains(":")) {
            String rest = u.substring("https://".length());
            if (rest.contains(":")) {
                try {
                    int p = Integer.parseInt(rest.substring(rest.lastIndexOf(':') + 1));
                    if (p <= 0 || p >= 65536) return false;
                } catch (NumberFormatException e) { return false; }
            }
        }
        return true;
    }

    private HttpResult http(String urlStr, String method, String body, String token) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream os = c.getOutputStream()) { os.write(b); }
        }
        int status = c.getResponseCode();
        InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        HttpResult res = new HttpResult();
        res.status = status; res.raw = sb.toString().trim();
        String t = res.raw;
        try {
            if (t.startsWith("{")) res.json = new JSONObject(t);
            else if (t.startsWith("[")) res.json = new JSONArray(t);
        } catch (Exception ignored) {}
        c.disconnect();
        return res;
    }

    public synchronized List<String> getDomains() {
        List<String> out = new ArrayList<>();
        for (String d : DEFAULT_DOMAINS) out.add(d);
        long now = System.currentTimeMillis();
        String cached = prefs.getString("reserve_domains", null);
        long cachedAt = prefs.getLong("reserve_at", 0);
        List<String> extra = new ArrayList<>();
        if (cached != null && now - cachedAt < RESERVE_TTL_MS) {
            try {
                JSONArray a = new JSONArray(cached);
                for (int i = 0; i < a.length(); i++) extra.add(a.getString(i));
            } catch (Exception ignored) {}
        } else {
            for (String bucket : RESERVE_BUCKETS) {
                try {
                    HttpResult r = http(bucket, "GET", null, null);
                    if (r.status == 200 && r.json instanceof JSONObject) {
                        JSONObject p = (JSONObject) r.json;
                        JSONObject doms = p.optJSONObject("domains");
                        if (doms != null) {
                            JSONArray f = doms.optJSONArray("free");
                            if (f != null) for (int i = 0; i < f.length(); i++) {
                                String u = f.optString(i, null);
                                if (u != null && validDomain(u)) extra.add(u);
                            }
                        }
                        Object free = p.opt("free");
                        if (free instanceof String) {
                            if (validDomain((String) free)) extra.add((String) free);
                        } else if (free instanceof JSONArray) {
                            JSONArray f = (JSONArray) free;
                            for (int i = 0; i < f.length(); i++) {
                                String u = f.optString(i, null);
                                if (u != null && validDomain(u)) extra.add(u);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (!extra.isEmpty()) {
                JSONArray a = new JSONArray();
                for (String u : extra) a.put(u);
                prefs.edit().putString("reserve_domains", a.toString())
                        .putLong("reserve_at", now).apply();
            }
        }
        for (String d : extra) {
            d = d.trim();
            if (d.endsWith("/")) d = d.substring(0, d.length() - 1);
            if (validDomain(d) && !out.contains(d)) out.add(d);
        }
        return out;
    }

    public synchronized String ensureToken(boolean force) throws Exception {
        String tok = prefs.getString("token", null);
        long at = prefs.getLong("token_at", 0);
        long now = System.currentTimeMillis();
        if (!force && tok != null && now - at < TOKEN_TTL_MS) return tok;
        JSONObject body = new JSONObject();
        body.put("udid", udid());
        body.put("appVersion", APP_VERSION);
        body.put("platform", "chrome");
        body.put("platformVersion", USER_AGENT);
        body.put("timeZone", TimeZone.getDefault().getID());
        body.put("deviceName", "Chrome 130");
        String last = "unknown";
        for (String domain : getDomains()) {
            try {
                HttpResult r = http(domain + "/v3/launch/", "POST", body.toString(), null);
                if (r.status == 200 && r.json instanceof JSONObject) {
                    String access = ((JSONObject) r.json).optString("access", null);
                    if (access != null && !access.isEmpty()) {
                        prefs.edit().putString("token", access).putLong("token_at", now).apply();
                        return access;
                    }
                    last = domain + ": bad launch payload";
                } else if (r.status == 429 || r.status >= 500) {
                    last = domain + ": HTTP " + r.status;
                    Thread.sleep(800);
                } else {
                    throw new ApiException("launch HTTP " + r.status);
                }
            } catch (ApiException e) { throw e; }
            catch (Exception e) { last = domain + ": " + e.getMessage(); }
        }
        throw new ApiException("Could not obtain token (" + last + ")");
    }

    private Object request(String path, String method, String body, boolean auth) throws Exception {
        boolean refreshed = false;
        String last = "unknown";
        for (String domain : getDomains()) {
            try {
                String token = auth ? ensureToken(false) : null;
                HttpResult r = http(domain + path, method, body, token);
                if (r.status >= 200 && r.status < 300) return r.json;
                if (auth && (r.status == 401 || r.status == 403) && !refreshed) {
                    refreshed = true;
                    ensureToken(true);
                    continue;
                }
                if (r.status == 429 || r.status >= 500) {
                    last = domain + ": HTTP " + r.status;
                    Thread.sleep(800);
                    continue;
                }
                throw new ApiException("HTTP " + r.status + " from " + domain);
            } catch (ApiException e) { throw e; }
            catch (Exception e) { last = domain + ": " + e.getMessage(); }
        }
        throw new ApiException("All API attempts failed (" + last + ")");
    }

    public List<Location> locations() throws Exception {
        Object o = request("/v3/location/extension/", "GET", null, true);
        List<Location> free = new ArrayList<>();
        if (o instanceof JSONObject) {
            JSONArray arr = ((JSONObject) o).optJSONArray("locations");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject l = arr.optJSONObject(i);
                    if (l == null) continue;
                    if (l.optInt("proxyType", -1) != 0) continue;
                    Location loc = new Location();
                    loc.region = l.optString("region", "");
                    loc.countryCode = l.optString("countryCode", "");
                    loc.name = l.optString("name", loc.region);
                    loc.id = l.optString("id", "");
                    free.add(loc);
                }
            }
        }
        return free;
    }

    public List<Server> serverList(String region) throws Exception {
        JSONObject body = new JSONObject();
        body.put("protocol", "https");
        body.put("region", region);
        body.put("type", 0);
        Object o = request("/v3/server/list/", "POST", body.toString(), true);
        List<Server> out = new ArrayList<>();
        if (o instanceof JSONArray) {
            JSONArray arr = (JSONArray) o;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                JSONArray addrs = s.optJSONArray("addresses");
                int port = s.optInt("port", 0);
                if (addrs == null || addrs.length() == 0 || port == 0) continue;
                Server sv = new Server();
                sv.region = region;
                sv.regionName = s.optString("regionName", region);
                for (int j = 0; j < addrs.length(); j++) sv.addresses.add(addrs.optString(j));
                sv.port = port;
                sv.username = s.optString("username", "");
                sv.password = s.optString("password", "");
                out.add(sv);
            }
        }
        return out;
    }
}
