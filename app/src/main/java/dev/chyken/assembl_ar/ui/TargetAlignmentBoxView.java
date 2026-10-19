package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import androidx.annotation.Nullable;

/** Visual positioning aid only; the guide bitmap is never fed to the recognizer. */
public final class TargetAlignmentBoxView extends ImageView {
    private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String asset;
    private String instruction;
    private final android.text.TextPaint caption=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    public void setInstruction(@Nullable String text){instruction=text;invalidate();}
    public TargetAlignmentBoxView(Context c,@Nullable AttributeSet attrs){super(c,attrs);setScaleType(ScaleType.FIT_CENTER);setImageAlpha(45);line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(UiUtils.dp(c,2));line.setColor(0x99D8E8D0);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void showGuide(@Nullable String path){
        ArHudView hud=getRootView().findViewById(dev.chyken.assembl_ar.R.id.arHudView);
        if(hud!=null)hud.setTargetGuideVisible(path!=null);
        if(path==null){setVisibility(GONE);instruction=null;return;}setVisibility(VISIBLE);if(!path.equals(asset)){asset=path;setImageDrawable(null);AssetImageLoader.loadBounded(this,path,960);}
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float p=UiUtils.dp(getContext(),3);
        canvas.drawRoundRect(p,p,getWidth()-p,getHeight()-p,UiUtils.dp(getContext(),10),UiUtils.dp(getContext(),10),line);
        if(instruction!=null){
            float pad=UiUtils.dp(getContext(),12);caption.setTextSize(UiUtils.dp(getContext(),14));
            String text=android.text.TextUtils.ellipsize(instruction,caption,Math.max(1,getWidth()-pad*4),android.text.TextUtils.TruncateAt.END).toString();
            caption.setColor(0xE61B211E);canvas.drawRoundRect(pad,pad,getWidth()-pad,pad*2+caption.getTextSize(),p*2,p*2,caption);
            caption.setColor(0xFFF1F5EE);canvas.drawText(text,pad*2,pad*1.5f+caption.getTextSize(),caption);
        }
    }
}
