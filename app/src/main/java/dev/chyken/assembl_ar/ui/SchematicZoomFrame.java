package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;

/** View transforms keep image, wires and touch coordinates together when inspecting a diagram. */
final class SchematicZoomFrame extends FrameLayout {
    private final ScaleGestureDetector pinch;
    private float zoom=1,lastX,lastY;private boolean moving;
    SchematicZoomFrame(Context context){super(context);setClipChildren(true);pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
        @Override public boolean onScale(ScaleGestureDetector detector){zoom=Math.max(1,Math.min(4,zoom*detector.getScaleFactor()));apply();return true;}
    });}
    private View diagram(){return getChildCount()==0?null:getChildAt(0);}
    private void apply(){View view=diagram();if(view==null)return;view.setScaleX(zoom);view.setScaleY(zoom);view.setTranslationX(Math.max(-getWidth()*(zoom-1)/2,Math.min(getWidth()*(zoom-1)/2,view.getTranslationX())));view.setTranslationY(Math.max(-getHeight()*(zoom-1)/2,Math.min(getHeight()*(zoom-1)/2,view.getTranslationY())));}
    void zoomBy(float factor){zoom=Math.max(1,Math.min(4,zoom*factor));apply();}
    void resetZoom(){zoom=1;View view=diagram();if(view!=null){view.setTranslationX(0);view.setTranslationY(0);}apply();}
    @Override public boolean dispatchTouchEvent(MotionEvent event){pinch.onTouchEvent(event);return super.dispatchTouchEvent(event);}
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=event.getX();lastY=event.getY();moving=false;}
        return event.getPointerCount()>1||zoom>1.05f&&event.getActionMasked()==MotionEvent.ACTION_MOVE;
    }
    @Override public boolean onTouchEvent(MotionEvent event){View view=diagram();if(view==null)return false;
        if(event.getPointerCount()==1&&event.getActionMasked()==MotionEvent.ACTION_MOVE&&!pinch.isInProgress()){
            if(moving){view.setTranslationX(view.getTranslationX()+event.getX()-lastX);view.setTranslationY(view.getTranslationY()+event.getY()-lastY);apply();}moving=true;lastX=event.getX();lastY=event.getY();
        }else moving=false;return true;
    }
}
