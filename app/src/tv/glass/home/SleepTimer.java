package tv.glass.home;

import android.content.Context;
import android.os.SystemClock;

// Sleep timer: puts the TV in standby after 15 minutes to 4 hours, with the minutes left shown over any app
// for the last 5 minutes (HomeService draws that). Standby goes through the TV's own ADB (the remote's Sleep key).
final class SleepTimer {
    private SleepTimer() {}

    static long at; // elapsedRealtime when the TV sleeps; 0 = off

    static long remaining() {
        return at == 0 ? 0 : Math.max(0, at - SystemClock.elapsedRealtime());
    }

    static void set(final Context c, int minutes) {
        final Context app = c.getApplicationContext();
        TvControl.main.removeCallbacks(fire);
        appContext = app;
        if (minutes <= 0) { at = 0; HomeService.sleepChanged(); return; }
        at = SystemClock.elapsedRealtime() + minutes * 60_000L;
        TvControl.main.postDelayed(fire, minutes * 60_000L);
        HomeService.sleepChanged();
    }

    static Context appContext;
    static final Runnable fire = () -> {
        at = 0;
        HomeService.sleepChanged();
        TvControl.sleepNow();
    };
}
