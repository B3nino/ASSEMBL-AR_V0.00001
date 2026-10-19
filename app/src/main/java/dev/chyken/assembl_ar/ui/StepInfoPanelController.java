package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import dev.chyken.assembl_ar.R;

/** Keeps camera guidance bounded independently of the amount of step content. */
public final class StepInfoPanelController {
    private static final String STATE_EXPANDED = "step_info_expanded";
    private static final String STATE_STEP = "step_info_step";
    private static final String STATE_SCROLL = "step_info_scroll";
    private static final String STATE_PREVIEW_WIDTH = "component_preview_width";
    private static final String STATE_PREVIEW_HEIGHT = "component_preview_height";

    private final Activity activity;
    private final View root;
    private final View panel;
    private final View toolbar;
    private final View banner;
    private final TextView bannerText;
    private final View progress;
    private final View header;
    private final ScrollView scroll;
    private final TextView toggle;
    private final ComponentPreviewWindow preview;
    private final PhaseOneGuideView phaseOneGuide;
    private boolean phaseOneActive;
    private final CompactGuideCard phaseTwoGuide;
    private boolean phaseTwoActive;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateLayout;
    private boolean expanded;
    private boolean contentReady;
    private boolean restoreScroll = true;
    private int savedScrollY;
    @Nullable private String stepId;
    private float dragStartX, dragStartY, panelStartX, panelStartY;
    private int previewWidth, previewHeight;
    private int guideWidth,guideHeight;
    private final TargetAlignmentBoxView guide;

    public StepInfoPanelController(Activity activity, @Nullable Bundle state) {
        this.activity = activity;
        root = activity.findViewById(R.id.assemblyRoot);
        panel = activity.findViewById(R.id.instructionPanel);
        toolbar = activity.findViewById(R.id.cameraToolbar);
        banner = activity.findViewById(R.id.recognitionBanner);
        bannerText = activity.findViewById(R.id.recognitionText);
        progress = activity.findViewById(R.id.assemblyProgress);
        header = activity.findViewById(R.id.instructionHeader);
        scroll = activity.findViewById(R.id.stepInfoScroll);
        toggle = activity.findViewById(R.id.toggleStepInfoButton);
        preview = activity.findViewById(R.id.componentPreviewWindow);
        phaseOneGuide = activity.findViewById(R.id.phaseOneGuide);
        phaseTwoGuide=activity.findViewById(R.id.phaseTwoGuide);
        guide=activity.findViewById(R.id.targetAlignmentBox);
        guideWidth=state==null?0:state.getInt("guide_width",0);guideHeight=state==null?0:state.getInt("guide_height",0);
        guide.setTranslationX(state==null?0:state.getFloat("guide_x",0));guide.setTranslationY(state==null?0:state.getFloat("guide_y",0));
        guide.setResizeListener((width,height)->{guideWidth=width;guideHeight=height;updateLayout();});
        phaseTwoGuide.setTranslationX(state==null?0:state.getFloat("phase_two_window_x",0));
        phaseTwoGuide.setTranslationY(state==null?0:state.getFloat("phase_two_window_y",0));
        phaseTwoGuide.dragHandle().setOnTouchListener((view,event)->drag(phaseTwoGuide,view,event));
        expanded = state == null || state.getBoolean(STATE_EXPANDED, true);
        stepId = state == null ? null : state.getString(STATE_STEP);
        savedScrollY = state == null ? 0 : state.getInt(STATE_SCROLL, 0);
        previewWidth = state == null ? 0 : state.getInt(STATE_PREVIEW_WIDTH, 0);
        previewHeight = state == null ? 0 : state.getInt(STATE_PREVIEW_HEIGHT, 0);
        preview.setResizeListener((width, height) -> {
            previewWidth = width;
            previewHeight = height;
            updateLayout();
        });
        panel.setTranslationX(state==null ? 0 : state.getFloat("info_window_x",0));
        panel.setTranslationY(state==null ? 0 : state.getFloat("info_window_y",0));
        header.setContentDescription(activity.getString(R.string.drag_info_window));
        header.setOnTouchListener((view,event) -> drag(panel,view,event));
        phaseOneGuide.getDragHandle().setOnTouchListener((view,event) -> drag(phaseOneGuide,view,event));
        phaseOneGuide.setTranslationX(state==null ? 0 : state.getFloat("phase_one_window_x",0));
        phaseOneGuide.setTranslationY(state==null ? 0 : state.getFloat("phase_one_window_y",0));
        // Project content loads asynchronously, after normal view-state restoration.
        scroll.setSaveEnabled(false);
        toggle.setOnClickListener(view -> {
            if (expanded && !restoreScroll) savedScrollY = scroll.getScrollY();
            expanded = !expanded;
            restoreScroll = true;
            applyExpandedState();
            updateLayout();
        });
        applyExpandedState();
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private boolean drag(View target,View view,MotionEvent event) {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragStartX=event.getRawX(); dragStartY=event.getRawY();
                    panelStartX=target.getTranslationX(); panelStartY=target.getTranslationY(); return true;
                case MotionEvent.ACTION_MOVE:
                    target.setTranslationX(panelStartX+event.getRawX()-dragStartX);
                    target.setTranslationY(panelStartY+event.getRawY()-dragStartY);
                    clampPanel(); return true;
                case MotionEvent.ACTION_UP:
                    view.performClick(); return true;
                case MotionEvent.ACTION_CANCEL: return true;
                default: return false;
            }
    }

    public void setPhaseOneActive(boolean active) {
        phaseOneActive=active;
        panel.setVisibility(active || phaseTwoActive ? View.GONE : contentReady ? View.VISIBLE : View.INVISIBLE);
        phaseOneGuide.setVisibility(active ? View.VISIBLE : View.GONE);
        updateLayout();
    }
    public void setPhaseTwoActive(boolean active){phaseTwoActive=active;phaseTwoGuide.setVisibility(active?View.VISIBLE:View.GONE);setPhaseOneActive(false);}

    public void onStepChanged(String newStepId) {
        if (!newStepId.equals(stepId)) {
            savedScrollY = 0;
            restoreScroll = true;
        }
        stepId = newStepId;
        contentReady = true;
        if(!phaseOneActive&&!phaseTwoActive) panel.setVisibility(View.VISIBLE);
        scroll.requestLayout();
    }

    public void saveState(Bundle state) {
        state.putInt("guide_width",guideWidth);state.putInt("guide_height",guideHeight);state.putFloat("guide_x",guide.getTranslationX());state.putFloat("guide_y",guide.getTranslationY());
        state.putFloat("info_window_x",panel.getTranslationX());
        state.putFloat("info_window_y",panel.getTranslationY());
        state.putFloat("phase_one_window_x",phaseOneGuide.getTranslationX());
        state.putFloat("phase_one_window_y",phaseOneGuide.getTranslationY());
        state.putFloat("phase_two_window_x",phaseTwoGuide.getTranslationX());
        state.putFloat("phase_two_window_y",phaseTwoGuide.getTranslationY());
        state.putInt(STATE_PREVIEW_WIDTH, preview.getWidth());
        state.putInt(STATE_PREVIEW_HEIGHT, preview.getHeight());
        state.putBoolean(STATE_EXPANDED, expanded);
        state.putString(STATE_STEP, stepId);
        state.putInt(STATE_SCROLL,
                expanded && !restoreScroll ? scroll.getScrollY() : savedScrollY);
    }

    public void dispose() {
        root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
    }

    private void applyExpandedState() {
        scroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
        activity.findViewById(R.id.demoControls).setVisibility(expanded ? View.VISIBLE : View.GONE);
        progress.setVisibility(expanded ? View.VISIBLE : View.GONE);
        toggle.setText(expanded ? R.string.hide_step_info : R.string.show_step_info);
        toggle.setContentDescription(activity.getString(expanded
                ? R.string.hide_step_information : R.string.show_step_information));
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more, 0);
        ViewCompat.setStateDescription(toggle, activity.getString(expanded
                ? R.string.step_info_expanded : R.string.step_info_collapsed));
        // Never change the physical-check button here: only the workflow may unlock it.
    }

    private void updateLayout() {
        if (root.getWidth() == 0 || root.getHeight() == 0) return;
        WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(root);
        Insets bars = windowInsets == null ? Insets.NONE : windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        int safeWidth = root.getWidth() - bars.left - bars.right;
        int safeHeight = root.getHeight() - bars.top - bars.bottom;
        View alignment=activity.findViewById(R.id.targetAlignmentBox);
        FrameLayout.LayoutParams alignmentParams=(FrameLayout.LayoutParams)alignment.getLayoutParams();
        boolean rtl = ViewCompat.getLayoutDirection(root) == ViewCompat.LAYOUT_DIRECTION_RTL;
        int startInset = rtl ? bars.right : bars.left;
        int endInset = rtl ? bars.left : bars.right;
        boolean tablet = UiUtils.isTablet(activity);
        boolean sidebar = safeWidth >= dp(640) && (tablet || safeWidth > safeHeight);
        int edge = dp(tablet ? 20 : 12);
        int preferredWidth = tablet ? activity.getResources().getDimensionPixelSize(
                R.dimen.tablet_camera_panel_width) : dp(340);
        int panelWidth = Math.min(tablet ? Math.max(preferredWidth, dp(440)) : preferredWidth,Math.round(safeWidth*.46f));
        int maxGuideWidth=Math.max(1,safeWidth-panelWidth-3*edge);
        guideWidth=Math.max(Math.min(dp(170),maxGuideWidth),Math.min(maxGuideWidth,guideWidth==0?Math.round(safeWidth*.34f):guideWidth));
        int guideTop=Math.max(toolbar.getBottom(),banner.getBottom())+dp(8);
        int maxGuideHeight=Math.max(1,Math.min(Math.round(safeHeight*.55f),root.getHeight()-bars.bottom-edge-guideTop));
        guideHeight=Math.max(Math.min(dp(160),maxGuideHeight),Math.min(maxGuideHeight,guideHeight==0?Math.round(safeHeight*.33f):guideHeight));
        if(alignmentParams.width!=guideWidth||alignmentParams.height!=guideHeight||alignmentParams.gravity!=(Gravity.BOTTOM|Gravity.LEFT)){
            alignmentParams.width=guideWidth;alignmentParams.height=guideHeight;alignmentParams.gravity=Gravity.BOTTOM|Gravity.LEFT;
            alignmentParams.leftMargin=bars.left+edge;alignmentParams.bottomMargin=bars.bottom+edge;alignment.setLayoutParams(alignmentParams);
        }
        guide.setAllowedBounds(bars.left+edge,guideTop,root.getWidth()-bars.right-panelWidth-2*edge,root.getHeight()-bars.bottom-edge);
        FrameLayout.LayoutParams previewParams=(FrameLayout.LayoutParams) preview.getLayoutParams();
        int defaultPreviewWidth=Math.min(dp(tablet ? 340 : 220), Math.round(safeWidth*(tablet ? .27f : .38f)));
        int defaultPreviewHeight=Math.min(dp(tablet ? 290 : 220), Math.round(safeHeight*(tablet ? .34f : .28f)));
        int maxPreviewWidth=Math.min(Math.round(safeWidth*.42f), dp(tablet ? 520 : 300));
        int maxPreviewHeight=Math.min(Math.round(safeHeight*.48f), dp(tablet ? 460 : 330));
        int targetPreviewWidth=Math.max(Math.min(dp(200), maxPreviewWidth),
                Math.min(maxPreviewWidth, previewWidth == 0 ? defaultPreviewWidth : previewWidth));
        int targetPreviewHeight=Math.max(Math.min(dp(190), maxPreviewHeight),
                Math.min(maxPreviewHeight, previewHeight == 0 ? defaultPreviewHeight : previewHeight));
        // The model is a movable aid, so keep its largest size below a quarter of the camera.
        double areaLimit=safeWidth*(double)safeHeight*.22;
        if(targetPreviewWidth*(double)targetPreviewHeight>areaLimit) {
            targetPreviewHeight=(int)Math.max(dp(150),areaLimit/targetPreviewWidth);
        }
        previewWidth=targetPreviewWidth;
        previewHeight=targetPreviewHeight;
        int previewTop=toolbar.getBottom()+dp(8);
        if(sidebar) panelWidth=Math.min(panelWidth,safeWidth-targetPreviewWidth-3*edge);
        if(previewParams.width!=targetPreviewWidth || previewParams.height!=targetPreviewHeight
                || previewParams.topMargin!=previewTop
                || previewParams.getMarginEnd()!=endInset+edge) {
            previewParams.width=targetPreviewWidth; previewParams.height=targetPreviewHeight;
            previewParams.topMargin=previewTop; previewParams.setMarginEnd(endInset+edge);
            preview.setLayoutParams(previewParams);
        }

        // Place status below the inset-aware toolbar, not at a hard-coded screen offset.
        FrameLayout.LayoutParams bannerParams = (FrameLayout.LayoutParams) banner.getLayoutParams();
        int bannerTop = toolbar.getBottom() + dp(8);
        int bannerGravity = Gravity.TOP | GravityCompat.START;
        int bannerSpace = safeWidth-targetPreviewWidth-3*edge;
        int maxTextWidth = Math.max(dp(48), bannerSpace - dp(55));
        if (bannerText.getMaxWidth() != maxTextWidth) bannerText.setMaxWidth(maxTextWidth);
        if (bannerParams.topMargin != bannerTop || bannerParams.gravity != bannerGravity
                || bannerParams.getMarginStart() != startInset + edge
                || bannerParams.getMarginEnd() != endInset + edge) {
            bannerParams.gravity = bannerGravity;
            bannerParams.topMargin = bannerTop;
            bannerParams.setMarginStart(startInset + edge);
            bannerParams.setMarginEnd(endInset + edge);
            banner.setLayoutParams(bannerParams);
        }

        int reservedTop = Math.max(bannerTop+banner.getHeight(),previewTop+targetPreviewHeight)+dp(12);
        int availableHeight = root.getHeight() - bars.bottom - edge - reservedTop;
        int preferredHeight = activity.getResources().getDimensionPixelSize(
                R.dimen.assembly_info_panel_height);
        int expandedHeight = Math.min(tablet ? Math.max(preferredHeight,dp(760)) : preferredHeight,
                Math.min(Math.round(safeHeight * (tablet ? .70f : .64f)), availableHeight));
        int minimumExpandedHeight = dp(160);
        if (availableHeight >= minimumExpandedHeight) {
            expandedHeight = Math.max(minimumExpandedHeight, expandedHeight);
        }
        int collapsedHeight = panel.getPaddingTop()
                + Math.max(header.getMeasuredHeight(), dp(68)) + dp(2);
        // Even with very large text or a short split-screen window, the compact state must not
        // climb over the toolbar/status banner.
        int height = expanded ? Math.max(1, expandedHeight)
                : Math.max(1, Math.min(collapsedHeight, availableHeight));
        View target=phaseTwoActive?phaseTwoGuide:phaseOneActive ? phaseOneGuide : panel;
        int compactMaximum=Math.max(1,Math.min(availableHeight,Math.round(safeHeight*.46f)));
        if(phaseOneActive) phaseOneGuide.setMaximumHeight(compactMaximum);
        if(phaseTwoActive)phaseTwoGuide.setMaximumHeight(compactMaximum);
        int targetHeight=phaseOneActive||phaseTwoActive ? FrameLayout.LayoutParams.WRAP_CONTENT : height;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) target.getLayoutParams();
        int gravity = Gravity.BOTTOM | GravityCompat.END;
        if (params.width != panelWidth || params.height != targetHeight || params.gravity != gravity
                || params.getMarginStart() != startInset + edge
                || params.getMarginEnd() != endInset + edge
                || params.bottomMargin != bars.bottom + edge) {
            params.width = panelWidth;
            params.height = targetHeight;
            params.gravity = gravity;
            params.setMarginStart(startInset + edge);
            params.setMarginEnd(endInset + edge);
            params.bottomMargin = bars.bottom + edge;
            target.setLayoutParams(params);
        }
        panel.post(this::clampPanel);
        // Keep the instructions clear of the navigation bar while restoring the scroll position.
        if (restoreScroll && contentReady && expanded && scroll.getHeight() > 0
                && !scroll.isLayoutRequested()) {
            scroll.scrollTo(0, savedScrollY);
            restoreScroll = false;
        }
    }

    private int dp(int value) {
        return UiUtils.dp(activity, value);
    }

    private void clampPanel() {
        View panel=phaseTwoActive?phaseTwoGuide:phaseOneActive ? phaseOneGuide : this.panel;
        if(panel.getWidth()==0 || root.getWidth()==0) return;
        WindowInsetsCompat windowInsets=ViewCompat.getRootWindowInsets(root);
        Insets bars=windowInsets==null ? Insets.NONE : windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        float minimumX=bars.left+dp(8)-panel.getLeft();
        float maximumX=root.getWidth()-bars.right-dp(8)-panel.getRight();
        float minimumY=toolbar.getBottom()+dp(8)-panel.getTop();
        float maximumY=root.getHeight()-bars.bottom-dp(8)-panel.getBottom();
        panel.setTranslationX(Math.max(minimumX,Math.min(maximumX,panel.getTranslationX())));
        panel.setTranslationY(Math.max(minimumY,Math.min(maximumY,panel.getTranslationY())));
        if(guide.getVisibility()==View.VISIBLE){
            android.graphics.RectF card=new android.graphics.RectF(panel.getX(),panel.getY(),panel.getX()+panel.getWidth(),panel.getY()+panel.getHeight());
            android.graphics.RectF reference=new android.graphics.RectF(guide.getX(),guide.getY(),guide.getX()+guide.getWidth()+dp(12),guide.getY()+guide.getHeight());
            if(android.graphics.RectF.intersects(card,reference))panel.setX(root.getWidth()-bars.right-dp(12)-panel.getWidth());
        }
        // A SurfaceView can cover sibling text even when the card has a higher elevation.
        // Reserve real space for the model instead of relying on drawing order.
        if(preview.getVisibility()==View.VISIBLE && preview.getWidth()>0) {
            float gap=dp(12);
            float left=panel.getX(), top=panel.getY();
            float previewLeft=preview.getX(), previewBottom=preview.getY()+preview.getHeight();
            if(left+panel.getWidth()>previewLeft-gap && left<previewLeft+preview.getWidth()
                    && top<previewBottom+gap && top+panel.getHeight()>preview.getY()) {
                panel.setTranslationY(previewBottom+gap-panel.getTop());
            }
        }
    }
}
