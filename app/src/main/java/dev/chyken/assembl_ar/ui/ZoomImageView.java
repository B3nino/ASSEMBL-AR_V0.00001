package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public final class ZoomImageView extends AppCompatImageView {
    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;

    private final Matrix zoomMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float currentScale = MIN_SCALE;
    private float lastX;
    private float lastY;

    public ZoomImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent event) {
                float target = currentScale > 1.2f ? MIN_SCALE : 2.5f;
                float factor = target / currentScale;
                zoomMatrix.postScale(factor, factor, event.getX(), event.getY());
                currentScale = target;
                constrainAndApply();
                return true;
            }
        });
    }

    public void resetZoom() {
        currentScale = MIN_SCALE;
        fitDrawable();
    }

    @Override
    public void setImageBitmap(Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        currentScale = MIN_SCALE;
        post(this::fitDrawable);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        currentScale = MIN_SCALE;
        post(this::fitDrawable);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fitDrawable();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && currentScale > MIN_SCALE) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                zoomMatrix.postTranslate(dx, dy);
                lastX = event.getX();
                lastY = event.getY();
                constrainAndApply();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                performClick();
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void fitDrawable() {
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float drawableWidth = getDrawable().getIntrinsicWidth();
        float drawableHeight = getDrawable().getIntrinsicHeight();
        float fit = Math.min(getWidth() / drawableWidth, getHeight() / drawableHeight);
        float dx = (getWidth() - drawableWidth * fit) / 2f;
        float dy = (getHeight() - drawableHeight * fit) / 2f;
        zoomMatrix.reset();
        zoomMatrix.postScale(fit, fit);
        zoomMatrix.postTranslate(dx, dy);
        setImageMatrix(zoomMatrix);
    }

    private void constrainAndApply() {
        if (getDrawable() == null) {
            return;
        }
        RectF bounds = new RectF(0, 0,
                getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
        zoomMatrix.mapRect(bounds);
        float dx = 0f;
        float dy = 0f;
        if (bounds.width() <= getWidth()) {
            dx = getWidth() / 2f - bounds.centerX();
        } else if (bounds.left > 0) {
            dx = -bounds.left;
        } else if (bounds.right < getWidth()) {
            dx = getWidth() - bounds.right;
        }
        if (bounds.height() <= getHeight()) {
            dy = getHeight() / 2f - bounds.centerY();
        } else if (bounds.top > 0) {
            dy = -bounds.top;
        } else if (bounds.bottom < getHeight()) {
            dy = getHeight() - bounds.bottom;
        }
        zoomMatrix.postTranslate(dx, dy);
        setImageMatrix(zoomMatrix);
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            float requested = currentScale * detector.getScaleFactor();
            float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, requested));
            float factor = clamped / currentScale;
            currentScale = clamped;
            zoomMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            constrainAndApply();
            return true;
        }
    }
}
