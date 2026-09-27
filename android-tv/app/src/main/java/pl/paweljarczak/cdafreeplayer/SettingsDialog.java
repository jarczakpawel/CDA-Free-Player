package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebView;

public final class SettingsDialog {
    public interface Listener { void onDataChanged(); }

    private SettingsDialog() { }

    public static void show(Activity a, CdaDb db, UpdateManager updater, Listener listener, Runnable clearCacheAction) {
        LinearLayout title = new LinearLayout(a);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(a, 24), dp(a, 16), dp(a, 24), dp(a, 10));
        TextView titleText = text(a, "Ustawienia", 20, true);
        title.addView(titleText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView author = text(a, "Paweł Jarczak", 13, false);
        author.setTextColor(Color.LTGRAY);
        title.addView(author);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 22), dp(a, 4), dp(a, 22), dp(a, 16));

        Button fontScale = button(a, "Wielkość napisów: " + UiScale.label(a));
        fontScale.setOnClickListener(v -> {
            String[] labels = UiScale.labels();
            new AlertDialog.Builder(a)
                    .setTitle("Wielkość napisów")
                    .setSingleChoiceItems(labels, UiScale.selectedIndex(a), (choice, which) -> {
                        UiScale.set(a, UiScale.valueAt(which));
                        choice.dismiss();
                        Toast.makeText(a, "Wielkość napisów: " + labels[which], Toast.LENGTH_SHORT).show();
                        a.recreate();
                    })
                    .setNegativeButton("Anuluj", null)
                    .show();
        });
        box.addView(fontScale);

        TextView version = text(a, "Wersja: " + BuildConfig.VERSION_NAME, 14, false);
        version.setPadding(dp(a, 4), dp(a, 14), 0, dp(a, 2));
        box.addView(version);
        TextView updateStatus = text(a, "Sprawdzanie aktualizacji…", 12, false);
        updateStatus.setTextColor(Color.LTGRAY);
        updateStatus.setPadding(dp(a, 4), 0, 0, dp(a, 2));
        box.addView(updateStatus);
        Button update = button(a, "Sprawdź / zainstaluj aktualizację");
        update.setEnabled(false);
        box.addView(update);

        PackageInfo webView = WebView.getCurrentWebViewPackage();
        String webViewVersion = webView == null ? "brak" : webView.versionName;
        TextView webViewInfo = text(a, "Android System WebView: " + webViewVersion, 13, false);
        webViewInfo.setPadding(dp(a, 4), dp(a, 14), 0, dp(a, 2));
        box.addView(webViewInfo);
        Button updateWebView = button(a, "Aktualizuj Android System WebView");
        updateWebView.setOnClickListener(v -> {
            String pkg = webView == null ? "com.google.android.webview" : webView.packageName;
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
            } catch (RuntimeException e) {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
                } catch (RuntimeException ignored) {
                    Toast.makeText(a, "Nie można otworzyć aktualizacji WebView", Toast.LENGTH_LONG).show();
                }
            }
        });
        box.addView(updateWebView);

        Button clearHistory = button(a, "Wyczyść historię oglądania");
        clearHistory.setOnClickListener(v -> confirmDel(a, "Wyczyść historię oglądania", () -> {
            db.clearHistory();
            listener.onDataChanged();
        }));
        box.addView(clearHistory);

        Button clearFavorites = button(a, "Wyczyść ulubione");
        clearFavorites.setOnClickListener(v -> confirmDel(a, "Wyczyść ulubione", () -> {
            db.clearFavorites();
            listener.onDataChanged();
        }));
        box.addView(clearFavorites);

        Button clearCache = button(a, "Wyczyść cache");
        clearCache.setOnClickListener(v -> confirmDel(a, "Wyczyść cache", () -> {
            if (clearCacheAction != null) clearCacheAction.run();
            else db.clearCache();
            listener.onDataChanged();
        }));
        box.addView(clearCache);
        TextView cacheInfo = text(a, "Miniatury: cache adaptacyjny do ok. 400 MB. Starsze niż 24 h są usuwane automatycznie, a przy małej ilości miejsca limit sam maleje.", 12, false);
        cacheInfo.setTextColor(Color.LTGRAY);
        cacheInfo.setPadding(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 8));
        box.addView(cacheInfo);

        Button github = button(a, "GitHub");
        github.setOnClickListener(v -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jarczakpawel/CDA-Free-Player")));
            } catch (RuntimeException e) {
                Toast.makeText(a, "Brak przeglądarki na tym urządzeniu", Toast.LENGTH_LONG).show();
            }
        });
        box.addView(github);

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setCustomTitle(title)
                .setView(scroll)
                .setPositiveButton("Zamknij", null)
                .create();
        dialog.setOnShowListener(x -> fontScale.requestFocus());
        dialog.show();

        updater.check(new UpdateManager.CheckCallback() {
            @Override public void onResult(UpdateManager.UpdateInfo info) {
                if (info.available) {
                    updateStatus.setText("Dostępna wersja " + info.latestVersion + " • masz " + info.currentVersion);
                    update.setEnabled(true);
                    update.setOnClickListener(v -> updater.downloadAndInstall(info, new UpdateManager.InstallCallback() {
                        @Override public void onStatus(String value) { updateStatus.setText(value); }
                        @Override public void onError(String error) {
                            updateStatus.setText("Błąd aktualizacji: " + error);
                            Toast.makeText(a, error, Toast.LENGTH_LONG).show();
                        }
                    }));
                } else {
                    updateStatus.setText("Masz najnowszą wersję " + info.currentVersion + ".");
                    update.setEnabled(false);
                }
            }
            @Override public void onError(String error) {
                updateStatus.setText("Nie udało się sprawdzić aktualizacji: " + error);
            }
        });
    }

    private static void confirmDel(Activity a, String title, Runnable action) {
        EditText input = new EditText(a);
        input.setHint("Wpisz DEL");
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 10));
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage("Aby potwierdzić, wpisz dokładnie DEL.")
                .setView(input)
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Potwierdź", null)
                .create();
        d.setOnShowListener(x -> {
            Button ok = d.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                if (!"DEL".equals(input.getText().toString().trim())) {
                    Toast.makeText(a, "Wpisz dokładnie DEL.", Toast.LENGTH_SHORT).show();
                    return;
                }
                action.run();
                d.dismiss();
                Toast.makeText(a, "Gotowe", Toast.LENGTH_SHORT).show();
            });
            input.requestFocus();
        });
        d.show();
    }

    private static Button button(Activity a, String text) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        b.setMinHeight(dp(a, 50));
        b.setPadding(dp(a, 10), dp(a, 6), dp(a, 10), dp(a, 6));
        lp.setMargins(0, dp(a, 5), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private static TextView text(Activity a, String value, int sp, boolean bold) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private static int dp(Activity a, int x) {
        return (int) (x * a.getResources().getDisplayMetrics().density + .5f);
    }
}
