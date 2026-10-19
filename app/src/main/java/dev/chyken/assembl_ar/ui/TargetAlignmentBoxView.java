package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;
import androidx.annotation.Nullable;

/** Visual positioning aid only; the guide bitmap is never fed to the recognizer. */
public final class TargetAlignmentBoxView extends android.widget.FrameLayout {
    public interface ResizeListener{void onResize(int width,int height);}
    private ResizeListener resize;
    private final ImageView picture;
    private final android.widget.TextView captionView;
    private final android.graphics.RectF bounds=new android.graphics.RectF();
    private float startX,startY,originX,originY;
    private int startWidth,startHeight;
    private String asset;
    public void setInstruction(@Nullable String text){captionView.setText(text==null?"Hold title to move · Match this view":text);}
    public TargetAlignmentBoxView(Context c,@Nullable AttributeSet attrs){
        super(c,attrs);setBackgroundResource(dev.chyken.assembl_ar.R.drawable.bg_camera_panel);setElevation(dp(7));
        picture=new ImageView(c);picture.setScaleType(ImageView.ScaleType.FIT_CENTER);picture.setImageAlpha(100);
        LayoutParams imageParams=new LayoutParams(-1,-1);imageParams.topMargin=dp(56);imageParams.bottomMargin=dp(30);addView(picture,imageParams);
        android.widget.TextView title=new android.widget.TextView(c);title.setText("Recommended Scanning Frame");title.setTextColor(0xFFF0F3EF);title.setTextSize(13);title.setGravity(android.view.Gravity.CENTER_VERTICAL);title.setPadding(dp(10),0,dp(6),0);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);addView(title,new LayoutParams(-1,dp(32),android.view.Gravity.TOP));title.setContentDescription("Recommended Scanning Frame. Hold title to move.");
        captionView=new android.widget.TextView(c);captionView.setTextColor(0xFFB9C5BD);captionView.setTextSize(11);captionView.setPadding(dp(10),0,dp(5),0);captionView.setMaxLines(1);captionView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams infoParams=new LayoutParams(-1,dp(23),android.view.Gravity.TOP);infoParams.topMargin=dp(32);addView(captionView,infoParams);setInstruction(null);
        android.widget.TextView handle=new android.widget.TextView(c);handle.setText("↘  Drag to resize");handle.setTextSize(11);handle.setTextColor(0xFFB9C5BD);handle.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);handle.setPadding(0,0,dp(8),0);addView(handle,new LayoutParams(-1,dp(32),android.view.Gravity.BOTTOM));
        title.setOnTouchListener((v,e)->{switch(e.getActionMasked()){
            case android.view.MotionEvent.ACTION_DOWN:startX=e.getRawX();startY=e.getRawY();originX=getTranslationX();originY=getTranslationY();return true;
            case android.view.MotionEvent.ACTION_MOVE:setTranslationX(originX+e.getRawX()-startX);setTranslationY(originY+e.getRawY()-startY);clamp();return true;
            case android.view.MotionEvent.ACTION_UP:v.performClick();return true;default:return true;}});
        handle.setOnTouchListener((v,e)->{switch(e.getActionMasked()){
            case android.view.MotionEvent.ACTION_DOWN:startX=e.getRawX();startY=e.getRawY();startWidth=getWidth();startHeight=getHeight();originY=getY();return true;
            case android.view.MotionEvent.ACTION_MOVE:if(resize!=null){resize.onResize(startWidth+Math.round(e.getRawX()-startX),startHeight+Math.round(e.getRawY()-startY));post(()->{setY(originY);clamp();});}return true;
            case android.view.MotionEvent.ACTION_UP:v.performClick();return true;default:return true;}});
    }
    private int dp(int value){return UiUtils.dp(getContext(),value);}
    public void setResizeListener(ResizeListener listener){resize=listener;}
    public void setAllowedBounds(float l,float t,float r,float b){bounds.set(l,t,r,b);post(this::clamp);}
    private void clamp(){if(bounds.isEmpty()||getWidth()==0)return;setX(Math.max(bounds.left,Math.min(bounds.right-getWidth(),getX())));setY(Math.max(bounds.top,Math.min(bounds.bottom-getHeight(),getY())));}
    public void showGuide(@Nullable String path){
        ArHudView hud=getRootView().findViewById(dev.chyken.assembl_ar.R.id.arHudView);
        if(hud!=null)hud.setTargetGuideVisible(path!=null);
        if(path==null){setVisibility(GONE);return;}setVisibility(VISIBLE);if(!path.equals(asset)){asset=path;AssetImageLoader.loadBounded(picture,path,960);}
    }
}
