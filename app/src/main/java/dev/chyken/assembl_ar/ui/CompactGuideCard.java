package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;
import dev.chyken.assembl_ar.R;

/** A bounded, scrollable instruction stream with optional detail, reusable by future phases. */
public final class CompactGuideCard extends FrameLayout {
    private final LinearLayout body;
    private final ScrollView scroll;
    private final LinearLayout footer;
    private final TextView handle,heading,instruction,status,learn,details,nudge;
    public final MaterialButton previous,next,action,rescan,returnToCurrent;
    private int maximumHeight=Integer.MAX_VALUE;
    private boolean expanded;
    private String stepKey="";
    private final Runnable hideNudge=()->fadeNudge();
    public CompactGuideCard(Context context,@Nullable AttributeSet attrs){
        super(context,attrs);setBackgroundResource(R.drawable.bg_camera_panel);
        scroll=new ScrollView(context);scroll.setFillViewport(false);addView(scroll,new LayoutParams(-1,-2));
        body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),dp(10),dp(14),dp(4));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        footer=new LinearLayout(context);footer.setOrientation(LinearLayout.VERTICAL);footer.setPadding(dp(14),0,dp(14),dp(12));
        addView(footer,new LayoutParams(-1,-2,Gravity.BOTTOM));
        handle=text(11,0xFFABB8B0);handle.setText("Hold here to move");handle.setMinHeight(dp(28));
        heading=text(14,0xFFB4D58A);heading.setTypeface(null,1);
        instruction=text(17,0xFFF3F5F1);instruction.setPadding(0,dp(8),0,dp(6));
        instruction.setAccessibilityLiveRegion(1);
        status=text(13,0xFFB4D58A);status.setAccessibilityLiveRegion(1);
        learn=text(13,0xFFCFD8D0);learn.setMinHeight(dp(44));learn.setGravity(Gravity.CENTER_VERTICAL);
        learn.setOnClickListener(v->setExpanded(!expanded));learn.setFocusable(true);
        details=text(14,0xFFCFD8D0);details.setLineSpacing(dp(3),1);details.setPadding(0,0,0,dp(8));
        nudge=text(12,0xFFB4D58A);nudge.setText("↗ Explore this step in Expand to learn more before continuing.");nudge.setVisibility(GONE);
        action=button(context,"Continue");footer.addView(action,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout nav=new LinearLayout(context);footer.addView(nav,new LinearLayout.LayoutParams(-1,-2));
        previous=button(context,"Previous");next=button(context,"Next Step");
        secondary(previous);
        nav.addView(previous,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams nextParams=new LinearLayout.LayoutParams(0,-2,1);nextParams.leftMargin=dp(6);nav.addView(next,nextParams);
        rescan=button(context,"Rescan this step");footer.addView(rescan,new LinearLayout.LayoutParams(-1,-2));
        secondary(rescan);
        returnToCurrent=button(context,"Return to current phase");secondary(returnToCurrent);
        returnToCurrent.setVisibility(GONE);footer.addView(returnToCurrent,new LinearLayout.LayoutParams(-1,-2));
        setExpanded(false);
    }
    private TextView text(int sp,int color){TextView t=new TextView(getContext());t.setTextSize(sp);t.setTextColor(color);body.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private MaterialButton button(Context c,String text){MaterialButton b=new MaterialButton(c);b.setText(text);b.setTextSize(13);b.setMinHeight(dp(48));return b;}
    private void secondary(MaterialButton button){button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));button.setStrokeWidth(dp(1));button.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF526057));button.setTextColor(0xFFE1E8E0);}
    public View dragHandle(){return handle;}
    public void render(int step,String title,String summary,String more,String message,boolean completed,boolean canPrevious,boolean canRescan){
        String key=Integer.toString(step);boolean changed=!key.equals(stepKey);
        if(changed){stepKey=key;setExpanded(false);removeCallbacks(hideNudge);nudge.animate().cancel();nudge.setVisibility(GONE);}
        heading.setText(title);instruction.setText(summary);details.setText(more);
        boolean wasComplete=!changed&&status.getVisibility()==VISIBLE && "Step complete · Next step unlocked".contentEquals(status.getText());
        status.setText(completed?"Step complete · Next step unlocked":message);
        status.setVisibility(completed || message!=null&&!message.isEmpty()?VISIBLE:GONE);
        previous.setEnabled(canPrevious);previous.setAlpha(canPrevious?1:.4f);
        next.setEnabled(completed);next.setAlpha(completed?1:.4f);
        next.setText(step==6?"Finish Phase 2 →":"Next Step →");
        rescan.setVisibility(canRescan?VISIBLE:GONE);
        if(completed&&!wasComplete&&!expanded){nudge.setVisibility(VISIBLE);nudge.setAlpha(1);postDelayed(hideNudge,4500);}
    }
    private void setExpanded(boolean value){expanded=value;details.setVisibility(value?VISIBLE:GONE);learn.setText(value?"Show less ⌃":"Expand to learn more ⌄");if(value)nudge.setVisibility(GONE);}
    private void fadeNudge(){nudge.animate().alpha(0).setDuration(400).withEndAction(()->nudge.setVisibility(GONE)).start();}
    public void setMaximumHeight(int value){value=Math.max(1,value);if(value!=maximumHeight){maximumHeight=value;requestLayout();}}
    @Override protected void onMeasure(int width,int height){
        int limit=Math.min(maximumHeight,MeasureSpec.getSize(height));
        footer.measure(width,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST));
        scroll.measure(width,MeasureSpec.makeMeasureSpec(Math.max(0,limit-footer.getMeasuredHeight()),MeasureSpec.AT_MOST));
        setMeasuredDimension(MeasureSpec.getSize(width),footer.getMeasuredHeight()+scroll.getMeasuredHeight());
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(hideNudge);nudge.animate().cancel();super.onDetachedFromWindow();}
    private int dp(int v){return UiUtils.dp(getContext(),v);}
}
