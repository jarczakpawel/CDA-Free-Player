package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsDialog {
    public interface Listener { void onDataChanged(); }

    private SettingsDialog() { }

    public static void show(Activity a, CdaDb db, UpdateManager updater, Listener listener) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 22), dp(a, 12), dp(a, 22), dp(a, 16));

        TextView version = text(a, "CDA Free Player  " + BuildConfig.VERSION_NAME, 18, true);
        box.addView(version);
        TextView author = text(a, "Autor: Paweł Jarczak", 14, false);
        author.setPadding(0, dp(a, 8), 0, 0);
        box.addView(author);

        Button github = button(a, "GitHub");
        github.setOnClickListener(v -> a.startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/jarczakpawel/CDA-Free-Player"))));
        box.addView(github);

        TextView updateStatus = text(a, "Aktualizacja: sprawdzanie…", 14, false);
        updateStatus.setPadding(0, dp(a, 14), 0, dp(a, 5));
        box.addView(updateStatus);
        Button update = button(a, "Sprawdź / zainstaluj aktualizację");
        update.setEnabled(false);
        box.addView(update);

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

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Ustawienia")
                .setView(box)
                .setPositiveButton("Zamknij", null)
                .create();
        dialog.setOnShowListener(x -> github.requestFocus());
        dialog.show();

        updater.check(new UpdateManager.CheckCallback() {
            @Override public void onResult(UpdateManager.UpdateInfo info) {
                if (info.available) {
                    updateStatus.setText("Dostępna wersja " + info.latestVersion + " • masz " + info.currentVersion);
                    update.setEnabled(true);
                    update.setOnClickListener(v -> updater.downloadAndInstall(info, new UpdateManager.InstallCallback() {
                        @Override public void onStatus(String text) { updateStatus.setText(text); }
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(a, 50));
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
