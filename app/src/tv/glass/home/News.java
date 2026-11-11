package tv.glass.home;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Html;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The news row on Home: headlines with a picture and a short summary, for the topics you pick in
// Glass settings > News, from the sources you leave on. Hacker News (its public API), Reddit (its RSS feeds:
// Reddit's JSON needs an account now) and newspapers' and sites' own RSS feeds. No keys, no accounts.
// Where a story comes without a picture or a summary (Hacker News, some feeds), Glass reads the article page's
// own preview tags (og:image, og:description), the same ones a chat app shows when you paste a link.
// Each topic is fetched when its tab first shows, then every 30 minutes.
final class News {
    private News() {}

    static final class Source {
        final String id, name, type, url; // type: hn, rss
        Source(String id, String name, String type, String url) { this.id = id; this.name = name; this.type = type; this.url = url; }
    }

    static final class Topic {
        final String id, name;
        final Source[] sources;
        Topic(String id, String name, Source... s) { this.id = id; this.name = name; sources = s; }
    }

    static Source hn() { return new Source("hn", "Hacker News", "hn", "https://hacker-news.firebaseio.com/v0/topstories.json"); }
    static Source reddit(String sub) { return new Source("r/" + sub, "r/" + sub, "rss", "https://www.reddit.com/r/" + sub + "/.rss"); }
    static Source rss(String id, String name, String url) { return new Source(id, name, "rss", url); }

    static final Topic[] TOPICS = {
        new Topic("trending", "Trending", hn(), reddit("popular")),
        new Topic("tech", "Tech", hn(), rss("verge", "The Verge", "https://www.theverge.com/rss/index.xml"),
                rss("ars", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
                rss("techcrunch", "TechCrunch", "https://techcrunch.com/feed/"),
                rss("bbc-tech", "BBC", "https://feeds.bbci.co.uk/news/technology/rss.xml"), reddit("technology")),
        new Topic("ai", "AI", rss("mittr", "MIT Technology Review", "https://www.technologyreview.com/feed/"),
                reddit("artificial"), reddit("MachineLearning")),
        new Topic("world", "World", rss("bbc-world", "BBC", "https://feeds.bbci.co.uk/news/world/rss.xml"),
                rss("nyt-world", "The New York Times", "https://rss.nytimes.com/services/xml/rss/nyt/World.xml"),
                rss("aljazeera", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"), reddit("worldnews")),
        new Topic("us", "U.S.", rss("nyt-us", "The New York Times", "https://rss.nytimes.com/services/xml/rss/nyt/US.xml"),
                rss("npr", "NPR", "https://feeds.npr.org/1001/rss.xml"), reddit("news")),
        new Topic("business", "Business", rss("cnbc", "CNBC", "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114"),
                rss("bbc-business", "BBC", "https://feeds.bbci.co.uk/news/business/rss.xml"), reddit("economics")),
        new Topic("science", "Science", rss("bbc-science", "BBC", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml"),
                rss("sciencedaily", "ScienceDaily", "https://www.sciencedaily.com/rss/top/science.xml"), reddit("science")),
        new Topic("space", "Space", rss("nasa", "NASA", "https://www.nasa.gov/news-release/feed/"),
                rss("spacecom", "Space.com", "https://www.space.com/feeds.xml"), reddit("space")),
        new Topic("health", "Health", rss("bbc-health", "BBC", "https://feeds.bbci.co.uk/news/health/rss.xml"), reddit("health")),
        new Topic("sports", "Sports", rss("espn", "ESPN", "https://www.espn.com/espn/rss/news"),
                rss("bbc-sport", "BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml"), reddit("sports")),
        new Topic("entertainment", "Entertainment", rss("variety", "Variety", "https://variety.com/feed/"),
                rss("bbc-ent", "BBC", "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml"), reddit("movies"), reddit("television")),
        new Topic("gaming", "Gaming", rss("ign", "IGN", "https://feeds.feedburner.com/ign/all"),
                rss("polygon", "Polygon", "https://www.polygon.com/feed/"), reddit("gaming")),
    };

    static final int PER_TOPIC = 8, PER_SOURCE = 4;
    static final String UA = "android:tv.glass.home:2.0 (Glass home screen for Fire TV)";

    static final Map<String, List<Picks.Pick>> stories = new HashMap<>();
    // Own threads, so the news never waits behind the weather or the picks: one per topic being fetched, and four
    // more for the stories' pages and pictures, fetched side by side instead of one after another.
    static final java.util.concurrent.ExecutorService topics = java.util.concurrent.Executors.newFixedThreadPool(2);
    // Reddit refuses apps that ask too often (HTTP 429). So: one Reddit request every 4 seconds at most, a feed
    // fetched in the last 15 minutes is reused, and after a refusal Reddit is left alone for 10 minutes.
    static final Object redditGate = new Object();
    static long redditLast, redditPausedUntil;
    static final Map<String, Object[]> feedCache = new HashMap<>(); // url -> {time, List<Item>}
    static final Map<String, Long> fetchedAt = new HashMap<>();
    static final Set<String> loading = new HashSet<>();

    // ---- Settings ----

    static Topic topic(String id) {
        for (Topic t : TOPICS) if (t.id.equals(id)) return t;
        return null;
    }

    static List<Topic> chosen(Context c) {
        List<Topic> out = new ArrayList<>();
        for (String id : Prefs.str(c, "news_topics", "trending,tech").split(",")) {
            Topic t = topic(id);
            if (t != null && !out.contains(t)) out.add(t);
        }
        if (out.isEmpty()) out.add(TOPICS[0]);
        return out;
    }

    static boolean topicOn(Context c, String id) {
        return ("," + Prefs.str(c, "news_topics", "trending,tech") + ",").contains("," + id + ",");
    }

    static void setTopic(Context c, String id, boolean on) {
        List<String> ids = new ArrayList<>();
        for (Topic t : TOPICS) if (t.id.equals(id) ? on : topicOn(c, t.id)) ids.add(t.id); // keeps the catalog's order
        Prefs.set(c, "news_topics", android.text.TextUtils.join(",", ids));
        fetchedAt.remove(id);
    }

    // A source is off for one topic only, so turning BBC off under World leaves it on under Tech.
    static boolean sourceOn(Context c, String topic, String source) {
        return !("," + Prefs.str(c, "news_off", "") + ",").contains("," + topic + ":" + source + ",");
    }

    static void setSource(Context c, String topic, String source, boolean on) {
        List<String> off = new ArrayList<>();
        for (String x : Prefs.str(c, "news_off", "").split(",")) if (!x.isEmpty()) off.add(x);
        String key = topic + ":" + source;
        off.remove(key);
        if (!on) off.add(key);
        Prefs.set(c, "news_off", android.text.TextUtils.join(",", off));
        fetchedAt.remove(topic);
    }

    // ---- Fetching ----

    interface Ready { void ready(); }

    // Pictures cost memory: only the 3 topics looked at most recently keep their stories; the others load again
    // (from the network, in a few seconds) when their tab opens.
    static final List<String> recent = new ArrayList<>();

    static void keepRecent(String topic) {
        recent.remove(topic);
        recent.add(topic);
        while (recent.size() > 3) {
            String old = recent.remove(0);
            stories.remove(old);
            fetchedAt.remove(old);
        }
    }

    static List<Picks.Pick> get(String topic) {
        List<Picks.Pick> l = stories.get(topic);
        return l == null ? new ArrayList<>() : l;
    }

    static boolean known(String topic) { return stories.containsKey(topic); }

    static void refresh(Context c, final String topicId, boolean force, final Ready ready) {
        final Context app = c.getApplicationContext();
        final Topic t = topic(topicId);
        if (t == null || loading.contains(topicId)) return;
        Long at = fetchedAt.get(topicId);
        List<Picks.Pick> have = stories.get(topicId);
        long wait = (have == null || have.isEmpty() ? 5 : 30) * 60 * 1000L;
        if (!force && at != null && System.currentTimeMillis() - at < wait) return;
        loading.add(topicId);
        topics.execute(() -> {
            List<Picks.Pick> got = new ArrayList<>();
            try {
                got = fetch(app, t);
            } catch (Throwable e) {
                // Whatever went wrong (even running out of memory on a huge picture), "loading" must end, or the
                // tab says "Getting the news…" forever and never tries again.
                android.util.Log.w("GlassNews", t.id + " failed: " + e);
            }
            final List<Picks.Pick> out = got;
            TvControl.main.post(() -> {
                if (!out.isEmpty() || !stories.containsKey(topicId)) stories.put(topicId, out);
                keepRecent(topicId);
                fetchedAt.put(topicId, System.currentTimeMillis());
                loading.remove(topicId);
                android.util.Log.i("GlassNews", t.id + ": " + out.size() + " stories");
                if (ready != null) ready.ready();
            });
        });
    }

    static final class Item {
        String title, link, summary, image, source, article; // article: the story's own page (for Reddit link posts)
        long time;
    }

    static List<Picks.Pick> fetch(Context c, Topic t) {
        // A few from each source that's on, taken in turn, so no single source fills the row.
        List<List<Item>> perSource = new ArrayList<>();
        for (Source s : t.sources) {
            if (!sourceOn(c, t.id, s.id)) continue;
            try {
                perSource.add(s.type.equals("hn") ? hackerNews() : s.id.startsWith("r/") ? reddit(s) : feed(s));
            } catch (Exception e) {
                android.util.Log.w("GlassNews", s.id + " failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        List<Item> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < PER_SOURCE && merged.size() < PER_TOPIC; i++) {
            for (List<Item> l : perSource) {
                if (i >= l.size() || merged.size() >= PER_TOPIC) continue;
                Item it = l.get(i);
                String key = it.title.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
                if (it.link == null || seen.contains(key)) continue;
                seen.add(key);
                merged.add(it);
            }
        }
        android.util.Log.i("GlassNews", t.id + ": " + merged.size() + " headlines from " + perSource.size() + " sources");
        // Each story's preview and picture, four at a time; a story that isn't done in 40 seconds is left out.
        List<java.util.concurrent.Callable<Picks.Pick>> jobs = new ArrayList<>();
        for (final Item it : merged) jobs.add(() -> story(c, it));
        // Workers of this topic's own, so a topic never waits in line behind another one's pictures.
        List<Picks.Pick> out = new ArrayList<>();
        java.util.concurrent.ExecutorService own = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            for (java.util.concurrent.Future<Picks.Pick> f : own.invokeAll(jobs, 60, java.util.concurrent.TimeUnit.SECONDS)) {
                try {
                    if (f.isCancelled()) android.util.Log.w("GlassNews", t.id + ": a story took longer than 60 s");
                    else { Picks.Pick p = f.get(); if (p != null) out.add(p); }
                } catch (Exception e) {
                    android.util.Log.w("GlassNews", t.id + ": " + e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            own.shutdownNow();
        }
        // Stories with pictures first.
        List<Picks.Pick> sorted = new ArrayList<>();
        for (Picks.Pick p : out) if (p.bitmap != null) sorted.add(p);
        // Fewer than three with pictures: the rest come too, on the blurred wallpaper (see PickCard).
        if (sorted.size() < 3) for (Picks.Pick p : out) if (p.bitmap == null) sorted.add(p);
        return sorted;
    }

    static Picks.Pick story(Context c, Item it) {
        try {
            if (it.image == null || it.summary == null || it.summary.isEmpty()) preview(it);
            Picks.Pick p = new Picks.Pick();
            p.kind = "news";
            p.title = it.title;
            p.summary = it.summary == null ? "" : it.summary;
            p.url = it.link;
            p.service = it.source;
            p.subtitle = it.source + (it.time > 0 ? " · " + ago(it.time) : "");
            p.image = it.image;
            p.pkg = browser(c);
            p.bitmap = it.image == null ? null : picture(it.image);
            // Reddit often refuses its own pictures to apps without an account; a post that links to an article
            // can use the article's picture instead.
            if (p.bitmap == null && it.article != null && it.article.startsWith("http") && !it.article.contains("reddit.com")
                    && !it.article.contains("redd.it")) {
                Item a = new Item();
                a.link = it.article;
                preview(a);
                if (a.image != null) p.bitmap = picture(a.image);
                if (p.summary.isEmpty() && a.summary != null) p.summary = a.summary;
            }
            if (p.bitmap != null) p.blurBitmap = HomeScreen.blurred(p.bitmap);
            return p;
        } catch (Throwable e) {
            android.util.Log.w("GlassNews", "story dropped (" + it.source + "): " + e);
            return null; // one bad story never stops the rest
        }
    }

    // A story's picture: redirects followed (og:image addresses often redirect), at most 3 MB, decoded small.
    static android.graphics.Bitmap picture(String url) {
        try {
            byte[] data = fetchPublic(url, 3 * 1024 * 1024);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 1040) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            return fit(b, 1040);
        } catch (Throwable e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Item> reddit(Source s) throws Exception {
        synchronized (feedCache) {
            Object[] hit = feedCache.get(s.url);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 15 * 60_000L) return (List<Item>) hit[1];
        }
        synchronized (redditGate) {
            long now = System.currentTimeMillis();
            if (now < redditPausedUntil) throw new IOException("Reddit asked Glass to slow down; trying again later");
            long wait = redditLast + 4000 - now;
            if (wait > 0) Thread.sleep(wait);
            redditLast = System.currentTimeMillis();
        }
        try {
            List<Item> items = feed(s);
            synchronized (feedCache) { feedCache.put(s.url, new Object[] {System.currentTimeMillis(), items}); }
            return items;
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("429")) synchronized (redditGate) { redditPausedUntil = System.currentTimeMillis() + 10 * 60_000L; }
            throw e;
        }
    }

    // Hacker News: the front page's top stories (title, link, time). Pictures and summaries come from the pages.
    static List<Item> hackerNews() throws Exception {
        synchronized (feedCache) {
            Object[] hit = feedCache.get("hn");
            @SuppressWarnings("unchecked") List<Item> l = hit == null ? null : (List<Item>) hit[1];
            if (l != null && System.currentTimeMillis() - (Long) hit[0] < 10 * 60_000L) return l;
        }
        List<Item> got = hackerNewsFetch();
        synchronized (feedCache) { feedCache.put("hn", new Object[] {System.currentTimeMillis(), got}); }
        return got;
    }

    static List<Item> hackerNewsFetch() throws Exception {
        JSONArray ids = new JSONArray(Net.get("https://hacker-news.firebaseio.com/v0/topstories.json", null));
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < ids.length() && out.size() < PER_SOURCE; i++) {
            JSONObject o = new JSONObject(Net.get("https://hacker-news.firebaseio.com/v0/item/" + ids.getLong(i) + ".json", null));
            if (!"story".equals(o.optString("type")) || o.optString("title").isEmpty()) continue;
            Item it = new Item();
            it.title = o.optString("title");
            it.link = o.optString("url", "");
            if (it.link.isEmpty()) it.link = "https://news.ycombinator.com/item?id=" + o.getLong("id");
            it.article = it.link;
            it.time = o.optLong("time") * 1000;
            it.source = "Hacker News";
            out.add(it);
        }
        return out;
    }

    // RSS 2.0 and Atom, with the usual picture tags: media:thumbnail, media:content, enclosure, or an <img> in the text.
    static List<Item> feed(Source s) throws Exception {
        byte[] data = fetchPublic(s.url, 2 * 1024 * 1024);
        XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        x.setInput(new ByteArrayInputStream(data), null);
        List<Item> out = new ArrayList<>();
        Item it = null;
        String text = null;
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < PER_SOURCE * 2; ev = x.next()) {
            String n = x.getName();
            if (ev == XmlPullParser.START_TAG) {
                text = null;
                if (n.equals("item") || n.equals("entry")) { it = new Item(); it.source = s.name; continue; }
                if (it == null) continue;
                if (n.equals("link") && x.getAttributeValue(null, "href") != null) {
                    String rel = x.getAttributeValue(null, "rel");
                    if (rel == null || rel.equals("alternate")) it.link = x.getAttributeValue(null, "href");
                } else if (n.equals("media:thumbnail") && it.image == null) {
                    it.image = x.getAttributeValue(null, "url");
                } else if (n.equals("media:content") || n.equals("enclosure")) {
                    String type = x.getAttributeValue(null, "type"), medium = x.getAttributeValue(null, "medium"), u = x.getAttributeValue(null, "url");
                    boolean img = (type != null && type.startsWith("image")) || "image".equals(medium)
                            || (u != null && u.matches("(?i).*\\.(jpe?g|png|webp)(\\?.*)?"));
                    if (img && u != null) it.image = u; // media:content is usually bigger than the thumbnail
                }
            } else if (ev == XmlPullParser.TEXT) {
                text = text == null ? x.getText() : text + x.getText();
            } else if (ev == XmlPullParser.END_TAG && it != null) {
                if (n.equals("item") || n.equals("entry")) {
                    if (it.title != null && it.link != null) out.add(it);
                    it = null;
                } else if (text != null) {
                    String v = text.trim();
                    if (n.equals("title")) it.title = clean(v, 200);
                    else if (n.equals("link") && it.link == null && v.startsWith("http")) it.link = v;
                    else if (n.equals("description") || n.equals("summary") || n.equals("content") || n.equals("content:encoded")) {
                        if (it.image == null) it.image = firstImage(v);
                        if (s.id.startsWith("r/")) it.article = redditLink(v);
                        String sum = clean(v, 260);
                        if (s.id.startsWith("r/")) sum = sum.replaceAll("\\s*submitted by\\s+/u/\\S+.*$", "").trim();
                        if (it.summary == null || it.summary.length() < sum.length()) it.summary = sum;
                    } else if (n.equals("pubDate") || n.equals("published") || n.equals("updated") || n.equals("dc:date")) {
                        if (it.time == 0) it.time = parseDate(v);
                    }
                }
                text = null;
            }
        }
        return out;
    }

    static final Pattern IMG = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    static final Pattern REDDIT_LINK = Pattern.compile("<a href=\"([^\"]+)\">\\[link\\]</a>");

    static String firstImage(String html) {
        Matcher m = IMG.matcher(html);
        return m.find() ? Html.fromHtml(m.group(1)).toString() : null;
    }

    // A Reddit post's own link: the article it points to (or the post itself when it's text).
    static String redditLink(String html) {
        Matcher m = REDDIT_LINK.matcher(html);
        return m.find() ? Html.fromHtml(m.group(1)).toString() : null;
    }

    // Reads the story page's preview tags for a picture and a summary, if the feed didn't have them.
    static void preview(Item it) {
        String page = it.article != null ? it.article : it.link;
        if (page == null || !page.startsWith("http")) return;
        try {
            // The preview tags are in the page's <head>, near the top: 120 KB is plenty and quick on slow Wi-Fi.
            String html = new String(fetchPublic(page, 120 * 1024), "UTF-8");
            int head = html.toLowerCase(Locale.US).indexOf("</head>");
            if (head > 0) html = html.substring(0, head);
            if (it.image == null) it.image = meta(html, "og:image", "twitter:image");
            if (it.image != null && it.image.startsWith("//")) it.image = "https:" + it.image;
            if (it.image != null && it.image.startsWith("/")) it.image = new URL(new URL(page), it.image).toString();
            if (it.summary == null || it.summary.isEmpty()) {
                String d = meta(html, "og:description", "description");
                if (d != null) it.summary = clean(d, 260);
            }
        } catch (Exception ignored) {
        }
    }

    static String meta(String html, String... names) {
        for (String name : names) {
            Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(name) + "[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) {
                Matcher c = Pattern.compile("content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(m.group());
                if (c.find() && !c.group(1).isEmpty()) return Html.fromHtml(c.group(1)).toString();
            }
        }
        return null;
    }

    // Text from HTML, without tags, and cut at a word near max characters.
    static String clean(String s, int max) {
        String t = Html.fromHtml(s).toString().replace('￼', ' ').replaceAll("\\s+", " ").trim();
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max);
        return t.substring(0, cut > max / 2 ? cut : max).replaceAll("[,;:.\\-–—]$", "") + "…";
    }

    static long parseDate(String v) {
        String[] formats = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssZ"};
        for (String f : formats) {
            try {
                Date d = new SimpleDateFormat(f, Locale.US).parse(v);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    static String ago(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        if (m < 60) return Tr.t("{n} min ago", Math.max(1, m));
        if (m < 48 * 60) return Tr.t("{n} h ago", m / 60);
        return Tr.t("{n} days ago", m / (24 * 60));
    }

    // Public pages and feeds: redirects are followed (no key ever goes to these), up to `limit` bytes.
    static byte[] fetchPublic(String url, int limit) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/html, */*");
                int code = c.getResponseCode();
                if (code / 100 == 3) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without a location");
                    url = new URL(new URL(url), loc).toString();
                    if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IOException("bad redirect");
                    continue;
                }
                if (code / 100 != 2) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    for (int n; (n = in.read(b)) > 0 && out.size() < limit; ) out.write(b, 0, n);
                    return out.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    // ---- Opening a story ----

    static final String SILK = "com.amazon.cloud9";

    static String browser(Context c) {
        return Picks.installed(c.getPackageManager(), SILK) ? SILK : null;
    }

    static Intent open(Picks.Pick p) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(p.url));
        if (p.pkg != null) i.setPackage(p.pkg);
        return i;
    }

    // No wider than the card it's shown on: a 2000 px picture costs four times the memory of a 1040 px one.
    static android.graphics.Bitmap fit(android.graphics.Bitmap b, int maxW) {
        if (b == null || b.getWidth() <= maxW) return b;
        android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(b, maxW, Math.max(1, Math.round((float) b.getHeight() * maxW / b.getWidth())), true);
        if (s != b) b.recycle();
        return s;
    }

    // For the settings page: "BBC, NPR, r/news" (only the ones that are on).
    static String sourcesLine(Context c, Topic t) {
        LinkedHashMap<String, Boolean> names = new LinkedHashMap<>();
        for (Source s : t.sources) if (sourceOn(c, t.id, s.id)) names.put(s.name, true);
        return android.text.TextUtils.join(", ", names.keySet());
    }
}
