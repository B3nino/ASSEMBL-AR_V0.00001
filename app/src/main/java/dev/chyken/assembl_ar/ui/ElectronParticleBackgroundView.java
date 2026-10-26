package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import java.util.Random;

/** Forty bounded vector nodes; allocation-free drawing on Android's hardware Canvas. */
public final class ElectronParticleBackgroundView extends LifecycleAnimatedView {
    private static final int COUNT=40;
    private final float[] x=new float[COUNT],y=new float[COUNT],vx=new float[COUNT],vy=new float[COUNT];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public ElectronParticleBackgroundView(Context c){this(c,null);}
    public ElectronParticleBackgroundView(Context c,AttributeSet a){super(c,a);Random r=new Random(1040);for(int i=0;i<COUNT;i++){x[i]=r.nextFloat();y[i]=r.nextFloat();vx[i]=(r.nextFloat()-.5f)*.018f;vy[i]=(r.nextFloat()-.5f)*.018f;}}
    @Override protected void advance(float dt){for(int i=0;i<COUNT;i++){x[i]+=vx[i]*dt;y[i]+=vy[i]*dt;if(x[i]<0||x[i]>1){vx[i]=-vx[i];x[i]=Math.max(0,Math.min(1,x[i]));}if(y[i]<0||y[i]>1){vy[i]=-vy[i];y[i]=Math.max(0,Math.min(1,y[i]));}}}
    @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(getContext().getColor(dev.chyken.assembl_ar.R.color.canvas));float w=getWidth(),h=getHeight(),d=getResources().getDisplayMetrics().density;paint.setStrokeWidth(d*.6f);
        for(int i=0;i<COUNT;i++)for(int j=i+1;j<COUNT;j++){float dx=(x[i]-x[j])*w,dy=(y[i]-y[j])*h,limit=Math.min(w,h)*.28f,dist=dx*dx+dy*dy;if(dist<limit*limit){paint.setColor(i%2==0?0xFF3F7D14:0xFF257E86);paint.setAlpha((int)(40*(1-dist/(limit*limit))));c.drawLine(x[i]*w,y[i]*h,x[j]*w,y[j]*h,paint);}}
        for(int i=0;i<COUNT;i++){paint.setColor(i%3==0?0xFF257E86:0xFF3F7D14);paint.setAlpha(125);c.drawCircle(x[i]*w,y[i]*h,(i%4==0?2:1.2f)*d,paint);}
    }
}
