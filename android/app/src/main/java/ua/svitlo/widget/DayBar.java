package ua.svitlo.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Полоса суток: зелёное — свет есть, красное — нет, жёлтое — возможно отключение. */
public class DayBar extends View {
    static final int ON = 0xFF2E9E5B, OFF = 0xFFE5484D, MAYBE = 0xFFF2B33D;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private String halves;      // 48 символов: X ? .
    private float nowHour = -1; // текущее время в часах, -1 — не показывать

    public DayBar(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void set(String halves, float nowHour) {
        this.halves = halves;
        this.nowHour = nowHour;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (58 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float top = 6 * d, bottom = 34 * d;
        RectF bar = new RectF(0, top, w, bottom);

        // полоса со скруглёнными краями
        Path clip = new Path();
        clip.addRoundRect(bar, 10 * d, 10 * d, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        if (halves == null) {
            paint.setColor(0xFF3A424C);
            c.drawRect(bar, paint);
        } else {
            float step = w / 48f;
            for (int i = 0; i < 48; i++) {
                char ch = halves.charAt(i);
                paint.setColor(ch == 'X' ? OFF : ch == '?' ? MAYBE : ON);
                c.drawRect(i * step, top, (i + 1) * step + 1, bottom, paint);
            }
        }
        // деления каждые 6 часов
        paint.setColor(0x55000000);
        paint.setStrokeWidth(d);
        for (int h = 6; h < 24; h += 6) c.drawLine(w * h / 24f, top, w * h / 24f, bottom, paint);
        c.restore();

        // подписи часов
        paint.setColor(0xFF9AA4AF);
        paint.setTextSize(11 * d);
        for (int h = 0; h <= 24; h += 6) {
            String s = String.valueOf(h);
            float tw = paint.measureText(s);
            float x = w * h / 24f - tw / 2;
            x = Math.max(0, Math.min(w - tw, x));
            c.drawText(s, x, 52 * d, paint);
        }

        // отметка «сейчас»
        if (nowHour >= 0) {
            float x = Math.max(1.5f * d, Math.min(w - 1.5f * d, w * nowHour / 24f));
            paint.setColor(0xFFFFFFFF);
            paint.setStrokeWidth(3 * d);
            c.drawLine(x, 1 * d, x, 39 * d, paint);
        }
    }
}
