package tv.glass.home;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

// What Glass changes on the TV by itself, without a computer. Fire OS won't let an app use the TV's own ADB
// (it refuses connections from the TV itself), so ./glass.sh install grants Glass, once, from your computer:
//   WRITE_SECURE_SETTINGS  the Home button (Glass's own Home service), the screensaver, Now Playing access
//   usage access           the Recent apps list
// Each change below touches only Glass's own entry in a setting and never removes anyone else's.
// Turning apps on or off still needs a computer (./glass.sh debloat / undo): Android keeps that for ADB.
final class TvControl {
    private TvControl() {}

    static final Handler main = new Handler(Looper.getMainLooper());
    static final String PKG = "tv.glass.home";
    static final String SVC = PKG + "/" + PKG + ".HomeService";
    static final String LISTENER = PKG + "/" + PKG + ".NowPlaying";
    static final String DREAM = PKG + "/" + PKG + ".Dream";
    static final String AMAZON_DREAM = "com.amazon.ftv.screensaver/.app.services.ScreensaverService";
    static final String TVDIS = "/data/local/tmp/glass-disabled.txt", TVBG = "/data/local/tmp/glass-background.txt",
            TVSET = "/data/local/tmp/glass-settings.txt";
    static final String NEEDS = "Run ./glass.sh install from your computer once: it gives Glass this permission.";

    static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    // ---- A colon-separated list setting: add or remove only our own component ----

    static String setInList(Context c, String key, String component, boolean on) {
        if (!canWrite(c)) return Tr.t(NEEDS);
        try {
            ContentResolver r = c.getContentResolver();
            String cur = Settings.Secure.getString(r, key);
            List<String> parts = new ArrayList<>();
            if (cur != null && !cur.isEmpty() && !cur.equals("null")) for (String p : cur.split(":")) if (!p.isEmpty()) parts.add(p);
            parts.remove(component);
            if (on) parts.add(component);
            Settings.Secure.putString(r, key, parts.isEmpty() ? null : android.text.TextUtils.join(":", parts));
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    static boolean inList(Context c, String key, String component) {
        String cur = Settings.Secure.getString(c.getContentResolver(), key);
        return cur != null && (":" + cur + ":").contains(":" + component + ":");
    }

    // ---- Home button ----

    static String homeToGlass(Context c) {
        String err = setInList(c, "enabled_accessibility_services", SVC, true);
        if (err == null) Settings.Secure.putInt(c.getContentResolver(), "accessibility_enabled", 1);
        return err;
    }

    static String homeToAmazon(Context c) {
        return setInList(c, "enabled_accessibility_services", SVC, false);
    }

    static boolean glassIsHome(Context c) {
        return inList(c, "enabled_accessibility_services", SVC);
    }

    // ---- Now Playing ----

    static String listener(Context c, boolean on) {
        return setInList(c, "enabled_notification_listeners", LISTENER, on);
    }

    // ---- Screensaver: old values kept, so switching back (and ./glass.sh undo) restores them ----

    static String screensaver(Context c, boolean glass) {
        if (!canWrite(c)) return Tr.t(NEEDS);
        ContentResolver r = c.getContentResolver();
        try {
            if (glass) {
                if (!Prefs.get(c).contains("old_dream")) {
                    String old = Settings.Secure.getString(r, "screensaver_components");
                    String en = Settings.Secure.getString(r, "screensaver_enabled");
                    Prefs.get(c).edit().putString("old_dream", old == null ? "" : old).putString("old_dream_on", en == null ? "" : en).apply();
                }
                Settings.Secure.putString(r, "screensaver_components", DREAM);
                Settings.Secure.putString(r, "screensaver_enabled", "1");
            } else {
                String old = Prefs.str(c, "old_dream", AMAZON_DREAM), en = Prefs.str(c, "old_dream_on", "");
                Settings.Secure.putString(r, "screensaver_components", old.isEmpty() ? AMAZON_DREAM : old);
                Settings.Secure.putString(r, "screensaver_enabled", en.isEmpty() ? null : en);
                Prefs.get(c).edit().remove("old_dream").remove("old_dream_on").apply();
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ---- Ad-blocking DNS: Android's Private DNS pointed at AdGuard DNS (blocks ad and tracking domains in every app) ----

    static final String ADGUARD = "dns.adguard-dns.com";

    static boolean adDnsOn(Context c) {
        return "hostname".equals(Settings.Global.getString(c.getContentResolver(), "private_dns_mode"))
                && ADGUARD.equals(Settings.Global.getString(c.getContentResolver(), "private_dns_specifier"));
    }

    static String adDns(Context c, boolean on) {
        if (!canWrite(c)) return Tr.t(NEEDS);
        try {
            ContentResolver r = c.getContentResolver();
            if (on) {
                Settings.Global.putString(r, "private_dns_specifier", ADGUARD);
                Settings.Global.putString(r, "private_dns_mode", "hostname");
            } else {
                Settings.Global.putString(r, "private_dns_mode", "opportunistic"); // Android's own default
                Settings.Global.putString(r, "private_dns_specifier", null);
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ---- What glass.sh turned off, read from the files it keeps on the TV ----

    static List<String> readList(String path) {
        Set<String> out = new LinkedHashSet<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            for (String l; (l = r.readLine()) != null; ) if (!l.trim().isEmpty()) out.add(l.trim());
        } catch (Exception ignored) {}
        return new ArrayList<>(out);
    }

    // Apps glass.sh turned off that are on again: a Fire OS update can do that. (Not ones Amazon never let it turn off.)
    static List<String> backOn(Context c) {
        List<String> out = new ArrayList<>();
        List<String> limited = readList(TVBG);
        PackageManager pm = c.getPackageManager();
        for (String p : readList(TVDIS)) {
            if (limited.contains(p)) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                if (ai.enabled) out.add(p);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return out;
    }

    // On the TV: the settings glass.sh tune changed go back, and so does everything Glass switched itself.
    // Apps turned off by glass.sh need the computer (./glass.sh undo): Android only lets ADB turn apps on.
    static String restoreSettings(Context c) {
        if (!canWrite(c)) return Tr.t(NEEDS);
        ContentResolver r = c.getContentResolver();
        try (BufferedReader in = new BufferedReader(new FileReader(TVSET))) {
            for (String l; (l = in.readLine()) != null; ) {
                String[] f = l.trim().split(" ", 3);
                if (f.length < 2) continue;
                String old = f.length == 3 ? f[2] : null;
                if (old != null && (old.equals("null") || old.isEmpty())) old = null;
                if (f[0].equals("secure")) Settings.Secure.putString(r, f[1], old);
                else if (f[0].equals("global")) Settings.Global.putString(r, f[1], old);
            }
        } catch (java.io.FileNotFoundException ignored) {
        } catch (Exception e) {
            return e.getMessage();
        }
        screensaver(c, false);
        listener(c, false);
        Prefs.set(c, "now_playing", false);
        Prefs.set(c, "glass_dream", false);
        return homeToAmazon(c);
    }

    // ---- Recent apps (usage access) and closing them ----

    static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+");

    // Never closed: the same list glass.sh never turns off, plus Glass itself.
    static boolean isProtected(String p) {
        if (p.equals("android") || p.equals(PKG) || p.startsWith("com.android.")) return true;
        String[] prefixes = {"com.amazon.tv.settings", "com.amazon.device.settings", "com.amazon.tv.ime", "com.amazon.tv.inputpreference",
                "com.amazon.vizzini", "com.amazon.device.software.ota", "com.amazon.tv.forcedotaupdater", "com.amazon.dcp"};
        for (String x : prefixes) if (p.startsWith(x)) return true;
        String[] exact = {"com.amazon.tv.launcher", "com.amazon.fireinputdevices", "com.amazon.venezia", "com.amazon.tv.livetv",
                "com.amazon.connectivitycontroller", "com.amazon.whasettings", "com.amazon.uxcontrollerservice"};
        for (String x : exact) if (p.equals(x)) return true;
        return p.contains("bluetooth") || p.contains("webview") || p.contains("alexa") || p.contains("tvinput");
    }

    static boolean hasUsageAccess(Context c) {
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ops.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // Apps opened in the last 12 hours that have an icon on Home, most recent first.
    static List<String> recents(Context c) {
        List<String> out = new ArrayList<>();
        UsageStatsManager u = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = u.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 12 * 3600_000L, now);
        if (stats == null) return out;
        Collections.sort(stats, (a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        PackageManager pm = c.getPackageManager();
        for (UsageStats s : stats) {
            String p = s.getPackageName();
            if (s.getTotalTimeInForeground() <= 0 || out.contains(p) || !NAME.matcher(p).matches() || isProtected(p)) continue;
            if (pm.getLeanbackLaunchIntentForPackage(p) == null && pm.getLaunchIntentForPackage(p) == null) continue;
            out.add(p);
            if (out.size() >= 12) break;
        }
        return out;
    }

    // Stops the app's background processes, so it starts fresh next time (Android lets an app do no more).
    static void close(Context c, String p) {
        if (!NAME.matcher(p).matches() || isProtected(p)) return;
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(p);
    }

    // ---- Sleep ----

    // Standby, through the accessibility service's "lock screen" action, which puts the TV to sleep.
    static boolean sleepNow() {
        HomeService s = HomeService.running;
        if (s == null) return false;
        return s.performGlobalAction(8 /* GLOBAL_ACTION_LOCK_SCREEN, Android 9 */);
    }

    static void openGlass(Context c) {
        c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
