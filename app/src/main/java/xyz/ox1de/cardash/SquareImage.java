package xyz.ox1de.cardash;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** ImageView that is always as wide as it is tall (sized from its height). */
public class SquareImage extends ImageView {
    public SquareImage(Context c) { super(c); }
    public SquareImage(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(h);
        int maxW = MeasureSpec.getSize(w);
        if (MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && maxW > 0) {
            size = Math.min(size, (int) (maxW * 0.45f));
        }
        setMeasuredDimension(size, size);
    }
}
