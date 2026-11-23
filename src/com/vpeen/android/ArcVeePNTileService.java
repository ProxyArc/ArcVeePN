package com.vpeen.android;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile.
 *
 *  3.1 fix3: the tap opened MainActivity with the
 *  auto-toggle extra, on the (then believed) ground that
 *  a TileService cannot start a foreground service from
 *  the background on Android 12+. That ground was wrong
 *  for the same reason as the widget's: a user tap on a
 *  tile is an explicit FGS background-start exemption.
 *
 *  3.1 fix4: the tap connects DIRECTLY (see DirectConnect)
 *  to the LAST SELECTED server — or the BEST server,
 *  ping-ranked, when Fastest is selected — and disconnects
 *  when connected. The app opens only as the fallback
 *  (no VPN permission yet, Firefox login lost, ...),
 *  via startActivityAndCollapse, which collapses the QS
 *  panel as the app opens.
 *
 *  3.1 fix4 (tile could not be added): the manifest
 *  declaration was missing the TOGGLEABLE_TILE and
 *  ACTIVE_TILE meta-data (see AndroidManifest.xml). The
 *  tile was listed in the editor but greyed out — the
 *  system would not bind it as an addable two-state
 *  tile. onStartListening syncs the tile state (active /
 *  inactive) from VpnTunnelService.running, and the
 *  service asks for a refresh on every connect /
 *  disconnect (requestTileUpdate). The subtitle shows the
 *  running / last server (API 29+ only). */
public class ArcVeePNTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        syncTile();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        syncTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            final ArcVeePNTileService self = this;
            // 3.1 fix8: the two definite failures are
            // checked HERE, on the tile thread, while the
            // tap interaction is still live. The old code
            // discovered them only on DirectConnect's
            // background thread and then fell back through
            // startActivityAndCollapse — which is only
            // valid during the tile interaction, so on a
            // strict ROM (HyperOS) the fallback could fail
            // silently and the tap did absolutely nothing.
            // A disconnect tap skips both checks (neither
            // the VPN permission nor the Firefox login is
            // needed to stop a tunnel).
            if (!VpnTunnelService.running) {
                if (!AppSettings.isProxyMode(self)) {
                    try {
                        if (VpnService.prepare(self) != null) {
                            openAppNow(self);
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
                if (AppSettings.isFirefoxMode(self)
                        && !FirefoxAuth.isLoggedIn(self)) {
                    openAppNow(self);
                    return;
                }
            }
            DirectConnect.toggle(self, null, new DirectConnect.Opener() {
                @Override public void openApp(Context ctx) {
                    openAppNow(self);
                }
            });
        } catch (Throwable ignored) {}
    }

    /** Open MainActivity with the auto-toggle extra,
     *  collapsing the QS panel. Called from onClick (or
     *  from the DirectConnect fallback) — best effort,
     *  never throws. */
    static void openAppNow(ArcVeePNTileService self) {
        try {
            Intent i = new Intent(self, MainActivity.class);
            i.setAction(MainActivity.ACTION_WIDGET_TOGGLE);
            i.putExtra(MainActivity.EXTRA_AUTO_TOGGLE, true);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(
                        self, 0, i, PendingIntent.FLAG_IMMUTABLE);
                self.startActivityAndCollapse(pi);
            } else {
                self.startActivityAndCollapse(i);
            }
        } catch (Throwable ignored) {
            // Last resort: a plain start (the tile tap's
            // interaction window may still allow it).
            try {
                Intent i = new Intent(self, MainActivity.class);
                i.setAction(MainActivity.ACTION_WIDGET_TOGGLE);
                i.putExtra(MainActivity.EXTRA_AUTO_TOGGLE, true);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                self.startActivity(i);
            } catch (Throwable ignored2) {}
        }
    }

    /** The tile's connected state, as a pure function
     *  (unit tested): the live `running` flag wins — the
     *  tunnel is up in this process — and a persisted
     *  "connected" counts only while its heartbeat is
     *  fresh. A persisted "connected" with a stale
     *  heartbeat means the writing process died without
     *  stopping the tunnel (its onDestroy never ran), so
     *  the tunnel is dead and the tile must show
     *  INACTIVE, not the stale ACTIVE. */
    static boolean tileShowsConnected(boolean running,
            AppSettings.TunnelState persisted, long nowMs) {
        if (running) return true;
        return AppSettings.isTunnelStateAlive(persisted, nowMs);
    }

    private void syncTile() {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;
            tile.setLabel("ArcVeePN");
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_arc));
            // 3.1 fix8: the state comes from the durable
            // tunnel record, not from the in-memory flag
            // alone — after a process death the static is
            // false but the SystemUI tile still shows the
            // last persisted ACTIVE. A stale persisted
            // "connected" is corrected to disconnected so
            // the next read is clean.
            AppSettings.TunnelState persisted =
                    AppSettings.getTunnelState(this);
            long now = System.currentTimeMillis();
            boolean connected = tileShowsConnected(
                    VpnTunnelService.running, persisted, now);
            if (!connected && persisted.connected) {
                try { AppSettings.setTunnelState(this, false, ""); }
                catch (Throwable ignored) {}
            }
            tile.setState(connected
                    ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        } catch (Throwable ignored) {}
        // 3.1 fix5: the subtitle is best-effort only — a
        // failure here must never break the state sync above.
        try {
            Tile tile = getQsTile();
            if (tile == null) return;
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(ArcVeePNWidget.serverDisplayName(this));
                tile.updateTile();
            }
        } catch (Throwable ignored) {}
    }

    /** Ask the system to re-run onStartListening (refresh
     *  the tile state). No-op when the tile is not added.
     *  (Needs ACTIVE_TILE declared on the service — see
     *  the manifest — or the system ignores the call.) */
    public static void requestTileUpdate(Context context) {
        try {
            TileService.requestListeningState(context,
                    new ComponentName(context, ArcVeePNTileService.class));
        } catch (Throwable ignored) {}
    }
}
