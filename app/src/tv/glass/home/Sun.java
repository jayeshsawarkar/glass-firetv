package tv.glass.home;

import android.content.Context;

import java.util.Calendar;
import java.util.TimeZone;

// Sunrise and sunset worked out on the TV, from the city's latitude and longitude: no internet needed.
// NOAA's sunrise equation (the "general solar position" approximation), good to a minute or two.
final class Sun {
    private Sun() {}

    // Today's sunrise and sunset as epoch milliseconds; {0, 0} in polar day or night.
    static long[] today(double lat, double lon, long now) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(now);
        int doy = cal.get(Calendar.DAY_OF_YEAR);
        double gamma = 2 * Math.PI / 365 * (doy - 1);
        double eqtime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double decl = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);
        double latR = Math.toRadians(lat);
        double cosH = Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl)) - Math.tan(latR) * Math.tan(decl);
        if (cosH > 1 || cosH < -1) return new long[] {0, 0};
        double ha = Math.toDegrees(Math.acos(cosH));
        double riseMin = 720 - 4 * (lon + ha) - eqtime, setMin = 720 - 4 * (lon - ha) - eqtime; // minutes from 00:00 UTC
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        long rise = midnight + Math.round(riseMin * 60000), set = midnight + Math.round(setMin * 60000);
        // West of Greenwich, today's local sunset can fall after midnight UTC; keep both on the local day.
        long day = 24 * 3600 * 1000L;
        while (set < rise) set += day;
        return new long[] {rise, set};
    }

    static boolean isDay(Context c) {
        long now = System.currentTimeMillis();
        double lat = Prefs.lat(c), lon = Prefs.lon(c);
        long[] rs = today(lat, lon, now);
        if (rs[0] == 0) return Math.abs(lat) < 66 || (lat > 0) == summer(now);
        // Check yesterday's and tomorrow's windows too, since UTC days don't line up with local ones.
        long day = 24 * 3600 * 1000L;
        for (long shift = -day; shift <= day; shift += day) {
            long[] w = today(lat, lon, now + shift);
            if (now >= w[0] && now < w[1]) return true;
        }
        return false;
    }

    static boolean summer(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int m = c.get(Calendar.MONTH);
        return m >= Calendar.APRIL && m <= Calendar.SEPTEMBER;
    }

    // Light theme by day, dark by night, unless Home Settings picked one.
    static boolean light(Context c) {
        String t = Prefs.str(c, "theme", "auto");
        if (t.equals("light")) return true;
        if (t.equals("dark")) return false;
        return isDay(c);
    }
}
