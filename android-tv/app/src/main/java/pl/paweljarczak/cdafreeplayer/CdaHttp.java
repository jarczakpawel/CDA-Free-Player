package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
        return CdaBrowserIdentity.userAgent(context);
    }

    public boolean hasSession(String url) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            return cookie != null && !cookie.trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    public Result get(String url, RequestToken token) throws Exception {
        return get(url, token, userAgent());
    }

    public Result getCatalog(String url, RequestToken token) throws Exception {
        return get(url, token, CdaBrowserIdentity.catalogUserAgent(context));
    }

    private Result get(String url, RequestToken token, String userAgent) throws Exception {
        checkCancelled(token);
        HttpURLConnection c = open(url, "GET", "https://www.cda.pl/", userAgent);
        try {
            if (token != null) token.attach(c);
            int status = c.getResponseCode();
            String body = readBody(c, status, token);
            syncCookies(url, c);
            Result r = new Result();
            r.status = status;
            r.body = body;
            r.challenge = isChallenge(status, body);
            if (!r.challenge && (status < 200 || status >= 300)) throw new java.io.IOException("CDA HTTP " + status);
            return r;
        } finally {
            if (token != null) token.detach(c);
            c.disconnect();
        }
    }

    public String videoGetLink(String pageUrl, String videoId, PlayerData data,
                               Object qualityValue, RequestToken token) throws Exception {
        checkCancelled(token);
        if (data == null || data.ts == null || data.ts == JSONObject.NULL || data.hash2.isEmpty()) return "";

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("method", "videoGetLink");
        JSONArray params = new JSONArray();
        params.put(videoId);
        params.put(qualityValue == null ? JSONObject.NULL : qualityValue);
        params.put(data.ts);
        params.put(data.hash2);
        params.put(new JSONObject());
        request.put("params", params);
        request.put("id", 2);

        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
        String result = postVideoGetLink("https://www.cda.pl/video/" + videoId + "/vjs", pageUrl, payload, token);
        if (!result.isEmpty()) return result;
        return postVideoGetLink(pageUrl, pageUrl, payload, token);
    }

    private String postVideoGetLink(String endpoint, String referer, byte[] payload, RequestToken token) throws Exception {
        HttpURLConnection c = open(endpoint, "POST", referer, userAgent());
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        c.setFixedLengthStreamingMode(payload.length);

        try {
            if (token != null) token.attach(c);
            try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            int status = c.getResponseCode();
            String body = readBody(c, status, token);
            syncCookies(endpoint, c);
            if (status != 200 || body.isEmpty()) return "";
            JSONObject root = new JSONObject(body);
            JSONObject result = root.optJSONObject("result");
            if (result == null) return "";
            String state = result.optString("status", "");
            if (!state.isEmpty() && !"ok".equalsIgnoreCase(state)) return "";
            return normalizeStreamUrl(result.optString("resp", ""));
        } finally {
            if (token != null) token.detach(c);
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url, String method, String referer, String userAgent) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.7");
        c.setRequestProperty("Referer", referer);
        if ("GET".equals(method)) c.setRequestProperty("Upgrade-Insecure-Requests", "1");

        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        } catch (Exception ignored) {}
        return c;
    }

    private static String readBody(HttpURLConnection c, int status, RequestToken token) throws Exception {
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream src = in) {
                byte[] buf = new byte[16_384];
                int n;
                while ((n = src.read(buf)) > 0) {
                    checkCancelled(token);
                    if (b.size() + n > 8 * 1024 * 1024) throw new java.io.IOException("Odpowiedź CDA jest zbyt duża");
                    b.write(buf, 0, n);
                }
            }
        }
        return b.toString(StandardCharsets.UTF_8.name());
    }

    private static void checkCancelled(RequestToken token) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || token != null && token.isCancelled()) throw new InterruptedException();
    }

    private static String normalizeStreamUrl(String url) {
        if (url == null) return "";
        String out = url.trim();
        if (out.startsWith("//")) out = "https:" + out;
        if (!out.startsWith("http://") && !out.startsWith("https://")) return "";
        return out;
    }

    private static void syncCookies(String url, HttpURLConnection c) {
        try {
            Map<String, List<String>> headers = c.getHeaderFields();
            if (headers == null) return;
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
        } catch (Exception ignored) {}
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
