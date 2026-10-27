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
    private final TextView resizeCaption;
    private final android.widget.ImageButton contentAction;
    private final com.google.android.material.button.MaterialButton skipPreview;
    private final Outline outline;
    private HomeRobotSurfaceView surface;
    private final android.widget.LinearLayout pairLabels;
    private final TextView screwLabel,nutLabel,spacerLabel;
    private android.widget.PopupMenu modelChoices;
    private String componentId;
    private boolean resumed;
    private boolean presentationSuppressed;
    private boolean compactControls;
    public void setCompactControls(boolean value){if(compactControls==value)return;compactControls=value;setLegendMode(outline.legend);}
    private int requestedVisibility=VISIBLE;
    /** Phone layout may hide this aid without losing the flow's requested content state. */
    public void setPresentationSuppressed(boolean value){
        if(presentationSuppressed==value)return;presentationSuppressed=value;
        super.setVisibility(value?GONE:requestedVisibility);
        if(surface!=null){if(resumed&&!value&&requestedVisibility==VISIBLE)surface.onResume();else surface.onPause();}
    }
    @Override public void setVisibility(int value){requestedVisibility=value;super.setVisibility(presentationSuppressed?GONE:value);}
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
        screwLabel=new TextView(context);nutLabel=new TextView(context);nutLabel.setText("M3 nut");spacerLabel=new TextView(context);spacerLabel.setText("Nylon spacer");
        for(TextView label:new TextView[]{screwLabel,nutLabel,spacerLabel}){label.setTextSize(12);label.setTextColor(0xFF26332D);label.setGravity(Gravity.CENTER);pairLabels.addView(label,new android.widget.LinearLayout.LayoutParams(0,-1,1));}spacerLabel.setVisibility(GONE);
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
        resizeCaption=new TextView(context);
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
        skipPreview=new com.google.android.material.button.MaterialButton(context);skipPreview.setText("Skip preview →");skipPreview.setTextSize(12);skipPreview.setMinHeight(UiUtils.dp(context,48));skipPreview.setTextColor(0xFFFFFFFF);skipPreview.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2E6812));
        LayoutParams skipParams=new LayoutParams(-2,UiUtils.dp(context,48),Gravity.TOP|Gravity.END);skipParams.topMargin=UiUtils.dp(context,31);skipParams.rightMargin=UiUtils.dp(context,6);addView(skipPreview,skipParams);skipPreview.setVisibility(GONE);
    }

    public void setResizeListener(@Nullable ResizeListener listener) { resizeListener=listener; }
    public void showInteractionHint(){hint.animate().cancel();hint.setAlpha(1);removeCallbacks(fadeHint);postDelayed(fadeHint,3_000);}
    public void setLegendMode(boolean value){
        outline.legend=value;outline.invalidate();
        resizeHandle.setVisibility(value||compactControls?GONE:VISIBLE);resizeCaption.setVisibility(value||compactControls?GONE:VISIBLE);hint.setVisibility(value?GONE:VISIBLE);
        LayoutParams p=(LayoutParams)viewport.getLayoutParams();p.topMargin=UiUtils.dp(getContext(),value?36:58);p.bottomMargin=UiUtils.dp(getContext(),value||compactControls?6:38);viewport.setLayoutParams(p);
    }
    public void showContent(View content,String name){
        setSkipPreviewAction(null);
        hideModelChoices();setLegendMode(false);
        contentAction.setVisibility(GONE);contentAction.setOnClickListener(null);
        pairLabels.setVisibility(GONE);
        if(surface!=null)surface.onPause();surface=null;componentId=null;
        viewport.removeAllViews();viewport.addView(content,new LayoutParams(-1,-1));
        title.setText(name);hint.animate().cancel();hint.setAlpha(0);removeCallbacks(fadeHint);
        setVisibility(VISIBLE);outline.state=ArHudView.HudState.SEARCHING;outline.invalidate();
    }

    public void showComponent(String id, String name) {
        setSkipPreviewAction(null);
        hideModelChoices();setLegendMode(false);
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
        if (resumed&&!presentationSuppressed) surface.onResume();
        outline.state=ArHudView.HudState.SEARCHING;
        showInteractionHint();
    }
    private void hideModelChoices(){
        if(modelChoices!=null){modelChoices.dismiss();modelChoices=null;}
        title.setOnClickListener(null);title.setFocusable(false);title.setContentDescription(null);
    }
    /** Select one rotatable model without taking height away from a small preview window. */
    public void showModelChoices(String[] ids,String[] names,int selected,java.util.function.Consumer<String> speak){
        if(ids.length!=names.length||ids.length==0||selected<0||selected>=ids.length)throw new IllegalArgumentException();
        showComponent(ids[selected],names[selected]);
        title.setText(names[selected]+" ▾");title.setFocusable(true);
        title.setContentDescription(names[selected]+". Select component or fastener preview.");
        modelChoices=new android.widget.PopupMenu(getContext(),title);
        for(int i=0;i<ids.length;i++)modelChoices.getMenu().add(0,i,i,names[i]).setCheckable(true).setChecked(i==selected);
        modelChoices.setOnMenuItemClickListener(item->{
            int index=item.getItemId();showModelChoices(ids,names,index,speak);
            if(speak!=null)speak.accept(names[index]+". Touch and drag to rotate.");return true;
        });
        title.setOnClickListener(v->{if(modelChoices!=null)modelChoices.show();});
    }
    public void showFasteners(boolean shortScrew){
        showComponent(shortScrew?ComponentModelCatalog.SHORT_FASTENERS:ComponentModelCatalog.LONG_FASTENERS,"Fasteners · Touch to rotate");
        screwLabel.setText(shortScrew?"10mm screw":"30mm screw");spacerLabel.setVisibility(GONE);pairLabels.setVisibility(VISIBLE);
    }
    public void showPhase7Hardware(boolean caster){
        showComponent(caster?ComponentModelCatalog.CASTER_FASTENERS:ComponentModelCatalog.TRACKER_FASTENERS,caster?"Caster mounting hardware":"Line tracker mounting hardware");
        screwLabel.setText(caster?"20mm screw":"10mm screw");nutLabel.setText("M3 nut");
        spacerLabel.setVisibility(caster?GONE:VISIBLE);pairLabels.setVisibility(VISIBLE);
    }
    public void setContentAction(String label,Runnable action){contentAction.setContentDescription(label);contentAction.setOnClickListener(v->action.run());contentAction.setVisibility(VISIBLE);}
    public void setSkipPreviewAction(@Nullable Runnable action){skipPreview.setVisibility(action==null?GONE:VISIBLE);skipPreview.setEnabled(action!=null);skipPreview.setOnClickListener(action==null?null:v->action.run());if(action!=null)skipPreview.bringToFront();}

    public void onHudState(ArHudView.HudState state) {
        if (outline.state==state) return;
        outline.state=state; outline.invalidate();
    }

    public void resume() { resumed=true; if(surface!=null&&!presentationSuppressed) surface.onResume(); }
    public void pause() { resumed=false; if(surface!=null) surface.onPause(); }
    public void dispose() { hideModelChoices(); removeCallbacks(fadeHint); hint.animate().cancel(); pause(); viewport.removeAllViews(); surface=null; }

    private static final class Outline extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ArHudView.HudState state=ArHudView.HudState.SEARCHING;
        boolean legend;
        Outline(Context context) { super(context); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(UiUtils.dp(context,3)); }
        @Override protected void onDraw(Canvas canvas) {
            boolean warning=state==ArHudView.HudState.MISMATCH || state==ArHudView.HudState.WRONG_ORIENTATION
                    || state==ArHudView.HudState.POLARITY_ERROR;
            paint.setStrokeWidth(UiUtils.dp(getContext(),legend?1:3));
            paint.setColor(legend?0xFFBACDBC:warning ? 0xFFFF5252 : state==ArHudView.HudState.MATCHED ? 0xFF47E597 : 0xFF72B6C2);
            paint.setAlpha(warning ? (int)(145+110*Math.sin(android.os.SystemClock.uptimeMillis()/150.0)) : 255);
            canvas.drawRoundRect(2,2,getWidth()-2,getHeight()-2,12,12,paint);
            if(warning && isShown()) postInvalidateOnAnimation();
        }
    }
}
