package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateManager {
    public interface CheckCallback {
        void onResult(UpdateInfo info);
        void onError(String error);
    }

    public interface InstallCallback {
        void onStatus(String text);
        void onError(String error);
    }

    public static final class UpdateInfo {
        public String currentVersion = BuildConfig.VERSION_NAME;
        public String latestVersion = BuildConfig.VERSION_NAME;
        public boolean available;
        public String releaseUrl = "https://github.com/jarczakpawel/CDA-Free-Player/releases/latest";
        public String apkUrl = "";
        public String apkName = "";
        public String sha256 = "";
        public int minSdk = 28;
    }

    private static final String API = "https://api.github.com/repos/jarczakpawel/CDA-Free-Player/releases/latest";
    private final Activity activity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private File pendingApk;
    private InstallCallback pendingInstallCallback;

    public UpdateManager(Activity activity) {
        this.activity = activity;
    }

    public void shutdown() {
        pendingApk = null;
        pendingInstallCallback = null;
        io.shutdownNow();
    }

    /** Resume an update after the user grants "install unknown apps" permission. */
    public void onHostResume() {
        if (pendingApk == null || pendingInstallCallback == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) return;
        File apk = pendingApk;
        InstallCallback callback = pendingInstallCallback;
        pendingApk = null;
        pendingInstallCallback = null;
        installDownloadedApk(apk, callback);
    }

    public void check(CheckCallback callback) {
        io.execute(() -> {
            try {
                UpdateInfo info = fetchLatest();
                main.post(() -> callback.onResult(info));
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    public void checkOnStartup() {
        check(new CheckCallback() {
            @Override public void onResult(UpdateInfo info) {
                if (info.available) {
                    Toast.makeText(activity,
                            "Dostępna aktualizacja " + info.latestVersion + " • Ustawienia → Aktualizacja",
                            Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onError(String error) { }
        });
    }

    public void downloadAndInstall(UpdateInfo info, InstallCallback callback) {
        if (info == null || !info.available || info.apkUrl.isEmpty()) {
            callback.onError("Brak pliku aktualizacji dla tego urządzenia.");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < info.minSdk) {
            callback.onError("Ta aktualizacja wymaga Android API " + info.minSdk + " lub nowszego.");
            return;
        }
        io.execute(() -> {
            try {
                main.post(() -> callback.onStatus("Pobieranie aktualizacji…"));
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Nie można utworzyć katalogu aktualizacji.");
                File apk = new File(dir, info.apkName.isEmpty() ? "cda-free-player-update.apk" : info.apkName);
                download(info.apkUrl, apk);
                if (!info.sha256.isEmpty()) {
                    String got = sha256(apk);
                    if (!got.equalsIgnoreCase(info.sha256)) {
                        apk.delete();
                        throw new SecurityException("SHA-256 pobranego APK nie zgadza się z release.");
                    }
                }
                main.post(() -> {
                    callback.onStatus("Pobrano. Otwieram instalator…");
                    requestInstall(apk, callback);
                });
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    private UpdateInfo fetchLatest() throws Exception {
        JSONObject release = new JSONObject(getText(API));
        UpdateInfo out = new UpdateInfo();
        out.latestVersion = stripV(release.optString("tag_name", BuildConfig.VERSION_NAME));
        out.available = compare(out.latestVersion, BuildConfig.VERSION_NAME) > 0;
        out.releaseUrl = release.optString("html_url", out.releaseUrl);

        JSONArray assets = release.optJSONArray("assets");
        String manifestUrl = "";
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if ("update-manifest.json".equals(a.optString("name"))) {
                    manifestUrl = a.optString("browser_download_url");
                    break;
                }
            }
        }

        if (!manifestUrl.isEmpty()) {
            JSONObject manifest = new JSONObject(getText(manifestUrl));
            JSONObject android = manifest.optJSONObject("assets");
            if (android != null) android = android.optJSONObject("android-universal");
            if (android != null) {
                out.apkName = android.optString("name");
                out.sha256 = android.optString("sha256");
                out.minSdk = android.optInt("min_sdk", 28);
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (out.apkName.equals(a.optString("name"))) {
                            out.apkUrl = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
            }
        }

        // Fallback if a manifest is missing but the release has one universal Android TV APK.
        if (out.apkUrl.isEmpty() && assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name");
                String low = name.toLowerCase(Locale.US);
                if (low.contains("androidtv") && low.endsWith(".apk")) {
                    out.apkName = name;
                    out.apkUrl = a.optString("browser_download_url");
                    String digest = a.optString("digest");
                    if (digest.startsWith("sha256:")) out.sha256 = digest.substring(7);
                    break;
                }
            }
        }
        return out;
    }

    private void requestInstall(File apk, InstallCallback callback) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            pendingInstallCallback = callback;
            callback.onStatus("Zezwól na instalowanie aktualizacji z CDA Free Player…");
            Toast.makeText(activity, "Włącz instalowanie z tego źródła. Po powrocie instalacja wznowi się automatycznie.", Toast.LENGTH_LONG).show();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return;
        }
        installDownloadedApk(apk, callback);
    }

    private void installDownloadedApk(File apk, InstallCallback callback) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".files", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            callback.onStatus("Uruchamiam instalator systemowy…");
            activity.startActivity(install);
        } catch (Exception e) {
            callback.onError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        c.setRequestProperty("User-Agent", "CDA-Free-Player-Android-Updater");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("GitHub HTTP " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static void download(String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CDA-Free-Player-Android-Updater");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("Pobieranie APK: HTTP " + code);
        try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) fos.write(buf, 0, n);
            fos.getFD().sync();
        } finally { c.disconnect(); }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(Locale.US, "%02x", b));
        return s.toString();
    }

    private static String stripV(String v) {
        if (v == null) return "0.0.0";
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    private static int compare(String a, String b) {
        int[] aa = parts(a), bb = parts(b);
        for (int i = 0; i < 3; i++) {
            if (aa[i] != bb[i]) return Integer.compare(aa[i], bb[i]);
        }
        return 0;
    }

    private static int[] parts(String s) {
        int[] out = {0,0,0};
        String[] p = stripV(s).split("\\.");
        for (int i = 0; i < Math.min(3, p.length); i++) {
            try { out[i] = Integer.parseInt(p[i].replaceAll("[^0-9].*$", "")); }
            catch (Exception ignored) { }
        }
        return out;
    }
}
