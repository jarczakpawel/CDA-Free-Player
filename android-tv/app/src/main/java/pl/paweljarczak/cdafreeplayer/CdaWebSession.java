package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Queue;

public final class CdaWebSession {
    public interface Callback {
        void onHtml(String html);
        void onError(String error);
        void onVerification(boolean interactive);
    }

    private static final long INTERACTIVE_GRACE_MS = 2200;
    private static final long WARM_IDLE_MS = 60_000;

    private static final class Job {
        final String url;
        final RequestToken token;
        final Callback cb;
        Job(String u, RequestToken t, Callback c) { url = u; token = t; cb = c; }
    }

    private final Activity activity;
    private final FrameLayout overlay, host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Queue<Job> jobs = new ArrayDeque<>();
    private WebView web;
    private Job current;
    private long started;
    private boolean shown;
    private Runnable destroyTask;

    public CdaWebSession(Activity a, FrameLayout overlay, FrameLayout host) {
        activity = a;
        this.overlay = overlay;
        this.host = host;
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private void ensureWeb() {
        if (web != null) {
            try { web.onResume(); } catch (Exception ignored) {}
            return;
        }
        web = new WebView(activity);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        if (Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) { inspect(); }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Job failed = current;
                current = null;
                dropWeb();
                if (failed != null) failed.cb.onError("Renderer WebView został odtworzony");
                h.postDelayed(CdaWebSession.this::startNext, 150);
                return true;
            }
        });
        host.addView(web, new FrameLayout.LayoutParams(-1, -1));
    }

    public void fetch(String url, RequestToken token, Callback cb) {
        jobs.add(new Job(url, token, cb));
        if (current == null) startNext();
    }

    private void startNext() {
        if (destroyTask != null) h.removeCallbacks(destroyTask);
        current = jobs.poll();
        if (current == null) {
            if (web != null) {
                try { web.onPause(); } catch (Exception ignored) {}
            }
            scheduleDestroy();
            return;
        }
        if (current.token != null && current.token.isCancelled()) {
            current.cb.onError("Anulowano");
            current = null;
            startNext();
            return;
        }
        ensureWeb();
        started = SystemClock.elapsedRealtime();
        shown = false;
        overlay.setVisibility(View.INVISIBLE);
        current.cb.onVerification(false);
        web.loadUrl(current.url);
    }

    private void inspect() {
        final Job job = current;
        if (job == null || web == null) return;
        if (job.token != null && job.token.isCancelled()) {
            cancelCurrent();
            return;
        }
        web.evaluateJavascript("(function(){return document.documentElement?document.documentElement.outerHTML:'';})()", value -> {
            if (job != current || web == null) return;
            String html = decode(value);
            if (isChallenge(html)) {
                if (!shown && SystemClock.elapsedRealtime() - started > INTERACTIVE_GRACE_MS) {
                    shown = true;
                    overlay.setVisibility(View.VISIBLE);
                    web.requestFocus();
                    job.cb.onVerification(true);
                }
                h.postDelayed(this::inspect, 250);
                return;
            }
            overlay.setVisibility(View.GONE);
            CookieManager.getInstance().flush();
            job.cb.onHtml(html);
            current = null;
            startNext();
        });
    }

    public boolean isInteractive() { return overlay.getVisibility() == View.VISIBLE; }

    public void cancelCurrent() {
        if (current != null) current.cb.onError("Anulowano");
        current = null;
        jobs.clear();
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
        }
        overlay.setVisibility(View.GONE);
        scheduleDestroy();
    }

    /** Playback gets all RAM/CPU. Cookies survive because they belong to CookieManager. */
    public void releaseForPlayback() {
        current = null;
        jobs.clear();
        overlay.setVisibility(View.GONE);
        if (destroyTask != null) h.removeCallbacks(destroyTask);
        dropWeb();
    }

    private void scheduleDestroy() {
        if (web == null) return;
        destroyTask = () -> {
            if (current != null || web == null) return;
            dropWeb();
        };
        h.postDelayed(destroyTask, WARM_IDLE_MS);
    }

    private void dropWeb() {
        WebView w = web;
        web = null;
        if (w == null) return;
        try { w.stopLoading(); } catch (Exception ignored) {}
        try { host.removeView(w); } catch (Exception ignored) {}
        try { w.clearHistory(); } catch (Exception ignored) {}
        try { w.destroy(); } catch (Exception ignored) {}
    }

    public void destroy() {
        jobs.clear();
        current = null;
        if (destroyTask != null) h.removeCallbacks(destroyTask);
        dropWeb();
        overlay.setVisibility(View.GONE);
    }

    private static boolean isChallenge(String html) {
        String s = html == null ? "" : html.toLowerCase();
        return s.contains("/cdn-cgi/challenge-platform/") || s.contains("cf-chl-") ||
                s.contains("checking if you are not a bot") || s.contains("verify you are human") ||
                s.contains("przeprowadzanie weryfikacji zabezpieczeń") || s.contains("just a moment...");
    }

    private static String decode(String v) {
        if (v == null || "null".equals(v)) return "";
        try { return new JSONObject("{\"x\":" + v + "}").getString("x"); }
        catch (Exception e) { return v; }
    }
}
