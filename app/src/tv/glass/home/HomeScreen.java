package tv.glass.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The home screen, in the style of Free My TV (github.com/simbasjungles/free-my-tv, MIT): a big "For You" title with
// Explore / Movies / Shows tabs, round frosted buttons top right, a row of picks where the focused one opens wide,
// and your apps on a frosted glass shelf at the bottom. The background is the focused pick's picture, blurred and
// washed light by day or dark by night.
//
// Navigation is by hand (an index per zone) rather than Android's focus search, so it's instant and the same in the
// app window and in the overlay window that HomeService shows over Amazon's launcher.
public class HomeScreen extends FrameLayout {
    // Laid out on a 1920x1080 canvas and scaled to the real screen.
    static final int MARGIN = 80;
    static final int PICKS_Y = 262, PICK_H = 442, NEWS_H = 360, WIDE_W = 1040, NARROW_W = 320, PICK_GAP = 40;
    static final int SHELF_X = 40, SHELF_Y = 815, SHELF_W = 1840, SHELF_H = 205;
    // TILE_PAD keeps the end tiles, even lifted, clear of the shelf's rounded ends.
    static final int TILE_W = 210, TILE_H = 126, TILE_STEP = 252, TILE_PAD = 64, SHELF_VISIBLE = 7;
    static final float TILE_FOCUS = 1.12f;
    static final int Z_SHELF = 0, Z_PICKS = 1, Z_TABS = 2, Z_TOP = 3;
    static final String APPSTORE = "com.amazon.venezia";

    // Apps that go first until you arrange your own.
    static final String[] PREFERRED = {
        "com.netflix.ninja", "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
        "com.amazon.firebat", "com.disney.disneyplus", "com.apple.atve.amazon.appletv", "com.hulu.plus", "com.wbd.stream",
        "com.hbo.hbonow", "com.spotify.tv.android", "com.plexapp.android",
        "com.cbs.ott", "com.peacocktv.peacockandroid", "com.tubitv", "com.crunchyroll.crunchyroid",
    };

    // Other home screens and Fire OS internals that list themselves as apps.
    static final Set<String> NEVER = new HashSet<>(Arrays.asList(
        HomeService.AMAZON_HOME, "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher", "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver",
        "com.amazon.ssm", "com.amazon.vizzini.ftvcds", "com.android.providers.downloads.ui"));

    static final String[] WALLPAPERS = {"aurora", "citylights", "earthrise", "southernlights", "sunrise"};
    static final String CUSTOM = "custom";
    static final String SETTINGS_TILE = "#settings";
    static final String[] TAB_KEYS = {"explore", "movie", "tv"};

    final Context ctx;
    final SharedPreferences prefs;
    final PackageManager pm;
    final float s;
    final int screenW, screenH;
    final Typeface regular, bold;
    final DecelerateInterpolator ease = new DecelerateInterpolator(2.2f);
    final OvershootInterpolator spring = new OvershootInterpolator(1.4f);

    // HomeService checks this: while a panel is open, the overlay waits before stepping aside.
    final List<ViewGroup> layers = new ArrayList<>();

    ImageView bgA, bgB, emptyArt;
    View wash;
    TextView header, info, toast, pickSub, summary, emptyTitle, emptySub;
    final List<TextView> tabViews = new ArrayList<>();
    final List<String> tabKeys = new ArrayList<>();
    LinearLayout tabsRow;
    android.widget.HorizontalScrollView tabsScroll;
    final List<Circle> circles = new ArrayList<>(); // the top row's focus order: the portfolio (when on), then the buttons
    Pulse pulse;
    Tickers tickers;
    final Runnable pfTick = new Runnable() {
        @Override public void run() {
            removeCallbacks(this);
            updatePulse();
            // Right after a fresh start the screen isn't "shown" yet; look again shortly instead of giving up.
            if (!isShown()) { if (isAttachedToWindow()) postDelayed(this, 1500); return; }
            Portfolio.refresh(ctx, false, HomeScreen.this::updatePulse);
            postDelayed(this, Portfolio.interval());
        }
    };
    StatusIcons status;
    FrameLayout picksLayer, strip, emptyCard;
    Glass shelf;

    Bitmap photo, blur, wallpaperBlur; // blur: the picture behind everything; the shelf's frost uses it too
    String shownWallpaper;
    static int photoVersion;
    int seenPhotoVersion = -1, seenSettings = -1;

    final List<String> apps = new ArrayList<>();
    final Map<String, String> names = new HashMap<>();
    final List<Tile> tiles = new ArrayList<>();
    final List<PickCard> cards = new ArrayList<>();
    int zone, appFocus, pickFocus, tab, topFocus, shelfFirst;
    boolean editing, appsChanged = true, light, longFired;
    boolean onPill; // true while the top buttons have focus
    int fg = Color.WHITE, dim = 0x8CFFFFFF;
    int glassTint = 0x4D2A2C32, glassEdge = 0x40FFFFFF;
    ValueAnimator picksAnim;

    final Runnable longPress = new Runnable() {
        @Override public void run() { longFired = true; if (zone == Z_SHELF && layers.isEmpty()) startEditing(); }
    };
    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (light != Sun.light(ctx)) applyTheme(); // sunrise or sunset just passed
            updateInfo();
            removeCallbacks(this);
            postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };
    final Runnable nowPlayingTick = new Runnable() {
        @Override public void run() {
            updateInfo();
            removeCallbacks(this);
            if (isShown()) postDelayed(this, 10_000);
            else if (isAttachedToWindow()) postDelayed(this, 1500);
        }
    };
    final BroadcastReceiver timeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { tick.run(); }
    };
    final BroadcastReceiver statusChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateStatus(); }
    };
    final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsChanged = true;
            if (hasWindowFocus() && layers.isEmpty() && !editing) build();
        }
    };

    HomeScreen(Context c) {
        super(c);
        ctx = c;
        prefs = c.getSharedPreferences("glass", Context.MODE_PRIVATE);
        pm = c.getPackageManager();
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        s = screenW / 1920f;
        regular = Typeface.createFromAsset(c.getAssets(), "fonts/Inter-Medium.ttf");
        bold = Typeface.createFromAsset(c.getAssets(), "fonts/Inter-SemiBold.ttf");
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClipChildren(false);

        // Background: the blurred picture, cross-faded, under a light or dark wash.
        bgA = image();
        bgB = image();
        bgB.setAlpha(0f);
        addView(bgA, full());
        addView(bgB, full());
        wash = new View(c);
        addView(wash, full());

        header = text(bold, 76, Color.WHITE);
        header.setLetterSpacing(-0.02f);
        addView(header, at(MARGIN - 4, 62, 1200, 100));

        // The tabs scroll sideways when there are more topics than fit beside the portfolio.
        tabsScroll = new android.widget.HorizontalScrollView(c);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.setFocusable(false);
        tabsRow = new LinearLayout(c);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsScroll.addView(tabsRow, new LayoutParams(LayoutParams.WRAP_CONTENT, px(60)));
        // The home screen lets children draw outside their bounds (for lifted cards and shadows), so a scroll view
        // alone wouldn't cut the tabs off at its edge. This box does.
        FrameLayout tabsBox = new FrameLayout(c);
        tabsBox.addView(tabsScroll, new LayoutParams(px(760), px(60)));
        addView(tabsBox, at(MARGIN - 22, 163, 760, 60));

        // The portfolio, between the tabs and the buttons.
        pulse = new Pulse(c);
        pulse.setVisibility(GONE);
        addView(pulse, at(990, 44, 570, 128));
        // Each holding on its own, in the middle of the top bar.
        tickers = new Tickers(c);
        tickers.setVisibility(GONE);
        // Lined up with the total beside it: the symbols' tops with the total's top (y 74), the last line with "All time" (y 180).
        addView(tickers, at(350, 56, 640, 104));

        // Top right: round frosted buttons (Appstore, Glass settings, the ☰ panel); under them a dim line with
        // the status icons, what's playing, the weather and the time.
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        circles.add(new Circle(c, Circle.BAG, this::openAppstore));
        circles.add(new Circle(c, Circle.GEAR, () -> openGlassSettings(null)));
        circles.add(new Circle(c, Circle.MENU, this::openPanel));
        for (Circle ci : circles) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(68), px(68));
            lp.leftMargin = px(20);
            top.addView(ci, lp);
        }
        LayoutParams tl = new LayoutParams(LayoutParams.WRAP_CONTENT, px(80), Gravity.TOP | Gravity.END);
        tl.topMargin = px(64);
        tl.rightMargin = px(MARGIN);
        addView(top, tl);

        LinearLayout infoRow = new LinearLayout(c);
        infoRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        status = new StatusIcons(c);
        infoRow.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(40)));
        info = text(regular, 26, 0x8CFFFFFF);
        info.setSingleLine(true);
        info.setEllipsize(TextUtils.TruncateAt.START);
        info.setMaxWidth(px(980));
        infoRow.addView(info);
        LayoutParams il = new LayoutParams(LayoutParams.WRAP_CONTENT, px(40), Gravity.TOP | Gravity.END);
        il.topMargin = px(173);
        il.rightMargin = px(MARGIN);
        addView(infoRow, il);

        // No picks yet: the wallpaper as one wide card, with a hint underneath.
        emptyCard = new FrameLayout(c);
        emptyCard.setClipToOutline(true);
        emptyCard.setOutlineProvider(rounded(18));
        emptyArt = image();
        emptyCard.addView(emptyArt, full());
        addView(emptyCard, at(MARGIN, PICKS_Y, 1760, PICK_H));
        emptyTitle = text(bold, 32, Color.WHITE);
        addView(emptyTitle, at(MARGIN, PICKS_Y + PICK_H + 22, 1700, 44));
        emptySub = text(regular, 26, 0x8CFFFFFF);
        emptySub.setSingleLine(true);
        emptySub.setEllipsize(TextUtils.TruncateAt.END);
        addView(emptySub, at(MARGIN, PICKS_Y + PICK_H + 70, 1700, 40));

        // Picks: cards and their titles are placed by hand (layoutPicks); the focused one is wide.
        picksLayer = new FrameLayout(c);
        picksLayer.setClipChildren(false);
        addView(picksLayer, full());
        pickSub = text(regular, 26, 0x8CFFFFFF);
        pickSub.setSingleLine(true);
        pickSub.setEllipsize(TextUtils.TruncateAt.END);
        addView(pickSub, at(MARGIN, PICKS_Y + PICK_H + 70, 1100, 40));
        // News: the focused story's summary, two lines, under its picture.
        summary = text(regular, 28, 0xCCFFFFFF);
        summary.setMaxLines(2);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setLineSpacing(px(4), 1f);
        summary.setVisibility(GONE);
        addView(summary, at(MARGIN, PICKS_Y + NEWS_H + 22, 1040, 80));

        // The glass shelf with your apps.
        shelf = new Glass(c, SHELF_H / 2f);
        addView(shelf, at(SHELF_X, SHELF_Y, SHELF_W, SHELF_H));
        strip = new FrameLayout(c);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        shelf.addView(strip, new LayoutParams(px(SHELF_W), px(SHELF_H)));

        toast = text(regular, 28, Color.WHITE);
        toast.setGravity(Gravity.CENTER);
        toast.setPadding(px(36), px(16), px(36), px(16));
        toast.setBackground(round(0xCC1C1C1E, 999));
        toast.setAlpha(0f);
        toast.setElevation(px(120)); // over the shelf and the ☰ panel
        relabel();
        LayoutParams tol = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tol.topMargin = px(40);
        addView(toast, tol);
    }

    // ---- Lifecycle ----

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        ctx.registerReceiver(timeChanged, t);
        IntentFilter p = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        p.addAction(Intent.ACTION_PACKAGE_REMOVED);
        p.addAction(Intent.ACTION_PACKAGE_CHANGED);
        p.addDataScheme("package");
        ctx.registerReceiver(packagesChanged, p);
        IntentFilter st = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        st.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        st.addAction(Intent.ACTION_MEDIA_MOUNTED);
        st.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        ctx.registerReceiver(statusChanged, st);
        tick.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        ctx.unregisterReceiver(timeChanged);
        ctx.unregisterReceiver(packagesChanged);
        ctx.unregisterReceiver(statusChanged);
        removeCallbacks(tick);
        removeCallbacks(nowPlayingTick);
        removeCallbacks(pfTick);
        super.onDetachedFromWindow();
    }

    // Called each time the screen comes up. reset: Home was pressed, so close panels and go back to the shelf.
    void onShown(boolean reset) {
        if (reset) {
            while (!layers.isEmpty()) closeLayer();
            if (editing) stopEditing();
        }
        Tr.reload();
        if (seenSettings != SettingsActivity.version) {
            seenSettings = SettingsActivity.version;
            appsChanged = true;
            relabel();
            applyTheme();
        }
        String w = wallpaperKey();
        if (!w.equals(shownWallpaper) || seenPhotoVersion != photoVersion) showWallpaper(w);
        if (appsChanged && layers.isEmpty() && !editing) build();
        if (!sameCards() || cards.isEmpty()) buildPicks();
        tick.run();
        nowPlayingTick.run();
        pfTick.run();
        updateStatus();
        Weather.refresh(ctx, this::updateInfo);
        refreshRow(false);
        requestFocus();
        if (reset) {
            leaveCurrent();
            focusApp(Math.min(prefs.getInt("last", 0), Math.max(0, tiles.size() - 1)), 0, true);
        }
        paintFocus();
        if (MainActivity.bootNote != null) { showToast(MainActivity.bootNote); MainActivity.bootNote = null; }
    }

    boolean quiet() { return layers.isEmpty() && !editing; }

    // adb can hand over a photo: am start -n tv.glass.home/.MainActivity --es from /data/local/tmp/x.jpg
    void applyExtras(Intent i) {
        // ./glass.sh portfolio: a holdings file handed over the same way as a wallpaper.
        String hold = i == null ? null : i.getStringExtra("holdings");
        if (hold != null) {
            try (InputStream in = new FileInputStream(hold)) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0 && b.size() < 16384; ) b.write(buf, 0, n);
                String bad = Portfolio.setHoldings(ctx, b.toString("UTF-8"));
                showToast(bad == null ? Tr.t("Holdings updated") : bad);
                SettingsActivity.version++;
                updatePulse();
            } catch (Exception e) {
                showToast(Tr.t("Couldn't read the holdings file"));
            }
        }
        String from = i == null ? null : i.getStringExtra("from");
        if (from == null) return;
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(customWallpaper())) {
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            prefs.edit().putString("wallpaper", CUSTOM).apply();
            photoVersion++;
            showWallpaper(CUSTOM);
            showToast(Tr.t("Wallpaper set"));
        } catch (Exception e) {
            showToast(Tr.t("Couldn't read that photo"));
        }
    }

    // News (the default) or the movie and TV picks: Glass settings > News > On Home.
    boolean news() { return !Prefs.str(ctx, "home_row", "news").equals("picks"); }

    // The header and the tabs: your news topics, or Explore / Movies / Shows.
    void relabel() {
        boolean n = news();
        header.setText(n ? Tr.t("News") : Tr.t("For You"));
        List<String> keys = new ArrayList<>(), labels = new ArrayList<>();
        if (n) for (News.Topic t : News.chosen(ctx)) { keys.add(t.id); labels.add(Tr.t(t.name)); }
        else { keys.addAll(Arrays.asList(TAB_KEYS)); labels.addAll(Arrays.asList(Tr.t("Explore"), Tr.t("Movies"), Tr.t("Shows"))); }
        String was = tab < tabKeys.size() ? tabKeys.get(tab) : null;
        tabKeys.clear();
        tabKeys.addAll(keys);
        tabsRow.removeAllViews();
        tabViews.clear();
        for (int i = 0; i < labels.size(); i++) {
            final int k = i;
            TextView t = text(regular, 32, fg);
            t.setText(labels.get(i));
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(22), 0, px(22), 0);
            t.setOnClickListener(v -> { focusTabs(); setTab(k); });
            t.setOnHoverListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER && layers.isEmpty()) { focusTabs(); setTab(k); }
                return false;
            });
            tabViews.add(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(60));
            lp.rightMargin = px(14);
            tabsRow.addView(t, lp);
        }
        int keep = was == null ? -1 : tabKeys.indexOf(was);
        tab = keep >= 0 ? keep : 0;
        for (PickCard c : cards) { picksLayer.removeView(c); picksLayer.removeView(c.title); }
        cards.clear();
    }

    String tabKey() { return tab < tabKeys.size() ? tabKeys.get(tab) : TAB_KEYS[0]; }

    int pickH() { return news() ? NEWS_H : PICK_H; }

    // Fetches what the row shows, if it's due (news every 30 minutes, picks every hour).
    void refreshRow(boolean force) {
        final String key = tabKey();
        if (news()) News.refresh(ctx, key, force, () -> { if (quiet() && key.equals(tabKey())) buildPicks(); });
        else if (tab == 0) Picks.refresh(ctx, force, () -> { if (quiet() && tab == 0) buildPicks(); });
        else Picks.refreshTab(ctx, key, () -> { if (quiet() && key.equals(tabKey())) buildPicks(); });
    }

    // ---- Theme: light by day, dark by night (or as picked in Glass settings) ----

    void applyTheme() {
        light = Sun.light(ctx);
        fg = light ? 0xFF1C1C1E : Color.WHITE;
        dim = light ? 0x8C1C1C1E : 0x8CFFFFFF;
        glassTint = light ? 0xB3FBFBFD : 0x4D2A2C32;
        glassEdge = light ? 0xE6FFFFFF : 0x40FFFFFF;
        wash.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, light
                ? new int[] {0xD9F2F3F5, 0xC7EEF0F3, 0xD9F2F3F5}
                : new int[] {0xD90A0B0F, 0xBF0A0B0F, 0xD90A0B0F}));
        header.setTextColor(fg);
        info.setTextColor(dim);
        pickSub.setTextColor(dim);
        summary.setTextColor(light ? 0xCC1C1C1E : 0xCCFFFFFF);
        emptyTitle.setTextColor(fg);
        emptySub.setTextColor(dim);
        status.color = dim;
        status.invalidate();
        shelf.tint.setColor(glassTint);
        shelf.edge.setColor(glassEdge);
        shelf.invalidate();
        for (Circle c : circles) c.invalidate();
        pulse.invalidate();
        tickers.invalidate();
        for (PickCard c : cards) c.title.setTextColor(fg);
        paintFocus();
    }

    // Tabs and top buttons show where the focus is.
    void paintFocus() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean focused = zone == Z_TABS && i == tab, selected = i == tab;
            t.setBackground(focused ? round(light ? 0xFF1C1C1E : Color.WHITE, 999) : null);
            t.setTextColor(focused ? (light ? Color.WHITE : Color.BLACK) : selected ? fg : dim);
            t.setTypeface(selected ? bold : regular);
        }
        for (int i = 0; i < circles.size(); i++) circles.get(i).setOn(zone == Z_TOP && i == topFocus);
        onPill = zone == Z_TOP;
        if (zone == Z_TABS && tab < tabViews.size()) {
            final View t = tabViews.get(tab);
            // Scroll only when the tab isn't fully in view, and then so it sits exactly at an edge: no slivers.
            tabsScroll.post(() -> {
                int x = tabsScroll.getScrollX(), w = tabsScroll.getWidth();
                if (t.getLeft() < x) tabsScroll.smoothScrollTo(t.getLeft(), 0);
                else if (t.getRight() > x + w) tabsScroll.smoothScrollTo(t.getRight() - w, 0);
            });
        }
    }

    // ---- The info line: status icons, what's playing, weather, time ----

    void updateInfo() {
        StringBuilder b = new StringBuilder();
        NowPlaying.Track t = NowPlaying.current(ctx);
        if (t != null) b.append(NowPlaying.line(t)).append("     ");
        if (Prefs.on(ctx, "weather", true) && Weather.has())
            b.append(Weather.symbol(Weather.code, Sun.isDay(ctx))).append(" ").append(Weather.deg(Weather.temp)).append("     ");
        String world = WorldClock.line(ctx);
        if (!world.isEmpty()) b.append(world).append("     ");
        // The TV's own time last, in full colour; everything before it stays quiet.
        int quietEnd = b.length();
        b.append(android.text.format.DateFormat.format(android.text.format.DateFormat.is24HourFormat(ctx) ? "H:mm" : "h:mm", new java.util.Date()));
        android.text.SpannableString sp = new android.text.SpannableString(b);
        sp.setSpan(new android.text.style.ForegroundColorSpan(fg), quietEnd, b.length(), 0);
        info.setText(sp);
    }

    void updateStatus() {
        boolean on = Prefs.on(ctx, "status_icons", true);
        status.setVisibility(on ? VISIBLE : GONE);
        if (!on) return;
        boolean wifi = false, ethernet = false, online = false, bt = false, usb = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            if (n != null && n.isConnected()) {
                online = true;
                wifi = n.getType() == ConnectivityManager.TYPE_WIFI;
                ethernet = n.getType() == ConnectivityManager.TYPE_ETHERNET;
            }
        } catch (Exception ignored) {}
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            bt = a != null && a.isEnabled() && a.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception ignored) {}
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) if (v.isRemovable() && "mounted".equals(v.getState())) usb = true;
        } catch (Exception ignored) {}
        status.set(online, wifi, ethernet, bt, usb);
    }

    // ---- Apps on the shelf ----

    void build() {
        appsChanged = false;
        names.clear();
        Map<String, Drawable> banners = new HashMap<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(ctx.getPackageName()) || NEVER.contains(p) || names.containsKey(p)) continue;
                names.put(p, r.activityInfo.applicationInfo.loadLabel(pm).toString());
                Drawable b = null;
                try { b = r.activityInfo.loadBanner(pm); } catch (Exception ignored) {}
                if (b == null) try { b = pm.getApplicationBanner(p); } catch (Exception ignored) {}
                if (b != null) banners.put(p, b);
            }
        }
        Set<String> hidden = new HashSet<>(Arrays.asList(prefs.getString("hidden", "").split(",")));
        apps.clear();
        String saved = prefs.getString("order", null);
        if (saved != null) {
            for (String p : saved.split(",")) {
                if ((names.containsKey(p) || p.equals(SETTINGS_TILE)) && !apps.contains(p)) apps.add(p);
            }
        }
        if (saved == null) for (String p : PREFERRED) if (names.containsKey(p) && !apps.contains(p)) apps.add(p);
        List<String> rest = new ArrayList<>();
        for (String p : names.keySet()) if (!apps.contains(p)) rest.add(p);
        // Apps you installed before the ones Amazon put there, each group A to Z.
        Collections.sort(rest, (a, b) -> {
            int sa = isSystem(a) ? 1 : 0, sb = isSystem(b) ? 1 : 0;
            return sa != sb ? sa - sb : names.get(a).compareToIgnoreCase(names.get(b));
        });
        apps.addAll(rest);
        if (!apps.contains(SETTINGS_TILE)) apps.add(SETTINGS_TILE);
        apps.removeAll(hidden);

        for (Tile t : tiles) strip.removeView(t);
        tiles.clear();
        for (String p : apps) {
            Tile t = new Tile(p, banners.get(p));
            tiles.add(t);
            strip.addView(t, new LayoutParams(px(TILE_W), px(TILE_H)));
        }
        LayoutParams sl = (LayoutParams) strip.getLayoutParams();
        sl.width = px(TILE_PAD * 2 + Math.max(1, tiles.size()) * TILE_STEP);
        strip.setLayoutParams(sl);
        layoutTiles(false);
        appFocus = Math.max(0, Math.min(appFocus, tiles.size() - 1));
        if (zone == Z_SHELF) focusApp(appFocus, 0, true);
    }

    boolean isSystem(String p) {
        try {
            return (pm.getApplicationInfo(p, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    String nameOf(String p) {
        return p.equals(SETTINGS_TILE) ? Tr.t("Settings") : names.get(p);
    }

    void layoutTiles(boolean animate) {
        float y = px((SHELF_H - TILE_H) / 2f);
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            float x = px(TILE_PAD + i * TILE_STEP);
            if (animate) t.animate().x(x).y(y).setDuration(220).setInterpolator(ease).start();
            else { t.setX(x); t.setY(y); }
        }
    }

    // Keeps the focused tile inside the shelf, sliding the strip a tile at a time.
    void scrollShelf(boolean instant) {
        if (appFocus < shelfFirst) shelfFirst = appFocus;
        if (appFocus > shelfFirst + SHELF_VISIBLE - 1) shelfFirst = appFocus - SHELF_VISIBLE + 1;
        shelfFirst = Math.max(0, Math.min(shelfFirst, Math.max(0, tiles.size() - SHELF_VISIBLE)));
        strip.animate().translationX(-px(shelfFirst * TILE_STEP)).setDuration(instant ? 0 : 280).setInterpolator(ease).start();
        // Only the seven tiles in view show; the next ones would peek in at the shelf's rounded ends.
        for (int i = 0; i < tiles.size(); i++) {
            boolean in = i >= shelfFirst && i < shelfFirst + SHELF_VISIBLE;
            Tile t = tiles.get(i);
            if (instant) t.setAlpha(in ? 1f : 0f);
            else t.animate().alpha(in ? 1f : 0f).setDuration(200).start();
        }
    }

    void focusApp(int i, int dir, boolean instant) {
        if (tiles.isEmpty()) return;
        i = Math.max(0, Math.min(i, tiles.size() - 1));
        if (appFocus < tiles.size() && (appFocus != i || zone != Z_SHELF)) tiles.get(appFocus).setFocused(false);
        if (zone == Z_PICKS) for (PickCard c : cards) c.setFocused(false);
        zone = Z_SHELF;
        appFocus = i;
        prefs.edit().putInt("last", i).apply();
        tiles.get(i).setFocused(true);
        scrollShelf(instant);
        paintFocus();
    }

    // ---- Picks ----

    List<Picks.Pick> currentPicks() {
        if (news()) return News.get(tabKey());
        if (tab == 0) return Picks.list;
        List<Picks.Pick> l = Picks.tabs.get(tabKey());
        return l == null ? Collections.<Picks.Pick>emptyList() : l;
    }

    boolean sameCards() {
        List<Picks.Pick> l = currentPicks();
        if (l.size() != cards.size()) return false;
        for (int i = 0; i < l.size(); i++) if (cards.get(i).pick != l.get(i)) return false;
        return true;
    }

    void buildPicks() {
        for (PickCard c : cards) { picksLayer.removeView(c); picksLayer.removeView(c.title); }
        cards.clear();
        List<Picks.Pick> l = currentPicks();
        for (int i = 0; i < l.size(); i++) {
            PickCard c = new PickCard(l.get(i), i);
            cards.add(c);
            picksLayer.addView(c, new LayoutParams(px(NARROW_W), px(pickH())));
            picksLayer.addView(c.title, new LayoutParams(px(NARROW_W), px(news() ? 76 : 44)));
        }
        boolean any = !cards.isEmpty(), n = news();
        // Text under the row: picks have one line; news has the summary and then source · time.
        moveTo(pickSub, MARGIN, PICKS_Y + pickH() + (n ? 112 : 70));
        moveTo(emptyTitle, MARGIN, PICKS_Y + pickH() + 22);
        moveTo(emptySub, MARGIN, PICKS_Y + pickH() + 70);
        LayoutParams el = (LayoutParams) emptyCard.getLayoutParams();
        el.height = px(pickH());
        emptyCard.setLayoutParams(el);
        emptyCard.setVisibility(any ? GONE : VISIBLE);
        emptyTitle.setVisibility(any ? GONE : VISIBLE);
        emptySub.setVisibility(any ? GONE : VISIBLE);
        pickSub.setVisibility(any ? VISIBLE : GONE);
        summary.setVisibility(any && n ? VISIBLE : GONE);
        if (!any) {
            String key = tabKey();
            emptyTitle.setText(n || tab > 0 ? tabViews.get(tab).getText() : "Glass");
            if (n) {
                boolean loading = News.loading.contains(key) || !News.known(key);
                emptySub.setText(loading ? Tr.t("Getting the news…") : Tr.t("Couldn't get the news. Glass tries again in 5 minutes."));
            } else {
                boolean noKey = Prefs.secret(ctx, "tmdb_key").isEmpty();
                boolean loading = tab == 0 ? Picks.loading : Picks.tabLoading.contains(key);
                emptySub.setText(!Prefs.on(ctx, "picks", true) ? Tr.t("Picks are off: Glass settings → Picks.")
                        : noKey ? Tr.t("Picks need a free TMDB key: Keys and services.")
                        : loading || (tab > 0 && !Picks.tabs.containsKey(key)) ? Tr.t("Finding new picks…")
                        : Tr.t("No trending titles on your apps right now. Glass tries again in 5 minutes."));
            }
            if (zone == Z_PICKS) focusApp(appFocus, 0, true);
            setBackdrop(wallpaperBlur);
            return;
        }
        pickFocus = Math.min(pickFocus, cards.size() - 1);
        layoutPicks(false);
        if (zone == Z_PICKS) cards.get(pickFocus).setFocused(true);
        showPick(pickFocus);
    }

    // The focused pick is wide; the others are posters. The row slides so the wide one is always fully on screen.
    void layoutPicks(boolean animate) {
        final int n = cards.size();
        if (n == 0) return;
        final float[] fromX = new float[n], fromW = new float[n], toX = new float[n], toW = new float[n];
        float x = MARGIN;
        for (int i = 0; i < n; i++) {
            toW[i] = i == pickFocus ? WIDE_W : NARROW_W;
            toX[i] = x;
            x += toW[i] + PICK_GAP;
        }
        float right = toX[pickFocus] + toW[pickFocus], limit = 1920 - MARGIN;
        float shift = right > limit ? right - limit : 0;
        // The summary and the source line sit under the wide card, wherever it is in the row.
        int textX = Math.round(toX[pickFocus] - shift);
        moveTo(summary, textX, PICKS_Y + pickH() + 22);
        moveTo(pickSub, textX, PICKS_Y + pickH() + (news() ? 112 : 70));
        for (int i = 0; i < n; i++) {
            toX[i] -= shift;
            fromX[i] = cards.get(i).x;
            fromW[i] = cards.get(i).w;
            cards.get(i).showWide(i == pickFocus);
        }
        if (picksAnim != null) picksAnim.cancel();
        if (!animate) {
            for (int i = 0; i < n; i++) cards.get(i).place(toX[i], toW[i]);
            return;
        }
        picksAnim = ValueAnimator.ofFloat(0f, 1f);
        picksAnim.setDuration(300);
        picksAnim.setInterpolator(ease);
        picksAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            for (int i = 0; i < n; i++) cards.get(i).place(fromX[i] + (toX[i] - fromX[i]) * f, fromW[i] + (toW[i] - fromW[i]) * f);
        });
        picksAnim.start();
    }

    void moveTo(View v, int x, int y) {
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        v.setLayoutParams(lp);
    }

    void showPick(int i) {
        if (i < 0 || i >= cards.size()) return;
        Picks.Pick p = cards.get(i).pick;
        pickSub.setText(p.subtitle);
        summary.setText(p.summary == null ? "" : p.summary);
        if (p.blurBitmap != null) setBackdrop(p.blurBitmap);
    }

    void focusPick(int i, int dir) {
        if (cards.isEmpty()) return;
        leaveCurrent();
        int old = pickFocus;
        zone = Z_PICKS;
        pickFocus = Math.max(0, Math.min(i, cards.size() - 1));
        for (int k = 0; k < cards.size(); k++) cards.get(k).setFocused(k == pickFocus);
        if (old != pickFocus) layoutPicks(true);
        showPick(pickFocus);
        paintFocus();
    }

    void openPick(final Picks.Pick p) {
        Picks.open(ctx, p, (intent, pkg) -> {
            if (intent != null && start(intent)) return;
            if (pkg == null) { showToast(Tr.t("Couldn't open {n}", p.title)); return; }
            if (ctx instanceof HomeService) ((HomeService) ctx).launchThroughAmazon(names.get(pkg), () -> startApp(pkg), 0);
            else startApp(pkg);
        });
    }

    // ---- Tabs and the top buttons ----

    void setTab(final int k) {
        if (k == tab || k < 0 || k >= tabViews.size()) return;
        tab = k;
        pickFocus = 0;
        if (news()) News.keepRecent(tabKey());
        buildPicks();
        refreshRow(false);
        paintFocus();
    }

    void focusTabs() {
        leaveCurrent();
        zone = Z_TABS;
        paintFocus();
    }

    void focusTop(int i) {
        leaveCurrent();
        zone = Z_TOP;
        topFocus = Math.max(0, Math.min(i, circles.size() - 1));
        paintFocus();
    }

    void leaveCurrent() {
        if (zone == Z_SHELF && appFocus < tiles.size()) tiles.get(appFocus).setFocused(false);
        if (zone == Z_PICKS) for (PickCard c : cards) c.setFocused(false);
    }

    // ---- Keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (!layers.isEmpty()) {
            if ((k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) && e.getAction() == KeyEvent.ACTION_UP) { closeLayer(); return true; }
            return super.dispatchKeyEvent(e);
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Tap opens; hold half a second on an app to rearrange.
                if (down && e.getRepeatCount() == 0) {
                    longFired = false;
                    postDelayed(longPress, 550);
                } else if (down && (e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 && !longFired) {
                    removeCallbacks(longPress);
                    longPress.run();
                } else if (!down) {
                    removeCallbacks(longPress);
                    if (!longFired) select();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    if (editing) stopEditing();
                    else if (zone != Z_SHELF) focusApp(appFocus, 4, false);
                    else if (appFocus != 0) focusApp(0, 1, false);
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!down && e.getRepeatCount() == 0) {
                    if (editing) hideFocused();
                    else openPanel();
                }
                return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        int dir = k == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 2
                : k == KeyEvent.KEYCODE_DPAD_UP ? 3 : k == KeyEvent.KEYCODE_DPAD_DOWN ? 4 : 0;
        if (dir == 0) return super.dispatchKeyEvent(e);
        if (editing) {
            int to = dir == 1 ? appFocus - 1 : dir == 2 ? appFocus + 1 : -1;
            if (to < 0 || to >= tiles.size()) { if (!tiles.isEmpty()) tiles.get(appFocus).nudge(dir); return true; }
            String p = apps.remove(appFocus);
            apps.add(to, p);
            Tile t = tiles.remove(appFocus);
            tiles.add(to, t);
            appFocus = to;
            layoutTiles(true);
            scrollShelf(false);
            return true;
        }
        switch (zone) {
            case Z_SHELF:
                if (dir == 1 && appFocus > 0) focusApp(appFocus - 1, dir, false);
                else if (dir == 2 && appFocus < tiles.size() - 1) focusApp(appFocus + 1, dir, false);
                else if (dir == 3) { if (!cards.isEmpty()) focusPick(pickFocus, 3); else focusTabs(); }
                else if (!tiles.isEmpty()) tiles.get(appFocus).nudge(dir);
                break;
            case Z_PICKS:
                if (dir == 1 && pickFocus > 0) focusPick(pickFocus - 1, dir);
                else if (dir == 2 && pickFocus < cards.size() - 1) focusPick(pickFocus + 1, dir);
                else if (dir == 3) focusTabs();
                else if (dir == 4) focusApp(appFocus, 4, false);
                break;
            case Z_TABS:
                if (dir == 1 && tab > 0) setTab(tab - 1);
                else if (dir == 2 && tab < tabViews.size() - 1) setTab(tab + 1);
                else if (dir == 2 || dir == 3) focusTop(dir == 2 ? 0 : circles.size() - 1);
                else if (dir == 4) { if (!cards.isEmpty()) focusPick(pickFocus, 4); else focusApp(appFocus, 4, false); }
                paintFocus();
                break;
            case Z_TOP:
                if (dir == 1) { if (topFocus > 0) focusTop(topFocus - 1); else focusTabs(); }
                else if (dir == 2 && topFocus < circles.size() - 1) focusTop(topFocus + 1);
                else if (dir == 4) { if (!cards.isEmpty()) focusPick(pickFocus, 4); else focusApp(appFocus, 4, false); }
                break;
        }
        return true;
    }

    // The mouse wheel moves along the shelf.
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_SCROLL && quiet() && !tiles.isEmpty()) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v < 0) focusApp(appFocus + 1, 2, false);
            else if (v > 0) focusApp(appFocus - 1, 1, false);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    void select() {
        if (editing) { stopEditing(); return; }
        if (zone == Z_TOP) { circles.get(topFocus).action().run(); return; }
        if (zone == Z_TABS) { if (!cards.isEmpty()) focusPick(pickFocus, 4); return; }
        if (zone == Z_PICKS) {
            if (cards.isEmpty()) return;
            PickCard c = cards.get(pickFocus);
            c.press();
            openPick(c.pick);
            return;
        }
        if (tiles.isEmpty()) return;
        String p = apps.get(appFocus);
        tiles.get(appFocus).press();
        if (p.equals(SETTINGS_TILE)) { openFireSettings(); return; }
        if (ctx instanceof HomeService) ((HomeService) ctx).launchThroughAmazon(names.get(p), () -> startApp(p), 0);
        else startApp(p);
    }

    void startApp(String p) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(p);
        if (i == null) i = pm.getLaunchIntentForPackage(p);
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            showToast(Tr.t("Couldn't open {n}", nameOf(p)));
        }
    }

    void openAppstore() {
        if (!isInstalled(APPSTORE)) return;
        // Straight to the Appstore: pressing its tile on Amazon's launcher can hit "Your Apps & Channels" instead.
        startApp(APPSTORE);
    }

    // ---- Rearranging ----

    void startEditing() {
        editing = true;
        for (Tile t : tiles) t.jiggle(true);
        showToast(Tr.t("Move with the arrows · OK when done · ☰ hides the app"));
    }

    void stopEditing() {
        editing = false;
        for (Tile t : tiles) t.jiggle(false);
        prefs.edit().putString("order", TextUtils.join(",", apps)).apply();
        focusApp(appFocus, 0, false);
    }

    void hideFocused() {
        String p = apps.get(appFocus);
        if (p.equals(SETTINGS_TILE)) { showToast(Tr.t("Settings stays on Home")); return; }
        Set<String> hidden = new HashSet<>(Arrays.asList(prefs.getString("hidden", "").split(",")));
        hidden.add(p);
        hidden.remove("");
        prefs.edit().putString("hidden", TextUtils.join(",", hidden)).putString("order", TextUtils.join(",", apps)).apply();
        showToast(Tr.t("{n} hidden. Bring it back from the ☰ panel.", nameOf(p)));
        editing = false;
        for (Tile t : tiles) t.jiggle(false);
        appsChanged = true;
        build();
    }

    // ---- The ☰ panel (frosted, slides in from the right) ----

    // Shows or hides the portfolio and keeps it first in the top row's focus order.
    void updatePulse() {
        boolean on = Portfolio.on(ctx);
        pulse.setVisibility(on ? VISIBLE : GONE);
        tickers.setVisibility(on && Portfolio.hasKey(ctx) && !Portfolio.quotes.isEmpty() ? VISIBLE : GONE);
        tickers.invalidate();
        boolean listed = circles.contains(pulse);
        if (on && !listed) { circles.add(0, pulse); if (zone == Z_TOP) topFocus++; }
        if (!on && listed) { circles.remove(pulse); if (zone == Z_TOP) topFocus = Math.max(0, topFocus - 1); }
        pulse.invalidate();
    }

    // The portfolio, in full: a frosted panel with the day, the record and each holding.
    void openPortfolio() {
        leaveCurrent();
        FrameLayout layer = new FrameLayout(ctx);
        View dimView = new View(ctx);
        dimView.setBackgroundColor(light ? 0x33000000 : 0x80000000);
        layer.addView(dimView, full());
        Glass panel = new Glass(ctx, 44);
        LayoutParams lp = new LayoutParams(px(860), screenH - px(80), Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = px(40);
        layer.addView(panel, lp);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(56), px(48), px(56), px(40));
        panel.addView(col, full());

        boolean hide = Portfolio.hideAmounts(ctx);
        Portfolio.Totals t = Portfolio.totals(ctx);
        int up = upColor(), down = downColor();
        TextView label = text(regular, 26, dim);
        label.setText(Tr.t("Portfolio"));
        col.addView(label);
        TextView big = text(bold, 76, fg);
        big.setLetterSpacing(-0.02f);
        big.setText(hide ? Portfolio.pct(t.dayPct) : Portfolio.money(t.value));
        if (hide) big.setTextColor(t.dayPl >= 0 ? up : down);
        col.addView(big);
        TextView day = text(bold, 30, t.dayPl >= 0 ? up : down);
        day.setPadding(0, px(6), 0, 0);
        day.setText((t.dayPl >= 0 ? "▲ " : "▼ ") + (hide ? Portfolio.pct(t.dayPct) : Portfolio.signedMoney(t.dayPl) + "  " + Portfolio.pct(t.dayPct)) + "  " + Tr.t("today"));
        col.addView(day);
        TextView all = text(regular, 24, dim);
        all.setPadding(0, px(8), 0, 0);
        all.setText(Tr.t("All time") + " " + (hide ? Portfolio.pct(t.totalPct) : Portfolio.signedMoney(t.totalPl) + " (" + Portfolio.pct(t.totalPct) + ")")
                + (t.since.isEmpty() ? "" : "   ·   " + Tr.t("Since {n}", Portfolio.shortDate(t.since)) + " " + (hide ? "" : Portfolio.signedMoney(t.sinceStart))));
        col.addView(all);

        Chart chart = new Chart(ctx, true);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(250));
        cl.topMargin = px(28);
        cl.bottomMargin = px(18);
        col.addView(chart, cl);

        // Each holding: symbol, price, today, value, gain or loss against what it cost.
        col.addView(holdingRow(Tr.t("Holding"), Tr.t("Price"), Tr.t("Today"), hide ? "" : Tr.t("Value"), Tr.t("Total"), dim, dim, dim, true));
        for (Portfolio.Holding h : Portfolio.holdings(ctx)) {
            Portfolio.Quote q = Portfolio.quotes.get(h.symbol);
            if (q == null) { col.addView(holdingRow(h.symbol, "…", "", "", "", fg, dim, dim, false)); continue; }
            double dPct = q.prevClose > 0 ? (q.price - q.prevClose) / q.prevClose * 100 : 0;
            double value = h.shares * q.price, gain = value - h.cost, gPct = h.cost > 0 ? gain / h.cost * 100 : 0;
            col.addView(holdingRow(h.symbol, Portfolio.price(q.price), Portfolio.pct(dPct), hide ? "" : Portfolio.money(value),
                    h.cost > 0 ? (hide ? Portfolio.pct(gPct) : Portfolio.signedMoney(gain) + "  " + Portfolio.pct(gPct)) : "",
                    fg, dPct >= 0 ? up : down, gain >= 0 ? up : down, false));
        }
        double cash = Portfolio.cash(ctx);
        if (cash > 0 && !hide) col.addView(holdingRow(Tr.t("Cash"), "", "", Portfolio.money(cash), "", dim, dim, dim, false));
        TextView foot = text(regular, 22, dim);
        foot.setPadding(0, px(18), 0, 0);
        String upd = Portfolio.updated(ctx);
        foot.setText(Tr.t("Prices from Finnhub") + (upd.isEmpty() ? "" : " · " + Tr.t("updated {n}", upd)) + (Portfolio.marketOpen() ? " · " + Tr.t("market open") : " · " + Tr.t("market closed")));
        col.addView(foot);

        layer.setElevation(px(100));
        layers.add(layer);
        addView(layer, full());
        panel.setTranslationX(px(900));
        panel.animate().translationX(0).setDuration(320).setInterpolator(ease).start();
        dimView.setAlpha(0f);
        dimView.animate().alpha(1f).setDuration(250).start();
        layer.setFocusable(true);
        layer.requestFocus();
    }

    View holdingRow(String sym, String price, String today, String value, String total, int symColor, int dayColor, int totColor, boolean head) {
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(head ? 4 : 12), 0, px(head ? 8 : 12));
        float size = head ? 20 : 26;
        String[] cells = {sym, price, today, value, total};
        float[] weights = {0.9f, 1.1f, 1f, 1.4f, 2.2f};
        int[] colors = {symColor, head ? dim : fg, dayColor, head ? dim : fg, totColor};
        for (int i = 0; i < cells.length; i++) {
            TextView t = text(i == 0 && !head ? bold : regular, size, colors[i]);
            t.setText(cells[i]);
            t.setSingleLine(true);
            t.setGravity(i == 0 ? Gravity.START : Gravity.END);
            if (i > 0) t.setPadding(px(16), 0, 0, 0); // room between the columns, so numbers never touch
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i]));
        }
        if (!head) {
            GradientDrawable line = new GradientDrawable();
            line.setColor(0);
            r.setBackground(null);
        }
        return r;
    }

    int upColor() { return light ? 0xFF1E8E3E : 0xFF34C759; }
    int downColor() { return light ? 0xFFD93025 : 0xFFFF453A; }

    void openPanel() {
        leaveCurrent();
        FrameLayout layer = new FrameLayout(ctx);
        View dimView = new View(ctx);
        dimView.setBackgroundColor(light ? 0x33000000 : 0x66000000);
        layer.addView(dimView, full());

        Glass panel = new Glass(ctx, 44);
        LayoutParams lp = new LayoutParams(px(600), screenH - px(80), Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = px(40);
        layer.addView(panel, lp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(52), px(56), px(52), px(44));
        panel.addView(col, full());

        TextView time = text(bold, 88, fg);
        time.setText(android.text.format.DateFormat.format(android.text.format.DateFormat.is24HourFormat(ctx) ? "H:mm" : "h:mm", new java.util.Date()));
        time.setLetterSpacing(-0.03f);
        col.addView(time);
        TextView date = text(regular, 28, dim);
        date.setText(android.text.format.DateFormat.format("EEEE, MMMM d", new java.util.Date()));
        col.addView(date);
        if (Prefs.on(ctx, "weather", true) && Weather.has()) {
            col.addView(forecast(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        View gap = new View(ctx);
        col.addView(gap, new LinearLayout.LayoutParams(1, px(30)));

        android.widget.ScrollView sc = new android.widget.ScrollView(ctx);
        sc.setVerticalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.VERTICAL);
        sc.addView(buttons);
        col.addView(sc, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        addButton(buttons, Tr.t("Glass settings"), () -> openGlassSettings(null));
        addButton(buttons, Tr.t("Fire TV settings"), this::openFireSettings);
        final TextView[] sleep = new TextView[1];
        sleep[0] = addButton(buttons, sleepLabel(), () -> {
            long left = SleepTimer.remaining();
            int[] mins = {0, 15, 30, 60, 90, 120};
            int next = 1;
            for (int k = 1; k < mins.length; k++) if (left > 0 && mins[k] * 60_000L >= left - 30_000) { next = (k + 1) % mins.length; break; }
            SleepTimer.set(ctx, mins[next]);
            sleep[0].setText(sleepLabel());
        });
        addButton(buttons, Tr.t("Recent apps"), () -> openGlassSettings("recents"));
        addButton(buttons, Tr.t("Headphones and speakers"), () -> openGlassSettings("audio"));
        addButton(buttons, Tr.t("Rearrange apps"), () -> { closeLayer(); focusApp(appFocus, 0, true); startEditing(); });
        if (!prefs.getString("hidden", "").isEmpty()) addButton(buttons, Tr.t("Show hidden apps"), () -> {
            prefs.edit().remove("hidden").apply();
            closeLayer();
            appsChanged = true;
            build();
            showToast(Tr.t("All apps are back on Home"));
        });
        if (news()) addButton(buttons, Tr.t("Refresh the news"), () -> {
            closeLayer();
            showToast(Tr.t("Getting the news…"));
            refreshRow(true);
        });
        else if (!Prefs.secret(ctx, "tmdb_key").isEmpty()) addButton(buttons, Tr.t("New picks"), () -> {
            closeLayer();
            showToast(Tr.t("Finding new picks…"));
            refreshRow(true);
        });
        addButton(buttons, Tr.t("Next wallpaper"), this::nextWallpaper);
        if (isInstalled(HomeService.AMAZON_HOME)) addButton(buttons, Tr.t("Amazon home screen"), this::openAmazonHome);

        // Above the shelf: it has a shadow (elevation), and Android draws raised views over flat ones.
        layer.setElevation(px(100));
        layers.add(layer);
        addView(layer, full());
        panel.setTranslationX(px(640));
        panel.animate().translationX(0).setDuration(300).setInterpolator(ease).start();
        dimView.setAlpha(0f);
        dimView.animate().alpha(1f).setDuration(250).start();
        buttons.getChildAt(0).requestFocus();
    }

    String sleepLabel() {
        long left = SleepTimer.remaining();
        return left <= 0 ? Tr.t("Sleep timer: Off") : Tr.t("Sleep timer: {n} min", (left + 59_999) / 60_000);
    }

    void openGlassSettings(String page) {
        while (!layers.isEmpty()) closeLayer();
        Intent i = new Intent(ctx, SettingsActivity.class);
        if (page != null) i.putExtra("page", page);
        start(i);
    }

    // The next hours and days, under the date in the panel.
    View forecast() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(26), 0, 0);
        TextView now = text(bold, 34, fg);
        now.setText(Weather.symbol(Weather.code, Sun.isDay(ctx)) + "  " + Weather.deg(Weather.temp) + "  " + Weather.words(Weather.code) + " · " + Prefs.city(ctx));
        now.setSingleLine(true);
        now.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(now);
        LinearLayout hours = new LinearLayout(ctx);
        hours.setPadding(0, px(18), 0, 0);
        for (Weather.Hour h : Weather.hours) {
            TextView t = text(regular, 22, dim);
            t.setGravity(Gravity.CENTER);
            t.setText(Weather.hourLabel(ctx, h.time) + "\n" + Weather.symbol(h.code, true) + "\n" + Weather.deg(h.temp));
            t.setLineSpacing(px(6), 1f);
            hours.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(hours);
        StringBuilder days = new StringBuilder();
        for (int di = 0; di < Math.min(3, Weather.days.size()); di++) { // 3 days fit the panel on one line
            Weather.Day d = Weather.days.get(di);
            if (days.length() > 0) days.append("   ");
            days.append(Weather.dayLabel(d.time)).append(" ").append(Weather.deg(d.hi)).append("/").append(Weather.deg(d.lo));
        }
        TextView dl = text(regular, 22, dim);
        dl.setSingleLine(true);
        dl.setEllipsize(TextUtils.TruncateAt.END);
        dl.setPadding(0, px(14), 0, 0);
        dl.setText(days);
        box.addView(dl);
        if (Weather.sunrise > 0) {
            String f = android.text.format.DateFormat.is24HourFormat(ctx) ? "H:mm" : "h:mm a";
            TextView sun = text(regular, 22, dim);
            sun.setPadding(0, px(10), 0, 0);
            sun.setText(Tr.t("Sunrise") + " " + android.text.format.DateFormat.format(f, Weather.sunrise) + "   " + Tr.t("Sunset") + " "
                    + android.text.format.DateFormat.format(f, Weather.sunset));
            box.addView(sun);
        }
        return box;
    }

    TextView addButton(LinearLayout col, String title, final Runnable action) {
        final TextView b = text(bold, 30, fg);
        b.setText(title);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(36), 0, px(36), 0);
        b.setFocusable(true);
        final int idle = light ? 0x14000000 : 0x26FFFFFF;
        final int on = light ? 0xFF1C1C1E : Color.WHITE, onText = light ? Color.WHITE : Color.BLACK;
        b.setBackground(round(idle, 22));
        b.setOnHoverListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) b.requestFocus(); return false; });
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(round(has ? on : idle, 22));
            b.setTextColor(has ? onText : fg);
            b.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.04f : 1f).setDuration(160).setInterpolator(spring).start();
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(88));
        lp.bottomMargin = px(16);
        col.addView(b, lp);
        return b;
    }

    void closeLayer() {
        if (layers.isEmpty()) return;
        final ViewGroup layer = layers.remove(layers.size() - 1);
        layer.animate().alpha(0f).setDuration(180).withEndAction(() -> removeView(layer)).start();
        requestFocus();
        if (layers.isEmpty()) {
            if (appsChanged) build();
            if (zone == Z_PICKS && !cards.isEmpty()) focusPick(pickFocus, 0);
            else if (zone == Z_SHELF || zone == Z_PICKS) focusApp(appFocus, 0, true);
            paintFocus();
            if (ctx instanceof HomeService) HomeService.menusClosed();
        }
    }

    void openFireSettings() {
        while (!layers.isEmpty()) closeLayer();
        HomeService.pause(true);
        // Settings.ACTION_SETTINGS goes through Amazon's home screen on Fire OS 7 and 8.
        if (!start(new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
                .setPackage(HomeService.AMAZON_HOME))) start(new Intent(Settings.ACTION_SETTINGS));
    }

    void openAmazonHome() {
        while (!layers.isEmpty()) closeLayer();
        HomeService.pause(false);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(HomeService.AMAZON_HOME);
        if (!start(home)) showToast(Tr.t("Amazon's home screen isn't available"));
        else if (ctx instanceof Activity) ((Activity) ctx).finish();
    }

    boolean start(Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean isInstalled(String p) {
        try { pm.getPackageInfo(p, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // ---- Background and wallpaper ----

    void setBackdrop(Bitmap b) {
        if (b == null || b == blur) return;
        blur = b;
        final ImageView in = bgA.getAlpha() > 0.5f ? bgB : bgA, out = in == bgA ? bgB : bgA;
        in.setImageBitmap(b);
        in.animate().alpha(1f).setDuration(450).start();
        out.animate().alpha(0f).setDuration(450).start();
        shelf.invalidate();
    }

    String wallpaperKey() {
        String w = prefs.getString("wallpaper", WALLPAPERS[0]);
        if (w.equals(CUSTOM) && customWallpaper().exists()) return w;
        return Arrays.asList(WALLPAPERS).contains(w) ? w : WALLPAPERS[0];
    }

    File customWallpaper() {
        return new File(ctx.getFilesDir(), "wallpaper.jpg");
    }

    void nextWallpaper() {
        List<String> all = new ArrayList<>(Arrays.asList(WALLPAPERS));
        if (customWallpaper().exists()) all.add(0, CUSTOM);
        String next = all.get((all.indexOf(wallpaperKey()) + 1) % all.size());
        prefs.edit().putString("wallpaper", next).apply();
        showWallpaper(next);
    }

    // One decoded wallpaper for both copies of the home screen (the app's window and the overlay).
    static String sharedKey;
    static int sharedVersion = -1;
    static Bitmap sharedPhoto, sharedBlur;

    void showWallpaper(String key) {
        shownWallpaper = key;
        seenPhotoVersion = photoVersion;
        if (key.equals(sharedKey) && sharedVersion == photoVersion && sharedPhoto != null) {
            photo = sharedPhoto;
            wallpaperBlur = sharedBlur;
            emptyArt.setImageBitmap(photo);
            if (cards.isEmpty()) setBackdrop(wallpaperBlur);
            return;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(key)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW && o.outHeight / (o.inSampleSize * 2) >= screenH) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; photos don't need alpha
            Bitmap b;
            try (InputStream in = open(key)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return;
            photo = b;
            wallpaperBlur = blurred(b);
            sharedKey = key;
            sharedVersion = photoVersion;
            sharedPhoto = photo;
            sharedBlur = wallpaperBlur;
            emptyArt.setImageBitmap(photo);
            if (cards.isEmpty()) setBackdrop(wallpaperBlur);
        } catch (Exception ignored) {
        }
    }

    InputStream open(String key) throws Exception {
        return key.equals(CUSTOM) ? new FileInputStream(customWallpaper()) : ctx.getAssets().open("wallpapers/" + key + ".jpg");
    }

    // A cheap, good-looking blur: shrink to 80 px wide, three box-blur passes, then draw it stretched.
    static Bitmap blurred(Bitmap b) {
        int w = 80, h = Math.max(1, Math.round(80f * b.getHeight() / b.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(b, w * 4, h * 4, true), w, h, true);
        int[] a = new int[w * h], t = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            box(a, t, h, w, w, 1, 3);
            box(t, a, w, h, 1, w, 3);
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
    }

    static void box(int[] src, int[] dst, int lines, int len, int lineStep, int step, int r) {
        for (int l = 0; l < lines; l++) {
            int start = l * lineStep;
            for (int i = 0; i < len; i++) {
                int red = 0, green = 0, blue = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++, n++) {
                    int c = src[start + k * step];
                    red += (c >> 16) & 255;
                    green += (c >> 8) & 255;
                    blue += c & 255;
                }
                dst[start + i * step] = 0xFF000000 | (red / n) << 16 | (green / n) << 8 | blue / n;
            }
        }
    }

    // ---- Pieces ----

    // One app on the shelf: its TV banner on a rounded tile that grows and lifts when focused.
    class Tile extends FrameLayout {
        final String pkg;
        final View sheen;
        ValueAnimator wobble;
        boolean focused;

        Tile(String p, Drawable banner) {
            super(ctx);
            pkg = p;
            setClipToOutline(true);
            setOutlineProvider(rounded(18));
            setPivotX(px(TILE_W) / 2f);
            setPivotY(px(TILE_H) / 2f);
            setOnHoverListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER && quiet()) {
                    int i = tiles.indexOf(this);
                    if (i >= 0 && (i != appFocus || zone != Z_SHELF)) { leaveCurrent(); focusApp(i, 0, false); }
                }
                return false;
            });
            setOnClickListener(v -> {
                int i = tiles.indexOf(this);
                if (i < 0 || !layers.isEmpty()) return;
                if (i != appFocus || zone != Z_SHELF) { leaveCurrent(); focusApp(i, 0, true); }
                select();
            });

            if (p.equals(SETTINGS_TILE)) {
                setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF8E8E93, 0xFF48484A}));
                addView(new Gear(ctx), new LayoutParams(px(70), px(70), Gravity.CENTER));
            } else if (banner != null) {
                ImageView art = new ImageView(ctx);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageDrawable(banner);
                setBackgroundColor(0xFF1C1C1E);
                addView(art, full());
            } else {
                // No TV banner: the app's icon on a tile tinted with the icon's own colour.
                Drawable icon = null;
                try { icon = pm.getApplicationIcon(p); } catch (PackageManager.NameNotFoundException ignored) {}
                int tint = averageColor(icon);
                setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {shade(tint, 0.6f), shade(tint, 0.3f)}));
                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(icon);
                col.addView(iv, new LinearLayout.LayoutParams(px(58), px(58)));
                TextView name = text(bold, 18, Color.WHITE);
                name.setText(names.get(p));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(px(12), px(8), px(12), 0);
                col.addView(name);
                addView(col, full());
            }
            sheen = new Sheen(ctx);
            sheen.setAlpha(0f);
            addView(sheen, new LayoutParams(px(TILE_W) / 2, LayoutParams.MATCH_PARENT));
        }

        void setFocused(boolean f) {
            if (f == focused) return;
            focused = f;
            animate().cancel();
            if (f) {
                setElevation(px(26));
                animate().scaleX(TILE_FOCUS).scaleY(TILE_FOCUS).setDuration(240).setInterpolator(spring).withLayer().start();
                sheen.setTranslationX(-px(TILE_W) / 2f);
                sheen.setAlpha(1f);
                sheen.animate().translationX(px(TILE_W)).setDuration(650).setStartDelay(80).setInterpolator(ease)
                        .withEndAction(() -> sheen.setAlpha(0f)).start();
                bringToFront();
            } else {
                setElevation(0);
                animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(ease).start();
                sheen.animate().cancel();
                sheen.setAlpha(0f);
            }
        }

        // At an edge: a small push toward the press and back, so the remote never feels dead.
        void nudge(int dir) {
            int i = tiles.indexOf(this);
            if (i < 0) return;
            final float x0 = px(TILE_PAD + i * TILE_STEP), y0 = px((SHELF_H - TILE_H) / 2f);
            float dx = dir == 1 ? -px(10) : dir == 2 ? px(10) : 0, dy = dir == 3 ? -px(8) : dir == 4 ? px(8) : 0;
            animate().x(x0 + dx).y(y0 + dy).setDuration(90).setInterpolator(ease).withEndAction(() ->
                    animate().x(x0).y(y0).setDuration(300).setInterpolator(spring).start()).start();
        }

        void press() {
            animate().scaleX(TILE_FOCUS * 0.94f).scaleY(TILE_FOCUS * 0.94f).setDuration(90).withEndAction(() ->
                    animate().scaleX(TILE_FOCUS).scaleY(TILE_FOCUS).setDuration(200).setInterpolator(spring).start()).start();
        }

        void jiggle(boolean on) {
            if (wobble != null) { wobble.cancel(); wobble = null; }
            setRotation(0);
            if (!on) return;
            wobble = ValueAnimator.ofFloat(-1.6f, 1.6f);
            wobble.setDuration(140 + (int) (Math.random() * 40));
            wobble.setRepeatMode(ValueAnimator.REVERSE);
            wobble.setRepeatCount(ValueAnimator.INFINITE);
            wobble.setStartDelay((long) (Math.random() * 120));
            wobble.addUpdateListener(a -> setRotation((float) a.getAnimatedValue()));
            wobble.start();
        }
    }

    // A pick: the wide picture when it's the chosen one, the poster otherwise, with its title underneath.
    class PickCard extends FrameLayout {
        final Picks.Pick pick;
        final ImageView art;
        final TextView title, tag, headline;
        final View ring;
        float x = MARGIN, w = NARROW_W;

        PickCard(Picks.Pick p, final int index) {
            super(ctx);
            pick = p;
            setClipToOutline(true);
            setOutlineProvider(rounded(18));
            setBackgroundColor(0xFF1C1C1E);
            art = image();
            addView(art, full());
            View fade = new View(ctx);
            boolean isNews = p.kind.equals("news");
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, isNews
                    ? new int[] {0x00000000, 0x00000000, 0x66000000, 0xD9000000}
                    : new int[] {0x00000000, 0x00000000, 0x00000000, 0x99000000}));
            addView(fade, full());
            // News: the headline on the picture, big, while the card is the wide one.
            headline = text(bold, 42, Color.WHITE);
            headline.setMaxLines(2);
            headline.setEllipsize(TextUtils.TruncateAt.END);
            headline.setLineSpacing(px(2), 1f);
            headline.setShadowLayer(px(12), 0, px(2), 0xB3000000);
            headline.setText(p.title);
            LayoutParams hl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            hl.leftMargin = px(32);
            hl.rightMargin = px(32);
            hl.bottomMargin = px(26);
            addView(headline, hl);
            if (p.bitmap == null) setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF3A3A44, 0xFF17171C}));
            tag = text(bold, 22, Color.WHITE);
            tag.setText(p.kind.equals("continue") ? Tr.t("Continue") : p.service);
            tag.setShadowLayer(px(8), 0, px(1), 0x99000000);
            LayoutParams tl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            tl.leftMargin = px(20);
            tl.bottomMargin = px(p.progress >= 0 ? 26 : 18);
            addView(tag, tl);
            if (p.progress >= 0) {
                View track = new View(ctx);
                track.setBackgroundColor(0x4DFFFFFF);
                addView(track, new LayoutParams(LayoutParams.MATCH_PARENT, px(6), Gravity.BOTTOM));
                View done = new View(ctx);
                done.setBackgroundColor(Color.WHITE);
                done.setPivotX(0);
                done.setScaleX(p.progress);
                addView(done, new LayoutParams(LayoutParams.MATCH_PARENT, px(6), Gravity.BOTTOM));
            }
            // The focus ring: a white edge while this card has the remote.
            ring = new View(ctx);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0);
            g.setCornerRadius(px(18));
            g.setStroke(px(5), Color.WHITE);
            ring.setBackground(g);
            ring.setAlpha(0f);
            addView(ring, full());

            title = text(bold, isNews ? 26 : 32, fg);
            if (isNews) { title.setMaxLines(2); title.setLineSpacing(px(2), 1f); } else title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(p.title);

            setOnHoverListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER && quiet() && (zone != Z_PICKS || pickFocus != index)) focusPick(index, 0);
                return false;
            });
            setOnClickListener(v -> { if (layers.isEmpty()) { focusPick(index, 0); select(); } });
            showWide(false);
        }

        void showWide(boolean on) {
            Bitmap b = on || pick.posterBitmap == null ? pick.bitmap : pick.posterBitmap;
            // No picture (Reddit sometimes refuses them): the wallpaper, blurred, so the card still looks made.
            if (b == null) b = wallpaperBlur;
            art.setImageBitmap(b);
            boolean isNews = pick.kind.equals("news");
            tag.setVisibility(on && !isNews ? VISIBLE : GONE);
            headline.setVisibility(on && isNews ? VISIBLE : GONE);
            // The wide news card has its headline on the picture, so the title underneath would repeat it.
            title.setVisibility(on && isNews ? INVISIBLE : VISIBLE);
        }

        void place(float nx, float nw) {
            x = nx;
            w = nw;
            LayoutParams lp = (LayoutParams) getLayoutParams();
            lp.leftMargin = px(nx);
            lp.topMargin = px(PICKS_Y);
            lp.width = px(nw);
            setLayoutParams(lp);
            LayoutParams t = (LayoutParams) title.getLayoutParams();
            t.leftMargin = px(nx);
            t.topMargin = px(PICKS_Y + pickH() + 22);
            t.width = px(nw);
            title.setLayoutParams(t);
            // Only whole cards show; one cut off by the screen's edge looks broken. They slide in as you move.
            float a = nx >= MARGIN - 2 && nx + nw <= 1920 - MARGIN + 2 ? 1f : 0f;
            setAlpha(a);
            title.setAlpha(a);
        }

        void setFocused(boolean f) {
            ring.animate().alpha(f ? 1f : 0f).setDuration(160).start();
            setElevation(f ? px(24) : 0);
        }

        void press() {
            animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).withEndAction(() ->
                    animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(spring).start()).start();
        }
    }

    // A round frosted button with a drawn icon: Appstore (an apps grid), Glass settings (a gear), the ☰ panel.
    // The portfolio in the header: the value, today, all time, the day as a line and the record as bars.
    // It's first in the top row's focus order; OK opens the full panel.
    class Pulse extends Circle {
        final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), mid = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Chart chart;

        Pulse(Context c) {
            super(c, -1, null);
            big.setTypeface(bold);
            big.setTextSize(px(46));
            big.setTextAlign(Paint.Align.RIGHT);
            mid.setTypeface(bold);
            mid.setTextSize(px(25));
            mid.setTextAlign(Paint.Align.RIGHT);
            small.setTypeface(regular);
            small.setTextSize(px(21));
            small.setTextAlign(Paint.Align.RIGHT);
            chart = new Chart(c, false);
            setOnClickListener(v -> openPortfolio());
        }

        @Override Runnable action() { return HomeScreen.this::openPortfolio; }

        @Override void setOn(boolean o) {
            if (on == o) return;
            on = o;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (on) {
                fill.setColor(light ? 0x1A000000 : 0x1FFFFFFF);
                edge.setColor(light ? 0x33000000 : 0x59FFFFFF);
                RectF r = new RectF(edge.getStrokeWidth(), edge.getStrokeWidth(), w - edge.getStrokeWidth(), h - edge.getStrokeWidth());
                c.drawRoundRect(r, px(28), px(28), fill);
                c.drawRoundRect(r, px(28), px(28), edge);
            }
            float right = w - px(232);
            big.setColor(fg);
            small.setColor(dim);
            boolean hide = Portfolio.hideAmounts(ctx);
            Portfolio.Totals t = Portfolio.totals(ctx);
            if (!Portfolio.hasKey(ctx) || Portfolio.quotes.isEmpty()) {
                big.setTextSize(px(34));
                c.drawText(Tr.t("Portfolio"), right, px(62), big);
                big.setTextSize(px(46));
                String msg = !Portfolio.hasKey(ctx) ? Tr.t("Add a free Finnhub key in Glass settings")
                        : !Portfolio.error.isEmpty() ? Portfolio.error : Tr.t("Getting prices…");
                c.drawText(msg, right, px(96), small);
                return;
            }
            int col = t.dayPl >= 0 ? upColor() : downColor();
            String arrow = t.dayPl >= 0 ? "▲ " : "▼ ";
            if (hide) {
                big.setColor(col);
                c.drawText(Portfolio.pct(t.dayPct), right, px(56), big);
                mid.setColor(dim);
                c.drawText(Tr.t("today"), right, px(84), mid);
                c.drawText(Tr.t("All time") + " " + Portfolio.pct(t.totalPct), right, px(110), small);
            } else {
                c.drawText(Portfolio.money(t.value), right, px(56), big);
                mid.setColor(col);
                c.drawText(arrow + Portfolio.signedMoney(t.dayPl) + "  " + Portfolio.pct(t.dayPct) + "  " + Tr.t("today"), right, px(84), mid);
                c.drawText(Tr.t("All time") + " " + Portfolio.signedMoney(t.totalPl) + " · " + Portfolio.pct(t.totalPct), right, px(110), small);
            }
            // The line and the bars, to the right of the numbers.
            c.save();
            c.translate(w - px(210), px(18));
            chart.layout(0, 0, px(190), px(104));
            chart.draw(c);
            c.restore();
        }
    }

    // One column per holding: the symbol with ▲ or ▼, the price, today's change, and since you bought it.
    // Up to four at a time; with more, they page every 8 seconds.
    class Tickers extends View {
        final Paint sym = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG),
                chg = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG), rule = new Paint();
        final Runnable page = this::invalidate;

        Tickers(Context c) {
            super(c);
            sym.setTypeface(bold);
            sym.setTextSize(px(24));
            num.setTypeface(regular);
            num.setTextSize(px(22));
            chg.setTypeface(bold);
            chg.setTextSize(px(20));
            small.setTypeface(regular);
            small.setTextSize(px(19));
        }

        @Override protected void onDraw(Canvas c) {
            List<Portfolio.Holding> hs = Portfolio.holdings(ctx);
            if (hs.isEmpty()) return;
            int per = 4, pages = (hs.size() + per - 1) / per;
            int first = pages <= 1 ? 0 : (int) (System.currentTimeMillis() / 8000 % pages) * per;
            removeCallbacks(page);
            if (pages > 1) postDelayed(page, 8000 - System.currentTimeMillis() % 8000);
            float colW = getWidth() / (float) per;
            boolean hide = Portfolio.hideAmounts(ctx);
            int up = upColor(), down = downColor();
            rule.setColor(light ? 0x1F000000 : 0x26FFFFFF);
            rule.setStrokeWidth(Math.max(1, px(1.5f)));
            for (int k = 0; k < per && first + k < hs.size(); k++) {
                Portfolio.Holding h = hs.get(first + k);
                Portfolio.Quote q = Portfolio.quotes.get(h.symbol);
                // Every column's text starts the same distance from the last, with the divider centred in the gap.
                float x = k * colW + px(4);
                if (k > 0) c.drawLine(x - px(18), px(8), x - px(18), px(102), rule);
                double dPct = q == null || q.prevClose <= 0 ? 0 : (q.price - q.prevClose) / q.prevClose * 100;
                int col = dPct >= 0 ? up : down;
                // Four short lines, stacked: the symbol with ▲ or ▼, the price, today's move, your gain or loss on it.
                sym.setColor(fg);
                c.drawText(h.symbol, x, px(27), sym);
                if (q == null) { num.setColor(dim); c.drawText("…", x, px(51), num); continue; }
                chg.setColor(col);
                c.drawText(dPct >= 0 ? "▲" : "▼", x + sym.measureText(h.symbol) + px(8), px(26), chg);
                num.setColor(fg);
                c.drawText(Portfolio.price(q.price), x, px(51), num);
                c.drawText(Portfolio.pct(dPct), x, px(75), chg);
                if (h.cost > 0) {
                    double gain = h.shares * q.price - h.cost, gPct = gain / h.cost * 100;
                    small.setColor(((gain >= 0 ? up : down) & 0x00FFFFFF) | 0xA6000000);
                    c.drawText(hide ? (gPct >= 0 ? "+" : "−") + String.format(java.util.Locale.US, "%.1f%%", Math.abs(gPct)) : Portfolio.signedMoney(gain), x, px(98), small);
                }
            }
        }
    }

    // Today's value as a line over yesterday's close (dashed), and below it one bar per day of the record:
    // green up, red down, the height its size. Small in the header, large in the panel.
    class Chart extends View {
        final boolean large;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
                bar = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), area = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path(), fillPath = new Path();

        Chart(Context c, boolean large) {
            super(c);
            this.large = large;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(px(large ? 4 : 3));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            base.setStyle(Paint.Style.STROKE);
            base.setStrokeWidth(Math.max(1, px(1.5f)));
            base.setPathEffect(new android.graphics.DashPathEffect(new float[] {px(5), px(6)}, 0));
            label.setTypeface(regular);
            label.setTextSize(px(20));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float lineH = h * (large ? 0.62f : 0.6f), barTop = lineH + h * (large ? 0.12f : 0.1f), barH = h - barTop - (large ? px(24) : 0);
            Portfolio.Totals t = Portfolio.totals(ctx);
            int up = upColor(), down = downColor(), col = t.dayPl >= 0 ? up : down;
            base.setColor(dim);
            label.setColor(dim);

            // Today: from yesterday's close (the dashed line) to now.
            // Only points from inside the trading day count (older versions also saved some at night and at weekends).
            List<long[]> pts = new ArrayList<>();
            for (long[] p : Portfolio.samples) {
                Calendar k = Calendar.getInstance(Portfolio.NY);
                k.setTimeInMillis(p[0]);
                int m = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
                if (m >= 9 * 60 + 30 && m <= 16 * 60) pts.add(p);
            }
            double cash = Portfolio.cash(ctx), prev = t.prevValue + cash;
            double lo = prev, hi = prev;
            for (long[] p : pts) { lo = Math.min(lo, p[1] / 100.0); hi = Math.max(hi, p[1] / 100.0); }
            lo = Math.min(lo, t.value); hi = Math.max(hi, t.value);
            if (hi - lo < prev * 0.002) { double mid = (hi + lo) / 2; hi = mid + prev * 0.001; lo = mid - prev * 0.001; }
            float pad = px(6);
            float baseY = (float) (pad + (hi - prev) / (hi - lo) * (lineH - 2 * pad));
            c.drawLine(0, baseY, w, baseY, base);
            if (pts.size() < 2) {
                // Not a day of points yet (the market's closed, or Glass just started): from yesterday's close at the
                // open straight to now's value at the close, so it still reads as "the day went this way".
                pts.clear();
                Calendar o = Calendar.getInstance(Portfolio.NY);
                o.set(Calendar.HOUR_OF_DAY, 9);
                o.set(Calendar.MINUTE, 30);
                pts.add(new long[] {o.getTimeInMillis(), Math.round(prev * 100)});
                pts.add(new long[] {o.getTimeInMillis() + (long) (6.5 * 3600_000), Math.round(t.value * 100)});
            }
            // x: the trading day from 9:30 to 16:00 New York time, so the line grows across the day.
            Calendar open = Calendar.getInstance(Portfolio.NY);
            open.setTimeInMillis(pts.get(0)[0]);
            open.set(Calendar.HOUR_OF_DAY, 9);
            open.set(Calendar.MINUTE, 30);
            open.set(Calendar.SECOND, 0);
            long start = open.getTimeInMillis(), span = (long) (6.5 * 3600_000);
            path.reset();
            fillPath.reset();
            float lastX = 0, lastY = baseY;
            for (int i = 0; i < pts.size(); i++) {
                float x = Math.max(0, Math.min(1, (pts.get(i)[0] - start) / (float) span)) * w;
                float y = (float) (pad + (hi - pts.get(i)[1] / 100.0) / (hi - lo) * (lineH - 2 * pad));
                if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, baseY); fillPath.lineTo(x, y); } else { path.lineTo(x, y); fillPath.lineTo(x, y); }
                lastX = x;
                lastY = y;
            }
            fillPath.lineTo(lastX, baseY);
            fillPath.close();
            area.setColor((col & 0x00FFFFFF) | 0x26000000);
            c.drawPath(fillPath, area);
            line.setColor(col);
            c.drawPath(path, line);
            dot.setColor(col);
            c.drawCircle(lastX, lastY, px(large ? 7 : 5), dot);
            if (large) {
                c.drawText("9:30", 0, lineH + px(2), label);
                label.setTextAlign(Paint.Align.RIGHT);
                c.drawText("16:00", w, lineH + px(2), label);
                label.setTextAlign(Paint.Align.LEFT);
            }

            // The record: the last days, one bar each.
            List<Portfolio.Day> days = Portfolio.days(ctx);
            int n = Math.min(days.size(), large ? 40 : 22);
            if (n == 0) return;
            List<Portfolio.Day> shown = days.subList(days.size() - n, days.size());
            double max = 0;
            for (Portfolio.Day d : shown) max = Math.max(max, Math.abs(d.pl));
            if (max <= 0) max = 1;
            int slots = large ? 40 : 22;
            float slot = w / slots, bw = slot * 0.62f, mid = barTop + barH / 2;
            for (int i = 0; i < n; i++) {
                Portfolio.Day d = shown.get(i);
                float x = w - (n - i) * slot + (slot - bw) / 2;
                float bh = Math.max(px(2), (float) (Math.abs(d.pl) / max * (barH / 2)));
                bar.setColor(d.pl >= 0 ? up : down);
                bar.setAlpha(i == n - 1 ? 255 : 160);
                c.drawRoundRect(new RectF(x, d.pl >= 0 ? mid - bh : mid, x + bw, d.pl >= 0 ? mid : mid + bh), bw / 2, bw / 2, bar);
            }
            if (large) {
                c.drawText(Tr.t("Each day since {n}", Portfolio.shortDate(shown.get(0).date)), 0, h - px(2), label);
            }
        }
    }

    class Circle extends View {
        static final int BAG = 0, GEAR = 1, MENU = 2;
        final int icon;
        final Runnable action;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        boolean on;

        Circle(Context c, int icon, Runnable action) {
            super(c);
            this.icon = icon;
            this.action = action;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, px(1.5f)));
            setOnClickListener(v -> action().run());
            setOnHoverListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER && layers.isEmpty()) focusTop(circles.indexOf(this));
                return false;
            });
        }

        Runnable action() { return action; }

        void setOn(boolean o) {
            if (on == o) return;
            on = o;
            animate().scaleX(o ? 1.15f : 1f).scaleY(o ? 1.15f : 1f).setDuration(o ? 220 : 160).setInterpolator(o ? spring : ease).start();
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f, cx = r, cy = getHeight() / 2f;
            fill.setColor(on ? (light ? 0xFF1C1C1E : Color.WHITE) : light ? 0xE6FFFFFF : 0x33FFFFFF);
            edge.setColor(light ? 0x1A000000 : 0x40FFFFFF);
            c.drawCircle(cx, cy, r - edge.getStrokeWidth(), fill);
            c.drawCircle(cx, cy, r - edge.getStrokeWidth(), edge);
            ink.setColor(on ? (light ? Color.WHITE : Color.BLACK) : fg);
            float u = getWidth() / 68f;
            if (icon == BAG) { // apps: four rounded squares
                ink.setStyle(Paint.Style.FILL);
                float a = 8.5f * u, g = 3f * u;
                for (int i = 0; i < 4; i++) {
                    float x0 = cx + (i % 2 == 0 ? -a - g / 2 : g / 2), y0 = cy + (i < 2 ? -a - g / 2 : g / 2);
                    c.drawRoundRect(new RectF(x0, y0, x0 + a, y0 + a), 2.5f * u, 2.5f * u, ink);
                }
            } else if (icon == GEAR) {
                ink.setStyle(Paint.Style.FILL);
                float gr = 14 * u;
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45, cx, cy);
                    c.drawRoundRect(new RectF(cx - 3.2f * u, cy - gr - 3 * u, cx + 3.2f * u, cy - gr * 0.5f), u, u, ink);
                    c.restore();
                }
                path.reset();
                path.addCircle(cx, cy, gr * 0.85f, Path.Direction.CW);
                path.addCircle(cx, cy, gr * 0.35f, Path.Direction.CCW);
                c.drawPath(path, ink);
            } else {
                ink.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawRoundRect(new RectF(cx - 12 * u, cy + i * 7.5f * u - 1.6f * u, cx + 12 * u, cy + i * 7.5f * u + 1.6f * u), u, u, ink);
            }
        }
    }

    class Sheen extends View {
        final Paint p = new Paint();
        Sheen(Context c) { super(c); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            p.setShader(new LinearGradient(0, 0, w, h * 0.4f, new int[] {0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), p); }
    }

    // A simple drawn gear for the Settings tile.
    class Gear extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        Gear(Context c) { super(c); p.setColor(0xFFF2F2F7); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = getWidth() / 2f;
            for (int i = 0; i < 12; i++) {
                c.save();
                c.rotate(i * 30, cx, cy);
                c.drawRoundRect(new RectF(cx - r * 0.13f, cy - r, cx + r * 0.13f, cy - r * 0.6f), r * 0.06f, r * 0.06f, p);
                c.restore();
            }
            path.reset();
            path.addCircle(cx, cy, r * 0.72f, Path.Direction.CW);
            path.addCircle(cx, cy, r * 0.3f, Path.Direction.CCW);
            c.drawPath(path, p);
        }
    }

    // Small drawn icons, the same on every Fire OS font: Wi-Fi (or Ethernet, or offline), Bluetooth headphones, a USB drive.
    class StatusIcons extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean online, wifi, ethernet, bt, usb;
        int color = Color.WHITE;

        StatusIcons(Context c) { super(c); }

        void set(boolean on, boolean w, boolean e, boolean b, boolean u) {
            if (on == online && w == wifi && e == ethernet && b == bt && u == usb && getWidth() > 0) return;
            online = on; wifi = w; ethernet = e; bt = b; usb = u;
            requestLayout();
            invalidate();
        }

        int count() { return 1 + (bt ? 1 : 0) + (usb ? 1 : 0); }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(px(count() * 44 + 16), px(40));
        }

        @Override protected void onDraw(Canvas c) {
            p.setColor(color);
            float u = px(0.75f), cy = getHeight() / 2f, x = 10 * u; // the Wi-Fi arcs reach 10u left of x
            if (!online) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 * u);
                c.drawLine(x, cy - 12 * u, x + 24 * u, cy + 12 * u, p);
                c.drawLine(x + 24 * u, cy - 12 * u, x, cy + 12 * u, p);
            } else if (ethernet) {
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(x, cy - 10 * u, x + 26 * u, cy + 8 * u), 3 * u, 3 * u, p);
                c.drawRect(x + 8 * u, cy + 8 * u, x + 18 * u, cy + 14 * u, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3.2f * u);
                p.setStrokeCap(Paint.Cap.ROUND);
                float bx = x + 13 * u, by = cy + 11 * u;
                for (int i = 1; i <= 3; i++) {
                    float r = i * 7.5f * u;
                    c.drawArc(new RectF(bx - r, by - r, bx + r, by + r), 225, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(bx, by - u, 2.6f * u, p);
            }
            x += px(44);
            if (bt) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 * u);
                c.drawArc(new RectF(x, cy - 13 * u, x + 26 * u, cy + 13 * u), 180, 180, false, p);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(x - u, cy, x + 7 * u, cy + 13 * u), 2 * u, 2 * u, p);
                c.drawRoundRect(new RectF(x + 19 * u, cy, x + 27 * u, cy + 13 * u), 2 * u, 2 * u, p);
                x += px(44);
            }
            if (usb) {
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(x + 5 * u, cy - 6 * u, x + 21 * u, cy + 14 * u), 3 * u, 3 * u, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f * u);
                c.drawRect(x + 8 * u, cy - 13 * u, x + 18 * u, cy - 6 * u, p);
            }
        }
    }

    // Frosted glass: the blurred background drawn exactly behind the view, a light or dark tint and a hairline edge.
    class Glass extends FrameLayout {
        final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final android.graphics.Matrix m = new android.graphics.Matrix();
        final int[] loc = new int[2];
        final float radius;
        Bitmap shaderFor;

        Glass(Context c, float radius) {
            super(c);
            this.radius = radius;
            setWillNotDraw(false);
            tint.setColor(glassTint);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, px(2f)));
            edge.setColor(glassEdge);
            setClipToOutline(true);
            setOutlineProvider(rounded(radius));
            setElevation(px(30));
        }

        @Override protected void onDraw(Canvas c) {
            float r = px(radius);
            rect.set(0, 0, getWidth(), getHeight());
            if (blur != null) {
                if (shaderFor != blur) {
                    photoPaint.setShader(new android.graphics.BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    shaderFor = blur;
                }
                float k = Math.max((float) screenW / blur.getWidth(), (float) screenH / blur.getHeight());
                getLocationInWindow(loc);
                m.setScale(k, k);
                m.postTranslate((screenW - blur.getWidth() * k) / 2 - loc[0], (screenH - blur.getHeight() * k) / 2 - loc[1]);
                photoPaint.getShader().setLocalMatrix(m);
                c.drawRoundRect(rect, r, r, photoPaint);
            }
            c.drawRoundRect(rect, r, r, tint);
            float e = edge.getStrokeWidth() / 2;
            rect.inset(e, e);
            c.drawRoundRect(rect, r - e, r - e, edge);
        }
    }

    ViewOutlineProvider rounded(final float r) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(r)); }
        };
    }

    static int averageColor(Drawable d) {
        if (d == null) return 0xFF3A3A3C;
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int x = 0; x < 24; x++) for (int y = 0; y < 24; y++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
            }
            return n == 0 ? 0xFF3A3A3C : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        } catch (Exception e) {
            return 0xFF3A3A3C;
        }
    }

    static int shade(int c, float k) {
        return Color.rgb((int) (Color.red(c) * k), (int) (Color.green(c) * k), (int) (Color.blue(c) * k));
    }

    void showToast(String msg) {
        toast.setText(msg);
        toast.bringToFront();
        toast.animate().cancel();
        toast.setAlpha(0f);
        toast.animate().alpha(1f).setDuration(200).withEndAction(() ->
                toast.animate().alpha(0f).setStartDelay(2600).setDuration(300).start()).start();
    }

    ImageView image() {
        ImageView v = new ImageView(ctx);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    TextView text(Typeface f, float size, int color) {
        TextView t = new TextView(ctx);
        t.setTypeface(f);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radius));
        return g;
    }

    LayoutParams full() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    LayoutParams at(int x, int y, int w, int h) {
        LayoutParams lp = new LayoutParams(px(w), px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    int px(float v) {
        return Math.round(v * s);
    }
}
