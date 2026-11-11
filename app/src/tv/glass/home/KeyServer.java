package tv.glass.home;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// Typing a 32-character key with a remote is no fun, so Glass shows a QR code: scan it with a phone on the same
// Wi-Fi, paste the keys, send. The page is only up while the code is on screen, for 5 minutes at most, at an
// address with a random one-time code in it, on your own network only; after one successful send it closes.
// It's plain HTTP inside your home network: on a network you don't trust, type the key on the TV instead.
final class KeyServer {
    interface Saved { void saved(String what); }

    static final long LIFETIME = 5 * 60 * 1000L;

    final Context app;
    final Saved saved;
    final String token;
    ServerSocket server;
    volatile boolean open;
    String url;

    KeyServer(Context c, Saved s) {
        app = c.getApplicationContext();
        saved = s;
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder t = new StringBuilder();
        for (byte x : b) t.append(String.format("%02x", x));
        token = t.toString();
    }

    // Returns the address to put in the QR code, or null if the TV has no address on the home network.
    String start() {
        String ip = lanAddress();
        if (ip == null) return null;
        try {
            server = new ServerSocket(0, 4, InetAddress.getByName(ip));
            server.setSoTimeout(1000);
        } catch (Exception e) {
            return null;
        }
        open = true;
        url = "http://" + ip + ":" + server.getLocalPort() + "/" + token;
        final long until = System.currentTimeMillis() + LIFETIME;
        Thread t = new Thread(() -> {
            while (open && System.currentTimeMillis() < until) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    handle(s);
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (!open) break;
                }
            }
            stop();
        }, "glass-keys");
        t.setDaemon(true);
        t.start();
        return url;
    }

    void stop() {
        open = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }

    static String lanAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    void handle(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] first = head.split("\r\n")[0].split(" ");
        if (first.length < 2) return;
        String method = first[0], path = first[1];
        OutputStream out = s.getOutputStream();
        if (!path.equals("/" + token)) { reply(out, 404, "Not found"); return; }
        if (method.equals("GET")) { reply(out, 200, page(null)); return; }
        if (!method.equals("POST")) { reply(out, 405, "No"); return; }
        int length = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        }
        if (length <= 0 || length > 8192) { reply(out, 413, "Too large"); return; }
        byte[] body = new byte[length];
        int got = 0;
        while (got < length) {
            int n = in.read(body, got, length - got);
            if (n < 0) break;
            got += n;
        }
        Map<String, String> form = parse(new String(body, 0, got, StandardCharsets.UTF_8));
        StringBuilder done = new StringBuilder(), problems = new StringBuilder();
        String tmdb = form.get("tmdb"), wm = form.get("watchmode"), plexUrl = form.get("plex_url"), plexToken = form.get("plex_token");
        if (tmdb != null && !tmdb.isEmpty()) {
            if (Picks.checkTmdb(tmdb)) { Prefs.setSecret(app, "tmdb_key", tmdb); done.append("TMDB "); }
            else problems.append(Tr.t("TMDB didn't accept that key.")).append(' ');
        }
        if (wm != null && !wm.isEmpty()) {
            if (Picks.checkWatchmode(wm)) { Prefs.setSecret(app, "watchmode_key", wm); done.append("Watchmode "); }
            else problems.append(Tr.t("Watchmode didn't accept that key.")).append(' ');
        }
        if (plexUrl != null && !plexUrl.isEmpty()) {
            if (Net.plexUrlOk(plexUrl)) { Prefs.setSecret(app, "plex_url", plexUrl); done.append("Plex "); }
            else problems.append(Tr.t("The Plex address must be your server's https://….plex.direct:32400 address.")).append(' ');
        }
        if (plexToken != null && !plexToken.isEmpty()) { Prefs.setSecret(app, "plex_token", plexToken); if (done.indexOf("Plex") < 0) done.append("Plex "); }
        String fh = form.get("finnhub"), holdings = form.get("holdings");
        if (fh != null && !fh.isEmpty()) {
            if (Portfolio.checkKey(fh)) { Prefs.setSecret(app, "finnhub_key", fh); done.append("Finnhub "); }
            else problems.append(Tr.t("Finnhub didn't accept that key.")).append(' ');
        }
        if (holdings != null && !holdings.isEmpty()) {
            final String text = holdings;
            String bad = Portfolio.setHoldings(app, text);
            if (bad == null) done.append(Tr.t("holdings")).append(' ');
            else problems.append(bad).append(' ');
        }
        String msg = done.length() > 0 ? Tr.t("Saved on the TV: {n}", done.toString().trim()) : "";
        reply(out, 200, page((msg + " " + problems).trim()));
        if (done.length() > 0) {
            final String what = done.toString().trim();
            TvControl.main.post(() -> saved.saved(what));
            if (problems.length() == 0) open = false; // one send, then the page closes
        }
    }

    static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 8192) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("UTF-8");
        }
        return null;
    }

    static Map<String, String> parse(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim());
            } catch (Exception ignored) {}
        }
        return m;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    String page(String message) {
        String m = message == null || message.isEmpty() ? "" : "<p class=m>" + esc(message) + "</p>";
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Glass keys</title><style>body{font:16px -apple-system,system-ui,sans-serif;margin:0;padding:24px;background:#111;color:#eee}"
                + "h1{font-size:22px}label{display:block;margin:18px 0 6px;color:#aaa}input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;"
                + "border-radius:10px;border:1px solid #444;background:#1c1c1e;color:#fff}button{margin-top:24px;width:100%;padding:14px;font-size:17px;"
                + "border:0;border-radius:12px;background:#fff;color:#000;font-weight:600}.m{padding:12px;border-radius:10px;background:#1f3d2a}"
                + "small{color:#888}</style></head><body><h1>" + esc(Tr.t("Keys for Glass on your TV")) + "</h1>" + m
                + "<form method=post><label>" + esc(Tr.t("TMDB key (for the picks)")) + "</label><input name=tmdb autocomplete=off>"
                + "<label>" + esc(Tr.t("Watchmode key (optional: open a pick on its own page)")) + "</label><input name=watchmode autocomplete=off>"
                + "<label>" + esc(Tr.t("Plex server address (optional)")) + "</label><input name=plex_url placeholder='https://192-168-1-20.….plex.direct:32400' autocomplete=off>"
                + "<label>" + esc(Tr.t("Plex token (optional; a managed user's is safest)")) + "</label><input name=plex_token autocomplete=off>"
                + "<label>" + esc(Tr.t("Finnhub key (free, for live stock prices: finnhub.io)")) + "</label><input name=finnhub autocomplete=off>"
                + "<label>" + esc(Tr.t("Holdings, one per line: symbol, shares, total cost. CASH for cash.")) + "</label>"
                + "<textarea name=holdings rows=6 style='width:100%;box-sizing:border-box;padding:12px;font-size:16px;border-radius:10px;border:1px solid #444;background:#1c1c1e;color:#fff' placeholder='AAPL 10 1500&#10;VTI 3.5 900&#10;CASH 50'>"
                + esc(Prefs.str(app, "pf_holdings", "")) + "</textarea>"
                + "<button>" + esc(Tr.t("Send to the TV")) + "</button></form><p><small>" + esc(Tr.t("Leave a box empty to keep what the TV has. Keys stay on the TV."))
                + "</small></p></body></html>";
    }

    static void reply(OutputStream out, int code, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
