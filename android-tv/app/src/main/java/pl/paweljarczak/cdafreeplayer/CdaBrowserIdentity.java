package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.webkit.WebSettings;

public final class CdaBrowserIdentity {
    private CdaBrowserIdentity() {}

    public static String userAgent(Context context) {
        return WebSettings.getDefaultUserAgent(context.getApplicationContext());
    }

    public static String mode(Context context) {
        return "native-webview";
    }

    public static void apply(Context context, WebSettings settings) {
        settings.setUserAgentString(userAgent(context));
    }
}
