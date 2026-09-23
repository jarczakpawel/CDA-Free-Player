package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.FrameLayout;

import java.util.ArrayDeque;
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
    private static final boolean TRACE = false;
    private static final long CATALOG_MIN_GAP_MS = 1700L;
    private static final long CATALOG_WINDOW_MS = 15_000L;
    private static final int CATALOG_WINDOW_MAX = 9;
    private static final long RATE_LIMIT_FALLBACK_MS = 3500L;
    private static final int RATE_LIMIT_HTTP_RETRIES = 2;

    private final Object catalogRateLock = new Object();
    private final ArrayDeque<Long> catalogStarts = new ArrayDeque<>();
    private long lastCatalogStart;
    private long catalogBlockedUntil;
    private long adaptiveCatalogGapMs = CATALOG_MIN_GAP_MS;
    private int catalogSuccessStreak;

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

    public void fetchComments(String url, RequestToken token, Callback cb) {
        fetch(url, true, token, new Callback() {
            @Override public void onHtml(String html, boolean viaWebView) {
                if (html != null && (html.contains("dobierzWszystkieOdpowiedzi") || html.contains("Pokaż wszystkie odpowiedzi"))) {
                    post(token, () -> web.fetchComments(url, token, bridge(token, cb)));
                } else {
                    cb.onHtml(html, viaWebView);
                }
            }
            @Override public void onError(String e) { cb.onError(e); }
            @Override public void onChallengeRequired() { cb.onChallengeRequired(); }
            @Override public void onVerification(boolean interactive) { cb.onVerification(interactive); }
        });
    }

    private void fetchHttp(String url, boolean catalog, boolean allowWeb, RequestToken token, Callback cb) {
        if (closed || token != null && token.isCancelled()) return;
        net.execute(() -> {
            try {
                CdaHttp.Result result;
                int rateRetries = 0;
                while (true) {
                    if (catalog) awaitCatalogSlot(token);
                    result = catalog ? http.getCatalog(url, token) : http.get(url, token);
                    if (token != null && token.isCancelled()) return;
                    logHttp(catalog ? "catalog" : "page", result, url);
                    if (catalog && result.status >= 200 && result.status < 400) noteCatalogSuccess();

                    if (!catalog || result.status != 429 || rateRetries >= RATE_LIMIT_HTTP_RETRIES) break;
                    long fallback = RATE_LIMIT_FALLBACK_MS * (rateRetries + 1L);
                    long backoff = Math.max(result.retryAfterMs, fallback);
                    noteCatalogRateLimit(backoff);
                    rateRetries++;
                    if (TRACE) Log.w(TAG, "catalog HTTP 429 -> cooldown " + backoff + "ms; retry=" +
                            rateRetries + "/" + RATE_LIMIT_HTTP_RETRIES +
                            "; clearance=" + http.hasClearance());
                }

                final CdaHttp.Result finalResult = result;

                if (finalResult.challenge) {
                    if (!allowWeb) {
                        post(token, cb::onChallengeRequired);
                        return;
                    }
                    if (TRACE) Log.i(TAG, "HTTP challenge -> WebView; clearance=" + http.hasClearance());
                    post(token, () -> fetchWeb(url, token, cb));
                    return;
                }

                if (allowWeb && http.isMobileResult(finalResult)) {
                    if (TRACE) Log.i(TAG, "mobile CDA response -> preparing full-site session over HTTP");
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
                    if (TRACE) Log.i(TAG, "full-site HTTP path needs WebView; clearance=" + http.hasClearance());
                    post(token, () -> fetchWeb(url, token, cb));
                    return;
                }

                if (!http.isMobileResult(finalResult)) web.markFullSitePrepared();
                post(token, () -> cb.onHtml(finalResult.body, false));
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
        if (!TRACE) return;
        Log.i(TAG, kind + " http status=" + result.status +
                " bytes=" + (result.body == null ? 0 : result.body.length()) +
                " challenge=" + result.challenge +
                " mobile=" + (result.finalUrl != null && result.finalUrl.startsWith("https://m.cda.pl/")) +
                " ms=" + result.elapsedMs +
                " retryAfterMs=" + result.retryAfterMs +
                " final=" + safeUrl(result.finalUrl) +
                " requested=" + safeUrl(url));
    }

    private void awaitCatalogSlot(RequestToken token) throws InterruptedException {
        boolean logged = false;
        while (true) {
            long wait;
            int recent;
            synchronized (catalogRateLock) {
                long now = SystemClock.elapsedRealtime();
                while (!catalogStarts.isEmpty() && now - catalogStarts.peekFirst() >= CATALOG_WINDOW_MS) {
                    catalogStarts.removeFirst();
                }
                long ready = Math.max(catalogBlockedUntil, lastCatalogStart + adaptiveCatalogGapMs);
                if (catalogStarts.size() >= CATALOG_WINDOW_MAX) {
                    ready = Math.max(ready, catalogStarts.peekFirst() + CATALOG_WINDOW_MS);
                }
                recent = catalogStarts.size();
                wait = ready - now;
                if (wait <= 0L) {
                    lastCatalogStart = now;
                    catalogStarts.addLast(now);
                    return;
                }
            }
            if (TRACE && !logged && wait >= 100L) {
                Log.i(TAG, "catalog throttle wait=" + wait + "ms; recent=" + recent +
                        "/" + CATALOG_WINDOW_MAX + "; gap=" + adaptiveCatalogGapMs);
                logged = true;
            }
            sleepCancellable(wait, token);
        }
    }

    private void noteCatalogRateLimit(long backoffMs) {
        synchronized (catalogRateLock) {
            long now = SystemClock.elapsedRealtime();
            long safeBackoff = Math.max(1000L, Math.min(backoffMs, 30_000L));
            long until = now + safeBackoff;
            if (until > catalogBlockedUntil) catalogBlockedUntil = until;
            adaptiveCatalogGapMs = Math.max(adaptiveCatalogGapMs, Math.min(5000L, safeBackoff + 500L));
            catalogSuccessStreak = 0;
        }
    }

    private void noteCatalogSuccess() {
        synchronized (catalogRateLock) {
            if (adaptiveCatalogGapMs <= CATALOG_MIN_GAP_MS) return;
            catalogSuccessStreak++;
            if (catalogSuccessStreak < 4) return;
            catalogSuccessStreak = 0;
            adaptiveCatalogGapMs = Math.max(CATALOG_MIN_GAP_MS, adaptiveCatalogGapMs - 500L);
        }
    }

    private static void sleepCancellable(long waitMs, RequestToken token) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + waitMs;
        while (true) {
            if (Thread.currentThread().isInterrupted() || token != null && token.isCancelled()) {
                throw new InterruptedException();
            }
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0L) return;
            Thread.sleep(Math.min(left, 250L));
        }
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

    public void setPlaybackContext(boolean active) { web.setPlaybackContext(active); }

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
