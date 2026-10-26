package xyz.ox1de.cardash;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Fits its children to a fixed aspect ratio, centred (letterboxed video without stretching). */
public class AspectFrame extends FrameLayout {
    float aspect = 16f / 9f;

    public AspectFrame(Context c) { super(c); }
    public AspectFrame(Context c, AttributeSet a) { super(c, a); }

    void setAspect(float a) {
        if (a > 0 && Math.abs(a - aspect) > 0.01f) { aspect = a; requestLayout(); }
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int fw = MeasureSpec.getSize(ws), fh = MeasureSpec.getSize(hs);
        setMeasuredDimension(fw, fh);
        int w = Math.max(0, fw - getPaddingLeft() - getPaddingRight()), h = Math.max(0, fh - getPaddingTop() - getPaddingBottom());
        int cw = w, ch = h;
        if (h > 0 && w / (float) h > aspect) cw = Math.round(h * aspect); else ch = Math.round(w / aspect);
        int mw = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), mh = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(mw, mh);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int iw = r - l - getPaddingLeft() - getPaddingRight(), ih = b - t - getPaddingTop() - getPaddingBottom();
            int x = getPaddingLeft() + (iw - c.getMeasuredWidth()) / 2, y = getPaddingTop() + (ih - c.getMeasuredHeight()) / 2;
            c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
        }
    }
}
