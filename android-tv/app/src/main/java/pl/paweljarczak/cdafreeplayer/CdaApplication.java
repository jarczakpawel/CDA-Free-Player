package pl.paweljarczak.cdafreeplayer;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class CdaApplication extends Application {
    private static final String TAG = "CDAFP";
    private static final boolean TRACE = false;
    private static final CountDownLatch WEBVIEW_STARTED = new CountDownLatch(1);
    private static volatile boolean webViewStartupFinished;
    private ExecutorService webStartup;

    @Override
    public void onCreate() {
        super.onCreate();
        final long started = SystemClock.elapsedRealtime();
        webStartup = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "webview-startup");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        try {
            WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(webStartup).build();
            WebViewCompat.startUpWebView(getApplicationContext(), config,
                    new WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>() {
                        @Override public void onResult(@NonNull WebViewStartUpResult result) {
                            try { CdaBrowserIdentity.userAgent(getApplicationContext()); } catch (Throwable ignored) {}
                            if (TRACE) Log.i(TAG, "WebView async startup ready in " + (SystemClock.elapsedRealtime() - started) + "ms");
                            finishWebViewStartup();
                        }
                        @Override public void onError(@NonNull WebViewStartupException error) {
                            Log.w(TAG, "WebView async startup failed after " + (SystemClock.elapsedRealtime() - started) + "ms: " + error.getClass().getSimpleName());
                            finishWebViewStartup();
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "WebView async startup unavailable: " + t.getClass().getSimpleName());
            finishWebViewStartup();
        }
    }

    public static void awaitWebViewStartup() {
        if (webViewStartupFinished || Looper.myLooper() == Looper.getMainLooper()) return;
        try { WEBVIEW_STARTED.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void finishWebViewStartup() {
        webViewStartupFinished = true;
        WEBVIEW_STARTED.countDown();
        shutdownStartupExecutor();
    }

    private void shutdownStartupExecutor() {
        ExecutorService e = webStartup;
        webStartup = null;
        if (e != null) e.shutdown();
    }
}
