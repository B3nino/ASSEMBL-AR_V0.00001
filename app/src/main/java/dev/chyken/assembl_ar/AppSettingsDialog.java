package dev.chyken.assembl_ar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.lifecycle.*;
import dev.chyken.assembl_ar.diagnostics.PreferencesManager;
import dev.chyken.assembl_ar.ui.UiUtils;

/** Presentation preferences only. Existing scan gates, administrator PIN and progress are retained. */
public final class AppSettingsDialog {
    private AppSettingsDialog(){}
    public static AlertDialog show(Activity activity,Context context){
        boolean resumePart=activity instanceof PartRecognitionTestActivity&&((PartRecognitionTestActivity)activity).pauseForDiagnostics();
        if(activity instanceof AssemblyActivity)((AssemblyActivity)activity).pauseForDiagnostics();
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);int p=UiUtils.dp(context,20);body.setPadding(p,p,p,p);body.setBackgroundColor(context.getColor(R.color.canvas));
        TextView heading=new TextView(context);heading.setText(R.string.settings_visibility);heading.setTextSize(18);heading.setTextColor(context.getColor(R.color.ink));body.addView(heading);
        SwitchCompat admin=toggle(body,R.id.settingsAdminSwitch,R.string.settings_admin,R.string.settings_admin_detail,PreferencesManager.administratorVisible(activity));
        admin.setOnCheckedChangeListener((v,checked)->PreferencesManager.administratorVisible(activity,checked));
        SwitchCompat dev=toggle(body,R.id.settingsDeveloperSwitch,R.string.settings_dev,R.string.settings_dev_detail,PreferencesManager.assemblyDeveloperVisible(activity));
        dev.setOnCheckedChangeListener((v,checked)->PreferencesManager.assemblyDeveloperVisible(activity,checked));
        ScrollView scroll=new ScrollView(context);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(context).setTitle(R.string.app_settings).setView(scroll)
            .setNeutralButton(R.string.settings_diagnostics,(d,w)->activity.startActivity(new Intent(activity,DevDiagnosticsActivity.class)))
            .setPositiveButton(R.string.settings_done,null).create();
        Lifecycle lifecycle=((LifecycleOwner)activity).getLifecycle();
        DefaultLifecycleObserver cleanup=new DefaultLifecycleObserver(){@Override public void onDestroy(LifecycleOwner owner){dialog.dismiss();}};
        lifecycle.addObserver(cleanup);
        dialog.setOnDismissListener(d->{lifecycle.removeObserver(cleanup);if(activity.isFinishing()||activity.isDestroyed())return;if(activity instanceof AssemblyActivity)((AssemblyActivity)activity).resumeAfterDiagnostics();else if(resumePart)((PartRecognitionTestActivity)activity).resumeAfterDiagnostics();});
        dialog.show();
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEUTRAL}){Button button=dialog.getButton(which);button.setTextColor(context.getColor(R.color.primary));button.setMinHeight(UiUtils.dp(context,48));}
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(UiUtils.dp(context,600),context.getResources().getDisplayMetrics().widthPixels-UiUtils.dp(context,24)),WindowManager.LayoutParams.WRAP_CONTENT);
        return dialog;
    }
    private static SwitchCompat toggle(LinearLayout body,int id,int label,int detail,boolean checked){
        Context c=body.getContext();SwitchCompat control=new SwitchCompat(c);control.setId(id);control.setText(label);control.setTextColor(c.getColor(R.color.ink));control.setTextSize(16);control.setMinHeight(UiUtils.dp(c,56));control.setChecked(checked);control.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{c.getColor(R.color.primary),0xFF708176}));control.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xFF9CC7A9,0xFFC6D3CB}));body.addView(control,new LinearLayout.LayoutParams(-1,-2));
        TextView explanation=new TextView(c);explanation.setText(detail);explanation.setTextSize(14);explanation.setTextColor(c.getColor(R.color.ink_soft));explanation.setPadding(0,0,0,UiUtils.dp(c,16));body.addView(explanation);return control;
    }
}
