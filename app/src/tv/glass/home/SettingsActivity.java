package tv.glass.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Glass's own settings, on the TV: every switch for the home screen, from the ☰ panel > Glass settings.
// Rows: OK (or ◀ ▶) changes the value; rows with › open a page; Back goes up a page.
public class SettingsActivity extends Activity {
    static int version; // bumped on every change, so the home screen knows to refresh

    interface Value { String get(); }

    final List<Runnable> pages = new ArrayList<>(); // to rebuild each page on Back
    LinearLayout list;
    TextView heading, note;
    ScrollView scroll;
    float s;
    Typeface bold, regular;
    boolean light;
    int fg, fgDim, rowBg;
    KeyServer keys;
    Runnable armed; // "press OK again" confirmations

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Tr.init(this);
        Crash.install(this);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        s = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        bold = Typeface.createFromAsset(getAssets(), "fonts/Inter-SemiBold.ttf");
        regular = Typeface.createFromAsset(getAssets(), "fonts/Inter-Medium.ttf");
        String page = getIntent().getStringExtra("page");
        build();
        if ("recents".equals(page)) open(this::recentsPage);
        else if ("audio".equals(page)) open(this::audioPage);
        else if ("keys".equals(page)) open(this::keysPage);
        else open(this::mainPage);
    }

    void build() {
        light = Sun.light(this);
        fg = light ? 0xFF111114 : Color.WHITE;
        fgDim = light ? 0x99111114 : 0xB3FFFFFF;
        rowBg = light ? 0x14000000 : 0x1FFFFFFF;
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                light ? new int[] {0xFFF2F2F7, 0xFFDCE3EE} : new int[] {0xFF1A1C24, 0xFF07080C}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(200), px(70), px(200), 0);
        heading = text(bold, 60, fg);
        col.addView(heading);
        note = text(regular, 26, fgDim);
        note.setPadding(0, px(14), 0, px(26));
        col.addView(note);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(10), 0, px(80));
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    void open(Runnable page) {
        pages.add(page);
        show(page);
    }

    void show(Runnable page) {
        armed = null;
        if (keys != null) { keys.stop(); keys = null; } // the phone page closes when you leave its page
        list.removeAllViews();
        note.setText("");
        page.run();
        list.post(() -> {
            for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) { list.getChildAt(i).requestFocus(); break; }
            scroll.scrollTo(0, 0);
        });
    }

    void refresh() {
        int focused = -1;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).hasFocus()) focused = i;
        String n = note.getText().toString();
        Runnable page = pages.get(pages.size() - 1);
        list.removeAllViews();
        page.run();
        note.setText(n);
        final int f = focused;
        if (f >= 0 && f < list.getChildCount()) list.getChildAt(f).requestFocus();
        version++;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            if (pages.size() > 1) {
                pages.remove(pages.size() - 1);
                show(pages.get(pages.size() - 1));
            } else finish();
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        if (keys != null) keys.stop();
        super.onDestroy();
    }

    // ---- Pages ----

    void mainPage() {
        heading.setText(Tr.t("Glass settings"));
        boolean glassHome = TvControl.glassIsHome(this);
        row(Tr.t("Home screen"), () -> TvControl.glassIsHome(this) ? "Glass" : Tr.t("Amazon (original)"), () -> {
            boolean toGlass = !TvControl.glassIsHome(this);
            if (toGlass) Crash.clear(this);
            String err = toGlass ? TvControl.homeToGlass(this) : TvControl.homeToAmazon(this);
            if (err != null) say(Tr.t("Couldn't switch: {n}", err));
            else say(toGlass ? Tr.t("Home now opens Glass.") : Tr.t("Home now opens Amazon's home screen. Switch back here any time."));
            list.postDelayed(this::refresh, 800);
        });
        if (!glassHome && Prefs.on(this, "safety_tripped", false)) say(Tr.t("Glass crashed 3 times in 5 minutes, so Home went back to Amazon. Switch it back when you're ready."));
        cycle(Tr.t("Language"), "lang", Tr.LANGS, Tr.LANG_NAMES, () -> { Tr.reload(); refresh(); });
        page(Tr.t("City"), () -> Prefs.city(this), this::cityPage);
        String[] wzNames = new String[WorldClock.NAMES.length];
        for (int i = 0; i < wzNames.length; i++) wzNames[i] = Tr.t(WorldClock.NAMES[i]);
        cycle(Tr.t("World clock"), "world_tz", WorldClock.ZONES, wzNames, null);
        cycle(Tr.t("Temperature"), "temp", new String[] {"auto", "F", "C"}, new String[] {Tr.t("Automatic"), "°F", "°C"}, () -> Weather.fetchedAt = 0);
        cycle(Tr.t("Theme"), "theme", new String[] {"auto", "light", "dark"}, new String[] {Tr.t("Light by day, dark by night"), Tr.t("Light"), Tr.t("Dark")}, this::recreate);
        toggle(Tr.t("Weather on Home"), "weather", true, null);
        page(Tr.t("Headphones and speakers"), () -> "", this::audioPage);
        page(Tr.t("Portfolio"), () -> Portfolio.holdings(this).isEmpty() ? Tr.t("Off") : Portfolio.holdings(this).size() + " " + Tr.t("holdings"), this::portfolioPage);
        page(Tr.t("News"), () -> Prefs.str(this, "home_row", "news").equals("picks") ? Tr.t("Movie and TV picks on Home") : News.chosen(this).size() + " " + Tr.t("topics"), this::newsPage);
        page(Tr.t("Picks"), () -> Prefs.on(this, "picks", true) ? Tr.t("On") : Tr.t("Off"), this::picksPage);
        // Continue Watching reads Android's Watch Next list. On Fire TV, Netflix, Prime Video and the rest don't write to
        // it, so it's usually empty; say so rather than look switched on and show nothing.
        final int watching = Picks.watchNextCount(this);
        row(Tr.t("Continue Watching"), () -> !Prefs.on(this, "continue", false) ? Tr.t("Off")
                : watching > 0 ? Tr.t("On") : Tr.t("On · nothing to show"), () -> {
            if (checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) { say(Tr.t(TvControl.NEEDS)); return; }
            Prefs.set(this, "continue", !Prefs.on(this, "continue", false));
            Picks.fetchedAt = 0;
            say(!Prefs.on(this, "continue", false) ? Tr.t("Off.")
                    : watching > 0 ? Tr.t("On. It shows first in the movie and TV picks (Glass settings → News → On Home).")
                    : Tr.t("On, but there's nothing to show: on Fire TV, Netflix, Prime Video and the others don't share what you're watching with Android. It appears in the movie and TV picks if an app ever does."));
            refresh();
        });
        row(Tr.t("Now Playing"), () -> Prefs.on(this, "now_playing", false) ? Tr.t("On") : Tr.t("Off"), () -> {
            boolean on = !Prefs.on(this, "now_playing", false);
            String err = TvControl.listener(this, on);
            if (err != null) { say(Tr.t("Couldn't switch: {n}", err)); return; }
            Prefs.set(this, "now_playing", on);
            say(on ? Tr.t("On.") : Tr.t("Off, and the permission is taken back."));
            refresh();
        });
        toggle(Tr.t("Status icons"), "status_icons", true, null);
        toggle(Tr.t("Appstore button"), "appstore_button", true, null);
        row(Tr.t("Block ads and trackers (AdGuard DNS)"), () -> TvControl.adDnsOn(this) ? Tr.t("On") : Tr.t("Off"), () -> {
            boolean on = !TvControl.adDnsOn(this);
            String err = TvControl.adDns(this, on);
            if (err != null) { say(Tr.t("Couldn't switch: {n}", err)); return; }
            say(on ? Tr.t("On: ad and tracking addresses are blocked in every app. YouTube's own ads aren't. If a site or app stops loading, switch this off.")
                    : Tr.t("Off: the TV looks up addresses the usual way again."));
            refresh();
        });
        row(Tr.t("Screensaver"), () -> Prefs.on(this, "glass_dream", false) ? "Glass" : "Amazon", () -> {
            final boolean on = !Prefs.on(this, "glass_dream", false);
            String err = TvControl.screensaver(this, on);
            if (err != null) { say(Tr.t("Couldn't switch: {n}", err)); return; }
            Prefs.set(this, "glass_dream", on);
            say(on ? Tr.t("Glass is the screensaver. Fire OS may still show Amazon's after an update; switch again here.") : Tr.t("Amazon's screensaver is back."));
            refresh();
        });
        sleepRow();
        page(Tr.t("Recent apps"), () -> "", this::recentsPage);
        page(Tr.t("Keys and services"), () -> Prefs.secret(this, "tmdb_key").isEmpty() ? Tr.t("No TMDB key") : "TMDB ✓", this::keysPage);
        final java.util.List<String> back = TvControl.backOn(this);
        row(Tr.t("Warn when Fire OS updates turn apps back on"), () -> Prefs.on(this, "reapply", true)
                ? (back.isEmpty() ? Tr.t("On · all still off") : Tr.t("On · {n} back on", back.size())) : Tr.t("Off"), () -> {
            if (!back.isEmpty()) say(Tr.t("Back on: {n}. From your computer: ./glass.sh debloat", android.text.TextUtils.join(", ", back)));
            Prefs.set(this, "reapply", !Prefs.on(this, "reapply", true));
            refresh();
        });
        row(Tr.t("Permissions from glass.sh"), () -> TvControl.canWrite(this) && TvControl.hasUsageAccess(this) ? Tr.t("All given") : Tr.t("Missing"), () ->
                say(TvControl.canWrite(this) && TvControl.hasUsageAccess(this) ? Tr.t("Glass can switch Home, the screensaver and Now Playing, and list recent apps.") : Tr.t(TvControl.NEEDS)));
        row(Tr.t("Restore settings"), () -> Tr.t("settings, Home"), () -> confirm(Tr.t("Press OK again: settings glass.sh changed go back, and Home goes to Amazon. Apps it turned off need the computer: ./glass.sh undo"), () -> {
            String err = TvControl.restoreSettings(this);
            say(err == null ? Tr.t("Settings are back as they were and Home opens Amazon's home screen. For the apps: ./glass.sh undo") : Tr.t("Couldn't restore: {n}", err));
            list.postDelayed(this::refresh, 800);
        }));
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        info(Tr.t("Glass {n}", v) + " · " + Tr.t("no ads, no tracking"));
    }

    void sleepRow() {
        final int[] mins = {0, 15, 30, 45, 60, 90, 120, 180, 240};
        row(Tr.t("Sleep timer"), () -> {
            long left = SleepTimer.remaining();
            return left <= 0 ? Tr.t("Off") : Tr.t("{n} min left", (left + 59_999) / 60_000);
        }, () -> {
            long left = SleepTimer.remaining();
            int cur = 0;
            for (int i = 0; i < mins.length; i++) if (left > 0 && mins[i] * 60_000L >= left - 30_000) { cur = i; break; }
            int next = left <= 0 ? 1 : (cur + 1) % mins.length;
            SleepTimer.set(this, mins[next]);
            refresh();
        });
    }

    void cityPage() {
        heading.setText(Tr.t("City"));
        say(Tr.t("Now: {n}. Type a city and press OK on the keyboard, or type latitude, longitude.", Prefs.city(this)));
        final EditText field = field(Tr.t("City name"), false);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        field.setOnEditorActionListener((v, id, ev) -> {
            String q = field.getText().toString().trim();
            if (q.isEmpty()) return true;
            String[] ll = q.split("[,\\s]+");
            if (ll.length == 2) {
                try {
                    double lat = Double.parseDouble(ll[0]), lon = Double.parseDouble(ll[1]);
                    if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                        Prefs.setPlace(this, Tr.t("Here"), lat, lon);
                        placeChanged();
                        return true;
                    }
                } catch (NumberFormatException ignored) {}
            }
            say(Tr.t("Searching…"));
            Net.pool.execute(() -> {
                try {
                    JSONObject j = new JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=" + Tr.lang() + "&name=" + Net.enc(q), null));
                    JSONArray r = j.optJSONArray("results");
                    TvControl.main.post(() -> {
                        results.removeAllViews();
                        if (r == null || r.length() == 0) { say(Tr.t("No city by that name.")); return; }
                        say(Tr.t("Pick one:"));
                        for (int i = 0; i < r.length(); i++) {
                            JSONObject o = r.optJSONObject(i);
                            final String name = o.optString("name");
                            final double lat = o.optDouble("latitude"), lon = o.optDouble("longitude");
                            String where = o.optString("admin1", "") + (o.optString("country", "").isEmpty() ? "" : ", " + o.optString("country"));
                            results.addView(rowView(name, where, () -> { Prefs.setPlace(this, name, lat, lon); placeChanged(); }));
                        }
                        if (results.getChildCount() > 0) results.getChildAt(0).requestFocus();
                    });
                } catch (Exception e) {
                    TvControl.main.post(() -> say(Tr.t("Couldn't search: is the TV online?")));
                }
            });
            return true;
        });
        list.addView(results);
        row(Tr.t("Back to New York"), () -> "", () -> { Prefs.setPlace(this, Prefs.DEFAULT_CITY, Prefs.DEFAULT_LAT, Prefs.DEFAULT_LON); placeChanged(); });
    }

    void placeChanged() {
        Weather.fetchedAt = 0;
        version++;
        say(Tr.t("City set: {n}", Prefs.city(this)));
        pages.remove(pages.size() - 1);
        show(pages.get(pages.size() - 1));
    }

    void audioPage() {
        heading.setText(Tr.t("Headphones and speakers"));
        say(Tr.t("Paired headphones and speakers. OK connects one (and disconnects the one in use) or disconnects it."));
        BtAudio.withA2dp(this, p -> {
            if (pages.isEmpty() || !(heading.getText().toString().equals(Tr.t("Headphones and speakers")))) return;
            for (final android.bluetooth.BluetoothDevice d : BtAudio.devices()) {
                final boolean on = BtAudio.connected(p, d);
                row(BtAudio.name(d), () -> on ? Tr.t("Connected") : Tr.t("Not connected"), () -> {
                    if (!BtAudio.set(p, d, !on)) { say(Tr.t("Fire TV didn't allow that. Use Pair or manage devices below.")); return; }
                    say(on ? Tr.t("Disconnecting…") : Tr.t("Connecting… make sure it's on and nearby."));
                    list.postDelayed(this::refresh, 4000);
                });
            }
            if (BtAudio.devices().isEmpty()) info(Tr.t("No headphones or speakers are paired yet."));
            row(Tr.t("Pair or manage devices"), () -> "›", () -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { say(Tr.t("Couldn't open Fire TV's Bluetooth settings.")); }
            });
        });
    }

    void portfolioPage() {
        heading.setText(Tr.t("Portfolio"));
        say(Tr.t("Your stocks on Home: what they're worth, today, all time, and a bar for every day. Prices from Finnhub (a free key)."));
        toggle(Tr.t("Portfolio on Home"), "portfolio", true, null);
        toggle(Tr.t("Hide amounts (percentages only)"), "pf_hide", false, null);
        row(Tr.t("Finnhub key"), () -> Portfolio.hasKey(this) ? "✓" : Tr.t("Not yet"), () -> open(this::keysPage));
        row(Tr.t("Edit holdings from your phone"), () -> "›", () -> open(this::keysPage));
        info(Tr.t("Holdings:"));
        for (Portfolio.Holding h : Portfolio.holdings(this)) {
            String shares = (h.shares == Math.rint(h.shares) ? String.valueOf((long) h.shares) : String.valueOf(h.shares));
            info(h.symbol + "   " + Tr.t("{n} shares", shares) + (h.cost > 0 ? "   · " + Tr.t("cost") + " " + Portfolio.money(h.cost) : ""));
        }
        double cash = Portfolio.cash(this);
        if (cash > 0) info(Tr.t("Cash") + "   " + Portfolio.money(cash));
        row(Tr.t("Start the daily record over"), () -> "", () -> confirm(Tr.t("Press OK again to clear the record of past days."), () -> {
            Prefs.set(this, "pf_days", "[]");
            Prefs.set(this, "pf_samples", "{}");
            Portfolio.samples.clear();
            Portfolio.samplesDate = "";
            say(Tr.t("The record starts again today."));
            refresh();
        }));
    }

    void newsPage() {
        heading.setText(Tr.t("News"));
        say(Tr.t("Headlines with a picture and a short summary on Home, one tab per topic. OK on a story opens it in Silk."));
        cycle(Tr.t("On Home"), "home_row", new String[] {"news", "picks"}, new String[] {Tr.t("News"), Tr.t("Movie and TV picks")}, null);
        info(Tr.t("Topics (each one is a tab on Home):"));
        for (final News.Topic t : News.TOPICS) {
            page(Tr.t(t.name), () -> News.topicOn(this, t.id) ? Tr.t("On") + " · " + News.sourcesLine(this, t) : Tr.t("Off"), () -> topicPage(t));
        }
    }

    void topicPage(final News.Topic t) {
        heading.setText(Tr.t(t.name));
        say(Tr.t("Sources for this topic. A few stories from each one that's on, newest and most talked-about first."));
        row(Tr.t("Show this topic on Home"), () -> News.topicOn(this, t.id) ? Tr.t("On") : Tr.t("Off"), () -> {
            News.setTopic(this, t.id, !News.topicOn(this, t.id));
            if (News.chosen(this).isEmpty()) News.setTopic(this, t.id, true);
            refresh();
        });
        info(Tr.t("Sources:"));
        for (final News.Source src : t.sources) {
            row(src.name, () -> News.sourceOn(this, t.id, src.id) ? Tr.t("On") : Tr.t("Off"), () -> {
                News.setSource(this, t.id, src.id, !News.sourceOn(this, t.id, src.id));
                refresh();
            });
        }
    }

    void picksPage() {
        heading.setText(Tr.t("Picks"));
        if (Prefs.secret(this, "tmdb_key").isEmpty()) say(Tr.t("Picks need a free TMDB key: Keys and services."));
        else if (Picks.loading) say(Tr.t("Finding new picks…"));
        else if (!Picks.lastError.isEmpty() && !Picks.lastError.equals("nokey")) say(Tr.t("Couldn't get picks: {n}. Glass tries again in 5 minutes.", Picks.lastError));
        else if (Picks.fetchedAt > 0 && Picks.list.isEmpty()) say(Tr.t("No trending titles on your apps right now. Glass tries again in 5 minutes."));
        else say(Tr.t("Trending titles, only from apps on this TV. A new handful every hour.") + (Picks.list.isEmpty() ? "" : " " + Tr.t("Now: {n}.", Picks.list.size())));
        toggle(Tr.t("Picks above your apps"), "picks", true, () -> Picks.fetchedAt = 0);
        final String[] counts = {"3", "4", "5", "6"};
        row(Tr.t("How many"), () -> String.valueOf(Prefs.num(this, "picks_count", 3)), () -> {
            int n = Prefs.num(this, "picks_count", 3);
            Prefs.set(this, "picks_count", n >= 6 ? 3 : n + 1);
            Picks.fetchedAt = 0;
            refresh();
        });
        row(Tr.t("Cartoons and anime"), () -> Prefs.on(this, "hide_animation", false) ? Tr.t("Left out") : Tr.t("Included"), () -> {
            Prefs.set(this, "hide_animation", !Prefs.on(this, "hide_animation", false));
            Picks.fetchedAt = 0;
            refresh();
        });
        info(Tr.t("Picks from:"));
        for (final String svc : Picks.availableServices(this)) {
            row(svc, () -> Picks.serviceOn(this, svc) ? Tr.t("On") : Tr.t("Off"), () -> {
                List<String> off = new ArrayList<>();
                for (String x : Prefs.str(this, "picks_off", "").split(",")) if (!x.isEmpty()) off.add(x);
                if (off.contains(svc)) off.remove(svc); else off.add(svc);
                Prefs.set(this, "picks_off", android.text.TextUtils.join(",", off));
                Picks.fetchedAt = 0;
                refresh();
            });
        }
    }

    void keysPage() {
        heading.setText(Tr.t("Keys and services"));
        if (keys == null) {
            keys = new KeyServer(this, what -> { say(Tr.t("Saved on the TV: {n}", what)); Picks.fetchedAt = 0; version++; refresh(); });
            String url = keys.start();
            if (url == null) { keys = null; say(Tr.t("The TV has no address on your network, so type the keys below.")); }
            else {
                say(Tr.t("Scan with your phone (same Wi-Fi), paste the keys and send. Open for 5 minutes, one send.") + "\n" + url);
                ImageView qr = new ImageView(this);
                qr.setImageBitmap(qrBitmap(url));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(300), px(300));
                lp.bottomMargin = px(24);
                list.addView(qr, lp);
            }
        }
        info(Tr.t("Or type them with the remote:"));
        keyField(Tr.t("TMDB key (the short 32-character API Key is easiest)"), "tmdb_key");
        keyField(Tr.t("Watchmode key"), "watchmode_key");
        keyField(Tr.t("Plex server address (https://….plex.direct:32400)"), "plex_url");
        keyField(Tr.t("Plex token"), "plex_token");
        row(Tr.t("Remove all keys"), () -> "", () -> confirm(Tr.t("Press OK again to remove every key from the TV."), () -> {
            for (String k : new String[] {"tmdb_key", "watchmode_key", "plex_url", "plex_token"}) Prefs.setSecret(this, k, "");
            Picks.list.clear();
            say(Tr.t("Keys removed."));
            refresh();
        }));
        info("TMDB: themoviedb.org/settings/api · Watchmode: api.watchmode.com · " + Tr.t("This product uses the TMDB API but is not endorsed or certified by TMDB."));
    }

    void keyField(String hint, final String key) {
        final EditText f = field(hint + (Prefs.secret(this, key).isEmpty() ? "" : "  ✓"), true);
        f.setOnEditorActionListener((v, id, ev) -> {
            final String val = f.getText().toString().trim();
            if (val.isEmpty()) return true;
            say(Tr.t("Checking…"));
            Net.pool.execute(() -> {
                boolean ok = key.equals("tmdb_key") ? Picks.checkTmdb(val) : key.equals("watchmode_key") ? Picks.checkWatchmode(val)
                        : !key.equals("plex_url") || Net.plexUrlOk(val);
                TvControl.main.post(() -> {
                    if (!ok) { say(Tr.t("That key wasn't accepted.")); return; }
                    Prefs.setSecret(this, key, val);
                    f.setText("");
                    Picks.fetchedAt = 0;
                    say(Tr.t("Saved."));
                    refresh();
                });
            });
            return true;
        });
    }

    void recentsPage() {
        heading.setText(Tr.t("Recent apps"));
        if (!TvControl.hasUsageAccess(this)) { say(Tr.t(TvControl.NEEDS)); return; }
        List<String> pkgs = TvControl.recents(this);
        if (pkgs.isEmpty()) { say(Tr.t("No apps opened recently.")); return; }
        say(Tr.t("Apps you used in the last 12 hours. Up/down to pick one, OK to open it, ▶ to close it so it starts fresh next time."));
        for (final String p : pkgs) {
            final View[] me = new View[1];
            me[0] = rowView(Picks.label(this, p), Tr.t("▶ Close"), () -> {
                Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(p);
                if (i == null) i = getPackageManager().getLaunchIntentForPackage(p);
                if (i != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); }
            });
            me[0].setOnKeyListener((x, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN || code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                TvControl.close(this, p);
                int i = list.indexOfChild(me[0]);
                list.removeView(me[0]);
                if (list.getChildCount() > 0) list.getChildAt(Math.min(i, list.getChildCount() - 1)).requestFocus();
                else say(Tr.t("No apps opened recently."));
                return true;
            });
            list.addView(me[0]);
        }
    }

    // ---- Rows ----

    void toggle(String title, final String key, final boolean def, final Runnable after) {
        row(title, () -> Prefs.on(this, key, def) ? Tr.t("On") : Tr.t("Off"), () -> {
            Prefs.set(this, key, !Prefs.on(this, key, def));
            if (after != null) after.run();
            refresh();
        });
    }

    void cycle(String title, final String key, final String[] values, final String[] names, final Runnable after) {
        row(title, () -> {
            String cur = Prefs.str(this, key, values[0]);
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) return names[i];
            return names[0];
        }, () -> {
            String cur = Prefs.str(this, key, values[0]);
            int i = 0;
            for (int k = 0; k < values.length; k++) if (values[k].equals(cur)) i = k;
            Prefs.set(this, key, values[(i + 1) % values.length]);
            if (after != null) after.run();
            refresh();
        });
    }

    void page(String title, Value value, final Runnable page) {
        row(title, () -> { String v = value.get(); return (v.isEmpty() ? "" : v + "  ") + "›"; }, () -> open(page));
    }

    void row(String title, Value value, Runnable action) {
        list.addView(rowView(title, value.get(), action));
    }

    void confirm(String message, Runnable action) {
        if (armed == action) return;
        say(message);
        armed = action;
    }

    View rowView(String title, String value, final Runnable action) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(36), 0, px(36), 0);
        r.setFocusable(true);
        r.setBackground(round(rowBg, 22));
        final TextView t = text(bold, 30, fg);
        t.setText(title);
        t.setSingleLine(true);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView v = text(regular, 28, fgDim);
        v.setText(value);
        v.setSingleLine(true);
        r.addView(v);
        r.setOnFocusChangeListener((x, has) -> {
            r.setBackground(round(has ? (light ? 0xFF111114 : Color.WHITE) : rowBg, 22));
            t.setTextColor(has ? (light ? Color.WHITE : Color.BLACK) : fg);
            v.setTextColor(has ? (light ? 0xCCFFFFFF : 0x99000000) : fgDim);
            if (!has && armed != null) armed = null;
        });
        r.setOnClickListener(x -> {
            if (armed != null && note.getText().length() > 0) { Runnable a = armed; armed = null; a.run(); return; }
            action.run();
        });
        r.setOnKeyListener((x, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT)
                    && !value.contains("›")) { action.run(); return true; }
            return false;
        });
        r.setOnHoverListener((x, e) -> { if (e.getAction() == android.view.MotionEvent.ACTION_HOVER_ENTER) r.requestFocus(); return false; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(86));
        lp.bottomMargin = px(12);
        r.setLayoutParams(lp);
        return r;
    }

    EditText field(String hint, boolean secret) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setHintTextColor(fgDim);
        f.setTextColor(fg);
        f.setTypeface(regular);
        f.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28));
        f.setSingleLine(true);
        f.setImeOptions(EditorInfo.IME_ACTION_DONE);
        f.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : 0));
        f.setTypeface(regular); // the input type above switches to a monospace font; keep Inter
        f.setPadding(px(36), 0, px(36), 0);
        f.setBackground(round(rowBg, 22));
        f.setOnFocusChangeListener((x, has) -> {
            GradientDrawable g = round(rowBg, 22);
            if (has) g.setStroke(px(3), fg);
            f.setBackground(g);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(86));
        lp.bottomMargin = px(12);
        list.addView(f, lp);
        return f;
    }

    void info(String s) {
        TextView t = text(regular, 24, fgDim);
        t.setText(s);
        t.setPadding(px(8), px(18), px(8), px(10));
        list.addView(t);
    }

    void say(String s) {
        note.setText(s);
    }

    Bitmap qrBitmap(String text) {
        boolean[][] m = Qr.encode(text);
        int n = m.length, q = 4, scale = 8, size = (n + 2 * q) * scale;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (m[y][x]) {
            for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++) b.setPixel((x + q) * scale + dx, (y + q) * scale + dy, Color.BLACK);
        }
        return b;
    }

    GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(r));
        return g;
    }

    TextView text(Typeface f, float size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(f);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setIncludeFontPadding(false);
        return t;
    }

    int px(float v) { return Math.round(v * s); }
}
