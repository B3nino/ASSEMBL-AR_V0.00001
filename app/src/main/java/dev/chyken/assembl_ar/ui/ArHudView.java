package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.R;

public final class ArHudView extends View {
    public enum HudState {
        SEARCHING,
        TRACKING,
        MATCHED,
        NOT_RECOGNIZED,
        MISMATCH,
        WRONG_ORIENTATION,
        POLARITY_ERROR
    }

    public enum PartStatus { ACCEPTED, PENDING, OTHER, INVALID }

    /** One localized object in the current camera frame. */
    public static final class DetectedPart {
        private final String label;
        private final float[] corners;
        private final PartStatus status;

        public DetectedPart(String label, float[] corners, PartStatus status) {
            if (corners == null || corners.length != 8 || status == null) {
                throw new IllegalArgumentException("A detected part needs four corners and a status");
            }
            this.label = label == null ? "" : label;
            this.corners = Arrays.copyOf(corners, corners.length);
            this.status = status;
        }
    }

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contrastPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final int trackingColor;
    private final int warningColor;
    private final int dangerColor;
    private final int labelBackgroundColor;
    private HudState state = HudState.SEARCHING;
    private boolean targetGuideVisible;
    private boolean scanningEnabled=true;
    public void setScanningEnabled(boolean enabled){if(scanningEnabled!=enabled){scanningEnabled=enabled;invalidate();}}
    public boolean isScanningEnabled(){return scanningEnabled;}
    public void setTargetGuideVisible(boolean visible){if(targetGuideVisible!=visible){targetGuideVisible=visible;invalidate();}}
    private String componentLabel = "";
    private float[] normalizedCorners;
    private List<DetectedPart> detectedParts = Collections.emptyList();
    private float[] boardCorners;
    private float[] leftTarget;
    private float[] rightTarget;
    private boolean leftAccepted;
    private boolean rightAccepted;
    private long stateChangedAt = SystemClock.uptimeMillis();
    private java.util.function.Consumer<HudState> stateListener;

    public void setStateListener(java.util.function.Consumer<HudState> listener) {
        stateListener=listener;
        if(listener!=null) listener.accept(state);
    }

    public ArHudView(@NonNull Context context) {
        this(context, null);
    }

    public ArHudView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        trackingColor = context.getColor(R.color.hud_tracking);
        warningColor = context.getColor(R.color.hud_warning);
        dangerColor = context.getColor(R.color.hud_danger);
        labelBackgroundColor = context.getColor(R.color.hud_label_background);
        contrastPaint.setColor(context.getColor(R.color.hud_contrast_outline));
        contrastPaint.setStyle(Paint.Style.STROKE);
        contrastPaint.setStrokeCap(Paint.Cap.ROUND);
        contrastPaint.setStrokeJoin(Paint.Join.ROUND);
        contrastPaint.setStrokeWidth(UiUtils.dp(context, 6));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeWidth(UiUtils.dp(context, 3));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(UiUtils.dp(context, 1));
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                13,
                context.getResources().getDisplayMetrics()));
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
    }

    public void update(HudState newState, @Nullable String label, @Nullable float[] corners) {
        state = newState;
        if(stateListener!=null) stateListener.accept(newState);
        componentLabel = label == null ? "" : label;
        detectedParts = Collections.emptyList();
        if (corners == null || corners.length != 8) {
            normalizedCorners = null;
        } else {
            normalizedCorners = Arrays.copyOf(corners, corners.length);
        }
        stateChangedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Draws all current-frame detections independently of step validation and targets. */
    public void setDetectedParts(@Nullable List<DetectedPart> parts) {
        normalizedCorners = null;
        componentLabel = "";
        detectedParts = parts == null ? Collections.emptyList()
                : new ArrayList<>(parts);
        invalidate();
    }

    public HudState getHudState() { return state; }

    public void reset() {
        scanningEnabled=true;
        boardCorners = null;
        clearAssemblyTargets();
        update(HudState.SEARCHING, "", null);
    }

    /** Independent board outline; current-component errors cannot erase board evidence. */
    public void setBoardCorners(@Nullable float[] corners) {
        boardCorners = corners != null && corners.length == 8 ? Arrays.copyOf(corners, 8) : null;
        invalidate();
    }

    public void setAssemblyTargets(@Nullable float[] left, boolean leftChecked,
                                   @Nullable float[] right, boolean rightChecked) {
        leftTarget = left != null && left.length == 8 ? Arrays.copyOf(left, 8) : null;
        rightTarget = right != null && right.length == 8 ? Arrays.copyOf(right, 8) : null;
        leftAccepted = leftChecked;
        rightAccepted = rightChecked;
        invalidate();
    }

    public void clearAssemblyTargets() {
        leftTarget = null;
        rightTarget = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawAssemblyTarget(canvas, boardCorners, false);
        if (normalizedCorners == null && detectedParts.isEmpty()
                && boardCorners == null) {
            if(scanningEnabled&&!targetGuideVisible)drawScanner(canvas);
        } else if (!detectedParts.isEmpty()) {
            for (DetectedPart part : detectedParts) {
                int color = part.status == PartStatus.INVALID ? dangerColor
                        : part.status == PartStatus.ACCEPTED ? trackingColor : warningColor;
                drawTrackedObject(canvas, part.corners, part.label, color,
                        part.status == PartStatus.ACCEPTED);
            }
        } else if (normalizedCorners != null) {
            int color = state == HudState.MISMATCH
                    || state == HudState.WRONG_ORIENTATION
                    || state == HudState.POLARITY_ERROR ? dangerColor : trackingColor;
            drawTrackedObject(canvas, normalizedCorners, componentLabel, color,
                    state == HudState.MATCHED);
        }
        drawAssemblyTarget(canvas, leftTarget, leftAccepted);
        drawAssemblyTarget(canvas, rightTarget, rightAccepted);
        if (scanningEnabled && !targetGuideVisible && (state == HudState.SEARCHING || state == HudState.TRACKING)) {
            postInvalidateDelayed(32L);
        }
    }

    private void drawScanner(Canvas canvas) {
        float width = getWidth() * 0.68f;
        float height = Math.min(getHeight() * 0.34f, width * 0.76f);
        RectF target = new RectF(
                (getWidth() - width) / 2f,
                getHeight() * 0.22f,
                (getWidth() + width) / 2f,
                getHeight() * 0.22f + height);
        int color = state == HudState.NOT_RECOGNIZED
                ? warningColor
                : trackingColor;
        linePaint.setColor(color);
        linePaint.setAlpha(225);
        float length = UiUtils.dp(getContext(), 34);
        drawCorner(canvas, target.left, target.top, 1, 1, length);
        drawCorner(canvas, target.right, target.top, -1, 1, length);
        drawCorner(canvas, target.left, target.bottom, 1, -1, length);
        drawCorner(canvas, target.right, target.bottom, -1, -1, length);

        float elapsed = (SystemClock.uptimeMillis() - stateChangedAt) / 1000f;
        float scanY = target.top + target.height() * ((elapsed * 0.42f) % 1f);
        gridPaint.setColor(color);
        gridPaint.setAlpha(150);
        gridPaint.setStrokeWidth(UiUtils.dp(getContext(), 1));
        canvas.drawLine(target.left + length, scanY, target.right - length, scanY, gridPaint);
    }

    private void drawAssemblyTarget(Canvas canvas, @Nullable float[] target, boolean accepted) {
        if (target == null) return;
        outline.reset();
        outline.moveTo(target[0] * getWidth(), target[1] * getHeight());
        for (int i = 1; i < 4; i++) {
            outline.lineTo(target[2 * i] * getWidth(), target[2 * i + 1] * getHeight());
        }
        outline.close();
        linePaint.setColor(accepted ? trackingColor : warningColor);
        linePaint.setAlpha(255);
        canvas.drawPath(outline, contrastPaint);
        canvas.drawPath(outline, linePaint);
    }

    private void drawCorner(Canvas canvas, float x, float y, int horizontal,
                            int vertical, float length) {
        canvas.drawLine(x, y, x + horizontal * length, y, contrastPaint);
        canvas.drawLine(x, y, x, y + vertical * length, contrastPaint);
        canvas.drawLine(x, y, x + horizontal * length, y, linePaint);
        canvas.drawLine(x, y, x, y + vertical * length, linePaint);
    }

    private void drawTrackedObject(Canvas canvas, float[] corners, String label,
                                   int color, boolean accepted) {
        float[] points = new float[8];
        for (int index = 0; index < 4; index++) {
            points[index * 2] = corners[index * 2] * getWidth();
            points[index * 2 + 1] = corners[index * 2 + 1] * getHeight();
        }
        outline.reset();
        outline.moveTo(points[0], points[1]);
        for (int index = 1; index < 4; index++) {
            outline.lineTo(points[index * 2], points[index * 2 + 1]);
        }
        outline.close();

        fillPaint.setColor(color);
        fillPaint.setAlpha(accepted ? 38 : 20);
        canvas.drawPath(outline, fillPaint);
        linePaint.setColor(color);
        linePaint.setAlpha(245);
        canvas.drawPath(outline, contrastPaint);
        canvas.drawPath(outline, linePaint);
        drawPerspectiveGrid(canvas, points, color);
        drawComponentLabel(canvas, points[0], points[1], color, label);
    }

    private void drawPerspectiveGrid(Canvas canvas, float[] p, int color) {
        gridPaint.setColor(color);
        gridPaint.setAlpha(95);
        for (int index = 1; index < 5; index++) {
            float amount = index / 5f;
            float topX = lerp(p[0], p[2], amount);
            float topY = lerp(p[1], p[3], amount);
            float bottomX = lerp(p[6], p[4], amount);
            float bottomY = lerp(p[7], p[5], amount);
            canvas.drawLine(topX, topY, bottomX, bottomY, gridPaint);

            float leftX = lerp(p[0], p[6], amount);
            float leftY = lerp(p[1], p[7], amount);
            float rightX = lerp(p[2], p[4], amount);
            float rightY = lerp(p[3], p[5], amount);
            canvas.drawLine(leftX, leftY, rightX, rightY, gridPaint);
        }
    }

    private void drawComponentLabel(Canvas canvas, float anchorX, float anchorY,
                                    int color, String label) {
        if (label.isEmpty()) {
            return;
        }
        float padding = UiUtils.dp(getContext(), 8);
        float left = Math.max(UiUtils.dp(getContext(), 8), anchorX);
        float top = Math.max(UiUtils.dp(getContext(), 120),
                anchorY - UiUtils.dp(getContext(), 38));
        float availableTextWidth = Math.max(0,
                getWidth() - left - UiUtils.dp(getContext(), 8) - padding * 2);
        String visibleLabel = TextUtils.ellipsize(
                label, textPaint, availableTextWidth, TextUtils.TruncateAt.END)
                .toString();
        float textWidth = textPaint.measureText(visibleLabel);
        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float verticalPadding = UiUtils.dp(getContext(), 5);
        RectF background = new RectF(left, top,
                Math.min(getWidth() - UiUtils.dp(getContext(), 8), left + textWidth + padding * 2),
                top + fontMetrics.descent - fontMetrics.ascent + verticalPadding * 2);
        fillPaint.setColor(labelBackgroundColor);
        fillPaint.setAlpha(225);
        canvas.drawRoundRect(background, padding, padding, fillPaint);
        textPaint.setColor(color);
        canvas.drawText(visibleLabel, background.left + padding,
                background.top + verticalPadding - fontMetrics.ascent, textPaint);
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }
}
