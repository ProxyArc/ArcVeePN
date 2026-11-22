package com.vpeen.android;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.List;

/** Home-screen widget.
 *
 *  3.1 fix3: the tap toggled the last server — but by
 *  OPENING MainActivity, on the (then believed) ground
 *  that a widget tap cannot start a foreground service
 *  from the background on Android 12+. That ground was
 *  wrong: the FGS background-start rules explicitly
 *  exempt "the user performs an action on a UI element
 *  related to your app ... widget ...", so a direct
 *  start is permitted.
 *
 *  3.1 fix4: the tap connects DIRECTLY from the widget
 *  (see DirectConnect) and the app does not open. Two
 *  modes, by the widget's real size (the system reports
 *  it in dp; one cell is 70dp by Android's formula, so
 *  40dp is 1x1 and 110dp is 2x2):
 *   - SMALL (1x1, the default): the FLAG of the last
 *     connected server's country (or the best server's
 *     flag when Fastest is selected) plus a status dot.
 *     Tap = connect directly to that server / the best
 *     server, or disconnect when connected.
 *   - LARGE (resized big): the same header as before,
 *     plus a "Ping test" button and a SCROLLABLE server
 *     list (RemoteViews ListView, ranked by ping). A row
 *     tap connects directly to that server.
 *
 *  The widget updates on connect/disconnect (pushed by
 *  VpnTunnelService) and whenever the last server changes.
 *  No periodic updates.
 *
 *  The list factory reads the persisted cache only
 *  (DirectConnect.widgetServers, light parse, no network),
 *  the flag resolves from the same light list, and
 *  MainActivity pushes a widget update after every
 *  successful fetch. */
public class ArcVeePNWidget extends AppWidgetProvider {

    public static final String ACTION_TOGGLE = "com.vpeen.android.WIDGET_TOGGLE";
    public static final String ACTION_CONNECT_SERVER = "com.vpeen.android.WIDGET_CONNECT_SERVER";
    public static final String ACTION_PING_TEST = "com.vpeen.android.WIDGET_PING_TEST";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PROTO = "proto";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? null : intent.getAction();
            if (ACTION_TOGGLE.equals(action)) {
                BroadcastReceiver.PendingResult pending = goAsync();
                DirectConnect.toggle(context, pending, DirectConnect.activityOpener());
                return;
            }
            if (ACTION_CONNECT_SERVER.equals(action)) {
                String host = intent.getStringExtra(EXTRA_HOST);
                String proto = intent.getStringExtra(EXTRA_PROTO);
                BroadcastReceiver.PendingResult pending = goAsync();
                DirectConnect.connectHost(context, host, proto, pending,
                        DirectConnect.activityOpener());
                return;
            }
            if (ACTION_PING_TEST.equals(action)) {
                BroadcastReceiver.PendingResult pending = goAsync();
                DirectConnect.runPingSweep(context, pending);
                return;
            }
        } catch (Throwable ignored) {}
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(
            Context context, AppWidgetManager appWidgetManager,
            int appWidgetId, Bundle newOptions) {
        updateOne(context, appWidgetId);
    }

    /** Push the current state to every widget instance.
     *  Called by VpnTunnelService on connect/disconnect. */
    public static void updateAll(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, ArcVeePNWidget.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids == null) return;
            for (int id : ids) updateOne(context, id);
        } catch (Throwable ignored) {}
    }

    /** Rebuild one widget instance in the layout that fits
     *  its current size. */
    public static void updateOne(Context context, int appWidgetId) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            Bundle opts = null;
            try { opts = mgr.getAppWidgetOptions(appWidgetId); } catch (Throwable ignored) {}
            int minW = opts != null
                    ? opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
            int minH = opts != null
                    ? opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) : 0;
            RemoteViews views = WidgetList.isLarge(minW, minH)
                    ? buildLargeViews(context, appWidgetId)
                    : buildSmallViews(context);
            mgr.updateAppWidget(appWidgetId, views);
        } catch (Throwable ignored) {}
    }

    /** The small (1x1) widget: the flag of the last / best
     *  server, plus a status dot. Tap = direct toggle. */
    static RemoteViews buildSmallViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                R.layout.widget_arcveepn_small);
        boolean connected = VpnTunnelService.running;
        views.setTextViewText(R.id.widget_flag, smallFlag(context));
        views.setTextColor(R.id.widget_dot, connected ? 0xFF4ADE80 : 0xFF9AA7BD);
        Intent i = new Intent(context, ArcVeePNWidget.class);
        i.setAction(ACTION_TOGGLE);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_small_root, pi);
        return views;
    }

    /** The large widget: header (tap = direct toggle) +
     *  ping-test button + scrollable server list. */
    static RemoteViews buildLargeViews(Context context, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_arcveepn);
        boolean connected = VpnTunnelService.running;
        views.setTextViewText(R.id.widget_status, connected ? "Connected" : "Disconnected");
        views.setTextColor(R.id.widget_status, connected ? 0xFF4ADE80 : 0xFF9AA7BD);
        views.setTextViewText(R.id.widget_server, serverDisplayName(context));
        Intent tog = new Intent(context, ArcVeePNWidget.class);
        tog.setAction(ACTION_TOGGLE);
        PendingIntent piTog = PendingIntent.getBroadcast(context, 1, tog,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, piTog);
        Intent ping = new Intent(context, ArcVeePNWidget.class);
        ping.setAction(ACTION_PING_TEST);
        PendingIntent piPing = PendingIntent.getBroadcast(context, 2, ping,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_ping_btn, piPing);
        Intent svc = new Intent(context, WidgetListService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widget_server_list, svc);
        Intent row = new Intent(context, ArcVeePNWidget.class);
        row.setAction(ACTION_CONNECT_SERVER);
        // 3.1 fix7: the row template MUST be FLAG_MUTABLE. On
        // Android 12+ the launcher merges each row's fill-in
        // intent (host + proto, set by WidgetListService) into
        // the template ONLY when the PendingIntent is mutable;
        // an immutable template drops the fill-in, so every row
        // tap arrived with no host at all and did nothing — the
        // only thing that connected was the header toggle (the
        // best / first server). The other three PendingIntents
        // here carry no fill-in and stay FLAG_IMMUTABLE.
        PendingIntent piRow = PendingIntent.getBroadcast(context, 3, row,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.widget_server_list, piRow);
        views.setEmptyView(R.id.widget_server_list, R.id.widget_empty);
        return views;
    }

    /** Ask the launcher to re-query the list factory
     *  (after a ping test re-ranked the list). */
    public static void notifyDataChanged(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, ArcVeePNWidget.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null) mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_server_list);
        } catch (Throwable ignored) {}
    }

    /** The flag the small widget shows: the current
     *  server's flag while connected; otherwise the flag
     *  of the last selected server (the best server's
     *  flag when Fastest is selected); the globe when
     *  nothing is known yet. */
    static String smallFlag(Context context) {
        try {
            if (VpnTunnelService.running && VpnTunnelService.runningServer != null) {
                return WidgetList.flagOf(VpnTunnelService.runningServer);
            }
            String sel = AppSettings.getLastSelection(context);
            if (AppSettings.SEL_FASTEST.equals(sel)) {
                String f = AppSettings.getWidgetBestFlag(context);
                if (f != null && !f.isEmpty()) return f;
                Server best = WidgetList.bestOf(
                        DirectConnect.widgetServers(context),
                        AppSettings.getWidgetPings(context));
                if (best != null) return WidgetList.flagOf(best);
                return "\uD83C\uDF10";
            }
            String host = WidgetConnect.hostOf(sel);
            if (host != null && !host.isEmpty()) {
                // The LIGHT cache parse — the full parse cost
                // 100+ ms on the main thread on every widget
                // update.
                List<Server> cached = DirectConnect.widgetServers(context);
                if (cached != null) {
                    for (Server s : cached) {
                        if (host.equals(s.ip)) return WidgetList.flagOf(s);
                    }
                }
            }
            return "\uD83C\uDF10";
        } catch (Throwable ignored) {
            return "\uD83C\uDF10";
        }
    }

    /** The server line: the running server while connected,
     *  else the last selection ("Fastest — Auto" when none).
     *  A running Firefox server shows its flag + city name;
     *  a stored selection shows the address itself. */
    static String serverDisplayName(Context context) {
        try {
            if (VpnTunnelService.running && VpnTunnelService.runningServer != null) {
                Server s = VpnTunnelService.runningServer;
                String name = (s.name != null && !s.name.isEmpty()) ? s.name : s.ip;
                String flag = (s.flag != null && !s.flag.isEmpty()) ? s.flag + " " : "";
                return flag + name;
            }
            String sel = AppSettings.getLastSelection(context);
            if (AppSettings.SEL_FASTEST.equals(sel)) return "Fastest — Auto";
            String host = WidgetConnect.hostOf(sel);
            return host.isEmpty() ? "Fastest — Auto" : host;
        } catch (Throwable ignored) {
            return "Fastest — Auto";
        }
    }
}
