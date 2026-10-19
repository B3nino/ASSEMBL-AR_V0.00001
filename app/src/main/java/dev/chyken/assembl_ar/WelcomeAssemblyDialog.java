package dev.chyken.assembl_ar;
import androidx.appcompat.app.AlertDialog;
final class WelcomeAssemblyDialog {
    static AlertDialog show(AssemblyActivity activity,Runnable start){
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Welcome to ASSEMBL-AR")
                .setMessage("Welcome to ASSEMBL-AR! Prepare your workstation, components, and tools before starting.")
                .setPositiveButton("Let's Assemble",(d,w)->start.run()).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d->activity.finish());dialog.show();return dialog;
    }
}
