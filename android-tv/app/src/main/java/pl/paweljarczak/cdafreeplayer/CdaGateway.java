package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CdaGateway {
    public interface Callback {
        void onHtml(String html, boolean viaWebView);
        void onError(String error);
        void onChallengeRequired();
        void onVerification(boolean interactive);
    }

    private static final String TAG = "CDAFP";
    private final ExecutorService net = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "cda-net");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CdaHttp http;
    private final CdaWebSession web;
    private volatile boolean closed;

    public CdaGateway(Activity a, FrameLayout overlay, FrameLayout host) {
        http = new CdaHttp(a);
        web = new CdaWebSession(a, overlay, host);
    }

    public String userAgent() { return http.userAgent(); }
    public CdaWebSession webSession() { return web; }

    public void fetch(String url, boolean allowWeb, RequestToken token, Callback cb) {
        fetchHttp(url, false, allowWeb, token, cb);
    }

    public void fetchCatalog(String url, RequestToken token, Callback cb) {
        fetchHttp(url, true, true, token, cb);
    }

    private void fetchHttp(String url, boolean catalog, boolean allowWeb, RequestToken token, Callback cb) {
        if (closed || token != null && token.isCancelled()) return;
        net.execute(() -> {
            try {
                CdaHttp.Result result = catalog ? http.getCatalog(url, token) : http.get(url, token);
                if (token != null && token.isCancelled()) return;
                logHttp(catalog ? "catalog" : "page", result, url);

                if (result.challenge) {
                    if (!allowWeb) {
                        post(token, cb::onChallengeRequired);
                        return;
                    }
                    Log.i(TAG, "HTTP challenge -> WebView; clearance=" + http.hasClearance());
                    post(token, () -> fetchWeb(url, token, cb));
                    return;
                }

                if (allowWeb && http.isMobileResult(result)) {
                    Log.i(TAG, "mobile CDA response -> preparing full-site session over HTTP");
                    CdaHttp.Result prep = http.prepareFullSite(token);
                    if (token != null && token.isCancelled()) return;
                    logHttp("fullsite", prep, "https://m.cda.pl/gofullcda");
                    if (!prep.challenge) {
                        CdaHttp.Result retry = catalog ? http.getCatalog(url, token) : http.get(url, token);
                        if (token != null && token.isCancelled()) return;
                        logHttp((catalog ? "catalog" : "page") + " retry", retry, url);
                        if (!retry.challenge && !http.isMobileResult(retry)) {
                            web.markFullSitePrepared();
                            post(token, () -> cb.onHtml(retry.body, false));
                            return;
                        }
                    }
                    Log.i(TAG, "full-site HTTP path needs WebView; clearance=" + http.hasClearance());
                    post(token, () -> fetchWeb(url, token, cb));
                    return;
                }

                if (!http.isMobileResult(result)) web.markFullSitePrepared();
                post(token, () -> cb.onHtml(result.body, false));
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Log.w(TAG, (catalog ? "catalog" : "page") + " HTTP failed url=" + safeUrl(url) +
                        " error=" + e.getClass().getSimpleName());
                if (allowWeb) post(token, () -> fetchWeb(url, token, cb));
                else post(token, () -> cb.onError(e.toString()));
            }
        });
    }

    private static void logHttp(String kind, CdaHttp.Result result, String url) {
        Log.i(TAG, kind + " http status=" + result.status +
                " bytes=" + (result.body == null ? 0 : result.body.length()) +
                " challenge=" + result.challenge +
                " mobile=" + (result.finalUrl != null && result.finalUrl.startsWith("https://m.cda.pl/")) +
                " ms=" + result.elapsedMs +
                " final=" + safeUrl(result.finalUrl) +
                " requested=" + safeUrl(url));
    }

    public void fetchWeb(String url, RequestToken token, Callback cb) {
        post(token, () -> web.fetch(url, token, bridge(token, cb)));
    }

    public void fetchPlayerWeb(String url, RequestToken token, Callback cb) {
        post(token, () -> web.fetchPlayer(url, token, bridge(token, cb)));
    }

    private CdaWebSession.Callback bridge(RequestToken token, Callback cb) {
        return new CdaWebSession.Callback() {
            @Override public void onHtml(String html) { if (!closed && (token == null || !token.isCancelled())) cb.onHtml(html, true); }
            @Override public void onError(String e) { if (!closed && (token == null || !token.isCancelled())) cb.onError(e); }
            @Override public void onVerification(boolean interactive) { if (!closed && (token == null || !token.isCancelled())) cb.onVerification(interactive); }
        };
    }

    public PlayerData resolvePlayer(Movie movie, PlayerData input, RequestToken token) throws Exception {
        if (input == null) return null;
        PlayerData p = input.copy();

        if (!p.hasPlayableSource() && p.canResolveQuality()) {
            ArrayList<Map.Entry<String, Object>> candidates = new ArrayList<>(p.qualities.entrySet());
            candidates.sort(Comparator.comparingInt((Map.Entry<String, Object> e) -> qualityHeight(e.getKey())).reversed());
            for (Map.Entry<String, Object> candidate : candidates) {
                if (token != null && token.isCancelled()) throw new InterruptedException();
                String stream = http.videoGetLink(movie.url, movie.id, p, candidate.getValue(), token);
                if (stream.isEmpty()) continue;
                p.resolved = stream;
                p.resolvedKind = streamKind(stream);
                break;
            }
        }
        return p;
    }

    private static int qualityHeight(String label) {
        if (label == null) return 0;
        Matcher m = Pattern.compile("(\\d+)").matcher(label);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) { return 0; }
    }

    private static String streamKind(String url) {
        if (url == null) return "direct";
        String clean = url.toLowerCase().split("\\?", 2)[0];
        if (clean.endsWith(".mpd")) return "dash";
        if (clean.endsWith(".m3u8")) return "hls";
        if (clean.endsWith(".mp4") || clean.endsWith(".m4v")) return "direct";
        return "direct";
    }

    private void post(RequestToken token, Runnable callback) {
        main.post(() -> { if (!closed && (token == null || !token.isCancelled())) callback.run(); });
    }

    public void releaseForPlayback() { web.releaseForPlayback(); }

    public void shutdown() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        net.shutdownNow();
        web.destroy();
    }

    private static String safeUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
