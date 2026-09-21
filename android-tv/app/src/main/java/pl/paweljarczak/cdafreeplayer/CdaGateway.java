package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
                main.post(() -> web.fetch(url, token, new CdaWebSession.Callback() {
                    @Override public void onHtml(String html) { cb.onHtml(html, true); }
                    @Override public void onError(String e) { cb.onError(e); }
                    @Override public void onVerification(boolean interactive) { cb.onVerification(interactive); }
                }));
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                main.post(() -> cb.onError(e.toString()));
            }
        });
    }

    public void releaseForPlayback() { web.releaseForPlayback(); }

    public void shutdown() {
        net.shutdownNow();
        web.destroy();
    }
}
