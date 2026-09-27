package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.os.Build;
import android.webkit.WebSettings;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CdaBrowserIdentity {
    private static final String FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Android TV Build/RQ3A.211001.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/153.0.8010.36 Safari/537.36";
    private static final Pattern ANDROID_TOKEN = Pattern.compile("^Android\\s+.+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUILD_TOKEN = Pattern.compile("(?i)\\bBuild/([^;\\s)]+)");
    private static final Pattern LOCALE_TOKEN = Pattern.compile("(?i)^[a-z]{2,3}(?:[-_][a-z]{2})?$");
    private static volatile String cachedUserAgent;

    private CdaBrowserIdentity() {}

    public static String userAgent(Context context) {
        CdaApplication.awaitWebViewStartup();
        String cached = cachedUserAgent;
        if (cached != null && !cached.isEmpty()) return cached;
        synchronized (CdaBrowserIdentity.class) {
            cached = cachedUserAgent;
            if (cached == null || cached.isEmpty()) {
                String nativeUa = WebSettings.getDefaultUserAgent(context.getApplicationContext());
                cached = normalizeAndroidTv(nativeUa);
                cachedUserAgent = cached;
            }
            return cached;
        }
    }

    public static String catalogUserAgent(Context context) {
        return userAgent(context);
    }

    public static String mode(Context context) {
        return "android-tv-network-identity-native-build";
    }

    public static void apply(Context context, WebSettings settings) {
        String ua = userAgent(context);
        settings.setUserAgentString(ua);
        cachedUserAgent = ua;
    }

    private static String normalizeAndroidTv(String nativeUa) {
        if (nativeUa == null || nativeUa.trim().isEmpty()) return FALLBACK_USER_AGENT;

        int start = nativeUa.indexOf('(');
        int end = start < 0 ? -1 : nativeUa.indexOf(')', start + 1);
        if (start < 0 || end <= start) return FALLBACK_USER_AGENT;

        String inside = nativeUa.substring(start + 1, end);
        String[] rawParts = inside.split(";");
        ArrayList<String> parts = new ArrayList<>(rawParts.length + 1);
        for (String raw : rawParts) parts.add(raw.trim());

        int androidIndex = -1;
        for (int i = 0; i < parts.size(); i++) {
            if (ANDROID_TOKEN.matcher(parts.get(i)).matches()) {
                androidIndex = i;
                break;
            }
        }
        if (androidIndex < 0) return FALLBACK_USER_AGENT;

        int buildIndex = -1;
        String nativeBuildId = "";
        for (int i = androidIndex + 1; i < parts.size(); i++) {
            Matcher m = BUILD_TOKEN.matcher(parts.get(i));
            if (m.find()) {
                buildIndex = i;
                nativeBuildId = sanitizeBuildId(m.group(1));
                String buildAndSuffix = parts.get(i).substring(m.start()).trim();
                parts.set(i, "Android TV " + buildAndSuffix);
                break;
            }
        }

        if (buildIndex < 0) {
            String buildId = platformBuildId();
            int descriptorIndex = findDescriptorIndex(parts, androidIndex);
            String descriptor = "Android TV Build/" + buildId;
            if (descriptorIndex >= 0) parts.set(descriptorIndex, descriptor);
            else parts.add(androidIndex + 1, descriptor);
        } else if (nativeBuildId.isEmpty()) {
            parts.set(buildIndex, "Android TV Build/" + platformBuildId());
        }

        StringBuilder normalizedInside = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (normalizedInside.length() > 0) normalizedInside.append("; ");
            normalizedInside.append(part);
        }
        if (normalizedInside.length() == 0) return FALLBACK_USER_AGENT;

        return nativeUa.substring(0, start + 1) + normalizedInside + nativeUa.substring(end);
    }

    private static int findDescriptorIndex(ArrayList<String> parts, int androidIndex) {
        for (int i = androidIndex + 1; i < parts.size(); i++) {
            String token = parts.get(i).trim();
            if (token.isEmpty()) continue;
            if (token.equalsIgnoreCase("wv")) continue;
            if (LOCALE_TOKEN.matcher(token).matches()) continue;
            return i;
        }
        return -1;
    }

    private static String platformBuildId() {
        String id = sanitizeBuildId(Build.ID);
        if (usableBuildId(id)) return id;

        id = sanitizeBuildId(Build.DISPLAY);
        if (usableBuildId(id)) return id;

        id = sanitizeBuildId(Build.VERSION.INCREMENTAL);
        if (usableBuildId(id)) return id;

        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.trim();
        if (!fingerprint.isEmpty() && !"unknown".equalsIgnoreCase(fingerprint)) {
            return String.format(Locale.US, "AOSP.%08X", fingerprint.hashCode());
        }
        return "RQ3A.211001.001";
    }

    private static boolean usableBuildId(String value) {
        return value != null && !value.isEmpty() && !"UNKNOWN".equalsIgnoreCase(value);
    }

    private static String sanitizeBuildId(String value) {
        if (value == null) return "";
        String out = value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.length() > 64) out = out.substring(0, 64);
        return out;
    }
}
