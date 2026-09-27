package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.content.res.Configuration;

public final class UiScale {
    private static final String PREFS = "ui";
    private static final String KEY = "font_scale";
    private static final float DEFAULT = 1.0f;
    private static final float[] VALUES = {0.90f, 1.00f, 1.10f, 1.20f, 1.30f, 1.40f, 1.50f};
    private static final String[] LABELS = {
            "Małe (90%)",
            "Średnie (100%) - domyślne",
            "Duże (110%)",
            "Bardzo duże (120%)",
            "XL (130%)",
            "XXL (140%)",
            "Maksymalne (150%)"
    };

    private UiScale() { }

    public static Context wrap(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = config.fontScale * get(base);
        return base.createConfigurationContext(config);
    }

    public static float get(Context context) {
        float value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, DEFAULT);
        return value < VALUES[0] || value > VALUES[VALUES.length - 1] ? DEFAULT : value;
    }

    public static void set(Context context, float value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, value).apply();
    }

    public static int selectedIndex(Context context) {
        float current = get(context);
        int best = 0;
        float delta = Float.MAX_VALUE;
        for (int i = 0; i < VALUES.length; i++) {
            float d = Math.abs(current - VALUES[i]);
            if (d < delta) { best = i; delta = d; }
        }
        return best;
    }

    public static float valueAt(int index) { return VALUES[Math.max(0, Math.min(index, VALUES.length - 1))]; }
    public static String label(Context context) { return LABELS[selectedIndex(context)]; }
    public static String[] labels() { return LABELS.clone(); }
}
