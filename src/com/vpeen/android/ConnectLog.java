package com.vpeen.android;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Step-by-step connect diagnosis log for the experimental
 * sing-box build (1.8-sb3d).
 *
 * sb3 opens fine but Connect hangs on "connecting" forever; the
 * UI only shows the generic "Connection failed." after 20 s, which
 * says nothing about WHERE it stops. This log records every step
 * of the connect path (VpnTunnelService -> SingBoxRunner ->
 * SingBoxPlatform.openTun) so one user test reveals the exact
 * stuck step: the last line in the file is where it stopped.
 *
 * The file is opened and closed on EVERY call, so lines are
 * flushed even if the process dies or a native call hangs.
 * Logging never throws and never logs credentials: server
 * hostnames and IPs are fine, username/password/token are
 * never written here (or anywhere in the connect path).
 *
 * Written to getFilesDir()/connect.log, read back via
 * Bridge.getConnectLog(). Cleared at the start of each
 * user-initiated Connect so each attempt has a fresh log.
 *
 * sb5: the log is SILENT — MainActivity no longer shows it (or
 * the crash log) on screen at the user's request. It is also
 * mirrored to getExternalFilesDir()/connect.log so it stays
 * retrievable via the Files app if ever needed. No UI, no popup.
 */
public final class ConnectLog {
    public static final String FILE_NAME = "connect.log";
    private static final int MAX_BYTES = 64 * 1024;

    private ConnectLog() {}

    /** Append one timestamped line. Never throws. */
    public static synchronized void log(Context ctx, String step) {
        try {
            if (ctx == null || step == null) return;
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            String line = "[" + ts + "] " + step + "\n";
            appendTo(new File(ctx.getFilesDir(), FILE_NAME), line);
            // Mirror to external files dir (Files app can reach it),
            // best-effort — external storage may be unavailable.
            try {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) appendTo(new File(ext, FILE_NAME), line);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
            // Logging must never break the connect path.
        }
    }

    /** Append one line to a file, truncating at MAX_BYTES. Never throws. */
    private static void appendTo(File f, String line) {
        try {
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            // Cap the file so a reconnect loop cannot grow it forever.
            if (f.exists() && f.length() > MAX_BYTES) {
                FileOutputStream trunc = new FileOutputStream(f, false);
                try { trunc.write(bytes); }
                finally { trunc.close(); }
                return;
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Full connect-log text, or "" if there is no log yet. */
    public static String read(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            if (!f.exists()) return "";
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) != -1) n += r;
                return new String(buf, 0, n, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Delete the log (fresh log per user-initiated Connect). */
    public static void clear(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) {
                File f = new File(ext, FILE_NAME);
                if (f.exists()) f.delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
