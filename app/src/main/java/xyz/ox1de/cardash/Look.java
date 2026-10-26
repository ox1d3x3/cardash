package xyz.ox1de.cardash;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;

/** Dark / light palettes and the few drawables that depend on them. Built in code so a theme
 *  switch is just re-assigning colours: no activity restart, no re-inflation, no flash. */
final class Look {
    final boolean light;
    final int bg, card, cardHi, line, text, text2, text3, accent, dock, slot, ripple, drawer, alert, carAuto;

    static final Look DARK = new Look(false);
    static final Look LIGHT = new Look(true);

    private Look(boolean l) {
        light = l;
        if (!l) {
            bg = 0xFF07080A; card = 0xFF131519; cardHi = 0xFF1B1E23; line = 0xFF23262C;
            text = 0xFFF2F3F5; text2 = 0xFF8B919A; text3 = 0xFF565B63;
            dock = 0xFF0E0F12; slot = 0xFF16181C; ripple = 0x33FFFFFF; drawer = 0xF507080A;
            alert = 0xFFFF5A5F; carAuto = 0xFFF4F5F7;   // white car on dark
        } else {
            bg = 0xFFE9ECEF; card = 0xFFFFFFFF; cardHi = 0xFFF1F3F5; line = 0xFFDCE0E5;
            text = 0xFF15181D; text2 = 0xFF5D646E; text3 = 0xFF9298A1;
            dock = 0xFFFFFFFF; slot = 0xFFF0F2F4; ripple = 0x1F000000; drawer = 0xF5E9ECEF;
            alert = 0xFFE0353C; carAuto = 0xFF393D44;   // black car on light (charcoal so the glass stays readable)
        }
        accent = 0xFF3E6AE1;
    }

    ColorStateList csl(int c) { return ColorStateList.valueOf(c); }

    Drawable card(float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadius(r);
        g.setStroke(stroke, line);
        return g;
    }

    Drawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    /** Pressed feedback only; draws nothing at rest. */
    Drawable rippleRect(float r) { return new RippleDrawable(csl(ripple), null, round(0xFFFFFFFF, r)); }

    Drawable rippleRound() {
        GradientDrawable m = new GradientDrawable();
        m.setShape(GradientDrawable.OVAL);
        m.setColor(0xFFFFFFFF);
        return new RippleDrawable(csl(ripple), null, m);
    }

    Drawable slot(float r) { return new RippleDrawable(csl(ripple), round(slot, r), null); }

    Drawable play() {
        GradientDrawable o = new GradientDrawable();
        o.setShape(GradientDrawable.OVAL);
        o.setColor(text);
        return new RippleDrawable(csl(light ? 0x40FFFFFF : 0x40000000), o, null);
    }

    Drawable dockBg(int px) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{
                new ColorDrawable(line), new InsetDrawable(new ColorDrawable(dock), 0, px, 0, 0)});
        return ld;
    }
}
