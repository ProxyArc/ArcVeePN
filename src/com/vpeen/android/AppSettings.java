package com.vpeen.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * sb10: persisted user settings for the SB build, kept in a
 * dedicated SharedPreferences file ("arcveepn_sb").
 *
 * - mode: "vpn" (default — whole device through the TUN) or
 *   "proxy" (no TUN, no VPN permission, sing-box serves a local
 *   SOCKS5/HTTP proxy on 127.0.0.1:1080/8080).
 * - split_mode: "off" (default) / "bypass" (selected apps skip
 *   the VPN) / "only" (only selected apps use the VPN).
 * - split_packages: the package names the split mode applies to.
 * Split tunneling is VPN-mode only; the app itself is always
 * kept off the TUN (see SingBoxPlatform.openTun).
 */
public final class AppSettings {
    private AppSettings() {}

    public static final String PREFS = "arcveepn_sb";
    public static final String KEY_MODE = "mode";
    public static final String KEY_SPLIT_MODE = "split_mode";
    public static final String KEY_SPLIT_PKGS = "split_packages";
    // sb12: the last non-off split mode, so the split toggle can
    // restore it when it is turned back on (default Bypass).
    public static final String KEY_SPLIT_LAST_MODE = "split_last_mode";
    // sb14: the last server the user picked ("fastest" or a
    // region code). First launch, or a saved "fastest", stays
    // Fastest; afterwards the app reopens on the last pick.
    public static final String KEY_LAST_SELECTION = "last_selection";
    public static final String SEL_FASTEST = "fastest";
    // v8: the last successfully fetched server list, so the
    // country dropdown is never empty when the internet is
    // down on app open. SS is the raw subscription text
    // (ss:// lines for the primary sub, or the base64 vmess
    // blob for the backup) with the fetch time. Stored as a
    // JSON string: {"fetchedAt":<ms>,"kind":"ss"|"vmess","text":"..."}.
    public static final String KEY_CACHE_SS = "cache_ss";
    // 2.7 fix: the emergency toggle (hamburger menu). When ON,
    // the app fetches the BACKUP subscription instead of the
    // primary (SubscriptionApi.setEmergencyMode), so the list shows
    // the emergency servers and connects use them. Persists
    // across launches.
    public static final String KEY_EMERGENCY_MODE = "emergency_mode";
    // 2.7 fix2: the learned IP->country map (compact JSON
    // {ip: cc}), so flag-less backup servers keep their country
    // across restarts. Written after every successful fetch,
    // read on launch before any fetch runs.
    public static final String KEY_IP_CC = "ip_cc";
    // 3.1: the Firefox toggle (hamburger menu). When ON, the
    // app uses ONLY the Firefox (Mozilla VPN) servers — it
    // fetches nothing from the main or emergency subs. The
    // Firefox toggle takes priority over both; turning it ON
    // turns the emergency toggle OFF, and vice versa.
    // Persists across launches.
    public static final String KEY_FIREFOX_MODE = "firefox_mode";
    // 3.1 fix4: the home-screen widget's ping table + best
    // server, persisted so the widget (which runs in its own
    // process moments, without the app's in-memory sweep
    // results) can rank servers and show the best flag.
    // Pings: JSON {"<ip>": <ms>, ...} (-1 = timeout).
    // Best: JSON {"ip": "<ip>", "flag": "<emoji>"}.
    public static final String KEY_WIDGET_PINGS = "widget_pings";
    public static final String KEY_WIDGET_BEST = "widget_best";

    public static final String MODE_VPN = "vpn";
    public static final String MODE_PROXY = "proxy";
    public static final String SPLIT_OFF = "off";
    public static final String SPLIT_BYPASS = "bypass";
    public static final String SPLIT_ONLY = "only";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getMode(Context ctx) {
        try {
            String m = prefs(ctx).getString(KEY_MODE, MODE_VPN);
            return MODE_PROXY.equals(m) ? MODE_PROXY : MODE_VPN;
        } catch (Throwable ignored) {
            return MODE_VPN;
        }
    }

    public static boolean isProxyMode(Context ctx) {
        return MODE_PROXY.equals(getMode(ctx));
    }

    public static boolean isEmergencyMode(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_EMERGENCY_MODE, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setEmergencyMode(Context ctx, boolean on) {
        try {
            prefs(ctx).edit().putBoolean(KEY_EMERGENCY_MODE, on).apply();
        } catch (Throwable ignored) {}
    }

    public static boolean isFirefoxMode(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_FIREFOX_MODE, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setFirefoxMode(Context ctx, boolean on) {
        try {
            prefs(ctx).edit().putBoolean(KEY_FIREFOX_MODE, on).apply();
        } catch (Throwable ignored) {}
    }

    public static void setMode(Context ctx, String mode) {
        try {
            prefs(ctx).edit()
                    .putString(KEY_MODE, MODE_PROXY.equals(mode) ? MODE_PROXY : MODE_VPN)
                    .apply();
        } catch (Throwable ignored) {}
    }

    public static String getSplitMode(Context ctx) {
        try {
            String m = prefs(ctx).getString(KEY_SPLIT_MODE, SPLIT_OFF);
            if (SPLIT_BYPASS.equals(m)) return SPLIT_BYPASS;
            if (SPLIT_ONLY.equals(m)) return SPLIT_ONLY;
            return SPLIT_OFF;
        } catch (Throwable ignored) {
            return SPLIT_OFF;
        }
    }

    public static void setSplitMode(Context ctx, String mode) {
        try {
            String m = SPLIT_BYPASS.equals(mode) ? SPLIT_BYPASS
                    : SPLIT_ONLY.equals(mode) ? SPLIT_ONLY : SPLIT_OFF;
            SharedPreferences.Editor e = prefs(ctx).edit().putString(KEY_SPLIT_MODE, m);
            // Remember the last non-off mode (sb12): the split
            // toggle restores it when it is turned back on.
            if (SPLIT_BYPASS.equals(m) || SPLIT_ONLY.equals(m)) {
                e.putString(KEY_SPLIT_LAST_MODE, m);
            }
            e.apply();
        } catch (Throwable ignored) {}
    }

    /** Last non-off split mode (sb12) — what the split toggle
     *  restores when it is turned back on. Default Bypass. */
    public static String getSplitLastMode(Context ctx) {
        try {
            String m = prefs(ctx).getString(KEY_SPLIT_LAST_MODE, SPLIT_BYPASS);
            return SPLIT_ONLY.equals(m) ? SPLIT_ONLY : SPLIT_BYPASS;
        } catch (Throwable ignored) {
            return SPLIT_BYPASS;
        }
    }

    /** sb14: last server the user picked — "fastest" or
     *  "sub:<ip>" (3.1 fix3: or "sub:<hostname>" for a
     *  Firefox server). Unknown values fall back to Fastest. */
    public static String getLastSelection(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY_LAST_SELECTION, SEL_FASTEST);
            if (SEL_FASTEST.equals(s)) return SEL_FASTEST;
            if (s != null && s.startsWith("sub:") && isSubSelection(s)) return s;
            return SEL_FASTEST;
        } catch (Throwable ignored) {
            return SEL_FASTEST;
        }
    }

    /** Sub v2: "sub:<server>" — an IPv4 literal, or
     *  (3.1 fix3) a DNS hostname: Firefox servers are
     *  hostnames (essb127.m1.fastly-masque.net), and the old
     *  IPv4-only check silently turned every Firefox pick
     *  into "fastest" — so the home-screen widget could never
     *  reconnect to the last Firefox server, and the app
     *  never reopened on it either. */
    private static boolean isSubSelection(String s) {
        try {
            String host = s.substring("sub:".length());
            if (host.isEmpty() || host.length() > 253) return false;
            if (isIpv4(host)) return true;
            return isHostname(host);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Dotted-quad IPv4 literal. */
    static boolean isIpv4(String host) {
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') dots++;
            else if (c < '0' || c > '9') return false;
        }
        return dots == 3;
    }

    /** A DNS hostname: labels of letters, digits and
     *  hyphens, at least one dot, no leading/trailing dot
     *  or hyphen, no label longer than 63 chars. */
    static boolean isHostname(String host) {
        if (!host.contains(".")) return false;
        if (host.startsWith(".") || host.endsWith(".")
                || host.startsWith("-") || host.endsWith("-")) return false;
        String[] labels = host.split("\\.", -1);
        for (String l : labels) {
            if (l.isEmpty() || l.length() > 63) return false;
            if (l.startsWith("-") || l.endsWith("-")) return false;
            for (int i = 0; i < l.length(); i++) {
                char c = l.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                        || c >= '0' && c <= '9' || c == '-')) return false;
            }
        }
        return true;
    }

    public static void setLastSelection(Context ctx, String code) {
        try {
            String v = SEL_FASTEST;
            if (code != null) {
                if (SEL_FASTEST.equals(code)) v = SEL_FASTEST;
                else if (code.startsWith("sub:") && isSubSelection(code)) v = code;
            }
            prefs(ctx).edit().putString(KEY_LAST_SELECTION, v).apply();
        } catch (Throwable ignored) {}
    }

    public static Set<String> getSplitPackages(Context ctx) {
        try {
            Set<String> s = prefs(ctx).getStringSet(KEY_SPLIT_PKGS, null);
            return s == null ? new HashSet<>() : new HashSet<>(s);
        } catch (Throwable ignored) {
            return new HashSet<>();
        }
    }

    public static void setSplitPackages(Context ctx, Set<String> pkgs) {
        try {
            prefs(ctx).edit()
                    .putStringSet(KEY_SPLIT_PKGS, pkgs == null ? new HashSet<>() : new HashSet<>(pkgs))
                    .apply();
        } catch (Throwable ignored) {}
    }


    /** v8: persist the last successfully fetched SS
     *  subscription (raw text + fetch time + kind) so the
     *  country list survives a bad connection on the next
     *  open. The raw text is stored: on load it is
     *  re-parsed exactly like a fresh fetch. */
    public static void saveCachedSs(Context ctx, String cacheJson) {
        try {
            prefs(ctx).edit().putString(KEY_CACHE_SS, cacheJson == null ? "" : cacheJson).apply();
        } catch (Throwable ignored) {}
    }

    public static String getCachedSs(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY_CACHE_SS, "");
            return s == null ? "" : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 2.7 fix2: persist the learned IP->country map. */
    public static void saveIpCc(Context ctx, String json) {
        try {
            prefs(ctx).edit().putString(KEY_IP_CC, json == null ? "" : json).apply();
        } catch (Throwable ignored) {}
    }

    public static String getIpCc(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY_IP_CC, "");
            return s == null ? "" : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 3.1 fix4: the widget's ping table — {"<ip>": <ms>}
     *  (-1 = timeout). Written by the app's ping sweep and
     *  by the widget's own ping test; read by the widget
     *  list and by the direct-connect Fastest pick. */
    public static void saveWidgetPings(Context ctx, Map<String, Long> pings) {
        try {
            JSONObject o = new JSONObject();
            if (pings != null) {
                for (Map.Entry<String, Long> e : pings.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) o.put(e.getKey(), e.getValue());
                }
            }
            prefs(ctx).edit().putString(KEY_WIDGET_PINGS, o.toString()).apply();
        } catch (Throwable ignored) {}
    }

    public static Map<String, Long> getWidgetPings(Context ctx) {
        Map<String, Long> out = new HashMap<>();
        try {
            String s = prefs(ctx).getString(KEY_WIDGET_PINGS, "");
            if (s == null || s.isEmpty()) return out;
            JSONObject o = new JSONObject(s);
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try { out.put(k, o.optLong(k, -1)); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 3.1 fix4: the best server the widget knows —
     *  {"ip": "<ip>", "flag": "<emoji>"}. Written whenever
     *  a Fastest connect picks a server or a sweep finds a
     *  best; the small widget shows this flag when Fastest
     *  is selected and nothing is connected yet. */
    public static void saveWidgetBest(Context ctx, String ip, String flag) {
        try {
            JSONObject o = new JSONObject();
            o.put("ip", ip == null ? "" : ip);
            o.put("flag", flag == null ? "" : flag);
            prefs(ctx).edit().putString(KEY_WIDGET_BEST, o.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** The flag of the persisted best server ("" when none). */
    public static String getWidgetBestFlag(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY_WIDGET_BEST, "");
            if (s == null || s.isEmpty()) return "";
            return new JSONObject(s).optString("flag", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The ip of the persisted best server ("" when none). */
    public static String getWidgetBestIp(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY_WIDGET_BEST, "");
            if (s == null || s.isEmpty()) return "";
            return new JSONObject(s).optString("ip", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

}
