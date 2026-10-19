package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.render.ComponentModelCatalog;
import dev.chyken.assembl_ar.render.HomeRobotSurfaceView;

/** Small, independently rendered component viewer. Gestures never enter the camera executor. */
public final class ComponentPreviewWindow extends FrameLayout {
    private final FrameLayout viewport;
    private final TextView title, hint;
    private final TextView resizeHandle;
    private final android.widget.ImageButton contentAction;
    private final Outline outline;
    private HomeRobotSurfaceView surface;
    private final android.widget.LinearLayout pairLabels;
    private final TextView screwLabel;
    private String componentId;
    private boolean resumed;
    @Nullable private ResizeListener resizeListener;
    private float resizeStartX, resizeStartY;
    private int resizeStartWidth, resizeStartHeight;
    public interface ResizeListener { void onResize(int width, int height); }
    private final Runnable fadeHint = this::hideHint;
    private void hideHint() { hint.animate().alpha(0f).setDuration(300).start(); }

    public ComponentPreviewWindow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(0xD0F0F0F0);
        int pad=UiUtils.dp(context,4);
        setPadding(pad,pad,pad,pad);
        viewport=new FrameLayout(context);
        LayoutParams modelParams=new LayoutParams(-1,-1);
        modelParams.topMargin=UiUtils.dp(context,58);
        modelParams.bottomMargin=UiUtils.dp(context,38);
        addView(viewport,modelParams);
        pairLabels=new android.widget.LinearLayout(context);
        screwLabel=new TextView(context);TextView nutLabel=new TextView(context);nutLabel.setText("M3 nut");
        for(TextView label:new TextView[]{screwLabel,nutLabel}){label.setTextSize(12);label.setTextColor(0xFF26332D);label.setGravity(Gravity.CENTER);pairLabels.addView(label,new android.widget.LinearLayout.LayoutParams(0,-1,1));}
        LayoutParams labelsParams=new LayoutParams(-1,UiUtils.dp(context,26),Gravity.BOTTOM);labelsParams.bottomMargin=UiUtils.dp(context,36);addView(pairLabels,labelsParams);pairLabels.setVisibility(GONE);
        title=new TextView(context);
        title.setTextColor(0xFF26332D); title.setTextSize(13); title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL); title.setPadding(UiUtils.dp(context,10),0,UiUtils.dp(context,44),0);
        addView(title,new LayoutParams(-1,UiUtils.dp(context,32),Gravity.TOP));
        hint=new TextView(context);
        hint.setText(R.string.preview_rotate_hint); hint.setTextColor(0xFF47534C); hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams hintParams=new LayoutParams(-1,UiUtils.dp(context,22),Gravity.TOP);
        hintParams.topMargin=UiUtils.dp(context,32);
        hintParams.leftMargin=UiUtils.dp(context,10);
        addView(hint,hintParams);
        TextView resizeCaption=new TextView(context);
        resizeCaption.setText(R.string.preview_resize_visible); resizeCaption.setTextSize(11);
        resizeCaption.setTextColor(0xFF47534C); resizeCaption.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        LayoutParams captionParams=new LayoutParams(-1,UiUtils.dp(context,38),Gravity.BOTTOM);
        captionParams.leftMargin=UiUtils.dp(context,48);
        captionParams.rightMargin=UiUtils.dp(context,10);
        addView(resizeCaption,captionParams);
        outline=new Outline(context); outline.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(outline,new LayoutParams(-1,-1));
        resizeHandle=new TextView(context);
        resizeHandle.setText("↙"); resizeHandle.setTextSize(23); resizeHandle.setTextColor(Color.WHITE);
        resizeHandle.setGravity(Gravity.CENTER); resizeHandle.setBackgroundColor(0xCC162020);
        resizeHandle.setContentDescription(context.getString(R.string.preview_resize_hint));
        resizeHandle.setClickable(true);
        resizeHandle.setFocusable(true);
        resizeHandle.setOnTouchListener((view,event) -> {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    resizeStartX=event.getRawX(); resizeStartY=event.getRawY();
                    resizeStartWidth=getWidth(); resizeStartHeight=getHeight();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(resizeListener!=null) resizeListener.onResize(
                            resizeStartWidth+Math.round(resizeStartX-event.getRawX()),
                            resizeStartHeight+Math.round(event.getRawY()-resizeStartY));
                    return true;
                case MotionEvent.ACTION_UP:
                    view.performClick(); return true;
                case MotionEvent.ACTION_CANCEL: return true;
                default: return false;
            }
        });
        addView(resizeHandle,new LayoutParams(UiUtils.dp(context,44),UiUtils.dp(context,44),Gravity.LEFT|Gravity.BOTTOM));
        contentAction=new android.widget.ImageButton(context);contentAction.setImageResource(R.drawable.ic_expand_diagram);contentAction.setBackgroundColor(Color.TRANSPARENT);contentAction.setPadding(UiUtils.dp(context,12),UiUtils.dp(context,12),UiUtils.dp(context,12),UiUtils.dp(context,12));
        addView(contentAction,new LayoutParams(UiUtils.dp(context,48),UiUtils.dp(context,48),Gravity.RIGHT|Gravity.TOP));contentAction.setVisibility(GONE);
    }

    public void setResizeListener(@Nullable ResizeListener listener) { resizeListener=listener; }
    public void showInteractionHint(){hint.animate().cancel();hint.setAlpha(1);removeCallbacks(fadeHint);postDelayed(fadeHint,3_000);}
    public void showContent(View content,String name){
        contentAction.setVisibility(GONE);contentAction.setOnClickListener(null);
        pairLabels.setVisibility(GONE);
        if(surface!=null)surface.onPause();surface=null;componentId=null;
        viewport.removeAllViews();viewport.addView(content,new LayoutParams(-1,-1));
        title.setText(name);hint.animate().cancel();hint.setAlpha(0);removeCallbacks(fadeHint);
        setVisibility(VISIBLE);outline.state=ArHudView.HudState.SEARCHING;outline.invalidate();
    }

    public void showComponent(String id, String name) {
        contentAction.setVisibility(GONE);contentAction.setOnClickListener(null);
        pairLabels.setVisibility(GONE);
        if (id == null || !ComponentModelCatalog.hasModel(id)) { setVisibility(GONE); return; }
        setVisibility(VISIBLE); title.setText(getContext().getString(R.string.preview_model_title,name));
        if (id.equals(componentId)) return;
        componentId=id;
        if (surface!=null) surface.onPause();
        viewport.removeAllViews();
        surface=HomeRobotSurfaceView.forComponent(getContext(),id);
        surface.setZOrderMediaOverlay(true);
        surface.setContentDescription(getContext().getString(R.string.model_accessibility,name));
        viewport.addView(surface,new LayoutParams(-1,-1));
        if (resumed) surface.onResume();
        outline.state=ArHudView.HudState.SEARCHING;
        showInteractionHint();
    }
    public void showFasteners(boolean shortScrew){
        showComponent(shortScrew?ComponentModelCatalog.SHORT_FASTENERS:ComponentModelCatalog.LONG_FASTENERS,"Fasteners · Touch to rotate");
        screwLabel.setText(shortScrew?"10mm screw":"30mm screw");pairLabels.setVisibility(VISIBLE);
    }
    public void setContentAction(String label,Runnable action){contentAction.setContentDescription(label);contentAction.setOnClickListener(v->action.run());contentAction.setVisibility(VISIBLE);}

    public void onHudState(ArHudView.HudState state) {
        if (outline.state==state) return;
        outline.state=state; outline.invalidate();
    }

    public void resume() { resumed=true; if(surface!=null) surface.onResume(); }
    public void pause() { resumed=false; if(surface!=null) surface.onPause(); }
    public void dispose() { removeCallbacks(fadeHint); hint.animate().cancel(); pause(); viewport.removeAllViews(); surface=null; }

    private static final class Outline extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ArHudView.HudState state=ArHudView.HudState.SEARCHING;
        Outline(Context context) { super(context); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(UiUtils.dp(context,3)); }
        @Override protected void onDraw(Canvas canvas) {
            boolean warning=state==ArHudView.HudState.MISMATCH || state==ArHudView.HudState.WRONG_ORIENTATION
                    || state==ArHudView.HudState.POLARITY_ERROR;
            paint.setColor(warning ? 0xFFFF5252 : state==ArHudView.HudState.MATCHED ? 0xFF47E597 : 0xFF72B6C2);
            paint.setAlpha(warning ? (int)(145+110*Math.sin(android.os.SystemClock.uptimeMillis()/150.0)) : 255);
            canvas.drawRoundRect(2,2,getWidth()-2,getHeight()-2,12,12,paint);
            if(warning && isShown()) postInvalidateOnAnimation();
        }
    }
}
