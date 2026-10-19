package dev.chyken.assembl_ar.render;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Opaque, touch-interactive home-screen preview of the assembled NEOBOT. */
public final class HomeRobotSurfaceView extends GLSurfaceView {
    private static final int INVALID_POINTER_ID = -1;
    private static final float ORBIT_DEGREES_PER_DP = 0.34f;
    private static final float ACCESSIBILITY_ORBIT_STEP_DEGREES = 18f;

    private final OrbitController orbitController;
    private final HomeRobotRenderer robotRenderer;
    private final boolean componentPreview;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleGestureDetector;
    private final float orbitDegreesPerPixel;
    private final int touchSlop;

    private int activePointerId = INVALID_POINTER_ID;
    private float dragAnchorX;
    private float dragAnchorY;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private boolean modelGestureClaimed;

    public HomeRobotSurfaceView(@NonNull Context context) {
        this(context, null);
    }

    public HomeRobotSurfaceView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, null);
    }

    /** Isolated component preview; the assembled home robot remains the default. */
    public static HomeRobotSurfaceView forComponent(@NonNull Context context, @NonNull String componentId) {
        if (!ComponentModelCatalog.hasModel(componentId)) {
            throw new IllegalArgumentException("No model for component: " + componentId);
        }
        return new HomeRobotSurfaceView(context, null, componentId);
    }

    private HomeRobotSurfaceView(Context context, @Nullable AttributeSet attrs,
                                 @Nullable String componentId) {
        super(context, attrs);
        orbitController = new OrbitController();
        componentPreview = componentId != null;
        if (componentPreview) orbitController.restore(-20f, 48f, 5.3f);
        robotRenderer = new HomeRobotRenderer(context.getApplicationContext(), orbitController, componentId);
        float density = getResources().getDisplayMetrics().density;
        orbitDegreesPerPixel = ORBIT_DEGREES_PER_DP / Math.max(1f, density);
        touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();

        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        getHolder().setFormat(PixelFormat.OPAQUE);
        setPreserveEGLContextOnPause(true);
        setRenderer(robotRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        setClickable(true);
        setFocusable(true);
        setLongClickable(true);

        scaleGestureDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                        dragging = false;
                        claimModelGesture();
                        return true;
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        orbitController.zoomBy(detector.getScaleFactor());
                        requestRender();
                        return true;
                    }
                });
        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(@NonNull MotionEvent event) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent event) {
                        return dispatchClickEvent();
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent event) {
                        claimModelGesture();
                        resetView();
                        return true;
                    }

                    @Override
                    public void onLongPress(@NonNull MotionEvent event) {
                        claimModelGesture();
                        performLongClick();
                    }
                });
    }

    /** Restores the authored isometric camera pose and zoom. */
    public void resetView() {
        orbitController.reset();
        if (componentPreview) orbitController.restore(-20f, 48f, 5.3f);
        requestRender();
    }

    /** Selects an isolated component's reverse face. */
    public void showBack(boolean back) {
        if (!componentPreview) return;
        robotRenderer.showBack(back);
        requestRender();
    }

    @Override
    public void onResume() {
        super.onResume();
        requestRender();
    }

    @Override
    public void onPause() {
        finishModelGesture();
        super.onPause();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointerId = event.getPointerId(0);
            dragAnchorX = lastX = event.getX(0);
            dragAnchorY = lastY = event.getY(0);
            dragging = false;
            modelGestureClaimed = false;
            // This view sits inside a NestedScrollView. Claim the stream immediately so the
            // parent cannot intercept the first slop-crossing MOVE before we can classify it;
            // otherwise vertical orbit and the start of some pinch gestures are lost.
            claimModelGesture();
        }

        scaleGestureDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                activePointerId = INVALID_POINTER_ID;
                break;
            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                adoptRemainingPointer(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointerId = INVALID_POINTER_ID;
                dragging = false;
                finishModelGesture();
                break;
            default:
                break;
        }
        // The model owns touches that begin inside its viewport. The surrounding page remains
        // scrollable from the header, copy, CTA, and the rest of the hero card.
        return true;
    }

    private void handleMove(MotionEvent event) {
        if (event.getPointerCount() != 1 || scaleGestureDetector.isInProgress()) {
            return;
        }
        int pointerIndex = event.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            activePointerId = event.getPointerId(0);
            dragAnchorX = lastX = event.getX(0);
            dragAnchorY = lastY = event.getY(0);
            dragging = false;
            return;
        }
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        if (!dragging) {
            float totalX = x - dragAnchorX;
            float totalY = y - dragAnchorY;
            if (totalX * totalX + totalY * totalY <= touchSlop * touchSlop) {
                lastX = x;
                lastY = y;
                return;
            }
            dragging = true;
            claimModelGesture();
        }
        float deltaX = x - lastX;
        float deltaY = y - lastY;
        lastX = x;
        lastY = y;
        if (deltaX != 0f || deltaY != 0f) {
            orbitController.orbitBy(
                    -deltaX * orbitDegreesPerPixel,
                    deltaY * orbitDegreesPerPixel);
            requestRender();
        }
    }

    private void adoptRemainingPointer(MotionEvent event) {
        int liftedIndex = event.getActionIndex();
        int remainingIndex = liftedIndex == 0 ? 1 : 0;
        if (remainingIndex >= event.getPointerCount()) {
            activePointerId = INVALID_POINTER_ID;
            return;
        }
        activePointerId = event.getPointerId(remainingIndex);
        dragAnchorX = lastX = event.getX(remainingIndex);
        dragAnchorY = lastY = event.getY(remainingIndex);
        dragging = false;
    }

    private void claimModelGesture() {
        if (modelGestureClaimed) {
            return;
        }
        modelGestureClaimed = true;
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private void finishModelGesture() {
        if (!modelGestureClaimed) {
            return;
        }
        modelGestureClaimed = false;
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        resetView();
        return true;
    }

    private boolean dispatchClickEvent() {
        return super.performClick();
    }

    @Override
    public boolean performLongClick() {
        super.performLongClick();
        resetView();
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(@NonNull AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(HomeRobotSurfaceView.class.getName());
        info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_CLICK, "Reset 3D view"));
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_LONG_CLICK, "Reset 3D view"));
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            orbitController.orbitBy(ACCESSIBILITY_ORBIT_STEP_DEGREES, 0f);
            requestRender();
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            orbitController.orbitBy(-ACCESSIBILITY_ORBIT_STEP_DEGREES, 0f);
            requestRender();
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
            return performLongClick();
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Nullable
    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState savedState = new SavedState(super.onSaveInstanceState());
        OrbitController.Snapshot orbit = orbitController.snapshot();
        savedState.yawDegrees = orbit.yawDegrees;
        savedState.pitchDegrees = orbit.pitchDegrees;
        savedState.distance = orbit.distance;
        return savedState;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState savedState = (SavedState) state;
        super.onRestoreInstanceState(savedState.getSuperState());
        orbitController.restore(
                savedState.yawDegrees,
                savedState.pitchDegrees,
                savedState.distance);
        requestRender();
    }

    private static final class SavedState extends BaseSavedState {
        float yawDegrees;
        float pitchDegrees;
        float distance;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel source) {
            super(source);
            yawDegrees = source.readFloat();
            pitchDegrees = source.readFloat();
            distance = source.readFloat();
        }

        @Override
        public void writeToParcel(@NonNull Parcel destination, int flags) {
            super.writeToParcel(destination, flags);
            destination.writeFloat(yawDegrees);
            destination.writeFloat(pitchDegrees);
            destination.writeFloat(distance);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
                    @Override
                    public SavedState createFromParcel(Parcel source) {
                        return new SavedState(source);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }
}
