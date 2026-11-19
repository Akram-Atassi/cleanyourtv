package com.gathin.tvlist;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Beside the greeting: tonight's moon, drawn in its real phase, in the evening; a soft sun in the morning. Each sits
 * in a faint glow of its own light. Nothing is fetched: the phase comes from the mean length of the lunar month,
 * counted from a known new moon, which is right to within about a day.
 */
final class SkyMark extends View {
    /** Mean synodic month, in days. */
    static final double SYNODIC = 29.530588853;
    /** A new moon: 6 January 2000, 18:14 UTC, in milliseconds since the epoch. */
    static final long NEW_MOON_MS = 947182440000L;

    private static final int MOON = 0xFFECE5D3;
    private static final int MOON_DARK = 0xFF2A2724;
    private static final int SUN = 0xFFE8B86A;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path lit = new Path();
    private final RectF box = new RectF();
    private boolean sun;
    private double phase;

    SkyMark(Context context) {
        super(context);
    }

    /** The sun (morning) or the moon at {@code nowMs}. */
    void set(boolean morning, long nowMs) {
        sun = morning;
        phase = phase(nowMs);
        invalidate();
    }

    /** 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
    static double phase(long nowMs) {
        double days = (nowMs - NEW_MOON_MS) / 86_400_000.0;
        double p = (days / SYNODIC) % 1.0;
        return p < 0 ? p + 1 : p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.15f;
        float reach = Math.min(getWidth(), getHeight()) * 0.5f;   // the glow ends inside the view, never at its edge

        // The glow: its own light, fading out to nothing well past the disc.
        int light = sun ? SUN : MOON;
        glow.setShader(new RadialGradient(cx, cy, reach,
                new int[]{(light & 0x00FFFFFF) | 0x33000000, (light & 0x00FFFFFF) | 0x0D000000, 0},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, reach, glow);

        if (sun) {
            paint.setColor(SUN);
            canvas.drawCircle(cx, cy, r, paint);
            return;
        }

        // The whole disc, faintly, then the lit part over it.
        paint.setColor(MOON_DARK);
        canvas.drawCircle(cx, cy, r, paint);

        boolean waxing = phase < 0.5;
        boolean crescent = phase < 0.25 || phase > 0.75;
        // Half-width of the terminator, the ellipse between light and dark.
        float k = (float) Math.abs(Math.cos(2 * Math.PI * phase)) * r;
        lit.reset();
        box.set(cx - r, cy - r, cx + r, cy + r);
        lit.moveTo(cx, cy - r);
        lit.arcTo(box, -90, waxing ? 180 : -180);          // the lit limb: right when waxing, left when waning
        box.set(cx - k, cy - r, cx + k, cy + r);
        // Back up the terminator: bowing toward the lit limb for a crescent, away from it for a gibbous moon.
        boolean throughRight = waxing == crescent;
        lit.arcTo(box, 90, throughRight ? -180 : 180);
        lit.close();
        paint.setColor(MOON);
        canvas.drawPath(lit, paint);
    }
}
