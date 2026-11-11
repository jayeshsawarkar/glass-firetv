package tv.glass.home;

import android.content.Context;

import java.util.Calendar;
import java.util.TimeZone;

// A second clock beside the TV's own, for another city: India by default (Glass settings > World clock).
// "+1" when it's already tomorrow there, "−1" when it's still yesterday.
final class WorldClock {
    private WorldClock() {}

    // India first: it's the default, and the settings row starts from the first entry.
    static final String[] ZONES = {"Asia/Kolkata", "Europe/London", "Europe/Paris", "Asia/Dubai", "Asia/Singapore",
            "Asia/Tokyo", "Australia/Sydney", "America/Los_Angeles", "America/Chicago", "America/New_York", "off"};
    static final String[] NAMES = {"India", "London", "Paris", "Dubai", "Singapore", "Tokyo", "Sydney", "Los Angeles", "Chicago", "New York", "Off"};

    static String zone(Context c) { return Prefs.str(c, "world_tz", "Asia/Kolkata"); }

    static String name(Context c) {
        String z = zone(c);
        for (int i = 0; i < ZONES.length; i++) if (ZONES[i].equals(z)) return Tr.t(NAMES[i]);
        return z;
    }

    // "India 6:39 AM +1", or "" when it's off.
    static String line(Context c) {
        String z = zone(c);
        if (z.equals("off")) return "";
        TimeZone tz = TimeZone.getTimeZone(z);
        long now = System.currentTimeMillis();
        java.text.DateFormat f = new java.text.SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(c) ? "H:mm" : "h:mm a", java.util.Locale.US);
        f.setTimeZone(tz);
        Calendar here = Calendar.getInstance(), there = Calendar.getInstance(tz);
        here.setTimeInMillis(now);
        there.setTimeInMillis(now);
        int diff = Integer.compare(there.get(Calendar.YEAR) * 400 + there.get(Calendar.DAY_OF_YEAR), here.get(Calendar.YEAR) * 400 + here.get(Calendar.DAY_OF_YEAR));
        return name(c) + " " + f.format(new java.util.Date(now)) + (diff > 0 ? " +1" : diff < 0 ? " −1" : "");
    }
}
