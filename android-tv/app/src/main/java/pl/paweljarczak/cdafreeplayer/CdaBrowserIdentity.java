package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.webkit.WebSettings;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CdaBrowserIdentity {
    private CdaBrowserIdentity() {}

    public static String userAgent(Context context) {
        return WebSettings.getDefaultUserAgent(context.getApplicationContext());
    }

    public static String catalogUserAgent(Context context) {
        String nativeUa = userAgent(context);
        Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(nativeUa);
        String chrome = m.find() ? m.group(1) : "120.0.0.0";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome + " Safari/537.36";
    }

    public static String mode(Context context) {
        return "native-webview";
    }

    public static void apply(Context context, WebSettings settings) {
        settings.setUserAgentString(userAgent(context));
    }
}
