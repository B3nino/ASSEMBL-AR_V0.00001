package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import androidx.appcompat.widget.AppCompatImageView;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;
import java.util.Collections;
import java.util.List;

/** Highlights only existing authored photo regions; never draws an invented live detection. */
public final class PlanFReferenceImageView extends AppCompatImageView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final float[] matrixValues = new float[9];
    private List<List<VisionPoint>> regions = Collections.emptyList();

    public PlanFReferenceImageView(Context context) {
        super(context); setScaleType(ScaleType.FIT_CENTER);
        paint.setColor(0xFF00A86B); paint.setStyle(Paint.Style.STROKE);
    }

    public void showReference(ComponentReferenceSpec spec) {
        regions = spec == null ? Collections.emptyList() : spec.getFeatureRegionsNormalized();
        AssetImageLoader.loadBounded(this, spec == null ? null : spec.getJpegAssetPath(), 768);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getDrawable() == null || regions.isEmpty()) return;
        int w = getDrawable().getIntrinsicWidth(), h = getDrawable().getIntrinsicHeight();
        if (w <= 0 || h <= 0) return;
        getImageMatrix().getValues(matrixValues);
        float scale = Math.max(.001f, Math.abs(matrixValues[0]));
        paint.setStrokeWidth(UiUtils.dp(getContext(), 2) / scale);
        int save = canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop()); canvas.concat(getImageMatrix());
        canvas.clipRect(0, 0, w, h);
        for (List<VisionPoint> polygon : regions) {
            if (polygon.size() < 3) continue;
            outline.reset();
            for (int i = 0; i < polygon.size(); i++) {
                VisionPoint p = polygon.get(i);
                if (i == 0) outline.moveTo((float)p.getX() * w, (float)p.getY() * h);
                else outline.lineTo((float)p.getX() * w, (float)p.getY() * h);
            }
            outline.close(); canvas.drawPath(outline, paint);
        }
        canvas.restoreToCount(save);
    }
}
