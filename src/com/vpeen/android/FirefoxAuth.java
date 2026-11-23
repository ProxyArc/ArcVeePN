package com.vpeen.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Firefox (Mozilla VPN) servers — the 3.1 source, next to the
 * GitHub primary and the manual emergency subscription.
 *
 * The servers are Mozilla VPN / Firefox VPN on Fastly: plain
 * HTTP CONNECT (an HTTPS forward proxy) with Bearer auth, NOT
 * VLESS / Shadowsocks / MASQUE. The flow follows Arman VPN
 * v1.0.19 (armanlife/arman-vpn-client) exactly:
 *
 * 1. Sign-in happens in a WebView on Mozilla's own page
 *    (FirefoxLoginDialog) — the app never sees the password.
 *    The page reports the signed-in session over its WebChannel
 *    (fxaccounts:login -> sessionToken, 64 hex chars).
 * 2. The session is polled (GET /recovery_email/status, HAWK
 *    auth) until FxA confirms it, then exchanged for OAuth
 *    tokens (POST /oauth/token, grant fxa-credentials, HAWK
 *    auth), then the session is destroyed.
 * 3. The OAuth access token mints a ProxyPass JWT
 *    (GET https://vpn.mozilla.org/api/v1/fpn/token) — the
 *    Bearer token sing-box sends on CONNECT. 403 -> activate
 *    (POST /api/v1/fpn/activate) and retry once; 401 -> refresh
 *    the OAuth token and retry once.
 * 4. Quota rides the SAME token response's headers
 *    (X-Quota-Unlimited / -Limit / -Remaining / -Reset).
 *    HEAD on the same endpoint reads quota without minting.
 * 5. The server list is public (Remote Settings, no login):
 *    countries -> cities -> servers (hostname:port, default
 *    443; empty protocols -> CONNECT to hostname:port).
 *    Quarantined servers are skipped. REC is the recommended
 *    (anycast) location and is shown as its own group.
 *
 * Tokens live ONLY in the app's private SharedPreferences
 * ("arcveepn_firefox", MODE_PRIVATE). They are never logged,
 * never shown on screen, never written to any file the user
 * can see, and never put in the APK's assets. The ProxyPass
 * JWT is handed to SingBoxConfig only in memory, at connect
 * time, and only for the one tunnel being built.
 *
 * Network note: every HTTP call here runs on a background
 * thread (the callers own the threading). Timeouts are short
 * so a dead endpoint fails fast instead of hanging a connect.
 */
public final class FirefoxAuth {
    private FirefoxAuth() {}

    // ---- Endpoints (Arman v1.0.19) ----
    public static final String FXA_AUTH = "https://api.accounts.firefox.com/v1";
    public static final String FXA_OAUTH_TOKEN = "https://oauth.accounts.firefox.com/v1/token";
    public static final String FXA_CLIENT_ID = "5882386c6d801776";
    public static final String FXA_SCOPE = "profile https://identity.mozilla.com/apps/vpn";
    public static final String GUARDIAN = "https://vpn.mozilla.org";
    public static final String GUARDIAN_TOKEN = GUARDIAN + "/api/v1/fpn/token";
    public static final String GUARDIAN_ACTIVATE = GUARDIAN + "/api/v1/fpn/activate";
    public static final String GUARDIAN_STATUS = GUARDIAN + "/api/v1/fpn/status";
    public static final String SERVER_LIST_URL =
            "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records";
    public static final String LOGIN_URL =
            "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email";
    public static final String SIGNUP_URL =
            "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=signup";

    // Firefox user-agents, exactly as Arman sends them:
    // desktop Firefox (control plane + CONNECT) and Android
    // Firefox (the sign-in WebView — accounts.firefox.com
    // only speaks its WebChannel to what looks like Firefox).
    public static final String FIREFOX_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0";
    public static final String FIREFOX_ANDROID_UA =
            "Mozilla/5.0 (Android 14; Mobile; rv:145.0) Gecko/145.0 Firefox/145.0";

    // FxA errnos (Arman signin_flow.go): the OAuth grant may
    // see the confirmation a moment after the status does.
    static final int ERRNO_UNVERIFIED_ACCOUNT = 104;
    static final int ERRNO_UNVERIFIED_SESSION = 138;

    static final int CONNECT_TIMEOUT_MS = 15000;
    static final int READ_TIMEOUT_MS = 20000;
    static final long PROXY_PASS_EARLY_MS = 60_000; // refresh 60 s before expiry
    static final long ACCESS_EARLY_MS = 60_000;
    // 3.1 fix: the ProxyPass JWT lives 10 minutes (Arman
    // guardian/pass.go: ExpiresAt() = the JWT exp claim; the
    // test fixtures use now + 10 min). Arman renews at
    // expiry - proxyPassRenewLead (2 min, renewal.go:20) and
    // re-checks the WALL clock at least every 15 s
    // (wallCheckInterval, renewal.go:335) because a sleeping
    // phone's timers stall while the pass expires by wall
    // clock. Our renewal loop (VpnTunnelService) mirrors
    // this: check every 30 s, renew at expiry - 2 min,
    // retry a failed renewal after 30 s
    // (proxyPassRetryDelay, renewal.go:22); a dead login /
    // spent quota is terminal (15 min in Arman,
    // terminalRenewalRetryDelay, proxypass.go:20) — we stop
    // retrying at once and surface the auth-lost path.
    public static final long PROXY_PASS_RENEW_LEAD_MS = 120_000;
    public static final long RENEW_CHECK_INTERVAL_MS = 30_000;
    public static final long RENEW_RETRY_DELAY_MS = 30_000;
    public static final long STALL_WINDOW_MS = 90_000;
    // 3.1 fix3: the minimum gap between two live reloads of
    // the running tunnel. A reload is a FULL teardown of the
    // sing-box instance (oldInstance.Close() + a new TUN —
    // daemon/started_service.go StartOrReloadService), so a
    // reload every 30 s would keep killing every flow and
    // look like a permanent slowdown. Arman spaces
    // successful renewals by minRenewalInterval (10 s,
    // renewal.go) for the same reason — "a pass that is
    // already due for renewal when it arrives cannot cause
    // a tight loop". Our reload is far heavier than
    // Arman's token swap, so the floor is 60 s, not 10 s.
    public static final long MIN_RELOAD_INTERVAL_MS = 60_000;
    static final long SERVER_LIST_TTL_MS = 24L * 60 * 60 * 1000; // 24 h

    // ---- Private storage ----
    public static final String PREFS = "arcveepn_firefox";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_ACCESS_EXP = "access_expires_at";
    private static final String KEY_PASS = "proxypass_token";
    private static final String KEY_PASS_EXP = "proxypass_expires_at";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_QUOTA = "quota_json";
    private static final String KEY_LIST = "server_list_json";
    private static final String KEY_LIST_AT = "server_list_at";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isLoggedIn(Context ctx) {
        try {
            String r = prefs(ctx).getString(KEY_REFRESH, "");
            return r != null && !r.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getEmail(Context ctx) {
        try {
            String e = prefs(ctx).getString(KEY_EMAIL, "");
            return e == null ? "" : e;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The cached quota line for the UI ("نامحدود" or
     *  "50 GB · 37% used · resets 12 May"), "" when unknown. */
    public static String getQuotaLine(Context ctx) {
        try {
            String j = prefs(ctx).getString(KEY_QUOTA, "");
            if (j == null || j.isEmpty()) return "";
            Quota q = Quota.fromJson(new JSONObject(j));
            return q == null ? "" : q.formatLine();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void saveQuota(Context ctx, Quota q) {
        try {
            if (q == null) return;
            prefs(ctx).edit().putString(KEY_QUOTA, q.toJson().toString()).apply();
        } catch (Throwable ignored) {}
    }

    static void saveTokens(Context ctx, String email, TokenResponse tok) {
        try {
            SharedPreferences.Editor e = prefs(ctx).edit();
            if (email != null) e.putString(KEY_EMAIL, email);
            e.putString(KEY_ACCESS, tok.accessToken);
            e.putString(KEY_REFRESH, tok.refreshToken);
            long expAt = tok.expiresIn > 0
                    ? System.currentTimeMillis() + tok.expiresIn * 1000L
                    : System.currentTimeMillis() + 3600_000L;
            e.putLong(KEY_ACCESS_EXP, expAt);
            e.apply();
        } catch (Throwable ignored) {}
    }

    static void saveProxyPass(Context ctx, ProxyPass p) {
        try {
            SharedPreferences.Editor e = prefs(ctx).edit();
            e.putString(KEY_PASS, p.token);
            e.putLong(KEY_PASS_EXP, p.expiresAtMs);
            e.apply();
            if (p.quota != null) saveQuota(ctx, p.quota);
        } catch (Throwable ignored) {}
    }

    /** The stored ProxyPass expiry (ms since epoch), 0 when
     *  no pass is stored. Never logs or returns the token. */
    public static long getProxyPassExpiresAtMs(Context ctx) {
        try {
            return prefs(ctx).getLong(KEY_PASS_EXP, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Pure renewal decision (unit-testable, no Android):
     *  renew when the wall clock has passed expiry - lead.
     *  A pass with no known expiry (0) never triggers a
     *  proactive renewal — the caller must fetch one first. */
    public static boolean shouldRenew(long nowMs, long passExpMs, long leadMs) {
        if (passExpMs <= 0) return false;
        return nowMs > passExpMs - leadMs;
    }

    /** True when the stored pass is due for renewal at the
     *  2-minute lead (see PROXY_PASS_RENEW_LEAD_MS). */
    public static boolean needsRenewal(Context ctx) {
        return shouldRenew(System.currentTimeMillis(),
                getProxyPassExpiresAtMs(ctx), PROXY_PASS_RENEW_LEAD_MS);
    }

    /** 3.1 fix3 — pure reload decision for the renewal loop
     *  (unit-testable, no Android). A live reload is a FULL
     *  teardown of the tunnel (sing-box Close() + a new TUN),
     *  so it must happen ONLY when the fresh pass actually
     *  buys lifetime. The fix-build loop reloaded whenever
     *  the pass was due, without ever checking what the fetch
     *  returned: when Guardian answers with the SAME cached
     *  token (or one that is already due — Arman's own
     *  renewal.go expects this case), the stored expiry never
     *  advances, the pass stays due, and the loop reloads
     *  every 30 s forever — every reload kills every flow,
     *  which is exactly the "speed drops after ~10 min and
     *  never recovers" symptom. So reload only when ALL of
     *  these hold:
     *   - the new pass is not already dead (newExp > now);
     *   - the new pass lives LONGER than the old one
     *     (newExp > oldExp) — a same-or-earlier expiry means
     *     the fetch bought nothing, so reloading would only
     *     tear the tunnel down for the same token;
     *   - at least MIN_RELOAD_INTERVAL_MS has passed since
     *     the last reload (a backstop against a fast
     *     fetch/reload cycle even when each fetch extends the
     *     expiry by a little).
     *  An old expiry of 0 means "no baseline" (first pass)
     *  and does not block the reload. */
    public static boolean shouldReloadForRenewal(long nowMs,
            long oldExpMs, long newExpMs, long lastReloadAtMs) {
        if (newExpMs <= nowMs) return false;
        if (oldExpMs > 0 && newExpMs <= oldExpMs) return false;
        if (lastReloadAtMs > 0
                && nowMs - lastReloadAtMs < MIN_RELOAD_INTERVAL_MS) return false;
        return true;
    }

    /** Forget everything (sign-out / re-auth required). The
     *  server-list cache is kept — it is public data. */
    public static void clearAll(Context ctx) {
        try {
            prefs(ctx).edit()
                    .remove(KEY_ACCESS).remove(KEY_REFRESH)
                    .remove(KEY_ACCESS_EXP)
                    .remove(KEY_PASS).remove(KEY_PASS_EXP)
                    .remove(KEY_EMAIL).remove(KEY_QUOTA)
                    .apply();
        } catch (Throwable ignored) {}
    }

    // ---- Errors ----
    /** reauth = the stored credential was rejected for good
     *  (bad/expired refresh token, revoked session); the user
     *  must sign in again. Anything else is transient. */
    public static final class FfxException extends Exception {
        public final boolean reauth;
        public final int httpStatus;
        public final int errno;
        public FfxException(String msg, boolean reauth, int httpStatus, int errno) {
            super(msg);
            this.reauth = reauth;
            this.httpStatus = httpStatus;
            this.errno = errno;
        }
        public FfxException(String msg) { this(msg, false, 0, 0); }
    }

    // ---- Value types ----
    public static final class TokenResponse {
        public String accessToken = "";
        public String refreshToken = "";
        public int expiresIn = 0;
    }

    public static final class ProxyPass {
        public String token = "";
        public long expiresAtMs = 0;
        public Quota quota = null;
    }

    /** Quota from the Guardian X-Quota-* headers. */
    public static final class Quota {
        public boolean unlimited = false;
        public long limit = 0;
        public long remaining = 0;
        public long resetMs = 0;

        public int usedPct() {
            if (unlimited || limit <= 0) return 0;
            long used = limit - remaining;
            if (used < 0) used = 0;
            if (used > limit) used = limit;
            return (int) Math.round(used * 100.0 / limit);
        }

        /** "نامحدود" when unlimited, else e.g.
         *  "50 GB · 37% used · resets 12 May". */
        public String formatLine() {
            if (unlimited) return "نامحدود";
            if (limit <= 0) return "";
            String lim = humanBytes(limit);
            String line = lim + " · " + usedPct() + "% used";
            if (resetMs > 0) {
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("d MMM", Locale.US);
                f.setTimeZone(java.util.TimeZone.getDefault());
                line += " · resets " + f.format(new java.util.Date(resetMs));
            }
            return line;
        }

        static String humanBytes(long bytes) {
            if (bytes >= 1_000_000_000L) {
                double gb = bytes / 1_000_000_000.0;
                return (gb < 10 ? trim1(gb) : String.valueOf(Math.round(gb))) + " GB";
            }
            if (bytes >= 1_000_000L) {
                double mb = bytes / 1_000_000.0;
                return (mb < 10 ? trim1(mb) : String.valueOf(Math.round(mb))) + " MB";
            }
            return bytes + " B";
        }

        private static String trim1(double v) {
            String s = String.format(Locale.US, "%.1f", v);
            return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("unlimited", unlimited);
            o.put("limit", limit);
            o.put("remaining", remaining);
            o.put("reset", resetMs);
            return o;
        }

        public static Quota fromJson(JSONObject o) {
            if (o == null) return null;
            Quota q = new Quota();
            q.unlimited = o.optBoolean("unlimited", false);
            q.limit = o.optLong("limit", 0);
            q.remaining = o.optLong("remaining", 0);
            q.resetMs = o.optLong("reset", 0);
            return q;
        }
    }

    // ---- HTTP plumbing ----
    static final class Resp {
        int status;
        String body = "";
        String quotaUnlimited = "";
        String quotaLimit = "";
        String quotaRemaining = "";
        String quotaReset = "";
    }

    private static String readBody(HttpURLConnection c) throws Exception {
        InputStream is;
        try {
            is = c.getInputStream();
        } catch (Throwable e) {
            is = c.getErrorStream();
        }
        if (is == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > 256 * 1024) break;
            out.write(buf, 0, n);
        }
        try { is.close(); } catch (Throwable ignored) {}
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", FIREFOX_UA);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setUseCaches(false);
        return c;
    }

    private static Resp exec(HttpURLConnection c) throws Exception {
        Resp r = new Resp();
        r.status = c.getResponseCode();
        r.body = readBody(c);
        r.quotaUnlimited = header(c, "X-Quota-Unlimited");
        r.quotaLimit = header(c, "X-Quota-Limit");
        r.quotaRemaining = header(c, "X-Quota-Remaining");
        r.quotaReset = header(c, "X-Quota-Reset");
        try { c.disconnect(); } catch (Throwable ignored) {}
        return r;
    }

    private static String header(HttpURLConnection c, String name) {
        try {
            String v = c.getHeaderField(name);
            return v == null ? "" : v.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Resp get(String url) throws Exception {
        return exec(open(url, "GET"));
    }

    private static Resp postJson(String url, String bodyJson, String authHeader) throws Exception {
        HttpURLConnection c = open(url, "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (authHeader != null) c.setRequestProperty("Authorization", authHeader);
        byte[] b = bodyJson.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        java.io.OutputStream os = c.getOutputStream();
        os.write(b);
        os.flush();
        try { os.close(); } catch (Throwable ignored) {}
        return exec(c);
    }

    private static Resp getAuthed(String url, String authHeader) throws Exception {
        HttpURLConnection c = open(url, "GET");
        if (authHeader != null) c.setRequestProperty("Authorization", authHeader);
        return exec(c);
    }

    private static Resp headAuthed(String url, String authHeader) throws Exception {
        HttpURLConnection c = open(url, "HEAD");
        if (authHeader != null) c.setRequestProperty("Authorization", authHeader);
        return exec(c);
    }

    private static int errnoOf(String body) {
        try {
            return new JSONObject(body).optInt("errno", 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ---- HAWK (Arman core/internal/fxa/hawk.go) ----
    // HKDF-SHA256, RFC 5869. A null/empty salt is the Go
    // hkdf default: zeros of hash length.
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        if (salt == null || salt.length == 0) salt = new byte[32];
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        byte[] out = new byte[len];
        byte[] t = new byte[0];
        int done = 0;
        byte ctr = 1;
        while (done < len) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(t);
            mac.update(info);
            mac.update(new byte[]{ctr});
            t = mac.doFinal();
            int n = Math.min(t.length, len - done);
            System.arraycopy(t, 0, out, done, n);
            done += n;
            ctr++;
        }
        return out;
    }

    static String hexEncode(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format(Locale.US, "%02x", v & 0xff));
        return sb.toString();
    }

    static byte[] hexDecode(String hex) throws Exception {
        String h = hex.trim().toLowerCase(Locale.US);
        if (h.length() % 2 != 0) throw new Exception("odd hex length");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new Exception("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Derive the HAWK id + key from a session token (64 hex
     *  chars): HKDF-SHA256, info =
     *  "identity.mozilla.com/picl/v1/sessionToken", 96 bytes
     *  out — id = hex(out[0:32]), key = out[32:64]. Returns
     *  {idHex, keyHex}. */
    static String[] deriveHawkCredentials(String tokenHex) throws Exception {
        byte[] tokenBytes = hexDecode(tokenHex);
        byte[] info = ("identity.mozilla.com/picl/v1/sessionToken")
                .getBytes(StandardCharsets.UTF_8);
        byte[] out = hkdfSha256(tokenBytes, null, info, 96);
        return new String[]{
                hexEncode(Arrays.copyOfRange(out, 0, 32)),
                hexEncode(Arrays.copyOfRange(out, 32, 64))};
    }

    /** The HAWK Authorization header for one request.
     *  The fixed-nonce/ts overload exists so unit tests can
     *  assert the exact header string; the live path uses a
     *  random nonce and the current time. */
    static String hawkHeader(String method, String rawUrl, String hawkId,
            byte[] hawkKey, String payload, String nonce, long ts) throws Exception {
        URL u = new URL(rawUrl);
        String port = u.getPort() >= 0 ? String.valueOf(u.getPort())
                : ("https".equalsIgnoreCase(u.getProtocol()) ? "443" : "80");
        String uri = u.getPath();
        if (uri == null || uri.isEmpty()) uri = "/";
        if (u.getQuery() != null) uri += "?" + u.getQuery();
        String payloadHash = "";
        if (payload != null && !payload.isEmpty()) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("hawk.1.payload\napplication/json\n"
                    .getBytes(StandardCharsets.UTF_8));
            sha.update(payload.getBytes(StandardCharsets.UTF_8));
            sha.update("\n".getBytes(StandardCharsets.UTF_8));
            payloadHash = hexEncode(sha.digest());
        }
        String normalized = "hawk.1.header\n" + ts + "\n" + nonce + "\n"
                + method.toUpperCase(Locale.US) + "\n" + uri + "\n"
                + u.getHost() + "\n" + port + "\n" + payloadHash + "\n\n";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hawkKey, "HmacSHA256"));
        String macHex = hexEncode(mac.doFinal(
                normalized.getBytes(StandardCharsets.UTF_8)));
        String header = "Hawk id=\"" + hawkId + "\", ts=\"" + ts
                + "\", nonce=\"" + nonce + "\", mac=\"" + macHex + "\"";
        if (!payloadHash.isEmpty()) header += ", hash=\"" + payloadHash + "\"";
        return header;
    }

    static String hawkHeader(String method, String rawUrl, String hawkId,
            byte[] hawkKey, String payload) throws Exception {
        byte[] nonceBytes = new byte[6];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        return hawkHeader(method, rawUrl, hawkId, hawkKey, payload,
                hexEncode(nonceBytes), System.currentTimeMillis() / 1000L);
    }

    private static String hawkAuthFor(String method, String url,
            String sessionToken, String payload) throws Exception {
        String[] creds = deriveHawkCredentials(sessionToken);
        return hawkHeader(method, url, creds[0], hexDecode(creds[1]), payload);
    }

    // ---- FxA session calls ----

    /** GET /recovery_email/status (HAWK) — true when the
     *  session is confirmed (email + any 2FA code entered). */
    static boolean sessionVerified(String sessionToken) throws Exception {
        String url = FXA_AUTH + "/recovery_email/status";
        String auth = hawkAuthFor("GET", url, sessionToken, "");
        HttpURLConnection c = open(url, "GET");
        c.setRequestProperty("Authorization", auth);
        Resp r = exec(c);
        if (r.status != 200) {
            int errno = errnoOf(r.body);
            throw new FfxException("session status HTTP " + r.status,
                    r.status == 400 || r.status == 401 || r.status == 403,
                    r.status, errno);
        }
        JSONObject j = new JSONObject(r.body);
        if (!j.has("verified")) throw new FfxException("bad session status");
        return j.optBoolean("verified", false);
    }

    /** POST /oauth/token, grant fxa-credentials (HAWK).
     *  Retries while FxA says the account/session is not
     *  verified yet (errno 104/138), up to 10 attempts, 1 s
     *  apart (3.1 fix6: was 3 s — the exchange runs right
     *  after the status check just said verified, so the
     *  race is short; the 15-minute wait for an unverified
     *  account lives in the dialog's poll loop, not here) —
     *  the grant can see the confirmation a moment after
     *  the status does (Arman browserSignIn). */
    static TokenResponse exchangeOAuth(String sessionToken) throws Exception {
        // Go's encoding/json sorts map keys: the body is
        // exactly this string, and the HAWK payload hash is
        // computed over it — build it literally, never via a
        // JSONObject (whose key order is not guaranteed).
        String body = "{\"access_type\":\"offline\",\"client_id\":\""
                + FXA_CLIENT_ID + "\",\"grant_type\":\"fxa-credentials\",\"scope\":\""
                + FXA_SCOPE + "\"}";
        String url = FXA_AUTH + "/oauth/token";
        Exception lastErr = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            String auth = hawkAuthFor("POST", url, sessionToken, body);
            Resp r = postJson(url, body, auth);
            if (r.status == 200) {
                JSONObject j = new JSONObject(r.body);
                TokenResponse t = new TokenResponse();
                t.accessToken = j.optString("access_token", "");
                t.refreshToken = j.optString("refresh_token", "");
                t.expiresIn = j.optInt("expires_in", 0);
                if (t.accessToken.isEmpty() || t.refreshToken.isEmpty())
                    throw new FfxException("empty OAuth tokens");
                return t;
            }
            int errno = errnoOf(r.body);
            lastErr = new FfxException("oauth token HTTP " + r.status,
                    r.status == 400 || r.status == 401 || r.status == 403,
                    r.status, errno);
            if (errno != ERRNO_UNVERIFIED_ACCOUNT
                    && errno != ERRNO_UNVERIFIED_SESSION) break;
            if (attempt < 9) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastErr != null ? lastErr : new FfxException("oauth token failed");
    }

    /** POST /session/destroy (HAWK, body "{}"). Best effort
     *  after the OAuth tokens exist — a failure here never
     *  fails the sign-in. */
    static void destroySession(String sessionToken) {
        try {
            String url = FXA_AUTH + "/session/destroy";
            String auth = hawkAuthFor("POST", url, sessionToken, "{}");
            Resp r = postJson(url, "{}", auth);
            if (r.status != 200) {
                // Silent: the session just stays listed as
                // "unknown" in the account's services.
            }
        } catch (Throwable ignored) {}
    }

    /** POST /oauth/token, grant refresh_token. Primary is
     *  api.accounts.firefox.com/v1/oauth/token; on HTTP 406
     *  retry once at oauth.accounts.firefox.com/v1/token
     *  (Arman requestOAuthRefresh). A 400/401/403 here means
     *  the refresh token is dead — re-auth required. */
    static TokenResponse refreshOAuth(String refreshToken) throws Exception {
        // Go's encoding/json sorts map keys: client_id,
        // grant_type, refresh_token, scope. Build the body
        // literally (a JSONObject's key order is not
        // guaranteed); the refresh token is quoted as JSON.
        String body = "{\"client_id\":\"" + FXA_CLIENT_ID
                + "\",\"grant_type\":\"refresh_token\",\"refresh_token\":"
                + JSONObject.quote(refreshToken)
                + ",\"scope\":\"" + FXA_SCOPE + "\"}";
        Resp r = postJson(FXA_AUTH + "/oauth/token", body, null);
        if (r.status == 406) {
            r = postJson(FXA_OAUTH_TOKEN, body, null);
        }
        if (r.status != 200) {
            int errno = errnoOf(r.body);
            throw new FfxException("oauth refresh HTTP " + r.status,
                    r.status == 400 || r.status == 401 || r.status == 403,
                    r.status, errno);
        }
        JSONObject j = new JSONObject(r.body);
        TokenResponse t = new TokenResponse();
        t.accessToken = j.optString("access_token", "");
        t.refreshToken = j.optString("refresh_token", "");
        if (t.refreshToken.isEmpty()) t.refreshToken = refreshToken;
        t.expiresIn = j.optInt("expires_in", 0);
        if (t.accessToken.isEmpty()) throw new FfxException("empty refresh tokens");
        return t;
    }

    // ---- Guardian ----

    /** Parse the X-Quota-* headers (Arman parseProxyUsage):
     *  X-Quota-Unlimited: true needs nothing else; otherwise
     *  limit, remaining and reset must all be present and
     *  consistent. Reset is RFC3339 or unix seconds. */
    static Quota parseQuotaHeaders(String unlimited, String limit,
            String remaining, String reset) throws Exception {
        if ("true".equalsIgnoreCase(unlimited == null ? "" : unlimited.trim())) {
            Quota q = new Quota();
            q.unlimited = true;
            return q;
        }
        if (limit == null || limit.trim().isEmpty()
                || remaining == null || remaining.trim().isEmpty()
                || reset == null || reset.trim().isEmpty()) {
            throw new FfxException("quota headers missing");
        }
        long lim, rem;
        try {
            lim = Long.parseLong(limit.trim());
            rem = Long.parseLong(remaining.trim());
        } catch (NumberFormatException e) {
            throw new FfxException("invalid quota numbers");
        }
        if (lim < 0 || rem < 0 || rem > lim) throw new FfxException("invalid quota values");
        long resetMs = parseQuotaReset(reset.trim());
        Quota q = new Quota();
        q.limit = lim;
        q.remaining = rem;
        q.resetMs = resetMs;
        return q;
    }

    static long parseQuotaReset(String value) throws Exception {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            // RFC3339 with a zone designator / fractional seconds.
            String v = value;
            int z = Math.max(v.indexOf('Z'), Math.max(v.indexOf('+'), v.lastIndexOf('-', 10)));
            if (z > 10) v = v.substring(0, z);
            int dot = v.indexOf('.');
            if (dot > 0) v = v.substring(0, dot);
            try {
                return f.parse(v).getTime();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        try {
            long sec = Long.parseLong(value);
            if (sec > 0) return sec * 1000L;
        } catch (NumberFormatException ignored) {}
        throw new FfxException("invalid X-Quota-Reset");
    }

    /** "exp" (seconds) from a JWT's payload, 0 when absent. */
    static long jwtExpSeconds(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            String b64 = parts[1].replace('-', '+').replace('_', '/');
            int pad = b64.length() % 4;
            if (pad == 1) return 0;
            if (pad > 0) {
                StringBuilder sb = new StringBuilder(b64);
                for (int i = 0; i < 4 - pad; i++) sb.append('=');
                b64 = sb.toString();
            }
            byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8))
                    .optLong("exp", 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 3.1 fix3 — the start of a JWT's validity ("nbf" when
     *  present, else "iat"), in seconds, 0 when absent.
     *  Used for the clock anchoring below. */
    static long jwtStartSeconds(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            String b64 = parts[1].replace('-', '+').replace('_', '/');
            int pad = b64.length() % 4;
            if (pad == 1) return 0;
            if (pad > 0) {
                StringBuilder sb = new StringBuilder(b64);
                for (int i = 0; i < 4 - pad; i++) sb.append('=');
                b64 = sb.toString();
            }
            byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
            JSONObject j = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            long nbf = j.optLong("nbf", 0);
            if (nbf > 0) return nbf;
            return j.optLong("iat", 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 3.1 fix3 — Arman's anchorProxyPassClaims
     *  (core/internal/guardian/pass.go, Firefox bug 2054514):
     *  the JWT's exp is in the SERVER's clock, but the
     *  renewal loop compares it against the PHONE's wall
     *  clock. When the two disagree, the stored expiry is
     *  wrong by the skew — the loop can think a fresh pass is
     *  already due (or a dead one still valid). The offset is
     *  how far the receipt time (phone clock) sits from the
     *  token's start claim (server clock), truncated to whole
     *  seconds. It is 0 when the clocks agree within 60 s or
     *  when the token carries no start claim. The anchored
     *  expiry is exp + offset — the same instant, expressed
     *  in phone time — which never extends the pass beyond
     *  its signed lifetime, it only corrects the clock. */
    static long anchorOffsetSeconds(String jwt, long receivedAtMs) {
        long start = jwtStartSeconds(jwt);
        if (start <= 0) return 0;
        long offset = receivedAtMs / 1000L - start;
        if (offset > -60 && offset < 60) return 0;
        return offset;
    }

    /** 3.1 fix3 — the ProxyPass expiry in phone time (ms
     *  since epoch), anchored as above. Falls back to
     *  receivedAt + 1 h when the token carries no exp. */
    static long anchoredExpiresAtMs(String jwt, long receivedAtMs) {
        long exp = jwtExpSeconds(jwt);
        if (exp <= 0) return receivedAtMs + 3600_000L;
        return (exp + anchorOffsetSeconds(jwt, receivedAtMs)) * 1000L;
    }

    private static Quota quotaFromResp(Resp r) {
        try {
            return parseQuotaHeaders(r.quotaUnlimited, r.quotaLimit,
                    r.quotaRemaining, r.quotaReset);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** GET /api/v1/fpn/token — mints a ProxyPass JWT and
     *  reports quota in the response headers. */
    static ProxyPass fetchProxyPass(String accessToken) throws Exception {
        Resp r = getAuthed(GUARDIAN_TOKEN, "Bearer " + accessToken);
        if (r.status != 200) {
            throw new FfxException("guardian token HTTP " + r.status,
                    r.status == 401 || r.status == 403, r.status, 0);
        }
        JSONObject j = new JSONObject(r.body);
        String token = j.optString("token", "");
        if (token.isEmpty()) throw new FfxException("empty proxy pass");
        ProxyPass p = new ProxyPass();
        p.token = token;
        // 3.1 fix3: anchor the expiry to the receipt time
        // (see anchorOffsetSeconds) — the raw exp claim is in
        // the server's clock, and a skewed phone clock made
        // the loop misjudge when the pass was due.
        p.expiresAtMs = anchoredExpiresAtMs(token, System.currentTimeMillis());
        p.quota = quotaFromResp(r);
        return p;
    }

    /** POST /api/v1/fpn/activate — activates VPN access for
     *  the account (the 403 path of fetchProxyPass). */
    static void activate(String accessToken) throws Exception {
        HttpURLConnection c = open(GUARDIAN_ACTIVATE, "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + accessToken);
        c.setFixedLengthStreamingMode(0);
        Resp r = exec(c);
        if (r.status != 200) {
            throw new FfxException("guardian activate HTTP " + r.status,
                    r.status == 401 || r.status == 403, r.status, 0);
        }
    }

    /** HEAD /api/v1/fpn/token — quota only, no new token.
     *  Falls back to GET on 403 (Arman RefreshProxyUsage). */
    static Quota fetchQuota(String accessToken) throws Exception {
        Resp r = headAuthed(GUARDIAN_TOKEN, "Bearer " + accessToken);
        if (r.status == 403) {
            r = getAuthed(GUARDIAN_TOKEN, "Bearer " + accessToken);
            if (r.status != 200) {
                throw new FfxException("guardian quota HTTP " + r.status,
                        r.status == 401 || r.status == 403, r.status, 0);
            }
            return quotaFromResp(r);
        }
        if (r.status != 200) {
            throw new FfxException("guardian quota HTTP " + r.status,
                    r.status == 401 || r.status == 403, r.status, 0);
        }
        Quota q = quotaFromResp(r);
        if (q == null) throw new FfxException("quota headers missing");
        return q;
    }

    /** The stored ProxyPass token when it exists and has
     *  not expired yet — even inside the 60 s early
     *  margin. The service's renewal loop refreshes the
     *  pass live at expiry - 2 min, so a pass with any
     *  life left is usable for a fresh connect. Lets a
     *  widget/tile tap start the tunnel without a network
     *  fetch first (3.1 fix9: that fetch delayed the
     *  foreground-service start past the tap interaction
     *  window, so the start was refused and the tap fell
     *  back to opening the app). Null when there is no
     *  stored pass or it already expired. */
    public static String cachedProxyPass(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            String pass = p.getString(KEY_PASS, "");
            long exp = p.getLong(KEY_PASS_EXP, 0);
            if (pass != null && !pass.isEmpty()
                    && exp > System.currentTimeMillis()) return pass;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** A valid (unexpired) ProxyPass JWT for CONNECT, fetching
     *  or refreshing whatever is stale: access token first
     *  (refresh grant), then the pass itself; on 403 activate
     *  once and retry; on 401 refresh once and retry. Throws
     *  FfxException with reauth=true when the stored login is
     *  dead (the UI must ask the user to sign in again). */
    public static String ensureProxyPass(Context ctx) throws Exception {
        return ensureProxyPass(ctx, false);
    }

    /** 3.1 fix: FORCED refresh — mint a NEW pass even when
     *  the stored one is still (barely) valid. The renewal
     *  loop calls this at expiry - 2 min: the plain
     *  ensureProxyPass would return the stored pass (its
     *  60 s early margin has not been reached yet), so the
     *  tunnel would keep the expiring token and die at the
     *  10-minute mark. Same 403/401 handling as above. */
    public static String forceRefreshProxyPass(Context ctx) throws Exception {
        return ensureProxyPass(ctx, true);
    }

    private static String ensureProxyPass(Context ctx, boolean force) throws Exception {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        String pass = p.getString(KEY_PASS, "");
        long passExp = p.getLong(KEY_PASS_EXP, 0);
        if (!force && pass != null && !pass.isEmpty() && passExp - now > PROXY_PASS_EARLY_MS) {
            return pass;
        }
        String access = p.getString(KEY_ACCESS, "");
        long accessExp = p.getLong(KEY_ACCESS_EXP, 0);
        if (access == null || access.isEmpty()
                || accessExp - now <= ACCESS_EARLY_MS) {
            String refresh = p.getString(KEY_REFRESH, "");
            if (refresh == null || refresh.isEmpty())
                throw new FfxException("not signed in", true, 0, 0);
            TokenResponse tok = refreshOAuth(refresh);
            saveTokens(ctx, null, tok);
            access = tok.accessToken;
        }
        try {
            ProxyPass pp = fetchProxyPass(access);
            saveProxyPass(ctx, pp);
            return pp.token;
        } catch (FfxException e) {
            if (e.httpStatus == 403) {
                // Not activated yet — activate once, then retry.
                activate(access);
                ProxyPass pp = fetchProxyPass(access);
                saveProxyPass(ctx, pp);
                return pp.token;
            }
            if (e.httpStatus == 401) {
                // Access token died mid-flight — refresh once.
                String refresh = p.getString(KEY_REFRESH, "");
                if (refresh == null || refresh.isEmpty())
                    throw new FfxException("not signed in", true, 0, 0);
                TokenResponse tok = refreshOAuth(refresh);
                saveTokens(ctx, null, tok);
                ProxyPass pp = fetchProxyPass(tok.accessToken);
                saveProxyPass(ctx, pp);
                return pp.token;
            }
            throw e;
        }
    }

    /** Refresh the cached quota line (HEAD, no new token).
     *  Best effort — a failure leaves the old line in place. */
    public static void refreshQuotaCached(Context ctx) {
        try {
            String access = prefs(ctx).getString(KEY_ACCESS, "");
            if (access == null || access.isEmpty()) return;
            Quota q = fetchQuota(access);
            saveQuota(ctx, q);
        } catch (Throwable ignored) {}
    }

    // ---- Server list ----

    /** Flag emoji for a 2-letter country code ("FR" ->
     *  regional indicators), "" for anything else (REC has
     *  no flag — it is the recommended location, not a
     *  country). The UI draws its SVG flags from the code,
     *  so this is decoration only. */
    static String flagForCode(String code) {
        if (code == null || code.length() != 2) return "";
        int a = code.charAt(0) - 'A';
        int b = code.charAt(1) - 'A';
        if (a < 0 || a > 25 || b < 0 || b > 25) return "";
        return new String(new int[]{0x1F1E6 + a, 0x1F1E6 + b}, 0, 2);
    }

    /** Parse a Remote Settings server-list body into
     *  Servers (proto "firefox"). Skips quarantined
     *  servers and records with no usable server. Empty
     *  protocols -> CONNECT to hostname:port (Arman
     *  ConnectCandidates); a missing port defaults to 443. */
    static List<Server> parseServerList(String json) throws Exception {
        List<Server> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray data = root.optJSONArray("data");
        if (data == null) throw new FfxException("no server list data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject rec = data.optJSONObject(i);
            if (rec == null) continue;
            String code = rec.optString("code", "");
            if (code.isEmpty()) continue;
            JSONArray cities = rec.optJSONArray("cities");
            if (cities == null) continue;
            for (int j = 0; j < cities.length(); j++) {
                JSONObject city = cities.optJSONObject(j);
                if (city == null) continue;
                String cityName = city.optString("name", "");
                JSONArray servers = city.optJSONArray("servers");
                if (servers == null) continue;
                for (int k = 0; k < servers.length(); k++) {
                    JSONObject sv = servers.optJSONObject(k);
                    if (sv == null) continue;
                    if (sv.optBoolean("quarantined", false)) continue;
                    String host = sv.optString("hostname", "");
                    if (host.isEmpty()) continue;
                    int port = sv.optInt("port", 0);
                    if (port <= 0) port = 443;
                    Server s = new Server();
                    s.proto = "firefox";
                    s.ip = host;
                    s.port = port;
                    s.name = cityName;
                    s.flag = flagForCode(code);
                    s.countryCode = code;
                    out.add(s);
                }
            }
        }
        if (out.isEmpty()) throw new FfxException("server list empty");
        return out;
    }

    /** Fetch the list (network), cache the raw JSON, and
     *  return the parsed servers. */
    public static List<Server> fetchServerList(Context ctx) throws Exception {
        Resp r = get(SERVER_LIST_URL);
        if (r.status != 200) throw new FfxException("server list HTTP " + r.status);
        List<Server> list = parseServerList(r.body);
        try {
            prefs(ctx).edit()
                    .putString(KEY_LIST, r.body)
                    .putLong(KEY_LIST_AT, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {}
        return list;
    }

    /** The cached list (empty when never fetched). */
    public static List<Server> cachedServerList(Context ctx) {
        try {
            String j = prefs(ctx).getString(KEY_LIST, "");
            if (j == null || j.isEmpty()) return new ArrayList<>();
            return parseServerList(j);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** The raw cached server-list JSON ("" when never
     *  fetched). 3.1 fix5: the widget reads the cache
     *  without parsing it twice (see
     *  DirectConnect.widgetServers). */
    static String cachedListJson(Context ctx) {
        try {
            String j = prefs(ctx).getString(KEY_LIST, "");
            return j == null ? "" : j;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Fresh list when possible, the cache when the fetch
     *  fails, an exception when there is nothing at all. */
    public static List<Server> getServers(Context ctx, boolean fresh) throws Exception {
        if (!fresh) {
            List<Server> c = cachedServerList(ctx);
            if (!c.isEmpty()) return c;
        }
        try {
            return fetchServerList(ctx);
        } catch (Throwable e) {
            List<Server> c = cachedServerList(ctx);
            if (!c.isEmpty()) return c;
            throw e;
        }
    }
}
