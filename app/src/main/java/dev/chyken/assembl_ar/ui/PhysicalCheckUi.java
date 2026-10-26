package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;

/** Consistent, readable hands-on checks with generous touch targets and explicit answers. */
public final class PhysicalCheckUi {
    private PhysicalCheckUi(){}
    public static TextView prompt(Context context,String text){
        TextView view=new TextView(context);view.setText(text);view.setTextSize(17);view.setTextColor(0xFF263B30);
        view.setLineSpacing(UiUtils.dp(context,4),1);int p=UiUtils.dp(context,24);view.setPadding(p,UiUtils.dp(context,18),p,UiUtils.dp(context,22));
        view.setMinHeight(UiUtils.dp(context,112));view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);return view;
    }
    public static LinearLayout body(Context context){
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);int p=UiUtils.dp(context,24);body.setPadding(p,UiUtils.dp(context,12),p,UiUtils.dp(context,18));return body;
    }
    public static RadioGroup choices(Context context,String label){
        RadioGroup group=new RadioGroup(context);group.setOrientation(LinearLayout.HORIZONTAL);
        for(String option:new String[]{"Firm & Secure","Needs Adjustment"}){
            RadioButton radio=new RadioButton(context);radio.setId(View.generateViewId());radio.setText(option);radio.setContentDescription(label+": "+option);radio.setTextSize(16);
            radio.setMinHeight(UiUtils.dp(context,56));radio.setButtonTintList(ColorStateList.valueOf(0xFF4C7C41));
            RadioGroup.LayoutParams params=new RadioGroup.LayoutParams(0,-2,1);params.setMargins(0,UiUtils.dp(context,4),UiUtils.dp(context,8),UiUtils.dp(context,12));group.addView(radio,params);
            GradientDrawable shape=new GradientDrawable();shape.setColor(0xFF293239);shape.setCornerRadius(UiUtils.dp(context,18));radio.setBackground(shape);
            radio.setOnCheckedChangeListener((button,checked)->shape.setColor(checked?0xFF315640:0xFF293239));
            radio.setPadding(UiUtils.dp(context,8),0,UiUtils.dp(context,8),0);
        }return group;
    }
    public static void style(AlertDialog dialog){
        if(dialog instanceof PhysicalChecklistBottomSheetDialog)return;
        if(dialog.getWindow()==null)return;Context context=dialog.getContext();
        GradientDrawable background=new GradientDrawable();background.setColor(0xFFF8FBF7);background.setCornerRadius(UiUtils.dp(context,24));dialog.getWindow().setBackgroundDrawable(background);
        int width=Math.min(UiUtils.dp(context,560),context.getResources().getDisplayMetrics().widthPixels-UiUtils.dp(context,32));dialog.getWindow().setLayout(width,-2);
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){
            Button button=dialog.getButton(which);if(button==null)continue;button.setAllCaps(false);button.setTextSize(14);button.setMinHeight(UiUtils.dp(context,52));button.setTextColor(which==AlertDialog.BUTTON_POSITIVE?0xFF326A32:0xFF546357);
        }
        styleChecks(dialog.getWindow().getDecorView(),context);
    }
    private static void styleChecks(View view,Context context){
        // Camera activities use light text, but these dialogs deliberately use a light surface.
        // Fix inherited titles/messages/radio labels as well as explicitly created checkboxes.
        if(view instanceof TextView&&!(view instanceof Button))((TextView)view).setTextColor(0xFF263B30);
        if(view instanceof RadioButton)((RadioButton)view).setTextColor(0xFF263B30);
        if(view instanceof CheckBox){CheckBox box=(CheckBox)view;box.setMinHeight(UiUtils.dp(context,60));box.setTextSize(16);box.setTextColor(0xFF263B30);box.setButtonTintList(ColorStateList.valueOf(0xFF4C7C41));}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)styleChecks(group.getChildAt(i),context);}
    }
}

