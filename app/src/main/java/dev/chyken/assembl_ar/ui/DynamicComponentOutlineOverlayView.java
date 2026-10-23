package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.*;
import dev.chyken.assembl_ar.vision.TargetBoundingTracker;

/** One non-intercepting camera overlay shared by assembly and the standalone tool.
 * Receives normalized preview coordinates, including CameraX fill-center cropping. */
public final class DynamicComponentOutlineOverlayView extends View {
    public static final int LOCKED=0xFF00FF7F,WRONG=0xFFFF3B30,SEARCH=0xFFA1A1A1;
    private final Paint stroke=new Paint(3),glow=new Paint(3),surface=new Paint(3),text=new Paint(3);
    private final Path path=new Path();private TargetBoundingTracker.Target target;
    private List<float[]> polygons=Collections.emptyList();private boolean active;private long delivered;
    public DynamicComponentOutlineOverlayView(Context c,AttributeSet a){super(c,a);setClickable(false);setFocusable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void setCameraActive(boolean value){if(active==value)return;active=value;if(!value){target=null;polygons=Collections.emptyList();}invalidate();}
    public void clear(){target=null;polygons=Collections.emptyList();invalidate();}
    public void show(TargetBoundingTracker.Target next,List<float[]> normalized){
        active=true;target=next;delivered=SystemClock.uptimeMillis();List<float[]> copy=new ArrayList<>();if(normalized!=null)for(float[] p:normalized)if(p!=null&&p.length>=8)copy.add(p.clone());polygons=copy;invalidate();
    }
    public boolean hasMeasuredOutline(){return target!=null&&!target.predicted&&!polygons.isEmpty()&&SystemClock.uptimeMillis()-delivered<300;}
    // Included in Android's activity view dump for diagnosing an actual camera surface.
    @Override public String toString(){return super.toString()+" outlineActive="+active+" outlineState="+(target==null?"SEARCH":target.state)+" regions="+polygons.size()+" ageMs="+(delivered==0?-1:SystemClock.uptimeMillis()-delivered);}
    @Override protected void onDraw(Canvas canvas){
        if(!active)return;long now=SystemClock.uptimeMillis();
        if(target==null||polygons.isEmpty()||now-delivered>300){drawSearch(canvas);if(target!=null){target=null;polygons=Collections.emptyList();}return;}
        int color=target.state==TargetBoundingTracker.State.WRONG?WRONG:target.state==TargetBoundingTracker.State.ACQUIRING?SEARCH:LOCKED;
        float minX=getWidth(),minY=getHeight(),area=0;
        for(float[] p:polygons){path.reset();double signed=0;for(int i=0;i<p.length;i+=2){float x=p[i]*getWidth(),y=p[i+1]*getHeight();if(i==0)path.moveTo(x,y);else path.lineTo(x,y);minX=Math.min(minX,x);minY=Math.min(minY,y);int n=(i+2)%p.length;signed+=x*p[n+1]*getHeight()-p[n]*getWidth()*y;}path.close();area=(float)Math.abs(signed/2);
            float width=dp(Math.max(2,Math.min(3.6,2+Math.sqrt(area/Math.max(1f,getWidth()*getHeight()))*2)));
            glow.setStyle(Paint.Style.STROKE);glow.setColor(color);glow.setAlpha(target.predicted?35:65);glow.setStrokeWidth(width*2.5f);canvas.drawPath(path,glow);
            stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(width);stroke.setColor(color);stroke.setAlpha(target.predicted?125:255);stroke.setPathEffect(target.predicted||target.state==TargetBoundingTracker.State.ACQUIRING?new DashPathEffect(new float[]{dp(7),dp(5)},0):null);canvas.drawPath(path,stroke);
        }
        String label=target.predicted?"TRACKING PREDICTED: "+target.name:target.state==TargetBoundingTracker.State.WRONG?"WRONG COMPONENT DETECTED: "+target.name:target.state==TargetBoundingTracker.State.ACQUIRING?"ACQUIRING: "+target.name:"TARGET LOCKED: "+target.name;
        text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));text.setTextSize(dp(12));text.setColor(color);
        float max=Math.max(dp(80),getWidth()-dp(24));label=android.text.TextUtils.ellipsize(label,new android.text.TextPaint(text),max-dp(16),android.text.TextUtils.TruncateAt.END).toString();
        float w=Math.min(max,text.measureText(label)+dp(16)),x=Math.max(dp(8),Math.min(getWidth()-w-dp(8),minX)),y=Math.max(dp(82),Math.min(getHeight()-dp(52),minY-dp(31)));
        surface.setColor(0xE01B2521);canvas.drawRoundRect(x,y,x+w,y+dp(28),dp(7),dp(7),surface);canvas.drawText(label,x+dp(8),y+dp(19),text);
        // If callbacks stop (pause/navigation/failure), an old success polygon expires.
        postInvalidateDelayed(100);
    }
    private void drawSearch(Canvas c){stroke.setStyle(Paint.Style.STROKE);stroke.setColor(SEARCH);stroke.setAlpha(170);stroke.setStrokeWidth(dp(1.5));stroke.setPathEffect(new DashPathEffect(new float[]{dp(8),dp(7)},0));float w=getWidth()*.58f,h=Math.min(getHeight()*.48f,w*.65f);c.drawRoundRect((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2,dp(10),dp(10),stroke);}
    private float dp(double n){return (float)n*getResources().getDisplayMetrics().density;}
}
