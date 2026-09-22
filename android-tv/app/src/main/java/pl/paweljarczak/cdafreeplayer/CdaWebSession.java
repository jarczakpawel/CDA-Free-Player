package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public final class CdaWebSession {
    public interface Callback {
        void onHtml(String html);
        void onError(String error);
        void onVerification(boolean interactive);
    }

    private static final String TAG = "CDAFP";
    private static final long INTERACTIVE_GRACE_MS = 250;
    private static final long NORMAL_SETTLE_MS = 250;
    private static final long SEARCH_SETTLE_MS = 2500;
    private static final long PLAYER_SETTLE_MS = 12000;
    private static final long INSPECT_RETRY_MS = 180;
    private static final long CHALLENGE_RETRY_MS = 900;
    private static final long FULL_SITE_TIMEOUT_MS = 8000;
    private static final long PLAYBACK_IDLE_MS = 900;
    private static final long BROWSE_IDLE_MS = 1800;

    private static final Set<String> CDA_ORIGINS = new HashSet<>(Arrays.asList(
            "https://cda.pl",
            "https://www.cda.pl",
            "https://*.cda.pl"
    ));

    private String captureScript;
    private ScriptHandler captureHook;
    private Runnable inspectTask, timeoutTask, fullSiteTask;
    private boolean destroyed, fullSiteBootstrap, playbackContext;
    private volatile boolean fullSitePrepared;

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        boolean inspecting;
        final String url;
        final RequestToken token;
        final Callback cb;
        final boolean expectPlayer;
        Job(String u, RequestToken t, Callback c, boolean player) {
            url = u; token = t; cb = c; expectPlayer = player;
        }
    }

    private final Activity activity;
    private final FrameLayout overlay, host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Queue<Job> jobs = new ArrayDeque<>();
    private WebView web;
    private Job current;
    private long started, cleanSince, challengeSince;
    private boolean shown;
    private Runnable destroyTask;
    private String capturedPlayerData = "";

    public CdaWebSession(Activity a, FrameLayout overlay, FrameLayout host) {
        activity = a;
        this.overlay = overlay;
        this.host = host;
    }

    private void ensureWeb() {
        if (captureScript == null) {
            try (InputStream in = activity.getAssets().open("player_capture.js");
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                captureScript = out.toString(StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                throw new IllegalStateException("Brak skryptu playera w aplikacji", e);
            }
        }
        if (web != null) {
            if (destroyTask != null) { h.removeCallbacks(destroyTask); destroyTask = null; }
            try { web.onResume(); } catch (Exception ignored) {}
            return;
        }

        long webStarted = SystemClock.elapsedRealtime();
        web = new WebView(activity);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setHorizontalScrollBarEnabled(false);
        web.setVerticalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CdaBrowserIdentity.apply(activity, s);
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOWNLOAD_FAVICONS_ENABLED)) {
                WebSettingsCompat.setDownloadFaviconsEnabled(s, false);
            }
        } catch (Throwable ignored) {}

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);

        if (Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        PackageInfo provider = WebView.getCurrentWebViewPackage();
        Log.i(TAG, "WebView instance ready in " + (SystemClock.elapsedRealtime() - webStarted) + "ms" +
                (provider == null ? "" : "; provider=" + provider.packageName + "/" + provider.versionName));

        installPlayerCaptureBridge();

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) {
                Job job = current;
                if (job == null) return;
                if (fullSiteBootstrap) {
                    Log.i(TAG, "CDA full-site bootstrap page finished: " + safeUrl(u));
                    scheduleInspect(job, 0);
                    return;
                }
                if (!samePage(job.url, u)) return;
                Log.i(TAG, (job.expectPlayer ? "player" : "page") + " WebView ready: " + safeUrl(u));
                if (job.expectPlayer) {
                    try {
                        v.evaluateJavascript(scriptFor(job), ignored -> scheduleInspect(job, 0));
                        return;
                    } catch (Throwable ignored) {}
                }
                scheduleInspect(job, 0);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && current != null && samePage(current.url, request.getUrl().toString())) {
                    failCurrent(current, "Błąd WebView: " + error.getErrorCode());
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Job failed = current;
                boolean wasInteractive = shown;
                current = null;
                clearPending();
                shown = false;
                overlay.setAlpha(1f);
                overlay.setFocusable(false);
                overlay.setFocusableInTouchMode(false);
                overlay.setVisibility(View.GONE);
                dropWeb();
                if (failed != null) {
                    if (wasInteractive) failed.cb.onVerification(false);
                    failed.cb.onError("Renderer WebView został odtworzony");
                }
                h.postDelayed(CdaWebSession.this::startNext, 150);
                return true;
            }
        });
        host.addView(web, new FrameLayout.LayoutParams(-1, -1));
        Log.i(TAG, "WebView created; identity=" + CdaBrowserIdentity.mode(activity) +
                "; ua=" + s.getUserAgentString());
    }

    public void clearCache() {
        h.post(() -> {
            try { if (web != null) web.clearCache(true); }
            catch (RuntimeException ignored) {}
        });
    }

    private void installPlayerCaptureBridge() {
        if (web == null) return;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(web, "CdaFreePlayerBridge", CDA_ORIGINS,
                        new WebViewCompat.WebMessageListener() {
                            @Override
                            public void onPostMessage(WebView view, WebMessageCompat message, Uri sourceOrigin,
                                                      boolean isMainFrame, JavaScriptReplyProxy replyProxy) {
                                Job job = current;
                                if (job == null || !job.expectPlayer || fullSiteBootstrap || job.token != null && job.token.isCancelled()) return;
                                try {
                                    JSONObject data = new JSONObject(message.getData());
                                    if (!job.id.equals(data.optString("job"))) return;
                                    String raw = data.optString("data");
                                    if (raw.isEmpty() || raw.length() > 2 * 1024 * 1024) return;
                                    PlayerData parsed = CdaParser.parsePlayerData(CdaParser.PLAYER_DATA_PREFIX + raw);
                                    if (parsed == null || !parsed.hasPlayableSource() && !parsed.canResolveQuality()) return;
                                    capturedPlayerData = raw;
                                    Log.i(TAG, "player_data captured; bytes=" + raw.length() + "; mainFrame=" + isMainFrame);
                                    finishCurrent(job, CdaParser.PLAYER_DATA_PREFIX + raw);
                                } catch (Exception ignored) {}
                            }
                        });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Player capture bridge unavailable: " + t);
        }
    }

    public boolean isFullSitePrepared() { return fullSitePrepared; }

    public void markFullSitePrepared() { fullSitePrepared = true; }

    public void fetch(String url, RequestToken token, Callback cb) {
        enqueue(new Job(url, token, cb, false));
    }

    public void fetchPlayer(String url, RequestToken token, Callback cb) {
        enqueue(new Job(url, token, cb, true));
    }

    private void enqueue(Job job) {
        if (destroyed) return;
        jobs.add(job);
        if (current == null) startNext();
    }

    private void startNext() {
        if (destroyed || current != null) return;
        if (destroyTask != null) h.removeCallbacks(destroyTask);
        current = jobs.poll();
        if (current == null) {
            parkIdleWeb();
            scheduleDestroy();
            return;
        }
        if (current.token != null && current.token.isCancelled()) {
            current.cb.onError("Anulowano");
            current = null;
            startNext();
            return;
        }

        try {
            ensureWeb();
        } catch (Exception e) {
            dropWeb();
            failCurrent(current, "Nie można uruchomić WebView. Sprawdź Android System WebView.");
            return;
        }
        started = SystemClock.elapsedRealtime();
        cleanSince = 0L;
        challengeSince = 0L;
        shown = false;
        capturedPlayerData = "";
        overlay.setAlpha(0f);
        overlay.setFocusable(false);
        overlay.setFocusableInTouchMode(false);
        overlay.setVisibility(View.VISIBLE);
        if (web != null) {
            web.setFocusable(false);
            web.setFocusableInTouchMode(false);
        }
        current.cb.onVerification(false);
        Log.i(TAG, (current.expectPlayer ? "player" : "page") + " WebView load: " + safeUrl(current.url));
        Job job = current;
        try {
            if (captureHook != null) { captureHook.remove(); captureHook = null; }
            if (job.expectPlayer && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                captureHook = WebViewCompat.addDocumentStartJavaScript(web, scriptFor(job), CDA_ORIGINS);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Document-start capture unavailable; using page-load capture");
        }
        timeoutTask = () -> failCurrent(job, "Przekroczono czas ładowania strony CDA");
        h.postDelayed(timeoutTask, 120000);
        try {
            if (!fullSitePrepared) {
                fullSiteBootstrap = true;
                Log.i(TAG, "Preparing CDA full-site session");
                fullSiteTask = () -> {
                    if (job != current || web == null || !fullSiteBootstrap) return;
                    fullSiteBootstrap = false;
                    Log.w(TAG, "CDA full-site bootstrap timed out; continuing without forcing site mode");
                    web.loadUrl(job.url);
                };
                h.postDelayed(fullSiteTask, FULL_SITE_TIMEOUT_MS);
                web.loadUrl("https://m.cda.pl/gofullcda");
                scheduleInspect(job, 250);
            } else {
                web.loadUrl(job.url);
                scheduleInspect(job, 250);
            }
        } catch (RuntimeException e) { failCurrent(job, "Nie można otworzyć strony CDA"); }
    }

    private void inspect(Job job) {
        if (job != current || web == null || job.inspecting) return;
        if (job.token != null && job.token.isCancelled()) {
            cancel(job.token);
            return;
        }
        job.inspecting = true;
        if (fullSiteBootstrap) inspectFullSiteBootstrap(job);
        else if (job.expectPlayer) inspectPlayer(job);
        else inspectPage(job);
    }

    private void inspectFullSiteBootstrap(final Job job) {
        if (job != current || web == null) return;
        String deep = challengeSince == 0L ?
                "var s=(document.body&&document.body.innerText.length<4096?document.body.innerText:'').toLowerCase();" +
                "if(s.indexOf('checking if you are not a bot')>=0||s.indexOf('verify you are human')>=0||s.indexOf('przeprowadzanie weryfikacji zabezpieczeń')>=0)return 'CF';" : "";
        String script = "(function(){try{" +
                "if(document.querySelector('#challenge-running,#challenge-form,.cf-challenge,iframe[src*=\"challenges.cloudflare.com\"]')||" +
                "(document.title||'').toLowerCase().indexOf('just a moment')>=0)return 'CF';" + deep +
                "return document.readyState+'|'+location.host+'|'+location.pathname;}catch(e){return 'ERR';}})()";
        web.evaluateJavascript(script, value -> {
            job.inspecting = false;
            if (job != current || web == null || !fullSiteBootstrap) return;
            String state = decode(value);
            long now = SystemClock.elapsedRealtime();
            if ("CF".equals(state)) {
                cleanSince = 0L;
                markChallenge(job, now);
                showVerificationWhenNeeded(job, now);
                scheduleInspect(job, CHALLENGE_RETRY_MS);
                return;
            }
            if (!state.startsWith("complete|")) {
                scheduleInspect(job, INSPECT_RETRY_MS);
                return;
            }
            markChallengeCleared(now);
            String[] parts = state.split("\\|", 3);
            String hostName = parts.length > 1 ? parts[1] : "";
            if ("www.cda.pl".equalsIgnoreCase(hostName) || "cda.pl".equalsIgnoreCase(hostName)) {
                fullSiteBootstrap = false;
                fullSitePrepared = true;
                if (fullSiteTask != null) h.removeCallbacks(fullSiteTask);
                fullSiteTask = null;
                Log.i(TAG, "CDA full-site session prepared in " + (now - started) + "ms");
                h.post(() -> {
                    if (job == current && web != null) web.loadUrl(job.url);
                });
                return;
            }
            if (fullSiteTask == null) {
                fullSiteTask = () -> {
                    if (job != current || web == null || !fullSiteBootstrap) return;
                    fullSiteBootstrap = false;
                    Log.w(TAG, "CDA full-site redirect did not complete; continuing with current site mode");
                    web.loadUrl(job.url);
                };
                h.postDelayed(fullSiteTask, 700);
            }
            scheduleInspect(job, INSPECT_RETRY_MS);
        });
    }

    private void inspectPlayer(final Job job) {
        if (job != current || web == null) return;
        if (!capturedPlayerData.isEmpty()) {
            finishCurrent(job, CdaParser.PLAYER_DATA_PREFIX + capturedPlayerData);
            return;
        }

        String deep = challengeSince == 0L ?
                "var s=(document.body&&document.body.innerText.length<4096?document.body.innerText:'').toLowerCase();" +
                "if(s.indexOf('checking if you are not a bot')>=0||s.indexOf('verify you are human')>=0||s.indexOf('przeprowadzanie weryfikacji zabezpieczeń')>=0)return 'CF:';" : "";
        String script = "(function(){try{" +
                "var raw=window.__CDA_FP_READ_PLAYER?window.__CDA_FP_READ_PLAYER():'';if(raw)return 'PD:'+raw;" +
                "if(document.querySelector('#challenge-running,#challenge-form,.cf-challenge,iframe[src*=\"challenges.cloudflare.com\"]')||" +
                "(document.title||'').toLowerCase().indexOf('just a moment')>=0)return 'CF:';" + deep +
                "return 'OK:'+document.readyState;}catch(e){return 'ERR:'+String(e);}})()";

        web.evaluateJavascript(script, value -> {
            job.inspecting = false;
            if (job != current || web == null) return;
            String state = decode(value);
            long now = SystemClock.elapsedRealtime();

            if (state.startsWith("PD:")) {
                String raw = state.substring(3);
                PlayerData parsed = CdaParser.parsePlayerData(CdaParser.PLAYER_DATA_PREFIX + raw);
                if (parsed != null && (parsed.hasPlayableSource() || parsed.canResolveQuality())) {
                    capturedPlayerData = raw;
                    finishCurrent(job, CdaParser.PLAYER_DATA_PREFIX + raw);
                    return;
                }
            }

            if (state.startsWith("CF:")) {
                cleanSince = 0L;
                markChallenge(job, now);
                showVerificationWhenNeeded(job, now);
                scheduleInspect(job, CHALLENGE_RETRY_MS);
                return;
            }

            if (!state.startsWith("OK:complete")) {
                scheduleInspect(job, INSPECT_RETRY_MS);
                return;
            }
            markChallengeCleared(now);
            if (cleanSince == 0L) cleanSince = now;
            long cleanFor = now - cleanSince;
            if (cleanFor < PLAYER_SETTLE_MS) {
                scheduleInspect(job, INSPECT_RETRY_MS);
                return;
            }

            web.evaluateJavascript(
                    "(function(){return document.documentElement?document.documentElement.outerHTML:'';})()",
                    htmlValue -> {
                        if (job != current || web == null) return;
                        String html = decode(htmlValue);
                        Log.i(TAG, "player WebView settle without direct capture; htmlBytes=" + html.length() +
                                "; signal=" + hasPlayerSignal(html));
                        finishCurrent(job, html);
                    });
        });
    }

    private void inspectPage(final Job job) {
        String deep = challengeSince == 0L ?
                "var s=(document.body&&document.body.innerText.length<4096?document.body.innerText:'').toLowerCase();" +
                "if(s.indexOf('checking if you are not a bot')>=0||s.indexOf('verify you are human')>=0||s.indexOf('przeprowadzanie weryfikacji zabezpieczeń')>=0)return 'CF';" : "";
        String script = "(function(){try{" +
                "if(document.querySelector('#challenge-running,#challenge-form,.cf-challenge,iframe[src*=\"challenges.cloudflare.com\"]')||" +
                "(document.title||'').toLowerCase().indexOf('just a moment')>=0)return 'CF';" + deep +
                "return document.readyState+':'+(document.querySelector('.video-clip-wrapper,.link-title-visit,a[href*=\"/video/\"]')?'1':'0');" +
                "}catch(e){return 'ERR';}})()";
        web.evaluateJavascript(script, value -> {
            job.inspecting = false;
            if (job != current || web == null) return;
            String state = decode(value);
            long now = SystemClock.elapsedRealtime();
            if ("CF".equals(state)) {
                cleanSince = 0L;
                markChallenge(job, now);
                showVerificationWhenNeeded(job, now);
                scheduleInspect(job, CHALLENGE_RETRY_MS);
                return;
            }
            if (!state.startsWith("complete:")) {
                scheduleInspect(job, INSPECT_RETRY_MS);
                return;
            }
            markChallengeCleared(now);
            if (cleanSince == 0L) cleanSince = now;
            long cleanFor = now - cleanSince;
            boolean waitForSearch = isSearchUrl(job.url) && !state.endsWith(":1") && cleanFor < SEARCH_SETTLE_MS;
            if (cleanFor < NORMAL_SETTLE_MS || waitForSearch) {
                scheduleInspect(job, INSPECT_RETRY_MS);
                return;
            }
            web.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''", html -> {
                if (job == current) finishCurrent(job, decode(html));
            });
        });
    }

    private void markChallenge(Job job, long now) {
        if (challengeSince != 0L) return;
        challengeSince = now;
        if (fullSiteBootstrap && fullSiteTask != null) {
            h.removeCallbacks(fullSiteTask);
            fullSiteTask = null;
        }
        Log.i(TAG, "Cloudflare challenge detected after " + (now - started) + "ms; bootstrap=" + fullSiteBootstrap);
    }

    private void markChallengeCleared(long now) {
        if (challengeSince == 0L) return;
        boolean clearance = false;
        try {
            String cookie = CookieManager.getInstance().getCookie("https://www.cda.pl/");
            clearance = cookie != null && cookie.contains("cf_clearance=");
        } catch (Exception ignored) {}
        Log.i(TAG, "Cloudflare challenge cleared in " + (now - challengeSince) + "ms; clearance=" + clearance);
        challengeSince = 0L;
        if (shown && fullSiteBootstrap) {
            shown = false;
            overlay.setAlpha(0f);
            overlay.setFocusable(false);
            overlay.setFocusableInTouchMode(false);
            if (web != null) {
                web.setFocusable(false);
                web.setFocusableInTouchMode(false);
            }
            Job job = current;
            if (job != null) job.cb.onVerification(false);
        }
    }

    private void showVerificationWhenNeeded(Job job, long now) {
        if (!shown && now - started > INTERACTIVE_GRACE_MS) {
            shown = true;
            overlay.setAlpha(1f);
            overlay.setFocusable(true);
            overlay.setFocusableInTouchMode(true);
            overlay.setVisibility(View.VISIBLE);
            if (web != null) {
                web.setFocusable(true);
                web.setFocusableInTouchMode(true);
                web.requestFocus();
            }
            job.cb.onVerification(true);
            Log.i(TAG, "Cloudflare verification shown after " + (now - started) + "ms");
        }
    }

    private void finishCurrent(Job job, String html) {
        if (job == null || job != current) return;
        boolean wasInteractive = shown;
        clearPending();
        hideOverlay();
        if (web != null) {
            web.setFocusable(false);
            web.setFocusableInTouchMode(false);
            web.stopLoading();
        }
        flushCookies();
        current = null;
        if (job.token == null || !job.token.isCancelled()) {
            if (wasInteractive) job.cb.onVerification(false);
            job.cb.onHtml(html == null ? "" : html);
        }
        startNext();
    }

    private String scriptFor(Job job) {
        return "window.__CDA_FP_JOB__=" + JSONObject.quote(job.id) + ";" + captureScript;
    }

    private void scheduleInspect(Job job, long delay) {
        if (job != current || destroyed) return;
        if (inspectTask != null) h.removeCallbacks(inspectTask);
        inspectTask = () -> inspect(job);
        h.postDelayed(inspectTask, delay);
    }

    private void clearPending() {
        if (inspectTask != null) h.removeCallbacks(inspectTask);
        if (timeoutTask != null) h.removeCallbacks(timeoutTask);
        if (fullSiteTask != null) h.removeCallbacks(fullSiteTask);
        inspectTask = null;
        timeoutTask = null;
        fullSiteTask = null;
        fullSiteBootstrap = false;
    }

    private void failCurrent(Job job, String error) {
        if (job == null || job != current) return;
        boolean wasInteractive = shown;
        clearPending();
        current = null;
        hideOverlay();
        if (web != null) {
            web.setFocusable(false);
            web.setFocusableInTouchMode(false);
            web.stopLoading();
        }
        if (job.token == null || !job.token.isCancelled()) {
            if (wasInteractive) job.cb.onVerification(false);
            job.cb.onError(error);
        }
        startNext();
    }

    private static boolean samePage(String expected, String actual) {
        if (actual == null) return false;
        Uri wanted = Uri.parse(expected), got = Uri.parse(actual);
        String host = got.getHost(), path = got.getPath(), target = wanted.getPath();
        return host != null && (host.equalsIgnoreCase("cda.pl") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".cda.pl")) &&
                path != null && target != null && (path.equals(target) ||
                !target.contains("/show/") && path.startsWith(target + "/"));
    }

    public boolean isInteractive() { return shown && overlay.getVisibility() == View.VISIBLE; }

    public void cancel(RequestToken token) {
        if (token == null) return;
        jobs.removeIf(job -> job.token == token);
        if (current != null && current.token == token) {
            Job cancelled = current;
            boolean wasInteractive = shown;
            clearPending();
            current = null;
            capturedPlayerData = "";
            if (web != null) {
                web.setFocusable(false);
                web.setFocusableInTouchMode(false);
                web.stopLoading();
                web.loadUrl("about:blank");
            }
            shown = false;
            overlay.setAlpha(1f);
            overlay.setFocusable(false);
            overlay.setFocusableInTouchMode(false);
            overlay.setVisibility(View.GONE);
            if (wasInteractive) cancelled.cb.onVerification(false);
            startNext();
        }
    }

    public void cancelCurrent() {
        Job cancelled = current;
        boolean wasInteractive = shown;
        current = null;
        clearPending();
        Queue<Job> cancelledJobs = new ArrayDeque<>(jobs);
        jobs.clear();
        capturedPlayerData = "";
        if (web != null) {
            try { web.stopLoading(); } catch (Exception ignored) {}
            try { web.loadUrl("about:blank"); } catch (Exception ignored) {}
        }
        shown = false;
        overlay.setAlpha(1f);
        overlay.setFocusable(false);
        overlay.setFocusableInTouchMode(false);
        overlay.setVisibility(View.GONE);
        if (cancelled != null) {
            if (wasInteractive) cancelled.cb.onVerification(false);
            cancelled.cb.onError("Anulowano");
        }
        for (Job job : cancelledJobs) job.cb.onError("Anulowano");
        scheduleDestroy();
    }

    public void setPlaybackContext(boolean active) {
        playbackContext = active;
        if (active && current == null) {
            parkIdleWeb();
            scheduleDestroy();
        }
    }

    public void releaseForPlayback() {
        clearPending();
        current = null;
        jobs.clear();
        capturedPlayerData = "";
        hideOverlay();
        if (destroyTask != null) { h.removeCallbacks(destroyTask); destroyTask = null; }
        flushCookies();
        dropWeb();
    }

    private void parkIdleWeb() {
        hideOverlay();
        if (web == null) return;
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        try { web.stopLoading(); } catch (Exception ignored) {}
        try { web.loadUrl("about:blank"); } catch (Exception ignored) {}
        try { web.onPause(); } catch (Exception ignored) {}
    }

    private void hideOverlay() {
        shown = false;
        overlay.setAlpha(1f);
        overlay.setFocusable(false);
        overlay.setFocusableInTouchMode(false);
        overlay.setVisibility(View.GONE);
    }

    private void flushCookies() {
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    private void scheduleDestroy() {
        if (destroyTask != null) h.removeCallbacks(destroyTask);
        destroyTask = null;
        if (web == null || current != null) return;
        final long delay = playbackContext ? PLAYBACK_IDLE_MS : BROWSE_IDLE_MS;
        destroyTask = () -> {
            destroyTask = null;
            if (current != null || web == null) return;
            flushCookies();
            dropWeb();
            Log.i(TAG, (playbackContext ? "Playback" : "Browse") + " WebView released after idle");
        };
        h.postDelayed(destroyTask, delay);
    }

    private void dropWeb() {
        if (destroyTask != null) { h.removeCallbacks(destroyTask); destroyTask = null; }
        WebView w = web;
        web = null;
        captureHook = null;
        if (w == null) return;
        try { w.stopLoading(); } catch (Exception ignored) {}
        try { host.removeView(w); } catch (Exception ignored) {}
        try { w.clearHistory(); } catch (Exception ignored) {}
        try { w.destroy(); } catch (Exception ignored) {}
    }

    public void destroy() {
        destroyed = true;
        h.removeCallbacksAndMessages(null);
        jobs.clear();
        current = null;
        capturedPlayerData = "";
        if (destroyTask != null) { h.removeCallbacks(destroyTask); destroyTask = null; }
        flushCookies();
        dropWeb();
        hideOverlay();
    }

    private static boolean isSearchUrl(String url) {
        return url != null && url.contains("/video/show/");
    }

    private static boolean hasPlayerSignal(String html) {
        String s = html == null ? "" : html.toLowerCase();
        return s.startsWith(CdaParser.PLAYER_DATA_PREFIX.toLowerCase()) ||
                s.contains("player_data=") || s.contains("player_data =") ||
                s.contains("manifest_apple") || (s.contains("\"qualities\"") && s.contains("\"hash2\""));
    }

    private static String decode(String v) {
        if (v == null || "null".equals(v)) return "";
        try { return new JSONObject("{\"x\":" + v + "}").getString("x"); }
        catch (Exception e) { return v; }
    }

    private static String safeUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
