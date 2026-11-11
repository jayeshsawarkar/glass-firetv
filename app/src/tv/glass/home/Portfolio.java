package tv.glass.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

// Your stocks, live, in the header: what it's worth now, today's gain or loss, the day as a thin line, and one
// small bar per day since Glass started tracking. Prices come from Finnhub (a free key, sent from your phone like
// the TMDB key): once a minute while the US market is open, every 15 minutes otherwise. Your holdings and the
// daily record stay in Glass's private storage on the TV; only the ticker symbols go to Finnhub.
final class Portfolio {
    private Portfolio() {}

    static final class Holding { String symbol; double shares, cost; }
    static final class Quote { double price, prevClose; long time; }
    static final class Day { String date; double value, pl; }

    static final TimeZone NY = TimeZone.getTimeZone("America/New_York");

    static final Map<String, Quote> quotes = new HashMap<>();
    static final List<long[]> samples = new ArrayList<>(); // today's {time, value in cents}, for the line
    static String samplesDate = "";
    static long fetchedAt;
    static boolean loading;
    static String error = "";

    interface Ready { void ready(); }

    // ---- Holdings: "SYMBOL SHARES COST" per line, "CASH 50", # comments ----

    static List<Holding> holdings(Context c) {
        List<Holding> out = new ArrayList<>();
        for (String line : Prefs.str(c, "pf_holdings", "").split("\n")) {
            String[] f = line.trim().split("[\\s,]+");
            if (f.length < 2 || f[0].startsWith("#") || f[0].equalsIgnoreCase("CASH")) continue;
            try {
                Holding h = new Holding();
                h.symbol = f[0].toUpperCase(Locale.US);
                h.shares = Double.parseDouble(f[1]);
                h.cost = f.length > 2 ? Double.parseDouble(f[2].replace("$", "")) : 0;
                if (h.symbol.matches("[A-Z][A-Z0-9.\\-]{0,9}") && h.shares > 0) out.add(h);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    static double cash(Context c) {
        for (String line : Prefs.str(c, "pf_holdings", "").split("\n")) {
            String[] f = line.trim().split("[\\s,]+");
            if (f.length >= 2 && f[0].equalsIgnoreCase("CASH")) try { return Double.parseDouble(f[1].replace("$", "")); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    // Checks the text and keeps it; returns null if fine, or what's wrong.
    static String setHoldings(Context c, String text) {
        String t = text == null ? "" : text.trim();
        Prefs.set(c, "pf_holdings", t);
        if (!t.isEmpty() && holdings(c).isEmpty()) return Tr.t("No holdings found. One per line: AAPL 10 1500");
        quotes.clear();
        fetchedAt = 0;
        return null;
    }

    static boolean on(Context c) { return Prefs.on(c, "portfolio", true) && !holdings(c).isEmpty(); }
    static boolean hideAmounts(Context c) { return Prefs.on(c, "pf_hide", false); }
    static boolean hasKey(Context c) { return !Prefs.secret(c, "finnhub_key").isEmpty(); }

    // ---- The numbers ----

    static final class Totals {
        double value, prevValue, dayPl, dayPct, cost, totalPl, totalPct, sinceStart;
        String since = "";
        boolean complete; // every holding has a price
    }

    static Totals totals(Context c) {
        Totals t = new Totals();
        double cash = cash(c);
        t.complete = true;
        double invested = 0;
        for (Holding h : holdings(c)) {
            Quote q = quotes.get(h.symbol);
            if (q == null) { t.complete = false; continue; }
            t.value += h.shares * q.price;
            t.prevValue += h.shares * q.prevClose;
            invested += h.cost;
        }
        t.dayPl = t.value - t.prevValue;
        t.dayPct = t.prevValue > 0 ? t.dayPl / t.prevValue * 100 : 0;
        t.totalPl = t.value - invested;
        t.cost = invested + cash; // as brokers show it: cash counts in the total
        t.totalPct = t.cost > 0 ? t.totalPl / t.cost * 100 : 0;
        t.value += cash;
        List<Day> days = days(c);
        for (Day d : days) t.sinceStart += d.pl;
        if (!days.isEmpty()) t.since = days.get(0).date;
        return t;
    }

    // ---- Fetching ----

    static boolean marketOpen() {
        Calendar n = Calendar.getInstance(NY);
        int dow = n.get(Calendar.DAY_OF_WEEK), mins = n.get(Calendar.HOUR_OF_DAY) * 60 + n.get(Calendar.MINUTE);
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && mins >= 9 * 60 + 30 && mins < 16 * 60;
    }

    static long interval() { return marketOpen() ? 60_000L : 15 * 60_000L; }

    static void refresh(Context c, boolean force, final Ready ready) {
        final Context app = c.getApplicationContext();
        if (loading || !on(app) || !hasKey(app)) return;
        if (!force && System.currentTimeMillis() - fetchedAt < interval()) return;
        loading = true;
        final List<Holding> hs = holdings(app);
        final String key = Prefs.secret(app, "finnhub_key");
        Net.pool.execute(() -> {
            Map<String, Quote> got = new HashMap<>();
            String err = "";
            for (Holding h : hs) {
                try {
                    JSONObject o = new JSONObject(Net.get("https://finnhub.io/api/v1/quote?symbol=" + Net.enc(h.symbol) + "&token=" + Net.enc(key), null));
                    Quote q = new Quote();
                    q.price = o.optDouble("c", 0);
                    q.prevClose = o.optDouble("pc", 0);
                    q.time = o.optLong("t", 0) * 1000;
                    if (q.price > 0) got.put(h.symbol, q);
                    else err = Tr.t("No price for {n}", h.symbol);
                } catch (Exception e) {
                    err = e.getMessage() != null && e.getMessage().contains("401") ? Tr.t("Finnhub didn't accept the key.") : Tr.t("Couldn't reach Finnhub.");
                }
            }
            final String e2 = err;
            TvControl.main.post(() -> {
                quotes.putAll(got);
                fetchedAt = System.currentTimeMillis();
                loading = false;
                error = got.isEmpty() ? e2 : "";
                if (!got.isEmpty()) record(app);
                if (ready != null) ready.ready();
            });
        });
    }

    static boolean checkKey(String key) {
        try {
            JSONObject o = new JSONObject(Net.get("https://finnhub.io/api/v1/quote?symbol=AAPL&token=" + Net.enc(key), null));
            return o.optDouble("c", 0) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- The record: today's line, and one result per trading day ----

    static String today() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(NY);
        return f.format(new java.util.Date());
    }

    // The trading day the latest prices belong to (Friday's, all weekend).
    static String quoteDay() {
        long t = 0;
        for (Quote q : quotes.values()) t = Math.max(t, q.time);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(NY);
        return f.format(new java.util.Date(t > 0 ? t : System.currentTimeMillis()));
    }

    static void record(Context c) {
        Totals t = totals(c);
        if (!t.complete) return;
        String day = quoteDay();
        // Today's line: a point at each update, kept across restarts for the same day.
        loadSamples(c, day);
        long now = System.currentTimeMillis();
        long cents = Math.round(t.value * 100);
        // Only while the market is open: the line is the trading day, 9:30 to 16:00.
        if (marketOpen() && (samples.isEmpty() || samples.get(samples.size() - 1)[1] != cents || now - samples.get(samples.size() - 1)[0] > 10 * 60_000L)) {
            samples.add(new long[] {now, cents});
            while (samples.size() > 500) samples.remove(1);
            saveSamples(c, day);
        }
        // The day's result: overwritten with each update, so the last one of the day is its close.
        List<Day> days = days(c);
        Day d = days.isEmpty() ? null : days.get(days.size() - 1);
        if (d == null || !d.date.equals(day)) { d = new Day(); d.date = day; days.add(d); }
        d.value = t.value;
        d.pl = t.dayPl;
        while (days.size() > 400) days.remove(0);
        saveDays(c, days);
    }

    static void loadSamples(Context c, String day) {
        if (day.equals(samplesDate)) return;
        samples.clear();
        samplesDate = day;
        try {
            JSONObject o = new JSONObject(Prefs.str(c, "pf_samples", "{}"));
            if (!day.equals(o.optString("d"))) return;
            JSONArray a = o.getJSONArray("s");
            for (int i = 0; i + 1 < a.length(); i += 2) samples.add(new long[] {a.getLong(i), a.getLong(i + 1)});
        } catch (Exception ignored) {}
    }

    static void saveSamples(Context c, String day) {
        try {
            JSONArray a = new JSONArray();
            for (long[] s : samples) { a.put(s[0]); a.put(s[1]); }
            Prefs.set(c, "pf_samples", new JSONObject().put("d", day).put("s", a).toString());
        } catch (Exception ignored) {}
    }

    static List<Day> days(Context c) {
        List<Day> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.str(c, "pf_days", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Day d = new Day();
                d.date = o.getString("d");
                d.value = o.getDouble("v");
                d.pl = o.getDouble("p");
                out.add(d);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void saveDays(Context c, List<Day> days) {
        try {
            JSONArray a = new JSONArray();
            for (Day d : days) a.put(new JSONObject().put("d", d.date).put("v", d.value).put("p", d.pl));
            Prefs.set(c, "pf_days", a.toString());
        } catch (Exception ignored) {}
    }

    // ---- Words ----

    static String money(double v) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        return f.format(v);
    }

    static String signedMoney(double v) {
        return (v >= 0 ? "+" : "−") + money(Math.abs(v));
    }

    static String pct(double v) {
        return (v >= 0 ? "+" : "−") + String.format(Locale.US, "%.2f%%", Math.abs(v));
    }

    static String price(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    // "Nov 7" for the start of the record.
    static String shortDate(String isoDay) {
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return android.text.format.DateFormat.format("MMM d", in.parse(isoDay)).toString();
        } catch (Exception e) {
            return isoDay;
        }
    }

    static String updated(Context c) {
        long t = 0;
        for (Quote q : quotes.values()) t = Math.max(t, q.time);
        if (t == 0) return "";
        String f = android.text.format.DateFormat.is24HourFormat(c) ? "EEE H:mm" : "EEE h:mm a";
        return android.text.format.DateFormat.format(f, t).toString();
    }
}
