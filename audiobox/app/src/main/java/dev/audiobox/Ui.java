package dev.audiobox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;

/** Small shared UI pieces: colours, the font, and three tiny custom views. */
final class Ui {
    static final int BG = 0xFF1E2A3A;
    static final int PANEL = 0xFF2A3A50;
    static final int ACCENT = 0xFFFFB74D;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_DIM = 0xFFB0BEC5;

    private static Typeface font;

    private Ui() {}

    /**
     * The playful font (APP-SPEC section 7): Nunito ExtraBold (OFL, see
     * assets/fonts/OFL.txt; covers Latin and Cyrillic), a static instance of the
     * Google Fonts variable font since API 22 can't pick a weight from a
     * variable font. Glyphs it lacks
     * (the PIN pad's arrow and cross, the dots) come from the system fallback.
     * Falls back to the system's bold sans-serif if the asset can't be read.
     */
    static synchronized Typeface font(Context c) {
        if (font == null) {
            try {
                font = Typeface.createFromAsset(c.getAssets(), "fonts/nunito.ttf");
            } catch (RuntimeException e) {
                font = Typeface.create("sans-serif", Typeface.BOLD);
            }
        }
        return font;
    }

    static TextView text(Context c, float sp, int colour) {
        TextView t = new TextView(c);
        t.setTypeface(font(c));
        t.setTextSize(sp);
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Battery outline with a fill; red below 15 %, plus a bolt while charging. */
    static final class BatteryView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level = -1;
        private boolean charging;

        BatteryView(Context c) {
            super(c);
        }

        void set(int percent, boolean charging) {
            if (percent == level && charging == this.charging) return;
            level = percent;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (level < 0) return;
            float w = getWidth(), h = getHeight(), s = 3;
            float body = w - 6;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s);
            p.setColor(TEXT_DIM);
            c.drawRoundRect(new RectF(s / 2, s / 2, body, h - s / 2), 5, 5, p);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(body, h * 0.3f, w, h * 0.7f, p);
            p.setColor(level < 15 && !charging ? 0xFFE57373 : 0xFF81C784);
            float inner = (body - 3 * s) * level / 100f;
            c.drawRoundRect(new RectF(2 * s, 2 * s, 2 * s + inner, h - 2 * s), 3, 3, p);
            if (charging) {
                p.setColor(TEXT);
                android.graphics.Path bolt = new android.graphics.Path();
                float cx = body / 2, cy = h / 2;
                bolt.moveTo(cx + 2, cy - h * 0.35f);
                bolt.lineTo(cx - 6, cy + 2);
                bolt.lineTo(cx, cy + 2);
                bolt.lineTo(cx - 2, cy + h * 0.35f);
                bolt.lineTo(cx + 6, cy - 2);
                bolt.lineTo(cx, cy - 2);
                bolt.close();
                c.drawPath(bolt, p);
            }
        }
    }

    /** Horizontal bar: value of max, with a mark at the cap. */
    static final class BarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value, cap = 1;
        private final int colour;

        BarView(Context c, int colour) {
            super(c);
            this.colour = colour;
        }

        /** Both as fractions 0..1. */
        void set(float value, float cap) {
            this.value = Math.max(0, Math.min(1, value));
            this.cap = Math.max(0, Math.min(1, cap));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setColor(PANEL);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            if (cap < 1) { // the part above the cap is out of reach: darker
                p.setColor(0xFF15202C);
                c.drawRoundRect(new RectF(w * cap, 0, w, h), r, r, p);
            }
            p.setColor(colour);
            if (value > 0) c.drawRoundRect(new RectF(0, 0, Math.max(h, w * value), h), r, r, p);
        }
    }

    /** One dot per page / small track count, the current one filled. */
    static final class DotsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count, current;

        DotsView(Context c) {
            super(c);
        }

        void set(int count, int current) {
            this.count = count;
            this.current = current;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (count <= 1) return;
            float d = Math.min(getHeight(), 18), gap = d * 0.8f;
            float total = count * d + (count - 1) * gap;
            float x = (getWidth() - total) / 2 + d / 2, y = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                p.setColor(i == current ? ACCENT : PANEL);
                c.drawCircle(x, y, d / 2, p);
                x += d + gap;
            }
        }
    }
}
