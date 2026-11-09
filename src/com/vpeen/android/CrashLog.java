package com.vpeen.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Process-wide uncaught-exception handler that persists the crash
 * to crash.log in getFilesDir() so the user can see it in the app
 * (no adb needed) and we can learn the real exception.
 *
 * Installed first thing in MainActivity.onCreate and in
 * VpnTunnelService.onCreate. The handler writes the log, then
 * chains to the previous default handler so the process still
 * dies exactly as it would without us (no silent swallowing).
 * A re-entrancy guard stops it recursing if writing the log
 * itself throws.
 */
public final class CrashLog {
    private static final String TAG = "ArcVeePN";
    public static final String FILE_NAME = "crash.log";
    private static volatile boolean installed = false;
    private static volatile boolean handling = false;

    private CrashLog() {}

    public static synchronized void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Context appCtx = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (!handling) {
                    handling = true;
                    try {
                        write(appCtx, t, e);
                    } catch (Throwable ignored) {
                    } finally {
                        handling = false;
                    }
                }
                try {
                    Log.e(TAG, "uncaught in thread " + t.getName(), e);
                } catch (Throwable ignored) {}
                if (prev != null) {
                    try { prev.uncaughtException(t, e); } catch (Throwable ignored) {}
                }
            }
        });
    }

    public static void write(Context ctx, Thread t, Throwable e) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("ArcVeePN crash " + ts);
            pw.println("thread: " + (t == null ? "?" : t.getName()));
            pw.println("exception: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(pw);
            pw.flush();
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // Never let crash logging itself crash.
        }
    }

    /**
     * sb7: silently copy a recorded crash.log to the app's external
     * files dir (Files app: Android/data/com.vpeen.android/
     * files/crash.log) so the crash text stays retrievable without
     * any on-screen report — the user explicitly wants no reports
     * shown in the app. Called from MainActivity.onCreate and
     * VpnTunnelService.onCreate right after install(): on the
     * launch after a crash, the previous run's crash.log is still
     * in getFilesDir() and gets mirrored before anything can
     * overwrite it. No-op when there is no crash log. Never throws.
     */
    public static void mirrorToExternal(Context ctx) {
        try {
            if (ctx == null) return;
            File src = new File(ctx.getFilesDir(), FILE_NAME);
            if (!src.exists() || src.length() <= 0) return;
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) return;
            File dst = new File(ext, FILE_NAME);
            FileInputStream in = new FileInputStream(src);
            try {
                FileOutputStream out = new FileOutputStream(dst, false);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            // Mirroring must never break launch.
        }
    }

    /** Full crash-log text, or "" if there is no recorded crash. */
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
}
