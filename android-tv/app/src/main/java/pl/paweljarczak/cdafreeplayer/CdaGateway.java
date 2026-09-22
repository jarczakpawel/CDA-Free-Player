package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
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
        if (closed || token != null && token.isCancelled()) return;
        if (allowWeb && (!web.isFullSitePrepared() || !http.hasSession(url))) {
            fetchWeb(url, token, cb);
            return;
        }

        net.execute(() -> {
            try {
                CdaHttp.Result result = http.get(url, token);
                if (token != null && token.isCancelled()) return;
                if (!result.challenge) {
                    post(token, () -> cb.onHtml(result.body, false));
                    return;
                }
                if (!allowWeb) {
                    post(token, cb::onChallengeRequired);
                    return;
                }
                post(token, () -> fetchWeb(url, token, cb));
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                post(token, () -> cb.onError(e.toString()));
            }
        });
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
}
