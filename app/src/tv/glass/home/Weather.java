package tv.glass.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

// The weather from Open-Meteo (no key, no account): now, the next hours and the next days, for the city in
// Home Settings. Fetched at most every 30 minutes, and only while the weather is switched on.
final class Weather {
    private Weather() {}

    static final class Hour { long time; double temp; int code; }
    static final class Day { long time; double hi, lo; int code; }

    static double temp = Double.NaN;
    static int code = -1;
    static long sunrise, sunset;
    static final List<Hour> hours = new ArrayList<>();
    static final List<Day> days = new ArrayList<>();
    static long fetchedAt;
    static String fetchedFor = "";
    static boolean loading;

    interface Ready { void ready(); }

    static boolean has() { return !Double.isNaN(temp); }

    static void refresh(final Context c, final Ready ready) {
        if (!Prefs.on(c, "weather", true)) return;
        final double lat = Prefs.lat(c), lon = Prefs.lon(c);
        final boolean f = Prefs.fahrenheit(c);
        final String key = lat + "," + lon + "," + f;
        if (loading || (key.equals(fetchedFor) && System.currentTimeMillis() - fetchedAt < 30 * 60 * 1000)) return;
        loading = true;
        Net.pool.execute(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset"
                        + "&timezone=auto&timeformat=unixtime&forecast_days=6"
                        + (f ? "&temperature_unit=fahrenheit" : "");
                JSONObject j = new JSONObject(Net.get(url, null));
                JSONObject cur = j.getJSONObject("current");
                List<Hour> hs = new ArrayList<>();
                JSONObject h = j.getJSONObject("hourly");
                JSONArray ht = h.getJSONArray("time"), htemp = h.getJSONArray("temperature_2m"), hcode = h.getJSONArray("weather_code");
                long now = System.currentTimeMillis();
                for (int i = 0; i < ht.length() && hs.size() < 6; i++) {
                    long t = ht.getLong(i) * 1000;
                    if (t + 3600_000 <= now) continue;
                    Hour x = new Hour();
                    x.time = t; x.temp = htemp.getDouble(i); x.code = hcode.getInt(i);
                    hs.add(x);
                }
                List<Day> ds = new ArrayList<>();
                JSONObject d = j.getJSONObject("daily");
                JSONArray dt = d.getJSONArray("time"), hi = d.getJSONArray("temperature_2m_max"), lo = d.getJSONArray("temperature_2m_min"),
                        dc = d.getJSONArray("weather_code"), rise = d.getJSONArray("sunrise"), set = d.getJSONArray("sunset");
                for (int i = 0; i < dt.length() && ds.size() < 5; i++) {
                    Day x = new Day();
                    x.time = dt.getLong(i) * 1000; x.hi = hi.getDouble(i); x.lo = lo.getDouble(i); x.code = dc.getInt(i);
                    ds.add(x);
                }
                final double t0 = cur.getDouble("temperature_2m");
                final int c0 = cur.getInt("weather_code");
                final long sr = rise.getLong(0) * 1000, ss = set.getLong(0) * 1000;
                TvControl.main.post(() -> {
                    temp = t0; code = c0; sunrise = sr; sunset = ss;
                    hours.clear(); hours.addAll(hs);
                    days.clear(); days.addAll(ds);
                    fetchedAt = System.currentTimeMillis();
                    fetchedFor = key;
                    loading = false;
                    if (ready != null) ready.ready();
                });
            } catch (Exception e) {
                // Offline or the service is down: try again in 5 minutes, not on every press of Home.
                TvControl.main.post(() -> { loading = false; fetchedFor = key; fetchedAt = System.currentTimeMillis() - 25 * 60 * 1000; });
            }
        });
    }

    static String deg(double t) {
        return Math.round(t) + "°";
    }

    // WMO weather codes, as words and as a small symbol drawn from plain text.
    static String words(int c) {
        if (c == 0) return Tr.t("Clear");
        if (c <= 2) return Tr.t("Partly cloudy");
        if (c == 3) return Tr.t("Cloudy");
        if (c == 45 || c == 48) return Tr.t("Fog");
        if (c >= 51 && c <= 57) return Tr.t("Drizzle");
        if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return Tr.t("Rain");
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return Tr.t("Snow");
        if (c >= 95) return Tr.t("Thunderstorms");
        return "";
    }

    static String symbol(int c, boolean day) {
        if (c == 0) return day ? "☀" : "☾";
        if (c <= 2) return day ? "⛅" : "☁";
        if (c == 3 || c == 45 || c == 48) return "☁";
        if ((c >= 51 && c <= 67) || (c >= 80 && c <= 82)) return "☂";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "❄";
        if (c >= 95) return "⚡";
        return "·";
    }

    static String hourLabel(Context c, long t) {
        return android.text.format.DateFormat.format(android.text.format.DateFormat.is24HourFormat(c) ? "H:00" : "h a", t).toString();
    }

    static String dayLabel(long t) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        Calendar today = Calendar.getInstance();
        if (cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return Tr.t("Today");
        return android.text.format.DateFormat.format("EEE", t).toString();
    }
}
