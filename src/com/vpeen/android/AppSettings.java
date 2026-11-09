package com.vpeen.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * sb10: persisted user settings for the SB build, kept in a
 * dedicated SharedPreferences file ("arcveepn_sb") — separate
 * from the "vpeen" file VeePnApi uses for its token/servers, so
 * a settings change can never disturb the API state.
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
    private static final String[] KNOWN_REGIONS = {
            "fr-prs", "gb-lnd", "nl", "ru-spb", "sg", "us-or", "us-va"};

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

    /** sb14: last server the user picked — "fastest" or a
     *  region code. Unknown values fall back to Fastest. */
    public static String getLastSelection(Context ctx) {
        try {
            String s = prefs(ctx).getString(KEY_LAST_SELECTION, SEL_FASTEST);
            if (SEL_FASTEST.equals(s)) return SEL_FASTEST;
            for (String r : KNOWN_REGIONS) if (r.equals(s)) return s;
            return SEL_FASTEST;
        } catch (Throwable ignored) {
            return SEL_FASTEST;
        }
    }

    public static void setLastSelection(Context ctx, String code) {
        try {
            String v = SEL_FASTEST;
            if (code != null) {
                if (SEL_FASTEST.equals(code)) v = SEL_FASTEST;
                else for (String r : KNOWN_REGIONS) {
                    if (r.equals(code)) { v = r; break; }
                }
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
}
