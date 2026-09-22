package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.webkit.WebSettings;

public final class CdaBrowserIdentity {
    private static volatile String cachedUserAgent;

    private CdaBrowserIdentity() {}

    public static String userAgent(Context context) {
        CdaApplication.awaitWebViewStartup();
        String cached = cachedUserAgent;
        if (cached != null && !cached.isEmpty()) return cached;
        synchronized (CdaBrowserIdentity.class) {
            cached = cachedUserAgent;
            if (cached == null || cached.isEmpty()) {
                cached = WebSettings.getDefaultUserAgent(context.getApplicationContext());
                cachedUserAgent = cached;
            }
            return cached;
        }
    }

    public static String catalogUserAgent(Context context) {
        return userAgent(context);
    }

    public static String mode(Context context) {
        return "native-webview-consistent";
    }

    public static void apply(Context context, WebSettings settings) {
        String nativeUa = settings.getUserAgentString();
        if (nativeUa != null && !nativeUa.isEmpty()) cachedUserAgent = nativeUa;
    }
}
