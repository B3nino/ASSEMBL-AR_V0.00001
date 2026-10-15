package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.vision.AdminReferenceGeometry;
import dev.chyken.assembl_ar.vision.VisionPoint;

/** A 4:3 camera surface that draws the canonical administrator reference quadrilateral. */
public final class AdminReferenceCaptureFrame extends FrameLayout {
    private final Paint outsidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint originPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float originRadius;
    private final Path guidePath = new Path();
    private final Path outsidePath = new Path();
    private double azimuthDegrees = AdminReferenceGeometry.FRONT_AZIMUTH_DEGREES;
    private double componentAspectRatio = 1.0;
    private List<VisionPoint> currentCorners = Collections.emptyList();
    private float originX;
    private float originY;

    public AdminReferenceCaptureFrame(@NonNull Context context) {
        this(context, null);
    }

    public AdminReferenceCaptureFrame(@NonNull Context context,
                                      @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        outsidePaint.setColor(0x66000000);
        outsidePaint.setStyle(Paint.Style.FILL);
        guidePaint.setColor(Color.WHITE);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(UiUtils.dp(context, 2));
        guidePaint.setStrokeJoin(Paint.Join.ROUND);
        originPaint.setColor(Color.WHITE);
        originPaint.setStyle(Paint.Style.FILL);
        originRadius = UiUtils.dp(context, 5);
    }

    public void setCaptureAzimuthDegrees(double azimuthDegrees) {
        this.azimuthDegrees = azimuthDegrees;
        rebuildPaths();
        invalidate();
    }

    public void setComponentAspectRatio(double componentAspectRatio) {
        if (!(componentAspectRatio > 0.0) || !Double.isFinite(componentAspectRatio)) {
            throw new IllegalArgumentException("Component aspect ratio must be positive");
        }
        this.componentAspectRatio = componentAspectRatio;
        rebuildPaths();
        invalidate();
    }

    /** Snapshot the exact normalized corners drawn for the next capture. */
    public List<VisionPoint> getNormalizedGuideCorners() {
        return Collections.unmodifiableList(new ArrayList<>(currentCorners));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0 || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int targetHeight = Math.round(width * (landscape ? 3.0f / 4.0f : 4.0f / 3.0f));
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        rebuildPaths();
    }

    private void rebuildPaths() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        List<VisionPoint> corners = AdminReferenceGeometry.cornersForCapture(
                azimuthDegrees,
                componentAspectRatio,
                getWidth() / (double) getHeight());
        currentCorners = corners;
        guidePath.reset();
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint point = corners.get(index);
            float x = (float) (point.getX() * getWidth());
            float y = (float) (point.getY() * getHeight());
            if (index == 0) {
                originX = x;
                originY = y;
                guidePath.moveTo(x, y);
            } else {
                guidePath.lineTo(x, y);
            }
        }
        guidePath.close();

        outsidePath.reset();
        outsidePath.setFillType(Path.FillType.EVEN_ODD);
        outsidePath.addRect(0.0f, 0.0f, getWidth(), getHeight(), Path.Direction.CW);
        outsidePath.addPath(guidePath);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        canvas.drawPath(outsidePath, outsidePaint);
        canvas.drawPath(guidePath, guidePaint);
        canvas.drawCircle(originX, originY, originRadius, originPaint);
    }
}
