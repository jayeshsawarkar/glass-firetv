package tv.glass.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

// Everything Glass remembers. Settings live in "glass" (the same file the home screen always used);
// keys for online services live apart in "secrets", which never leaves the TV and is never logged.
final class Prefs {
    private Prefs() {}

    // New York until you pick your own city in Glass settings > City.
    static final double DEFAULT_LAT = 40.7128, DEFAULT_LON = -74.0060;
    static final String DEFAULT_CITY = "New York";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("glass", Context.MODE_PRIVATE);
    }

    static SharedPreferences secrets(Context c) {
        return c.getSharedPreferences("secrets", Context.MODE_PRIVATE);
    }

    static double lat(Context c) { return Double.longBitsToDouble(get(c).getLong("lat", Double.doubleToLongBits(DEFAULT_LAT))); }
    static double lon(Context c) { return Double.longBitsToDouble(get(c).getLong("lon", Double.doubleToLongBits(DEFAULT_LON))); }
    static String city(Context c) { return get(c).getString("city", DEFAULT_CITY); }

    static void setPlace(Context c, String city, double lat, double lon) {
        get(c).edit().putString("city", city).putLong("lat", Double.doubleToLongBits(lat))
                .putLong("lon", Double.doubleToLongBits(lon)).apply();
    }

    static boolean on(Context c, String key, boolean def) { return get(c).getBoolean(key, def); }
    static void set(Context c, String key, boolean v) { get(c).edit().putBoolean(key, v).apply(); }
    static String str(Context c, String key, String def) { return get(c).getString(key, def); }
    static void set(Context c, String key, String v) { get(c).edit().putString(key, v).apply(); }
    static int num(Context c, String key, int def) { return get(c).getInt(key, def); }
    static void set(Context c, String key, int v) { get(c).edit().putInt(key, v).apply(); }

    static String secret(Context c, String key) { return secrets(c).getString(key, ""); }
    static void setSecret(Context c, String key, String v) {
        if (v == null || v.trim().isEmpty()) secrets(c).edit().remove(key).apply();
        else secrets(c).edit().putString(key, v.trim()).apply();
    }

    // °F in the US and a few others, °C everywhere else, unless picked in Home Settings.
    static boolean fahrenheit(Context c) {
        String t = str(c, "temp", "auto");
        if (t.equals("F")) return true;
        if (t.equals("C")) return false;
        String country = Locale.getDefault().getCountry();
        return country.equals("US") || country.equals("LR") || country.equals("MM") || country.equals("BS")
                || country.equals("KY") || country.equals("PW") || country.equals("FM") || country.equals("MH");
    }

    // TMDB's country for which app streams what. Follows the TV's region.
    static String region() {
        String r = Locale.getDefault().getCountry();
        return r.length() == 2 ? r : "US";
    }
}
