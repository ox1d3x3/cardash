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
    final int bg, card, cardHi, line, text, text2, text3, accent, dock, slot, ripple, drawer, alert, carAuto, track;

    static final Look DARK = new Look(false);
    static final Look LIGHT = new Look(true);

    private Look(boolean l) {
        light = l;
        // Tuned for cheap head-unit LCDs, which flatten contrast: true black behind clearly lifted cards
        // and bright text at night; by day a grey background with off-white cards (pure white blooms and
        // washes out), darker text and visible outlines.
        if (!l) {
            bg = 0xFF000000; card = 0xFF181B21; cardHi = 0xFF23272E; line = 0xFF343A43;
            text = 0xFFFFFFFF; text2 = 0xFFAAB1BB; text3 = 0xFF747B86;
            dock = 0xFF0B0C0F; slot = 0xFF1E2127; ripple = 0x40FFFFFF; drawer = 0xF7000000;
            alert = 0xFFFF5A5F; carAuto = 0xFFF4F5F7;   // white car on dark
            track = 0xFF3D434C;                          // slider/progress rail: visible on a car screen in sunlight
            accent = 0xFF4F82FF;                         // a brighter blue reads better on black
        } else {
            bg = 0xFFC9CFD7; card = 0xFFF4F6F8; cardHi = 0xFFE3E7EC; line = 0xFFAEB6C1;
            text = 0xFF0A0C0F; text2 = 0xFF3A414B; text3 = 0xFF626A75;
            dock = 0xFFE6E9ED; slot = 0xFFD5DAE1; ripple = 0x26000000; drawer = 0xF5C9CFD7;
            alert = 0xFFD62C33; carAuto = 0xFF393D44;   // black car on light (charcoal so the glass stays readable)
            track = 0xFFAEB6C1;
            accent = 0xFF2C58D6;                         // a deeper blue holds its own on grey
        }
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
