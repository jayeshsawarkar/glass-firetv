// Adapted from FireFrame's HomeService (https://github.com/dyascj/FireFrame), MIT License, Copyright (c) 2026 CJ Dyas.
package tv.glass.home;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

// Fire TV won't let another app be the home screen, and Amazon's launcher can't be turned off.
// So this service keeps Amazon's home screen out of sight:
//  1. As soon as Amazon's launcher opens (Home, or leaving an app) it shows Glass as an overlay
//     window. Overlays aren't held back the way app launches are.
//  2. It opens the Glass app too, which Fire OS holds back for 5 seconds after Home. When the app
//     has drawn, the overlay steps aside. Same screen, so the swap can't be seen.
//  3. An app picked on the overlay in those 5 seconds would be held back too. So Glass presses
//     that app's tile on Amazon's launcher, hidden behind the overlay: Amazon's launches aren't held.
public class HomeService extends AccessibilityService {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    static final int SHOWN = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN;
    static final int HIDDEN = SHOWN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

    // Windows that pop up over whatever is in front without replacing it.
    static final java.util.Set<String> IGNORED = new java.util.HashSet<>(java.util.Arrays.asList(
            "android", "com.android.systemui", "com.amazon.tv.ime"));

    // "Amazon home screen" and "Fire TV settings" in Glass's settings menu pause all this until
    // Home is pressed or another app opens. Settings can pass through Amazon's launcher, so it doesn't count.
    // Leaving settings for Amazon's home screen ends the pause too.
    static long pausedUntil, ignoreHomeUntil;
    static boolean forSettings, settingsShown;
    static HomeService running;

    TextView sleepPill;
    boolean sleepAdded;

    WindowManager wm;
    WindowManager.LayoutParams lp;
    HomeScreen overlay;
    boolean added, showing, hideWhenMenusClose;
    final Runnable hideNow = this::hide;

    // Amazon's launcher sends this, unguarded, from its own goHome. It can reach us a little before the
    // launcher's window has drawn, so we cover sooner than the accessibility event alone would let us.
    final BroadcastReceiver homePressed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // The launcher sends it when Glass opens Amazon's home screen, too: that isn't Home.
            if (SystemClock.elapsedRealtime() < ignoreHomeUntil) return;
            pausedUntil = 0;
            cover();
        }
    };

    static void pause(boolean settings) {
        long now = SystemClock.elapsedRealtime();
        pausedUntil = now + 10 * 60 * 1000;
        ignoreHomeUntil = now + 2000;
        forSettings = settings;
        settingsShown = false;
        hideOverlay();
    }

    static boolean isSettingsPackage(String p) {
        return p.startsWith("com.amazon.tv.settings") || p.equals("com.android.settings");
    }

    static boolean paused() {
        return SystemClock.elapsedRealtime() < pausedUntil;
    }

    // The Glass app is in front and drawn: the overlay can go, unless a menu is open on it.
    static void appShown() {
        HomeService s = running;
        if (s == null || !s.showing) return;
        if (s.overlay.layers.isEmpty()) s.hide();
        else s.hideWhenMenusClose = true;
    }

    static void menusClosed() {
        HomeService s = running;
        if (s != null && s.hideWhenMenusClose) s.hide();
    }

    static void hideOverlay() {
        if (running != null) running.hide();
    }

    @Override
    protected void onServiceConnected() {
        Crash.install(this);
        Tr.init(this);
        // The safety net: three crashes in five minutes and Home goes back to Amazon, by itself.
        if (Crash.tripped(this)) {
            Prefs.get(this).edit().putBoolean("safety_tripped", true).remove("crashes").commit();
            if (android.os.Build.VERSION.SDK_INT >= 24) { disableSelf(); return; }
        }
        Prefs.set(this, "safety_tripped", false);
        running = this;
        // The overlay window stays attached and hidden, so showing it is instant, even the first time.
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new HomeScreen(this);
        overlay.setVisibility(View.GONE);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, HIDDEN, PixelFormat.OPAQUE);
        // Hidden, the window is 1x1: a full-screen window keeps a full-screen drawing surface (about 25 MB)
        // even while its content is gone. It's made full size again the moment Amazon's home screen opens.
        lp.width = 1;
        lp.height = 1;
        attach();
        registerReceiver(homePressed, new IntentFilter("com.amazon.tv.launcher.HOME_PRESSED"));
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (running != this) return super.onUnbind(intent); // turned itself off at once (safety net)
        try { unregisterReceiver(homePressed); } catch (RuntimeException ignored) {}
        if (sleepAdded) try { wm.removeViewImmediate(sleepPill); } catch (RuntimeException ignored) {}
        sleepAdded = false;
        if (added) wm.removeViewImmediate(overlay);
        added = false;
        running = null;
        return super.onUnbind(intent);
    }

    // Can fail if the service is being turned off and on at that moment; show() tries again.
    boolean attach() {
        if (added) return true;
        try {
            wm.addView(overlay, lp);
            added = true;
        } catch (RuntimeException e) {
            added = false;
        }
        return added;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(AMAZON_HOME)) {
            // Fire TV's settings screens live in Amazon's launcher: never cover them.
            String cls = String.valueOf(e.getClassName());
            if (cls.contains("Settings")) {
                if (forSettings) settingsShown = true;
                return;
            }
            if (settingsShown && cls.contains("HomeActivity")) pausedUntil = 0; // Back out of settings
            cover();
        } else if (!IGNORED.contains(p) && !p.equals(getPackageName())) {
            if (!isSettingsPackage(p)) pausedUntil = 0; // another app opened
            if (showing) {
                // Let the app have the remote now, but keep covering until it has drawn its first frame.
                setFocusable(false);
                overlay.postDelayed(hideNow, 600);
            }
        }
    }

    // Amazon's home screen opened. Cover it and bring up the Glass app.
    void cover() {
        if (paused()) return;
        show();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    void show() {
        if (overlay == null || !attach()) return;
        overlay.removeCallbacks(hideNow);
        boolean appearing = !showing; // Home again: back to the dock, menus closed
        if (appearing) {
            showing = true;
            hideWhenMenusClose = false;
            size(true);
            overlay.setVisibility(View.VISIBLE);
        }
        setFocusable(true);
        overlay.onShown(appearing);
    }

    void hide() {
        if (overlay == null || !showing || !added) return;
        overlay.removeCallbacks(hideNow);
        showing = false;
        hideWhenMenusClose = false;
        overlay.setVisibility(View.GONE);
        setFocusable(false);
        size(false);
    }

    void size(boolean full) {
        int want = full ? WindowManager.LayoutParams.MATCH_PARENT : 1;
        if (lp.width == want && lp.height == want) return;
        lp.width = want;
        lp.height = want;
        try { wm.updateViewLayout(overlay, lp); } catch (RuntimeException ignored) {}
    }

    void setFocusable(boolean focusable) {
        int flags = focusable ? SHOWN : HIDDEN;
        if (lp.flags == flags) return;
        lp.flags = flags;
        wm.updateViewLayout(overlay, lp);
    }

    // Opens an app by pressing its tile on Amazon's launcher, behind the overlay. Amazon's launcher can
    // start apps right away; Glass can't for 5 seconds after Home. Amazon's window can take a
    // moment to show up, so this tries for about a second before falling back to a normal launch.
    void launchThroughAmazon(final String label, final Runnable otherwise, final int attempt) {
        if (pressAmazonTile(label)) return;
        if (showing && attempt < 6) overlay.postDelayed(() -> launchThroughAmazon(label, otherwise, attempt + 1), 150);
        else otherwise.run();
    }

    boolean pressAmazonTile(String label) {
        if (!showing || label == null) return false;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !AMAZON_HOME.contentEquals(root.getPackageName())) continue;
            AccessibilityNodeInfo tile = find(root, label.toLowerCase());
            if (tile != null && tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    // A clickable node named like the app ("Hulu" matches "Hulu for Fire TV").
    static AccessibilityNodeInfo find(AccessibilityNodeInfo n, String label) {
        CharSequence d = n.getContentDescription();
        if (n.isClickable() && d != null && d.toString().toLowerCase().startsWith(label)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo hit = find(c, label);
            if (hit != null) return hit;
        }
        return null;
    }

    // ---- Sleep timer: the minutes left, over any app, for the last five minutes ----

    static void sleepChanged() {
        if (running != null) running.updateSleep();
    }

    final Runnable sleepTick = this::updateSleep;

    void updateSleep() {
        if (wm == null) return;
        TvControl.main.removeCallbacks(sleepTick);
        long left = SleepTimer.remaining();
        boolean show = left > 0 && left <= 5 * 60_000L;
        if (left > 0) TvControl.main.postDelayed(sleepTick, Math.min(30_000, Math.max(1000, left - 5 * 60_000L)));
        if (!show) {
            if (sleepAdded) { try { wm.removeViewImmediate(sleepPill); } catch (RuntimeException ignored) {} sleepAdded = false; }
            return;
        }
        float k = overlay == null ? 1 : overlay.s;
        if (sleepPill == null) {
            sleepPill = new TextView(this);
            sleepPill.setTypeface(Typeface.createFromAsset(getAssets(), "fonts/Inter-SemiBold.ttf"));
            sleepPill.setTextColor(0xFFFFFFFF);
            sleepPill.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * k);
            sleepPill.setPadding(Math.round(30 * k), Math.round(14 * k), Math.round(30 * k), Math.round(14 * k));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xCC1C1C1E);
            g.setCornerRadius(999);
            sleepPill.setBackground(g);
        }
        sleepPill.setText(Tr.t("Sleep in {n} min", (left + 59_999) / 60_000));
        if (!sleepAdded) {
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = Math.round(40 * k);
            try { wm.addView(sleepPill, p); sleepAdded = true; } catch (RuntimeException ignored) {}
        }
    }

    @Override
    public void onInterrupt() {}
}
