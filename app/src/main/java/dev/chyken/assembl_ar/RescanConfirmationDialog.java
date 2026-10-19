package dev.chyken.assembl_ar;
import androidx.appcompat.app.AlertDialog;
final class RescanConfirmationDialog {
    static AlertDialog show(AssemblyActivity activity,Runnable confirmed){
        return new AlertDialog.Builder(activity).setTitle("Rescan this step?")
                .setMessage("Are you sure you want to rescan this step? Current step verification progress will be reset.")
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton("Rescan",(d,w)->confirmed.run()).show();
    }
}
