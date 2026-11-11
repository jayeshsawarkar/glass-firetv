package tv.glass.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewTreeObserver;

// Glass as an app: the home screen in a window of its own. HomeScreen holds everything else.
// Adapted from FireFrame's MainActivity (https://github.com/dyascj/FireFrame), MIT License.
public class MainActivity extends Activity {
    HomeScreen screen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Crash.install(this);
        Tr.init(this);
        screen = new HomeScreen(this);
        screen.applyExtras(getIntent());
        setContentView(screen);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        screen.applyExtras(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeService.pausedUntil = 0;
        screen.onShown(false);
        // Once this has drawn, the overlay covering Amazon's launcher can step aside.
        screen.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                screen.getViewTreeObserver().removeOnPreDrawListener(this);
                screen.postDelayed(HomeService::appShown, 150);
                return true;
            }
        });
    }

    // Fire TV boots into Amazon's home screen; this opens Glass as soon as the TV has started.
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) checkDebloat(c);
            // Only open Glass when it has the Home button (not after "Home screen: Amazon" or the safety net).
            if (TvControl.glassIsHome(c))
                c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // After a Fire OS update, some apps glass.sh turned off can come back on. Android only lets a computer (ADB)
    // turn apps off, so at start-up Glass checks, and says so once on Home.
    static String bootNote;

    static void checkDebloat(final Context c) {
        if (!Prefs.on(c, "reapply", true)) return;
        java.util.List<String> back = TvControl.backOn(c);
        if (!back.isEmpty())
            bootNote = Tr.t("A Fire OS update turned {n} apps back on. To turn them off again: ./glass.sh debloat", back.size());
    }
}
