package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/** Main-thread, vsync-paced drawing. No worker, timer or frame callback survives inactivity. */
abstract class LifecycleAnimatedView extends View implements Choreographer.FrameCallback,AutoCloseable {
    private boolean resumed,attached,aggregated=true,scheduled,closed;
    private long last;
    protected LifecycleAnimatedView(Context c,AttributeSet a){super(c,a);setWillNotDraw(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public final void resumeAnimation(){resumed=true;reconcile();}
    public final void pauseAnimation(){resumed=false;reconcile();}
    public final boolean isFrameScheduled(){return scheduled;}
    private boolean active(){return !closed&&resumed&&attached&&aggregated&&isShown()&&getWindowVisibility()==VISIBLE&&hasWindowFocus()&&BrandingAssets.motion();}
    private void reconcile(){
        if(active()){if(!scheduled){scheduled=true;Choreographer.getInstance().postFrameCallback(this);}}
        else{Choreographer.getInstance().removeFrameCallback(this);scheduled=false;last=0;}
    }
    @Override public final void doFrame(long time){scheduled=false;if(!active()){last=0;return;}float seconds=last==0?0:Math.min(.05f,(time-last)/1_000_000_000f);last=time;advance(seconds);invalidate();scheduled=true;Choreographer.getInstance().postFrameCallbackDelayed(this,32);}
    protected abstract void advance(float seconds);
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;reconcile();}
    @Override protected void onDetachedFromWindow(){attached=false;reconcile();super.onDetachedFromWindow();}
    @Override public void onVisibilityAggregated(boolean visible){super.onVisibilityAggregated(visible);aggregated=visible;reconcile();}
    @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);reconcile();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);reconcile();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);reconcile();}
    @Override public final void close(){closed=true;resumed=false;reconcile();}
}
