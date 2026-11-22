package com.vpeen.android;

/** 3.1 fix3 — the home-screen widget / Quick Settings tile
 *  tap decision, as pure functions over plain values (no
 *  Android) so it is unit-testable. The Activity maps its
 *  Context state into these values and acts on the returned
 *  decision; the widget/tile themselves only open
 *  MainActivity with the auto-toggle extra.
 *
 *  Tap behaviour (the widget shows the last server; the
 *  tile toggles the same way):
 *   - already connected  -> DISCONNECT (tap again to
 *     reconnect — the widget/tile never need a second
 *     control);
 *   - Firefox mode on but logged out -> OPEN_LOGIN: the
 *     app opens the Firefox login dialog instead of
 *     failing silently (the stored login can be lost while
 *     the toggle is still on);
 *   - Firefox mode on and logged in -> the last Firefox
 *     server when the selection names one, else the fastest
 *     Firefox server;
 *   - otherwise -> the last selection ("sub:<ip|host>")
 *     or the fastest server of the current source when the
 *     selection is Fastest.
 *  The decision carries no protocol: the caller resolves
 *  it from the cached server list when known, and passes
 *  null (match by address alone) on a cold start — the
 *  connect then re-fetches the list. */
public final class WidgetConnect {

    public static final int ACTION_DISCONNECT = 0;
    public static final int ACTION_CONNECT_FASTEST = 1;
    public static final int ACTION_CONNECT_SERVER = 2;
    public static final int ACTION_OPEN_LOGIN = 3;

    public static final class Decision {
        public final int action;
        /** "sub:<ip|host>" for CONNECT_SERVER, else "". */
        public final String server;
        public Decision(int action, String server) {
            this.action = action;
            this.server = server == null ? "" : server;
        }
    }

    private WidgetConnect() {}

    /** Decide what a tap does.
     *  @param running        a tunnel is up right now
     *  @param firefoxMode    the Firefox toggle is on
     *  @param loggedIn       a Firefox login is stored
     *  @param lastSelection  AppSettings.getLastSelection:
     *                        "fastest" or "sub:<ip|host>" */
    public static Decision decide(boolean running, boolean firefoxMode,
            boolean loggedIn, String lastSelection) {
        if (running) return new Decision(ACTION_DISCONNECT, "");
        if (firefoxMode) {
            if (!loggedIn) return new Decision(ACTION_OPEN_LOGIN, "");
            if (lastSelection != null && lastSelection.startsWith("sub:")) {
                return new Decision(ACTION_CONNECT_SERVER, lastSelection);
            }
            return new Decision(ACTION_CONNECT_FASTEST, "");
        }
        if (lastSelection != null && lastSelection.startsWith("sub:")) {
            return new Decision(ACTION_CONNECT_SERVER, lastSelection);
        }
        return new Decision(ACTION_CONNECT_FASTEST, "");
    }

    /** The address part of a "sub:<ip|host>" selection
     *  ("" for anything else). */
    public static String hostOf(String selection) {
        if (selection == null || !selection.startsWith("sub:")) return "";
        return selection.substring("sub:".length());
    }
}
