package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;
import dev.chyken.assembl_ar.R;

/** Phase 1's learner card. All content scrolls when large text or a short window needs it. */
public final class PhaseOneGuideView extends FrameLayout {
    private final LinearLayout body;
    private final TextView dragHandle, progress, instruction, status, learn, nudge;
    private final TextView details;
    private final MaterialButton next;
    private int maximumHeight = Integer.MAX_VALUE;
    private boolean expanded, scanned;
    private final Runnable dismissNudge = () -> fadeNudge();

    public PhaseOneGuideView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setBackgroundResource(R.drawable.bg_camera_panel);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        addView(scroll, new LayoutParams(-1, -2));
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(14));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        dragHandle = text(12, 0xFFB6C0BD);
        dragHandle.setText(R.string.phase_one_move);
        dragHandle.setMinHeight(dp(32));
        dragHandle.setGravity(Gravity.CENTER_VERTICAL);
        dragHandle.setContentDescription(context.getString(R.string.drag_info_window));
        TextView phase = text(15, 0xFF92BD54);
        phase.setTypeface(null, android.graphics.Typeface.BOLD);
        phase.setText(R.string.phase_one_heading);
        progress = text(12, 0xFFB6C0BD);
        progress.setText(R.string.phase_one_progress_scan);
        instruction = text(19, Color.WHITE);
        instruction.setPadding(0, dp(10), 0, dp(6));
        instruction.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        instruction.setText(R.string.phase_one_bring_board);
        status = text(14, 0xFFB4DA8A);
        status.setVisibility(GONE);
        status.setPadding(0, dp(6), 0, dp(6));
        status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        learn = text(14, 0xFFCED7D1);
        learn.setMinHeight(dp(48));
        learn.setGravity(Gravity.CENTER_VERTICAL);
        learn.setBackgroundResource(android.R.drawable.list_selector_background);
        learn.setFocusable(true);
        learn.setOnClickListener(v -> setExpanded(!expanded));
        details = text(15, 0xFFCED7D1);
        details.setText(R.string.phase_one_board_details);
        details.setPadding(0, dp(4), 0, dp(12));
        details.setLineSpacing(dp(4), 1f);
        details.setVisibility(GONE);
        nudge = text(13, 0xFFB4DA8A);
        nudge.setText(R.string.phase_one_learn_nudge);
        nudge.setPadding(0, dp(6), 0, dp(10));
        nudge.setVisibility(GONE);
        next = new MaterialButton(context);
        next.setMinHeight(dp(48));
        next.setText(R.string.phase_one_next_locked);
        next.setEnabled(false);
        body.addView(next, new LinearLayout.LayoutParams(-1, -2));
        setExpanded(false);
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(size); view.setTextColor(color);
        body.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    public View getDragHandle() { return dragHandle; }
    public void setNextAction(Runnable action) { next.setOnClickListener(v -> action.run()); }
    public boolean isNextEnabled() { return next.isEnabled(); }
    public CharSequence getInstruction() { return instruction.getText(); }
    public boolean isExpanded() { return expanded; }

    public void reset() {
        removeCallbacks(dismissNudge); nudge.animate().cancel(); nudge.setVisibility(GONE);
        scanned = false; setExpanded(false);
        setInstruction(getContext().getString(R.string.phase_one_bring_board));
        setScanned(false, false);
    }

    public void setInstruction(CharSequence text) {
        if (text.equals(instruction.getText())) return;
        instruction.setText(text);
        instruction.animate().cancel(); instruction.setAlpha(.4f);
        instruction.animate().alpha(1f).setDuration(180).start();
    }

    public void setScanned(boolean complete, boolean saving) {
        setScanned(complete,saving,false);
    }

    public void setScanned(boolean complete, boolean saving, boolean developer) {
        boolean newlyComplete = complete && !scanned;
        scanned = complete;
        progress.setText(complete ? R.string.phase_one_progress_done : R.string.phase_one_progress_scan);
        status.setVisibility(complete ? VISIBLE : GONE);
        status.setText(developer ? R.string.phase_one_dev_complete : R.string.phase_one_scan_success);
        next.setVisibility(complete ? VISIBLE : GONE);
        next.setEnabled(complete && !saving);
        next.setAlpha(complete && !saving ? 1f : .45f);
        next.setText(saving ? R.string.phase_saving : complete
                ? R.string.phase_one_next_unlocked : R.string.phase_one_next_locked);
        if (newlyComplete) {
            setInstruction(getContext().getString(R.string.phase_one_complete));
            if (!expanded) {
                nudge.animate().cancel(); nudge.setAlpha(1f); nudge.setVisibility(VISIBLE);
                removeCallbacks(dismissNudge); postDelayed(dismissNudge, 5_000);
            }
        }
    }

    private void setExpanded(boolean value) {
        expanded = value;
        details.setVisibility(value ? VISIBLE : GONE);
        learn.setText(value ? R.string.phase_one_less : R.string.phase_one_learn);
        if(value) { removeCallbacks(dismissNudge); nudge.setVisibility(GONE); }
    }

    private void fadeNudge() {
        nudge.animate().alpha(0f).setDuration(450).withEndAction(() -> nudge.setVisibility(GONE)).start();
    }

    public void setMaximumHeight(int height) {
        if (maximumHeight == height) return;
        maximumHeight = Math.max(1, height); requestLayout();
    }

    @Override protected void onMeasure(int width, int height) {
        int limit = Math.min(maximumHeight, MeasureSpec.getSize(height));
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(dismissNudge); nudge.animate().cancel(); instruction.animate().cancel();
        super.onDetachedFromWindow();
    }

    private int dp(int value) { return UiUtils.dp(getContext(),value); }
}
