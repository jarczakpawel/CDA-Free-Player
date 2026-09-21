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

    public CdaGateway(Activity a, FrameLayout overlay, FrameLayout host) {
        http = new CdaHttp(a);
        web = new CdaWebSession(a, overlay, host);
    }

    public String userAgent() { return http.userAgent(); }
    public CdaWebSession webSession() { return web; }

    public void fetch(String url, boolean allowWeb, RequestToken token, Callback cb) {
        // A fresh install is bootstrapped through WebView so HTTP and WebView use
        // the same real CDA session/cookies from the beginning.
        if (allowWeb && !http.hasSession(url)) {
            fetchWeb(url, token, cb);
            return;
        }

        net.execute(() -> {
            try {
                CdaHttp.Result result = http.get(url, token);
                if (token != null && token.isCancelled()) return;
                if (!result.challenge) {
                    main.post(() -> cb.onHtml(result.body, false));
                    return;
                }
                if (!allowWeb) {
                    main.post(cb::onChallengeRequired);
                    return;
                }
                main.post(() -> fetchWeb(url, token, cb));
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                main.post(() -> cb.onError(e.toString()));
            }
        });
    }

    public void fetchWeb(String url, RequestToken token, Callback cb) {
        main.post(() -> web.fetch(url, token, bridge(cb)));
    }

    /** Force WebView and wait for player_data/stream markup, not only page load. */
    public void fetchPlayerWeb(String url, RequestToken token, Callback cb) {
        main.post(() -> web.fetchPlayer(url, token, bridge(cb)));
    }

    private CdaWebSession.Callback bridge(Callback cb) {
        return new CdaWebSession.Callback() {
            @Override public void onHtml(String html) { cb.onHtml(html, true); }
            @Override public void onError(String e) { cb.onError(e); }
            @Override public void onVerification(boolean interactive) { cb.onVerification(interactive); }
        };
    }

    /**
     * Port of the desktop player's working resolver. CDA often exposes a
     * qualities/hash2/ts tuple instead of a ready manifest on Android. Resolve
     * the highest advertised free quality through the public videoGetLink call,
     * then keep manifest/HLS/file as fallback sources for Media3.
     */
    public PlayerData resolvePlayer(Movie movie, PlayerData input, RequestToken token) throws Exception {
        if (input == null) return null;
        PlayerData p = input.copy();
        if (p.premium) return p;

        if (p.canResolveQuality()) {
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

    public void releaseForPlayback() { web.releaseForPlayback(); }

    public void shutdown() {
        net.shutdownNow();
        web.destroy();
    }
}
