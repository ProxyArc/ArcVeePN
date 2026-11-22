package com.vpeen.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 3.1 fix4 — the home-screen widget's size/layout choice
 *  and server-list ranking, as pure functions over plain
 *  values (no Android) so they are unit-testable.
 *
 *  SIZE: the widget reports its size in dp through
 *  AppWidgetManager.getAppWidgetOptions
 *  (OPTION_APPWIDGET_MIN_WIDTH / MIN_HEIGHT). Android's
 *  cell formula is cells = (dp + 30) / 70, so 40dp is one
 *  cell and 110dp is two. The widget is SMALL (1x1, flag
 *  only) when it is one cell in both directions, LARGE
 *  (scrollable server list) otherwise.
 *
 *  RANKING: servers with a measured ping sort first by
 *  ping (lowest first); servers without a measurement sort
 *  after them, in list order. A timeout (-1) counts as
 *  unmeasured. */
public final class WidgetList {

    private WidgetList() {}

    /** Cells spanned by a dp size (Android's formula). */
    public static int cellsForDp(int dp) {
        if (dp <= 0) return 1;
        return Math.max(1, (dp + 30) / 70);
    }

    /** True when the widget is big enough for the server
     *  list (2+ cells wide or tall). */
    public static boolean isLarge(int minWidthDp, int minHeightDp) {
        return cellsForDp(minWidthDp) >= 2 || cellsForDp(minHeightDp) >= 2;
    }

    /** The ping of a server from the persisted table, or
     *  Long.MAX_VALUE when unmeasured / timed out. */
    public static long pingOf(Server s, Map<String, Long> pings) {
        if (s == null || s.ip == null || pings == null) return Long.MAX_VALUE;
        Long v = pings.get(s.ip);
        if (v == null || v.longValue() < 0) return Long.MAX_VALUE;
        return v.longValue();
    }

    /** The servers ranked for the large widget's list:
     *  measured pings first (lowest first), then the
     *  unmeasured in their original order. The input list
     *  is not modified. */
    public static List<Server> sortByPing(
            List<Server> servers, final Map<String, Long> pings) {
        List<Server> out = new ArrayList<>();
        if (servers != null) out.addAll(servers);
        Collections.sort(out, new Comparator<Server>() {
            @Override public int compare(Server a, Server b) {
                long pa = pingOf(a, pings);
                long pb = pingOf(b, pings);
                if (pa != pb) return pa < pb ? -1 : 1;
                return 0;
            }
        });
        return out;
    }

    /** The best (lowest-ping) server, or null when nothing
     *  is measured yet. */
    public static Server bestOf(
            List<Server> servers, Map<String, Long> pings) {
        Server best = null;
        long bestMs = Long.MAX_VALUE;
        if (servers != null) {
            for (Server s : servers) {
                long ms = pingOf(s, pings);
                if (ms < bestMs) { bestMs = ms; best = s; }
            }
        }
        return best;
    }

    /** The flag to show for a server (its own flag, or the
     *  globe when it has none). Never null. */
    public static String flagOf(Server s) {
        if (s != null && s.flag != null && !s.flag.isEmpty()) return s.flag;
        return "\uD83C\uDF10";
    }

    /** The row text: flag + name (or ip when the name is
     *  empty). Never null. */
    public static String rowText(Server s) {
        if (s == null) return "";
        String name = (s.name != null && !s.name.isEmpty()) ? s.name : s.ip;
        if (name == null) name = "";
        return flagOf(s) + " " + name;
    }

    /** The ping text for a row: "123 ms", or "—" when
     *  unmeasured. */
    public static String pingText(Server s, Map<String, Long> pings) {
        long ms = pingOf(s, pings);
        return ms == Long.MAX_VALUE ? "\u2014" : ms + " ms";
    }
}
