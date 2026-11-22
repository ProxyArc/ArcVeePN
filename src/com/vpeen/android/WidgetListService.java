package com.vpeen.android;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 3.1 fix4 — the large widget's scrollable server list.
 *  A RemoteViewsService + RemoteViewsFactory: the launcher
 *  binds it and asks for rows on demand. The list is the
 *  current source's servers (persisted cache first, so the
 *  list shows immediately; a fresh fetch only when the
 *  cache is empty), ranked by the persisted ping table —
 *  measured servers first, lowest ping first. A row tap
 *  connects directly to that server (fill-in intent with
 *  host + proto, handled by ArcVeePNWidget). */
public class WidgetListService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new ListFactory(getApplicationContext());
    }

    static class ListFactory implements RemoteViewsService.RemoteViewsFactory {
        private final Context ctx;
        private List<Server> servers = new ArrayList<>();
        private Map<String, Long> pings = new HashMap<>();

        ListFactory(Context ctx) {
            this.ctx = ctx.getApplicationContext();
        }

        @Override public void onCreate() {}

        @Override public void onDestroy() {
            servers.clear();
        }

        @Override public void onDataSetChanged() {
            // 3.1 fix5: CACHE ONLY, never the network. The
            // factory callbacks run on the main thread, and
            // fix4 called DirectConnect.loadServers() here —
            // a network fetch when the cache was empty — which
            // froze the app for seconds (notably the first
            // switch to Firefox, before its list was ever
            // fetched) and left the old list on screen. An
            // empty cache now means an empty list; the app
            // pushes a widget update after every successful
            // fetch, so the list fills in as soon as the
            // servers arrive. The parse is the LIGHT one
            // (no passwords are parsed — the widget only
            // shows flags/names/pings), so even a rebind is
            // cheap on the main thread.
            try {
                List<Server> list = DirectConnect.widgetServers(ctx);
                if (list == null) list = new ArrayList<>();
                if (list.size() > 200) list = new ArrayList<>(list.subList(0, 200));
                pings = AppSettings.getWidgetPings(ctx);
                servers = WidgetList.sortByPing(list, pings);
            } catch (Throwable ignored) {
                servers = new ArrayList<>();
            }
        }

        @Override public int getCount() {
            return servers.size();
        }

        @Override public RemoteViews getViewAt(int position) {
            try {
                Server s = servers.get(position);
                RemoteViews row = new RemoteViews(ctx.getPackageName(), R.layout.widget_row);
                row.setTextViewText(R.id.widget_row_text, WidgetList.rowText(s));
                row.setTextViewText(R.id.widget_row_ping, WidgetList.pingText(s, pings));
                Intent fill = new Intent();
                fill.putExtra(ArcVeePNWidget.EXTRA_HOST, s.ip);
                fill.putExtra(ArcVeePNWidget.EXTRA_PROTO, s.proto == null ? "ss" : s.proto);
                row.setOnClickFillInIntent(R.id.widget_row_root, fill);
                return row;
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override public RemoteViews getLoadingView() {
            return null;
        }

        @Override public int getViewTypeCount() {
            return 1;
        }

        @Override public long getItemId(int position) {
            try {
                Server s = servers.get(position);
                // 3.1 fix7: the id must identify THIS server, not
                // just its address — two rows can share an ip:port
                // under different protocols, and with stable ids
                // the launcher would then treat them as one row
                // and deliver the first row's tap for both.
                return (s.ip + ":" + s.port + ":"
                        + (s.proto == null ? "" : s.proto)).hashCode();
            } catch (Throwable ignored) {
                return position;
            }
        }

        @Override public boolean hasStableIds() {
            return true;
        }
    }
}
