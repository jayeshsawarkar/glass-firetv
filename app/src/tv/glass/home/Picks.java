package tv.glass.home;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

// The row above your apps: what you left halfway (Continue Watching, from the TV's own Watch Next list),
// the newest on your Plex server, and trending picks from TMDB, only from streaming apps that are on
// this TV and only from the services you leave switched on. Refreshed every hour.
final class Picks {
    private Picks() {}

    static final class Pick {
        String kind; // "continue", "plex", "pick" or "news"
        String summary, url; // news: a short summary and the story's address
        String title, subtitle, pkg, service;
        String image; // https:// for TMDB and Plex, content:// or android.resource:// for Continue Watching
        String poster; // TMDB only: the tall poster, shown while the card isn't focused
        Bitmap bitmap, posterBitmap, blurBitmap;
        int tmdbId;
        String mediaType; // movie or tv
        String intentUri; // Continue Watching: the app's own intent
        String plexKey;
        float progress = -1;
    }

    interface Ready { void ready(); }

    // TMDB's streaming-service ids and their Fire TV apps.
    static final Map<Integer, String[]> SERVICES = new LinkedHashMap<>();
    static {
        SERVICES.put(8, new String[] {"Netflix", "com.netflix.ninja"});
        SERVICES.put(9, new String[] {"Prime Video", "com.amazon.firebat"});
        SERVICES.put(119, new String[] {"Prime Video", "com.amazon.firebat"});
        SERVICES.put(337, new String[] {"Disney+", "com.disney.disneyplus"});
        SERVICES.put(15, new String[] {"Hulu", "com.hulu.plus"});
        SERVICES.put(1899, new String[] {"Max", "com.wbd.stream"});
        SERVICES.put(384, new String[] {"Max", "com.wbd.stream"});
        SERVICES.put(350, new String[] {"Apple TV+", "com.apple.atve.amazon.appletv"});
        SERVICES.put(2, new String[] {"Apple TV", "com.apple.atve.amazon.appletv"});
        SERVICES.put(531, new String[] {"Paramount+", "com.cbs.ott"});
        SERVICES.put(386, new String[] {"Peacock", "com.peacocktv.peacockandroid"});
        SERVICES.put(387, new String[] {"Peacock", "com.peacocktv.peacockandroid"});
        SERVICES.put(73, new String[] {"Tubi", "com.tubitv"});
        SERVICES.put(283, new String[] {"Crunchyroll", "com.crunchyroll.crunchyroid"});
        SERVICES.put(300, new String[] {"Pluto TV", "tv.pluto.android"});
        SERVICES.put(43, new String[] {"Starz", "com.bydeluxe.d3.android.program.starz"});
        SERVICES.put(613, new String[] {"Prime Video", "com.amazon.firebat"}); // Freevee is part of Prime Video now
    }

    static final List<Pick> list = new ArrayList<>();
    // The Movies and Shows tabs: fetched when first opened, then hourly.
    static final Map<String, List<Pick>> tabs = new HashMap<>();
    static final Map<String, Long> tabFetchedAt = new HashMap<>();
    static final java.util.Set<String> tabLoading = new java.util.HashSet<>();
    static long fetchedAt;
    static boolean loading;
    static String lastError = "";

    static boolean installed(PackageManager pm, String p) {
        try { pm.getPackageInfo(p, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // Services you could get picks from: their app is on the TV.
    static List<String> availableServices(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String[] s : SERVICES.values()) if (installed(pm, s[1]) && !out.contains(s[0])) out.add(s[0]);
        return out;
    }

    static boolean serviceOn(Context c, String name) {
        return !(("," + Prefs.str(c, "picks_off", "") + ",").contains("," + name + ","));
    }

    static void refresh(final Context c, boolean force, final Ready ready) {
        final Context app = c.getApplicationContext();
        if (loading) return;
        // Once an hour after picks came back; after an error or an empty result, again in 5 minutes.
        // Changing a setting resets fetchedAt.
        long wait = list.isEmpty() || !lastError.isEmpty() ? 5 * 60 * 1000 : 60 * 60 * 1000;
        if (!force && fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < wait) return;
        loading = true;
        Net.pool.execute(() -> {
            List<Pick> out = new ArrayList<>();
            String err = "";
            if (Prefs.on(app, "continue", false)) out.addAll(continueWatching(app));
            if (Prefs.on(app, "picks", true)) {
                try { out.addAll(plex(app)); } catch (Exception e) { /* server off: its titles are left out */ }
                String key = Prefs.secret(app, "tmdb_key");
                if (key.isEmpty()) err = "nokey";
                else try { out.addAll(tmdb(app, key, null)); } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage(); // never the key: URLs aren't in these messages
                    android.util.Log.w("GlassPicks", "TMDB failed: " + err);
                }
            }
            for (Pick p : out) loadImages(app, p);
            final String e2 = err == null ? "" : err;
            TvControl.main.post(() -> {
                list.clear();
                for (Pick p : out) if (p.bitmap != null || p.kind.equals("continue")) list.add(p);
                android.util.Log.i("GlassPicks", "picks: " + list.size() + " of " + out.size() + (e2.isEmpty() ? "" : " (" + e2 + ")"));
                fetchedAt = System.currentTimeMillis();
                lastError = e2;
                loading = false;
                if (ready != null) ready.ready();
            });
        });
    }

    static Map<String, String> tmdbAuth(String key) {
        Map<String, String> h = new HashMap<>();
        if (key.length() > 40) h.put("Authorization", "Bearer " + key);
        return h;
    }

    static String tmdbUrl(String key, String path) {
        String u = "https://api.themoviedb.org/3" + path;
        if (key.length() <= 40) u += (u.contains("?") ? "&" : "?") + "api_key=" + Net.enc(key);
        return u;
    }

    // Checks a key with TMDB before it's kept.
    static boolean checkTmdb(String key) {
        try {
            new JSONObject(Net.get(tmdbUrl(key, "/configuration"), tmdbAuth(key)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // One "discover" request per streaming service (what's popular on it in your country right now), so a
    // whole refresh is a handful of small requests: 3 picks from 3 services is 3 requests plus 3 pictures.
    // The service, its app and a different title each hour come from where the title was found.
    static List<Pick> tmdb(Context c, String key, String only) throws Exception {
        PackageManager pm = c.getPackageManager();
        String region = Prefs.region();
        int want = Math.max(3, Math.min(6, Prefs.num(c, "picks_count", 3)));
        boolean noAnimation = Prefs.on(c, "hide_animation", false);
        Map<String, String> auth = tmdbAuth(key);

        // Installed, switched-on services, each with all its TMDB ids (Prime Video has several).
        LinkedHashMap<String, String[]> services = new LinkedHashMap<>(); // name -> {ids "9|119", package}
        for (Map.Entry<Integer, String[]> e : SERVICES.entrySet()) {
            String name = e.getValue()[0], pkg = e.getValue()[1];
            if (!installed(pm, pkg) || !serviceOn(c, name)) continue;
            String[] cur = services.get(name);
            services.put(name, new String[] {cur == null ? String.valueOf(e.getKey()) : cur[0] + "|" + e.getKey(), pkg});
        }
        List<Pick> out = new ArrayList<>();
        if (services.isEmpty()) return out;
        List<String> names = new ArrayList<>(services.keySet());
        long hour = System.currentTimeMillis() / 3600_000L;
        Collections.rotate(names, (int) (hour % names.size())); // a different service goes first each hour

        Map<String, JSONArray> fetched = new HashMap<>();
        List<Integer> used = new ArrayList<>();
        Exception last = null;
        for (int i = 0; out.size() < want && i < want * 2; i++) {
            String name = names.get(i % names.size());
            String[] svc = services.get(name);
            String type = only != null ? only : (i + hour) % 2 == 0 ? "movie" : "tv";
            String cacheKey = name + type;
            JSONArray results = fetched.get(cacheKey);
            if (results == null) {
                try {
                    results = new JSONObject(Net.get(tmdbUrl(key, "/discover/" + type + "?watch_region=" + region
                            + "&with_watch_providers=" + Net.enc(svc[0]) + "&with_watch_monetization_types=" + Net.enc("flatrate|free|ads")
                            + "&sort_by=popularity.desc&include_adult=false&page=1" + (noAnimation ? "&without_genres=16" : "")), auth))
                            .getJSONArray("results");
                } catch (Exception e) {
                    last = e;
                    continue;
                }
                fetched.put(cacheKey, results);
            }
            int n = Math.min(12, results.length());
            for (int k = 0; k < n; k++) {
                JSONObject o = results.getJSONObject((int) ((k + hour * 3 + i) % n));
                String backdrop = o.optString("backdrop_path", "");
                if (backdrop.isEmpty() || backdrop.equals("null") || used.contains(o.optInt("id"))) continue;
                used.add(o.getInt("id"));
                Pick p = new Pick();
                p.kind = "pick";
                p.title = o.optString(type.equals("movie") ? "title" : "name");
                String date = o.optString(type.equals("movie") ? "release_date" : "first_air_date", "");
                String year = date.length() >= 4 ? date.substring(0, 4) : "";
                double vote = o.optDouble("vote_average", 0);
                p.service = name;
                p.pkg = svc[1];
                p.subtitle = Tr.t(type.equals("movie") ? "Movie" : "Show") + (year.isEmpty() ? "" : " · " + year)
                        + (vote > 0 ? " · ★ " + String.format(java.util.Locale.US, "%.1f", vote) : "") + " · " + Tr.t("On {n}", name);
                p.image = "https://image.tmdb.org/t/p/w780" + backdrop; // big enough for the wide card, quick on slow Wi-Fi
                String poster = o.optString("poster_path", "");
                if (!poster.isEmpty() && !poster.equals("null")) p.poster = "https://image.tmdb.org/t/p/w342" + poster;
                p.tmdbId = o.getInt("id");
                p.mediaType = type;
                out.add(p);
                break;
            }
        }
        if (out.isEmpty() && last != null) throw last;
        return out;
    }

    static boolean hasGenre(JSONObject o, int g) {
        JSONArray a = o.optJSONArray("genre_ids");
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (a.optInt(i) == g) return true;
        return false;
    }

    // ---- Plex: one of the newest movies and one of the newest shows ----

    static List<Pick> plex(Context c) throws Exception {
        List<Pick> out = new ArrayList<>();
        String base = Prefs.secret(c, "plex_url"), token = Prefs.secret(c, "plex_token");
        if (!Net.plexUrlOk(base) || token.isEmpty() || !installed(c.getPackageManager(), "com.plexapp.android")) return out;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        Map<String, String> h = new HashMap<>();
        h.put("X-Plex-Token", token);
        h.put("Accept", "application/json");
        String machine = new JSONObject(Net.get(base + "/identity", h)).getJSONObject("MediaContainer").optString("machineIdentifier");
        JSONArray items = new JSONObject(Net.get(base + "/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=24", h))
                .getJSONObject("MediaContainer").optJSONArray("Metadata");
        if (items == null) return out;
        List<JSONObject> movies = new ArrayList<>(), shows = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject m = items.getJSONObject(i);
            String type = m.optString("type");
            if (type.equals("movie") && movies.size() < 8) movies.add(m);
            else if ((type.equals("season") || type.equals("show") || type.equals("episode")) && shows.size() < 8) shows.add(m);
        }
        Random rnd = new Random(System.currentTimeMillis() / (4 * 3600_000L));
        for (List<JSONObject> group : java.util.Arrays.asList(movies, shows)) {
            if (group.isEmpty()) continue;
            JSONObject m = group.get(rnd.nextInt(group.size()));
            String art = m.optString("art", m.optString("grandparentArt", ""));
            if (art.isEmpty()) continue;
            Pick p = new Pick();
            p.kind = "plex";
            boolean show = !m.optString("type").equals("movie");
            p.title = show ? m.optString("parentTitle", m.optString("grandparentTitle", m.optString("title"))) : m.optString("title");
            if (p.title.isEmpty()) p.title = m.optString("title");
            p.subtitle = Tr.t(show ? "Show" : "Movie") + " · " + Tr.t("New on your Plex");
            p.service = "Plex";
            p.pkg = "com.plexapp.android";
            p.image = base + art + (art.contains("?") ? "&" : "?") + "X-Plex-Token=" + Net.enc(token);
            String key = show && m.has("parentRatingKey") && m.optString("type").equals("episode") ? m.optString("grandparentRatingKey") : m.optString("ratingKey");
            p.plexKey = machine + "|" + key;
            out.add(p);
        }
        return out;
    }

    // ---- Continue Watching: Android TV's Watch Next list, read with READ_TV_LISTINGS ----

    static List<Pick> continueWatching(Context c) {
        List<Pick> out = new ArrayList<>();
        Uri uri = Uri.parse("content://android.media.tv/watch_next_program");
        String[] cols = {"title", "episode_title", "poster_art_uri", "thumbnail_uri", "intent_uri", "package_name",
                "last_playback_position_millis", "duration_millis", "watch_next_type"};
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, "last_engagement_time_utc_millis DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < 4) {
                Pick p = new Pick();
                p.kind = "continue";
                p.title = cur.getString(0);
                String ep = cur.getString(1);
                p.pkg = cur.getString(5);
                String poster = cur.getString(3) != null ? cur.getString(3) : cur.getString(2);
                // Pictures only from the TV itself, never from the network.
                if (poster != null && (poster.startsWith("content://") || poster.startsWith("android.resource://"))) p.image = poster;
                p.intentUri = cur.getString(4);
                long pos = cur.getLong(6), dur = cur.getLong(7);
                if (dur > 0) p.progress = Math.min(1f, (float) pos / dur);
                if (p.title == null || p.pkg == null || !installed(c.getPackageManager(), p.pkg)) continue;
                p.service = label(c, p.pkg);
                p.subtitle = Tr.t("Continue watching") + (ep == null || ep.isEmpty() ? "" : " · " + ep) + " · " + p.service;
                out.add(p);
            }
        } catch (Exception e) {
            // No permission yet, or no Watch Next list on this Fire OS: nothing to continue.
        }
        return out;
    }

    // How many entries Android's Watch Next list has (0 without permission or on Fire OS, where apps don't fill it).
    static int watchNextCount(Context c) {
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"), new String[] {"_id"}, null, null, null)) {
            return cur == null ? 0 : cur.getCount();
        } catch (Exception e) {
            return 0;
        }
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    static void loadImages(Context c, Pick p) {
        p.bitmap = loadImage(c, p.image);
        if (p.poster != null) p.posterBitmap = loadImage(c, p.poster);
        if (p.bitmap != null) p.blurBitmap = HomeScreen.blurred(p.bitmap);
    }

    // Movies or Shows tab: the same request as the picks, for one kind only.
    static void refreshTab(Context c, final String type, final Ready ready) {
        final Context app = c.getApplicationContext();
        Long at = tabFetchedAt.get(type);
        List<Pick> have = tabs.get(type);
        if (tabLoading.contains(type)) return;
        if (at != null && System.currentTimeMillis() - at < ((have == null || have.isEmpty()) ? 5 : 60) * 60 * 1000L) return;
        final String key = Prefs.secret(app, "tmdb_key");
        if (key.isEmpty() || !Prefs.on(app, "picks", true)) return;
        tabLoading.add(type);
        Net.pool.execute(() -> {
            List<Pick> out = new ArrayList<>();
            try { out = tmdb(app, key, type); } catch (Exception e) { android.util.Log.w("GlassPicks", type + " failed: " + e.getClass().getSimpleName()); }
            for (Pick p : out) loadImages(app, p);
            final List<Pick> done = new ArrayList<>();
            for (Pick p : out) if (p.bitmap != null) done.add(p);
            TvControl.main.post(() -> {
                tabs.put(type, done);
                tabFetchedAt.put(type, System.currentTimeMillis());
                tabLoading.remove(type);
                if (ready != null) ready.ready();
            });
        });
    }

    static Bitmap loadImage(Context c, String url) {
        if (url == null) return null;
        try {
            byte[] data;
            if (url.startsWith("https://")) {
                data = Net.bytes(url, null);
            } else {
                ContentResolver r = c.getContentResolver();
                try (InputStream in = r.openInputStream(Uri.parse(url))) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
                    data = o.toByteArray();
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 1280) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return News.fit(BitmapFactory.decodeByteArray(data, 0, data.length, o), 1040);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Opening a pick ----

    interface Opener { void open(Intent i, String fallbackPkg); }

    static void open(final Context c, final Pick p, final Opener opener) {
        if (p.kind.equals("news")) { opener.open(News.open(p), p.pkg); return; }
        if (p.kind.equals("continue") && p.intentUri != null) {
            try {
                Intent i = Intent.parseUri(p.intentUri, Intent.URI_INTENT_SCHEME);
                i.setSelector(null);
                i.setComponent(null);
                i.setPackage(p.pkg); // only ever the app that wrote the entry
                opener.open(i, p.pkg);
                return;
            } catch (Exception ignored) {}
        }
        if (p.kind.equals("plex") && p.plexKey != null) {
            String[] mk = p.plexKey.split("\\|");
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("plex://preplay/?metadataKey=" + Net.enc("/library/metadata/" + mk[1])
                    + "&server=" + Net.enc(mk[0]))).setPackage(p.pkg);
            opener.open(i, p.pkg);
            return;
        }
        final String wm = Prefs.secret(c, "watchmode_key");
        if (wm.isEmpty() || p.tmdbId == 0) { opener.open(null, p.pkg); return; }
        final String cacheKey = "wm_" + p.mediaType + p.tmdbId + "_" + p.service;
        String cached = Prefs.str(c, cacheKey, null);
        if (cached != null) { opener.open(cached.isEmpty() ? null : view(cached, p.pkg), p.pkg); return; }
        Net.pool.execute(() -> {
            String url = "";
            try { url = watchmodeUrl(wm, p); } catch (Exception ignored) {}
            final String u = url == null ? "" : url;
            Prefs.set(c, cacheKey, u);
            TvControl.main.post(() -> opener.open(u.isEmpty() ? null : view(u, p.pkg), p.pkg));
        });
    }

    static Intent view(String url, String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg);
    }

    // Watchmode: TMDB id -> Watchmode title -> the service's own web address for it, which the app opens on the title's page.
    static String watchmodeUrl(String key, Pick p) throws Exception {
        String field = p.mediaType.equals("movie") ? "tmdb_movie_id" : "tmdb_tv_id";
        JSONArray r = new JSONObject(Net.get("https://api.watchmode.com/v1/search/?apiKey=" + Net.enc(key)
                + "&search_field=" + field + "&search_value=" + p.tmdbId, null)).optJSONArray("title_results");
        if (r == null || r.length() == 0) return "";
        int id = r.getJSONObject(0).getInt("id");
        JSONArray s = new JSONArray(Net.get("https://api.watchmode.com/v1/title/" + id + "/sources/?apiKey=" + Net.enc(key)
                + "&regions=" + Prefs.region(), null));
        String want = p.service.toLowerCase().replace("+", "").replace(" ", "");
        for (int i = 0; i < s.length(); i++) {
            JSONObject o = s.getJSONObject(i);
            String name = o.optString("name").toLowerCase().replace("+", "").replace(" ", "");
            String web = o.optString("web_url", "");
            if ((name.startsWith(want) || want.startsWith(name)) && web.startsWith("https://")) return web;
        }
        return "";
    }

    static boolean checkWatchmode(String key) {
        try {
            new JSONObject(Net.get("https://api.watchmode.com/v1/status/?apiKey=" + Net.enc(key), null));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
