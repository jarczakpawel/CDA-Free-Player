package pl.paweljarczak.cdafreeplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public final class StarRatingView extends View {
    private Double rating = null;
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path star = new Path();

    public StarRatingView(Context c, AttributeSet a) {
        super(c, a);
        white.setColor(Color.WHITE);
        gold.setColor(Color.rgb(255, 210, 46));
    }

    public void setRating(Double r) {
        rating = r;
        if (rating == null) {
            setVisibility(GONE);
        } else {
            rating = Math.max(0.0d, Math.min(5.0d, rating.doubleValue()));
            setVisibility(VISIBLE);
        }
        invalidate();
    }

    public Double getRating() { return rating; }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (rating == null) return;
        float gap = 2 * getResources().getDisplayMetrics().density;
        float cell = (getWidth() - gap * 4) / 5f;
        float size = Math.min(cell, getHeight());
        for (int i = 0; i < 5; i++) {
            float cx = i * (cell + gap) + cell / 2f;
            float cy = getHeight() / 2f;
            makeStar(cx, cy, size * .48f, size * .21f);
            c.drawPath(star, white);
            float f = (float) Math.max(0.0d, Math.min(1.0d, rating.doubleValue() - i));
            if (f > 0) {
                c.save();
                c.clipRect(i * (cell + gap), 0, i * (cell + gap) + cell * f, getHeight());
                c.drawPath(star, gold);
                c.restore();
            }
        }
    }

    private void makeStar(float cx, float cy, float ro, float ri) {
        star.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = (i % 2 == 0) ? ro : ri;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
        }
        star.close();
    }
}
