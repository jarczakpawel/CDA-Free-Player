package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

public final class TvDialogs {
    private static final int GOLD = Color.rgb(255, 210, 46);
    private TvDialogs() {}

    private static int step(KeyEvent event, ScrollView sc) {
        int viewport = Math.max(240, sc.getHeight());
        int repeat = event.getRepeatCount();
        if (repeat < 3) return Math.max(160, viewport / 5);
        if (repeat < 8) return Math.max(260, viewport / 3);
        return Math.max(420, viewport / 2);
    }

    private static void installTvScroll(Dialog dialog, ScrollView sc) {
        sc.setFocusable(true);
        sc.setFocusableInTouchMode(true);
        dialog.setOnShowListener(x -> sc.requestFocus());
        dialog.setOnKeyListener((x, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int amount = step(event, sc);
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                sc.smoothScrollBy(0, amount);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                sc.smoothScrollBy(0, -amount);
                return true;
            }
            if (key == KeyEvent.KEYCODE_PAGE_DOWN) {
                sc.smoothScrollBy(0, Math.max(300, sc.getHeight() - 80));
                return true;
            }
            if (key == KeyEvent.KEYCODE_PAGE_UP) {
                sc.smoothScrollBy(0, -Math.max(300, sc.getHeight() - 80));
                return true;
            }
            return false;
        });
    }

    public static void text(Activity activity, String title, String body) {
        ScrollView sc = new ScrollView(activity);
        sc.setFillViewport(true);
        TextView text = new TextView(activity);
        text.setText(body == null || body.isEmpty() ? "Brak danych." : body);
        text.setTextColor(Color.WHITE);
        text.setTextSize(17);
        text.setLineSpacing(0, 1.08f);
        text.setPadding(28, 20, 28, 30);
        sc.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(sc)
                .setPositiveButton("Zamknij", null)
                .create();
        installTvScroll(dialog, sc);
        dialog.show();
    }


    public static void confirm(Activity activity, String title, String message, Runnable yes, Runnable no) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Nie", (d, w) -> { if (no != null) no.run(); })
                .setPositiveButton("Tak", (d, w) -> { if (yes != null) yes.run(); })
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus());
        dialog.setOnCancelListener(x -> { if (no != null) no.run(); });
        dialog.show();
    }

    public static void comments(Activity activity, ArrayList<CommentItem> list) {
        ScrollView sc = new ScrollView(activity);
        sc.setFillViewport(true);
        TextView text = new TextView(activity);
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setLineSpacing(0, 1.08f);
        text.setPadding(28, 20, 28, 30);
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int i = 0; i < list.size(); i++) {
            CommentItem c = list.get(i);
            if (i > 0) b.append("\n────────────────────────\n\n");
            b.append(c.author);
            if (!c.date.isEmpty()) b.append("   ").append(c.date);
            if (!c.score.isEmpty()) {
                String score = c.score;
                try {
                    int n = Integer.parseInt(score.replaceAll("[^-0-9]", ""));
                    score = n > 0 ? "+" + n : String.valueOf(n);
                } catch (Exception ignored) {}
                int start = b.length();
                b.append("   ★ ").append(score);
                b.setSpan(new ForegroundColorSpan(GOLD), start, b.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            b.append("\n").append(c.text).append("\n");
        }
        text.setText(b.length() == 0 ? "Brak komentarzy." : b);
        sc.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Komentarze (" + list.size() + ")")
                .setView(sc)
                .setPositiveButton("Zamknij", null)
                .create();
        installTvScroll(dialog, sc);
        dialog.show();
    }
}
