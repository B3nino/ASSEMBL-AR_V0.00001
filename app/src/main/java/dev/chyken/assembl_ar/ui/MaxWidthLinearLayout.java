package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * A LinearLayout that still fills compact screens, but stops growing once its
 * android:maxWidth is reached. This keeps tablet content readable and centered.
 */
public final class MaxWidthLinearLayout extends LinearLayout {
    private int maxContentWidth = Integer.MAX_VALUE;

    public MaxWidthLinearLayout(Context context) {
        this(context, null);
    }

    public MaxWidthLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        if (attrs == null) {
            return;
        }
        TypedArray attributes = context.obtainStyledAttributes(
                attrs,
                new int[]{android.R.attr.maxWidth});
        maxContentWidth = attributes.getDimensionPixelSize(0, Integer.MAX_VALUE);
        attributes.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        if (maxContentWidth > 0
                && widthMode != MeasureSpec.UNSPECIFIED
                && availableWidth > maxContentWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxContentWidth, widthMode);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
