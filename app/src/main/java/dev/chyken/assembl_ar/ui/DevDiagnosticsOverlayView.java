package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Non-intercepting diagnostic readout. A window-level mirror also clears on-top GL surfaces. */
public final class DevDiagnosticsOverlayView extends androidx.appcompat.widget.AppCompatTextView {
    private PopupWindow window;
    private TextView mirror;
    private boolean attached;
    private final Runnable windowSync=this::syncWindow;
    private final int[] location=new int[2];
    private int windowX,windowY,windowWidth,windowHeight;
    public DevDiagnosticsOverlayView(Context c,AttributeSet a){
        super(c,a);style(this);setElevation(999*c.getResources().getDisplayMetrics().density);
        // The high Z is for ordering only. A 999dp shadow can allocate a surface
        // several times larger than the tablet screen, including in the popup.
        setOutlineProvider(null);setStateListAnimator(null);
        setClickable(false);setFocusable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    private static void style(TextView t){t.setTextColor(0xFFE6F2E2);t.setTextSize(11);t.setTypeface(android.graphics.Typeface.MONOSPACE);t.setBackgroundColor(0xE51A211B);t.setPadding(12,8,12,8);}
    private void scheduleWindowSync(){if(attached){removeCallbacks(windowSync);post(windowSync);}}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;scheduleWindowSync();}
    @Override protected void onDetachedFromWindow(){attached=false;removeCallbacks(windowSync);dismiss();super.onDetachedFromWindow();}
    @Override protected void onVisibilityChanged(View changed,int visibility){super.onVisibilityChanged(changed,visibility);scheduleWindowSync();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus){removeCallbacks(windowSync);dismiss();}else scheduleWindowSync();}
    @Override protected void onTextChanged(CharSequence text,int start,int before,int count){super.onTextChanged(text,start,before,count);scheduleWindowSync();}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);scheduleWindowSync();}
    private void syncWindow(){
        if(!attached||!isShown()||!hasWindowFocus()||getWindowToken()==null||getWidth()==0||getHeight()==0){dismiss();return;}
        if(window==null){mirror=new androidx.appcompat.widget.AppCompatTextView(getContext());style(mirror);mirror.setMaxWidth(getMaxWidth());mirror.setMaxLines(getMaxLines());mirror.setEllipsize(getEllipsize());mirror.setOutlineProvider(null);window=new PopupWindow(mirror,getWidth(),getHeight(),false);window.setTouchable(false);window.setFocusable(false);window.setOutsideTouchable(false);window.setElevation(0);window.setAnimationStyle(0);}
        if(!android.text.TextUtils.equals(mirror.getText(),getText()))mirror.setText(getText());getLocationInWindow(location);
        int x=location[0],y=location[1],w=getWidth(),h=getHeight();
        if(!window.isShowing()){window.showAtLocation(getRootView(),Gravity.TOP|Gravity.LEFT,x,y);}
        else if(x!=windowX||y!=windowY||w!=windowWidth||h!=windowHeight){window.update(x,y,w,h);}
        windowX=x;windowY=y;windowWidth=w;windowHeight=h;
    }
    private void dismiss(){if(window!=null){window.dismiss();window=null;mirror=null;}}
}
