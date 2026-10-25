package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.diagnostics.DiagnosticHelpContent;

/** Same readable mint/charcoal surface as the individual recognition workspace. */
public final class DiagnosticControlsHelp {
    private DiagnosticControlsHelp() { }
    public static void show(Activity activity){
        show(activity,null);
    }
    public static void show(Activity activity,Runnable onClose){
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        int padding=UiUtils.dp(activity,20);body.setPadding(padding,padding,padding,padding);
        body.setBackgroundColor(activity.getColor(R.color.canvas));
        for(String[] section:DiagnosticHelpContent.SECTIONS){
            TextView title=new TextView(activity);title.setText(section[0]);title.setTextSize(17);
            title.setTextColor(activity.getColor(R.color.ink));title.setTypeface(null,android.graphics.Typeface.BOLD);
            title.setPadding(0,UiUtils.dp(activity,12),0,UiUtils.dp(activity,4));body.addView(title);
            TextView text=new TextView(activity);text.setText(section[1]);text.setTextSize(14);
            text.setTextColor(activity.getColor(R.color.ink_soft));body.addView(text);
        }
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Diagnostic control guide").setView(scroll).setPositiveButton("Close",null).create();
        if(onClose!=null)dialog.setOnDismissListener(d->{if(!activity.isFinishing())onClose.run();});dialog.show();
    }
}
