package tv.glass.home;

import android.content.Context;
import android.content.SharedPreferences;

// The safety net: if Glass crashes three times in five minutes, it gives the Home button back to Amazon
// by itself, so a bad build or a broken app list can never leave the TV without a working home screen.
// Glass > Home Settings > Home screen > Glass (or ./glass.sh install) takes it back.
final class Crash {
    private Crash() {}

    static final long WINDOW = 5 * 60 * 1000L;
    static final int LIMIT = 3;

    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof Handler) return;
        Thread.setDefaultUncaughtExceptionHandler(new Handler(app.getApplicationContext(), previous));
    }

    static final class Handler implements Thread.UncaughtExceptionHandler {
        final Context app;
        final Thread.UncaughtExceptionHandler previous;
        Handler(Context c, Thread.UncaughtExceptionHandler p) { app = c; previous = p; }

        @Override public void uncaughtException(Thread t, Throwable e) {
            try { record(app); } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(t, e);
            else System.exit(1);
        }
    }

    // Written with commit(), not apply(): the process is about to die.
    static void record(Context c) {
        SharedPreferences p = Prefs.get(c);
        long now = System.currentTimeMillis();
        StringBuilder kept = new StringBuilder();
        for (String s : p.getString("crashes", "").split(",")) {
            try {
                long t = Long.parseLong(s);
                if (now - t < WINDOW) kept.append(t).append(',');
            } catch (NumberFormatException ignored) {}
        }
        kept.append(now);
        p.edit().putString("crashes", kept.toString()).commit();
    }

    static int recent(Context c) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (String s : Prefs.get(c).getString("crashes", "").split(",")) {
            try { if (now - Long.parseLong(s) < WINDOW) n++; } catch (NumberFormatException ignored) {}
        }
        return n;
    }

    static boolean tripped(Context c) {
        return recent(c) >= LIMIT;
    }

    static void clear(Context c) {
        Prefs.get(c).edit().remove("crashes").putBoolean("safety_tripped", false).apply();
    }
}
