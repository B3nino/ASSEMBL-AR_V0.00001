package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
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
    private final View guidedStepNavigation;
    private final View confirmation;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateLayout;
    private boolean expanded;
    private boolean contentReady;
    private boolean restoreScroll = true;
    private int savedScrollY;
    @Nullable private String stepId;

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
        guidedStepNavigation = activity.findViewById(R.id.guidedStepNavigationRow);
        confirmation = activity.findViewById(R.id.confirmPlacementButton);
        expanded = state == null || state.getBoolean(STATE_EXPANDED, true);
        stepId = state == null ? null : state.getString(STATE_STEP);
        savedScrollY = state == null ? 0 : state.getInt(STATE_SCROLL, 0);
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

    public void onStepChanged(String newStepId) {
        if (!newStepId.equals(stepId)) {
            savedScrollY = 0;
            restoreScroll = true;
        }
        stepId = newStepId;
        contentReady = true;
        scroll.requestLayout();
    }

    public void saveState(Bundle state) {
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
        boolean rtl = ViewCompat.getLayoutDirection(root) == ViewCompat.LAYOUT_DIRECTION_RTL;
        int startInset = rtl ? bars.right : bars.left;
        int endInset = rtl ? bars.left : bars.right;
        boolean tablet = UiUtils.isTablet(activity);
        boolean sidebar = safeWidth >= dp(640) && (tablet || safeWidth > safeHeight);
        int edge = dp(tablet ? 20 : 12);
        int preferredWidth = tablet ? activity.getResources().getDimensionPixelSize(
                R.dimen.tablet_camera_panel_width) : dp(340);
        int panelWidth = sidebar ? Math.min(preferredWidth, Math.round(safeWidth * 0.48f))
                : safeWidth - 2 * edge;

        // Place status below the inset-aware toolbar, not at a hard-coded screen offset.
        FrameLayout.LayoutParams bannerParams = (FrameLayout.LayoutParams) banner.getLayoutParams();
        int bannerTop = toolbar.getBottom() + dp(8);
        int bannerGravity = Gravity.TOP
                | (sidebar ? GravityCompat.START : Gravity.CENTER_HORIZONTAL);
        int bannerSpace = sidebar ? safeWidth - panelWidth - 3 * edge : safeWidth - 2 * edge;
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

        int reservedTop = sidebar ? toolbar.getBottom() + dp(8)
                : bannerTop + banner.getHeight() + dp(12);
        int availableHeight = root.getHeight() - bars.bottom - edge - reservedTop;
        int preferredHeight = activity.getResources().getDimensionPixelSize(
                R.dimen.assembly_info_panel_height);
        int expandedHeight = Math.min(preferredHeight,
                Math.min(Math.round(safeHeight * (sidebar ? 0.86f : 0.62f)), availableHeight));
        int minimumExpandedHeight = dp(160);
        if (availableHeight >= minimumExpandedHeight) {
            expandedHeight = Math.max(minimumExpandedHeight, expandedHeight);
        }
        int collapsedHeight = panel.getPaddingTop() + panel.getPaddingBottom()
                + Math.max(header.getMeasuredHeight(), dp(48));
        View partReference = activity.findViewById(R.id.requiredPartReference);
        if (partReference.getVisibility() == View.VISIBLE) {
            collapsedHeight += dp(4) + Math.max(partReference.getMeasuredHeight(), dp(56));
        }
        View motorStatus = activity.findViewById(R.id.motorInspectionRow);
        if (motorStatus.getVisibility() == View.VISIBLE) {
            collapsedHeight += Math.max(motorStatus.getMeasuredHeight(), dp(48));
        }
        if (guidedStepNavigation.getVisibility() == View.VISIBLE) {
            android.view.ViewGroup.MarginLayoutParams navigationParams =
                    (android.view.ViewGroup.MarginLayoutParams) guidedStepNavigation.getLayoutParams();
            collapsedHeight += navigationParams.topMargin
                    + Math.max(guidedStepNavigation.getMeasuredHeight(), dp(48));
        }
        if (confirmation.getVisibility() == View.VISIBLE) {
            android.view.ViewGroup.MarginLayoutParams confirmationParams =
                    (android.view.ViewGroup.MarginLayoutParams) confirmation.getLayoutParams();
            collapsedHeight += confirmationParams.topMargin
                    + Math.max(confirmation.getMeasuredHeight(), dp(48));
        }
        // Even with very large text or a short split-screen window, the compact state must not
        // climb over the toolbar/status banner.
        int height = expanded ? Math.max(1, expandedHeight)
                : Math.max(1, Math.min(collapsedHeight, availableHeight));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int gravity = Gravity.BOTTOM | GravityCompat.END;
        if (params.width != panelWidth || params.height != height || params.gravity != gravity
                || params.getMarginStart() != startInset + edge
                || params.getMarginEnd() != endInset + edge
                || params.bottomMargin != edge) {
            params.width = panelWidth;
            params.height = height;
            params.gravity = gravity;
            params.setMarginStart(startInset + edge);
            params.setMarginEnd(endInset + edge);
            params.bottomMargin = edge;
            panel.setLayoutParams(params);
        }
        // configureCameraWindow already offsets this panel above the bottom system inset.
        if (restoreScroll && contentReady && expanded && scroll.getHeight() > 0
                && !scroll.isLayoutRequested()) {
            scroll.scrollTo(0, savedScrollY);
            restoreScroll = false;
        }
    }

    private int dp(int value) {
        return UiUtils.dp(activity, value);
    }
}
