package dev.chyken.assembl_ar.ui;

import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import dev.chyken.assembl_ar.R;

/** Draws the supplied logo into growing stroke masks; the logo itself never slides or scales in. */
public final class LogoFormationView extends View {
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint mask=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path segment=new Path();
    private final PathMeasure[] traces=new PathMeasure[LogoFormationSpec.STROKES.length];
    private final PorterDuffXfermode sourceIn=new PorterDuffXfermode(PorterDuff.Mode.SRC_IN);
    private final ValueAnimator animator=ValueAnimator.ofFloat(0,1);
    private final Drawable fallback;
    private Bitmap artwork;
    private boolean artworkReady,requested,finished,paused,cancelled,delivered;
    private float progress;
    private Runnable complete;
    private final Runnable deliver=()->{if(!cancelled&&!delivered){delivered=true;if(complete!=null)complete.run();}};
    public LogoFormationView(Context context){this(context,null);}
    public LogoFormationView(Context context,AttributeSet attrs){
        super(context,attrs);setContentDescription("ASSEMBL-AR");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        fallback=androidx.core.content.ContextCompat.getDrawable(context,R.drawable.assembl_ar_brand_mark);
        mask.setColor(Color.WHITE);mask.setStrokeCap(Paint.Cap.ROUND);mask.setStrokeJoin(Paint.Join.ROUND);
        for(int i=0;i<traces.length;i++){
            float[] points=LogoFormationSpec.STROKES[i].points;Path path=new Path();path.moveTo(points[0],points[1]);
            for(int j=2;j<points.length;j+=2)path.lineTo(points[j],points[j+1]);traces[i]=new PathMeasure(path,false);
        }
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(value->{progress=(float)value.getAnimatedValue();invalidate();});
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){if(!cancelled){progress=1;finished=true;invalidate();postOnAnimation(deliver);}}});
        BrandingAssets.logo(this);
    }
    void setArtwork(Bitmap bitmap){if(cancelled)return;artwork=bitmap;artworkReady=true;invalidate();beginIfReady();}
    public void startFormation(long duration,Runnable onComplete){
        if(cancelled)return;complete=onComplete;requested=true;animator.setDuration(duration);beginIfReady();
    }
    private void beginIfReady(){
        if(cancelled||paused||!requested||!artworkReady||!isAttachedToWindow()||getWidth()==0||getWindowVisibility()!=VISIBLE)return;
        if(finished){postOnAnimation(deliver);return;}
        if(!BrandingAssets.motion()){progress=1;finished=true;invalidate();postOnAnimation(deliver);}
        else if(animator.isPaused())animator.resume();else if(!animator.isStarted())animator.start();
    }
    public void pauseFormation(){paused=true;if(animator.isStarted())animator.pause();}
    public void resumeFormation(){paused=false;beginIfReady();}
    public void cancelFormation(){cancelled=true;removeCallbacks(deliver);animator.cancel();complete=null;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();beginIfReady();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);beginIfReady();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(animator==null)return;if(visibility==VISIBLE)beginIfReady();else if(animator.isStarted())animator.pause();}
    @Override protected void onDetachedFromWindow(){if(animator.isStarted())animator.pause();removeCallbacks(deliver);super.onDetachedFromWindow();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(!artworkReady)return;
        float scale=Math.min(getWidth()/(float)LogoFormationSpec.WIDTH,getHeight()/(float)LogoFormationSpec.HEIGHT);
        int saved=canvas.save();canvas.translate((getWidth()-LogoFormationSpec.WIDTH*scale)/2,(getHeight()-LogoFormationSpec.HEIGHT*scale)/2);canvas.scale(scale,scale);
        if(artwork==null){if(fallback!=null){fallback.setBounds(180,180,1044,1044);fallback.setAlpha(Math.round(255*progress));fallback.draw(canvas);}canvas.restoreToCount(saved);return;}
        RectF bounds=new RectF(0,0,LogoFormationSpec.WIDTH,LogoFormationSpec.HEIGHT);
        if(progress>=1){ink.setXfermode(null);canvas.drawBitmap(artwork,null,bounds,ink);}
        else{
            int layer=canvas.saveLayer(bounds,null);
            mask.setStyle(Paint.Style.STROKE);
            for(int i=0;i<traces.length;i++){
                LogoFormationSpec.Stroke stroke=LogoFormationSpec.STROKES[i];float amount=stroke.progress(progress);if(amount<=0)continue;
                segment.reset();traces[i].getSegment(0,traces[i].getLength()*amount,segment,true);mask.setStrokeWidth(stroke.width);canvas.drawPath(segment,mask);
            }
            mask.setStyle(Paint.Style.FILL);
            float wordmark=LogoFormationSpec.wordmark(progress),tagline=LogoFormationSpec.tagline(progress);
            if(wordmark>0)canvas.drawRect(120,825,120+990*wordmark,955,mask);
            if(tagline>0)canvas.drawRect(125,974,125+980*tagline,1030,mask);
            ink.setXfermode(sourceIn);canvas.drawBitmap(artwork,null,bounds,ink);ink.setXfermode(null);canvas.restoreToCount(layer);
        }
        canvas.restoreToCount(saved);
    }
}

