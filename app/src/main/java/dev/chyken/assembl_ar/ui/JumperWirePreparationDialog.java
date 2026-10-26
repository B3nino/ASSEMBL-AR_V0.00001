package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.concurrent.*;
import java.lang.ref.WeakReference;

/** Reusable, explicit preparation gate. Decoding stays off the UI/camera workers. */
public final class JumperWirePreparationDialog {
    public static final String DISCLAIMER="Note: Wire color selection is strictly for your personal visual guidance and custom preferences. Choosing different colors has NO impact on the electrical circuit functionality!";
    private static final ExecutorService IMAGES=Executors.newSingleThreadExecutor();
    private static volatile Bitmap graphic;
    private JumperWirePreparationDialog(){}
    public static AlertDialog show(Context context,int count,boolean motorLeads,String extra,String action,Runnable confirm){
        LinearLayout body=PhysicalCheckUi.body(context);
        ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("Female-to-female jumper wires with socket housings at both ends");
        if(!motorLeads){body.addView(image,new LinearLayout.LayoutParams(-1,UiUtils.dp(context,150)));if(graphic!=null)image.setImageBitmap(graphic);else{Context app=context.getApplicationContext();WeakReference<ImageView> target=new WeakReference<>(image);IMAGES.execute(()->{try(java.io.InputStream in=app.getAssets().open("schematics/components/female_jumper_wires.png")){graphic=BitmapFactory.decodeStream(in);ImageView view=target.get();if(view!=null)view.post(()->view.setImageBitmap(graphic));}catch(java.io.IOException ignored){}});}}
        TextView countText=PhysicalCheckUi.prompt(context,"Prepare "+count+(motorLeads?" motor wire leads.":" female-to-female jumper wires."));countText.setPadding(0,UiUtils.dp(context,10),0,0);countText.setMinHeight(0);body.addView(countText);
        TextView description=PhysicalCheckUi.prompt(context,extra);description.setTextSize(15);description.setMinHeight(0);description.setPadding(0,UiUtils.dp(context,12),0,UiUtils.dp(context,12));body.addView(description);
        TextView note=PhysicalCheckUi.prompt(context,DISCLAIMER);note.setTextSize(14);note.setMinHeight(0);note.setPadding(UiUtils.dp(context,12),UiUtils.dp(context,12),UiUtils.dp(context,12),UiUtils.dp(context,12));note.setBackgroundColor(0xFFE4EFE1);body.addView(note);
        AlertDialog dialog=new PhysicalChecklistBottomSheetDialog.Builder(context).setTitle(motorLeads?"Prepare motor leads":"Prepare jumper wires").setView(body).setNegativeButton("Not yet",null).setPositiveButton(action,(d,w)->confirm.run()).create();
        dialog.show();return dialog;
    }
}
