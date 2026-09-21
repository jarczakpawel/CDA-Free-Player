package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class CdaHttp {
    public static final class Result {
        public int status;
        public String body = "";
        public boolean challenge;
    }

    private final Context context;

    public CdaHttp(Context c) {
        context = c.getApplicationContext();
    }

    public String userAgent() {
        return WebSettings.getDefaultUserAgent(context);
    }

    /**
     * Desktop first-run search is deliberately bootstrapped through a real WebView.
     * Do the same on Android: a naked HTTP request can receive a valid HTTP 200 page
     * without the actual catalogue (consent/anti-bot/mobile bootstrap), which used to
     * be cached as "0 films" for six hours.
     */
    public boolean hasSession(String url) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            return cookie != null && !cookie.trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    public Result get(String url, RequestToken token) throws Exception {
        if (token != null && token.isCancelled()) throw new InterruptedException();

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent());
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.7");
        c.setRequestProperty("Upgrade-Insecure-Requests", "1");
        c.setRequestProperty("Referer", "https://www.cda.pl/");

        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);

        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16_384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (token != null && token.isCancelled()) {
                    c.disconnect();
                    throw new InterruptedException();
                }
                b.write(buf, 0, n);
            }
            in.close();
        }

        // Keep WebView and direct HTTP on one cookie jar. This matters after CDA
        // rotates a clearance/session cookie in an ordinary HTTP response.
        try {
            Map<String, List<String>> headers = c.getHeaderFields();
            if (headers != null) {
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    String name = e.getKey();
                    if (name == null || !"set-cookie".equalsIgnoreCase(name)) continue;
                    List<String> values = e.getValue();
                    if (values == null) continue;
                    for (String value : values) {
                        if (value != null && !value.isEmpty()) CookieManager.getInstance().setCookie(url, value);
                    }
                }
                CookieManager.getInstance().flush();
            }
        } catch (Exception ignored) {}

        Result r = new Result();
        r.status = status;
        r.body = b.toString(StandardCharsets.UTF_8.name());
        r.challenge = isChallenge(status, r.body);
        c.disconnect();
        return r;
    }

    private static boolean isChallenge(int status, String body) {
        String s = body == null ? "" : body.toLowerCase();
        return status == 403 || status == 429 || status == 503 ||
                s.contains("/cdn-cgi/challenge-platform/") ||
                s.contains("cf-chl-") ||
                s.contains("checking if you are not a bot") ||
                s.contains("verify you are human") ||
                s.contains("przeprowadzanie weryfikacji zabezpieczeń") ||
                s.contains("just a moment...") ||
                s.contains("attention required! | cloudflare") ||
                s.contains("enable javascript and cookies to continue");
    }
}
