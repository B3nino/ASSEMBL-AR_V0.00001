package dev.chyken.assembl_ar;

import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.ui.*;

/** The action lives in a fixed footer, outside the scrolling copy and theme button bar. */
final class WelcomeAssemblyDialog {
    static AlertDialog show(AssemblyActivity activity,Runnable start){
        android.view.ContextThemeWrapper light=new android.view.ContextThemeWrapper(activity,R.style.Theme_ASSEMBLAR);
        View content=activity.getLayoutInflater().cloneInContext(light).inflate(R.layout.dialog_welcome_assembly,null);
        LogoFormationView logo=content.findViewById(R.id.welcomeLogo);
        Button action=content.findViewById(R.id.welcomeStartButton);
        action.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF326A32));
        action.setTextColor(0xFFFFFFFF);action.setAlpha(1);action.setEnabled(true);
        AlertDialog dialog=new AlertDialog.Builder(light).setView(content).create();
        dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener(d->activity.finish());dialog.show();
        PhysicalCheckUi.style(dialog);
        int width=Math.min(UiUtils.dp(activity,600),activity.getResources().getDisplayMetrics().widthPixels-UiUtils.dp(activity,32));
        int height=Math.min(UiUtils.dp(activity,580),activity.getResources().getDisplayMetrics().heightPixels-UiUtils.dp(activity,48));
        dialog.getWindow().setLayout(width,height);
        action.setOnClickListener(v->{dialog.dismiss();start.run();});
        // Animation never gates visibility, readability, or the ability to start.
        logo.startFormation(1150,()->{if(dialog.isShowing()&&activity.isActivityResumed())activity.voiceGuide().speak(activity.getString(R.string.welcome_assembly_body),"welcome:assembly");});
        dialog.setOnDismissListener(d->logo.cancelFormation());return dialog;
    }
}
