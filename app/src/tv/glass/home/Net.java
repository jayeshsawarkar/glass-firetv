package tv.glass.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Small, careful HTTP: GET only, short timeouts, no redirects (so a key only ever goes to the address it was
// meant for), responses capped at 4 MB.
final class Net {
    private Net() {}

    static final ExecutorService pool = Executors.newFixedThreadPool(2);
    static final int LIMIT = 4 * 1024 * 1024;

    static String get(String url, Map<String, String> headers) throws IOException {
        return new String(bytes(url, headers), "UTF-8");
    }

    static byte[] bytes(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Glass-FireTV/2.0");
            c.setRequestProperty("Accept", "application/json, image/*");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) {
                    out.write(b, 0, n);
                    if (out.size() > LIMIT) throw new IOException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    // A Plex address must be the server's own *.plex.direct name over https, so a typo can't send the token anywhere else.
    static boolean plexUrlOk(String u) {
        return u != null && u.matches("https://[a-z0-9-]+\\.[a-f0-9]{32}\\.plex\\.direct(:\\d+)?/?");
    }
}
