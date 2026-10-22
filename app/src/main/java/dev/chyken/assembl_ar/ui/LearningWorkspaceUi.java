package dev.chyken.assembl_ar.ui;
import android.content.Context;import android.graphics.drawable.GradientDrawable;import android.view.*;import android.widget.*;
/** Shared restrained typography, spacing and touch controls for the post-assembly hub. */
public final class LearningWorkspaceUi {
    private LearningWorkspaceUi(){}
    public static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(1);int p=UiUtils.dp(c,20);v.setPadding(p,p,p,p);v.setBackgroundColor(0xFFF1F6F0);return v;}
    public static TextView text(Context c,String text,int sp){TextView v=new TextView(c);v.setText(text);v.setTextSize(sp);v.setTextColor(0xFF20382B);v.setPadding(0,UiUtils.dp(c,6),0,UiUtils.dp(c,10));v.setLineSpacing(UiUtils.dp(c,3),1);return v;}
    public static Button button(Context c,String text,Runnable action){Button b=new Button(c);b.setText(text);b.setAllCaps(false);b.setTextColor(0xFF285F32);b.setMinHeight(UiUtils.dp(c,48));b.setOnClickListener(v->action.run());return b;}
    public static ScrollView scroll(Context c,LinearLayout content){ScrollView scroll=new ScrollView(c);scroll.setFillViewport(true);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));return scroll;}
}
