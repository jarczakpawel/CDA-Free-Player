package pl.paweljarczak.cdafreeplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

public final class TvDialogs {
    private static final int GOLD = Color.rgb(255, 210, 46);
    private static final int ORANGE = Color.rgb(255, 153, 30);
    private static final int MUTED = Color.rgb(165, 171, 182);
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
        text(activity, title, body, null);
    }

    public static void text(Activity activity, String title, String body, MovieMetadata metadata) {
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
                .setTitle(withRating(title, metadata))
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
        comments(activity, list, null);
    }

    public static void comments(Activity activity, ArrayList<CommentItem> list, MovieMetadata metadata) {
        int d = Math.max(1, Math.round(activity.getResources().getDisplayMetrics().density));
        ScrollView sc = new ScrollView(activity);
        sc.setFillViewport(true);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(8 * d, 8 * d, 8 * d, 14 * d);
        sc.addView(rows, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageLoader images = new ImageLoader(activity);

        if (list.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("Brak komentarzy.");
            empty.setTextColor(MUTED);
            empty.setTextSize(17);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(24 * d, 40 * d, 24 * d, 40 * d);
            rows.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            for (CommentItem c : list) {
                LinearLayout card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.TOP);
                card.setPadding(10 * d, 9 * d, 12 * d, 10 * d);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor(c.reply ? "#171A20" : "#101216"));
                bg.setCornerRadius(7 * d);
                bg.setStroke(c.reply ? 2 * d : d, Color.parseColor(c.reply ? "#365E84" : "#343A44"));
                card.setBackground(bg);
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.setMargins(c.reply ? 58 * d : 4 * d, 5 * d, 4 * d, 5 * d);
                rows.addView(card, cardLp);

                int avatarSize = (c.reply ? 34 : 48) * d;
                ImageView avatar = new ImageView(activity);
                avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GradientDrawable avatarBg = new GradientDrawable();
                avatarBg.setColor(Color.parseColor("#242831"));
                avatarBg.setCornerRadius(5 * d);
                avatar.setBackground(avatarBg);
                avatar.setClipToOutline(true);
                LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
                avatarLp.setMargins(0, 0, 11 * d, 0);
                card.addView(avatar, avatarLp);
                if (c.avatar != null && !c.avatar.isEmpty()) images.load(c.avatar, avatar);

                LinearLayout content = new LinearLayout(activity);
                content.setOrientation(LinearLayout.VERTICAL);
                card.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView header = new TextView(activity);
                header.setTextSize(c.reply ? 14 : 15);
                header.setTextColor(MUTED);
                header.setSingleLine(false);
                SpannableStringBuilder meta = new SpannableStringBuilder();
                int start = meta.length();
                meta.append(c.author == null || c.author.isEmpty() ? "anonim" : c.author);
                meta.setSpan(new ForegroundColorSpan(ORANGE), start, meta.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                meta.setSpan(new StyleSpan(Typeface.BOLD), start, meta.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (c.ip != null && !c.ip.isEmpty()) {
                    meta.append("   ");
                    start = meta.length();
                    meta.append(c.ip);
                    meta.setSpan(new ForegroundColorSpan(MUTED), start, meta.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    meta.setSpan(new TypefaceSpan("monospace"), start, meta.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (c.date != null && !c.date.isEmpty()) meta.append("   ").append(c.date);
                if (c.score != null && !c.score.isEmpty()) {
                    String score = c.score;
                    try {
                        int n = Integer.parseInt(score.replaceAll("[^-0-9]", ""));
                        score = n > 0 ? "+" + n : String.valueOf(n);
                    } catch (Exception ignored) {}
                    meta.append("   ");
                    start = meta.length();
                    meta.append("★ ").append(score);
                    meta.setSpan(new ForegroundColorSpan(GOLD), start, meta.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    meta.setSpan(new StyleSpan(Typeface.BOLD), start, meta.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                header.setText(meta);
                content.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView body = new TextView(activity);
                body.setText(c.text == null ? "" : c.text);
                body.setTextColor(Color.rgb(238, 238, 238));
                body.setTextSize(c.reply ? 15 : 16);
                body.setLineSpacing(0, 1.08f);
                body.setPadding(0, 4 * d, 0, 0);
                content.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(withRating("Komentarze (" + list.size() + ")", metadata))
                .setView(sc)
                .setPositiveButton("Zamknij", null)
                .create();
        dialog.setOnDismissListener(x -> images.shutdown());
        installTvScroll(dialog, sc);
        dialog.show();
        if (dialog.getWindow() != null) {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            dialog.getWindow().setLayout(Math.round(dm.widthPixels * 0.90f), Math.round(dm.heightPixels * 0.86f));
        }
    }

    private static String withRating(String title, MovieMetadata metadata) {
        if (metadata == null || metadata.rating == null) return title;
        String out = title + "   ★ " + String.format(Locale.US, "%.1f / 5", metadata.rating);
        if (metadata.cdaVotes != null && metadata.cdaVotes > 0) out += " • " + metadata.cdaVotes + " ocen";
        return out;
    }
}
