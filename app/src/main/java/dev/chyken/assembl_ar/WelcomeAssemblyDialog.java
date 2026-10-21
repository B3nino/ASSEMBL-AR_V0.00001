package dev.chyken.assembl_ar;

import android.view.View;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.ui.*;

final class WelcomeAssemblyDialog {
    static AlertDialog show(AssemblyActivity activity,Runnable start){
        View content=activity.getLayoutInflater().inflate(R.layout.dialog_welcome_assembly,null);
        LogoFormationView logo=content.findViewById(R.id.welcomeLogo);logo.setBackgroundColor(0xFFFFFFFF);
        View copy=content.findViewById(R.id.welcomeCopy);
        AlertDialog dialog=new AlertDialog.Builder(activity).setView(content).setPositiveButton("Let's Assemble",null).create();
        dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener(d->activity.finish());dialog.show();PhysicalCheckUi.style(dialog);
        android.widget.Button action=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        android.graphics.drawable.GradientDrawable fill=new android.graphics.drawable.GradientDrawable();fill.setColor(0xFF3F7D14);fill.setCornerRadius(UiUtils.dp(activity,12));
        action.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),fill,null));action.setTextColor(0xFFFFFFFF);action.setMinWidth(UiUtils.dp(activity,200));action.setPadding(UiUtils.dp(activity,24),0,UiUtils.dp(activity,24),0);

        Runnable ready=()->{if(dialog.isShowing()){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);if(BrandingAssets.motion())copy.animate().alpha(1).translationY(0).setDuration(180).start();else copy.setAlpha(1);if(activity.isActivityResumed())activity.voiceGuide().speak(activity.getString(R.string.welcome_assembly_body),"welcome:assembly");}};
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.dismiss();start.run();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        if(BrandingAssets.motion()){copy.setAlpha(0);copy.setTranslationY(UiUtils.dp(activity,8));}
        logo.startFormation(1150,ready);
        dialog.setOnDismissListener(d->{logo.cancelFormation();copy.animate().cancel();});
        return dialog;
    }
}
