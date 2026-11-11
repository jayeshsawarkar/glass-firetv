package tv.glass.home;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Translations. Each language is one file, app/assets/i18n/<lang>.tsv: the English text, a tab, the translation.
// Text missing from a file shows in English. "auto" follows the TV's language.
final class Tr {
    private Tr() {}

    static final String[] LANGS = {"auto", "en", "es", "fr", "de", "pt", "hi"};
    static final String[] LANG_NAMES = {"Automatic", "English", "Español", "Français", "Deutsch", "Português", "हिन्दी"};

    static Context app;
    static String loaded;
    static Map<String, String> table = new HashMap<>();

    static void init(Context c) {
        app = c.getApplicationContext();
        reload();
    }

    static String lang() {
        String l = app == null ? "auto" : Prefs.str(app, "lang", "auto");
        if (!l.equals("auto")) return l;
        String sys = Locale.getDefault().getLanguage();
        for (String x : LANGS) if (x.equals(sys)) return x;
        return "en";
    }

    static synchronized void reload() {
        String l = lang();
        if (l.equals(loaded)) return;
        Map<String, String> t = new HashMap<>();
        if (!l.equals("en") && app != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(app.getAssets().open("i18n/" + l + ".tsv"), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    int tab = line.indexOf('\t');
                    if (tab > 0 && !line.startsWith("#")) t.put(line.substring(0, tab), line.substring(tab + 1));
                }
            } catch (Exception ignored) {}
        }
        table = t;
        loaded = l;
    }

    // t("Sleep in {n} min", n) fills {n}; other placeholders stay as written.
    static String t(String english) {
        String s = table.get(english);
        return s == null ? english : s;
    }

    static String t(String english, Object n) {
        return t(english).replace("{n}", String.valueOf(n));
    }

    static String t(String english, String key, Object value) {
        return t(english).replace("{" + key + "}", String.valueOf(value));
    }
}
