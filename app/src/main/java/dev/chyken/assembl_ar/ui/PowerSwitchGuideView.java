package dev.chyken.assembl_ar.ui;
import android.content.Context;import android.util.AttributeSet;import android.graphics.*;import android.graphics.drawable.Drawable;import android.widget.ImageView;
/** Static retained mainboard photograph, accurately marked; no overlay on the camera feed. */
public final class PowerSwitchGuideView extends androidx.appcompat.widget.AppCompatImageView {
    private final Paint p=new Paint(3);
    public PowerSwitchGuideView(Context c){this(c,null);}public PowerSwitchGuideView(Context c,AttributeSet attrs){super(c,attrs);setScaleType(ImageView.ScaleType.FIT_CENTER);setContentDescription("Mainboard power switch: small black slider immediately left of the barrel power socket at the lower board edge. OFF to the left; ON to the right.");AssetImageLoader.loadBounded(this,"references/Phase1/P1_SCANNINGGUIDE/GUIDEFORMAINBOARDSCANNING.jpg",1600,new RectF(.40f,.74f,.77f,1f));}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);Drawable drawable=getDrawable();if(drawable==null)return;float[] xy={(.572f-.40f)/.37f*drawable.getIntrinsicWidth(),(.907f-.74f)/.26f*drawable.getIntrinsicHeight()};getImageMatrix().mapPoints(xy);float x=xy[0]+getPaddingLeft(),y=xy[1]+getPaddingTop();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(UiUtils.dp(getContext(),3));p.setColor(0xFF91DC47);canvas.drawCircle(x,y,UiUtils.dp(getContext(),24),p);p.setColor(Color.WHITE);p.setStrokeWidth(UiUtils.dp(getContext(),1));canvas.drawCircle(x,y,UiUtils.dp(getContext(),28),p);p.setStyle(Paint.Style.FILL);}
}
