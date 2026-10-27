package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import dev.chyken.assembl_ar.R;

/** Retains all tester controls and telemetry, moving secondary tools off the phone camera. */
public final class PhonePartTesterLayout implements AutoCloseable {
    private final Context context;
    private final View root;
    private final LinearLayout panes,body,toolbar;
    private final FrameLayout camera,controlPane;
    private final View controls,selector,start,navigation;
    private final View previous,next;
    private final LinearLayout adjacent;
    private final ViewGroup.LayoutParams previousParams,nextParams;
    private final TextView selected;
    private final int selectedLines;
    private final DevDiagnosticsOverlayView metrics;
    private final ViewGroup metricsParent;
    private final ViewGroup.LayoutParams metricsParams;
    private final int metricsIndex,selectorIndex,startIndex;
    private final ViewGroup.LayoutParams selectorParams,startParams;
    private final LinearLayout.LayoutParams cameraParams,controlParams;
    private final int panesOrientation,toolbarHeight;
    private final LinearLayout dock,essential,actions;
    private final com.google.android.material.button.MaterialButton toggle;
    private final ViewTreeObserver.OnGlobalLayoutListener listener=this::layout;
    private boolean active,expanded,closed;
    private int navigationVisibility;

    public PhonePartTesterLayout(Context context, View root, View navigation, Bundle saved) {
        this.context=context;this.root=root;this.navigation=navigation;
        panes=root.findViewById(R.id.partTestPanes);camera=root.findViewById(R.id.partTestCameraPane);
        controls=root.findViewById(R.id.partTestControls);controlPane=(FrameLayout)controls.getParent();
        body=(LinearLayout)((ViewGroup)controls).getChildAt(0);
        selector=(View)root.findViewById(R.id.partTestSelectButton).getParent();start=root.findViewById(R.id.partTestStartButton);
        previous=root.findViewById(R.id.partTestPreviousButton);next=root.findViewById(R.id.partTestNextButton);selected=root.findViewById(R.id.partTestSelectButton);selectedLines=selected.getMaxLines();
        previousParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)previous.getLayoutParams());nextParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)next.getLayoutParams());
        selectorIndex=body.indexOfChild(selector);startIndex=body.indexOfChild(start);selectorParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)selector.getLayoutParams());startParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)start.getLayoutParams());
        metrics=root.findViewById(R.id.devDiagnosticsOverlay);metricsParent=(ViewGroup)metrics.getParent();metricsParams=metrics.getLayoutParams();metricsIndex=metricsParent.indexOfChild(metrics);
        cameraParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)camera.getLayoutParams());controlParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)controlPane.getLayoutParams());panesOrientation=panes.getOrientation();
        toolbar=(LinearLayout)root.findViewById(R.id.toolbarTitle).getParent();toolbarHeight=toolbar.getLayoutParams().height;
        dock=new LinearLayout(context);dock.setId(R.id.phoneTesterDock);dock.setOrientation(LinearLayout.VERTICAL);dock.setPadding(dp(8),0,dp(8),dp(4));dock.setBackgroundColor(context.getColor(R.color.canvas));
        essential=new LinearLayout(context);actions=new LinearLayout(context);actions.setGravity(Gravity.CENTER_VERTICAL);
        adjacent=new LinearLayout(context);adjacent.setGravity(Gravity.CENTER_HORIZONTAL);
        toggle=new com.google.android.material.button.MaterialButton(context);toggle.setId(R.id.phoneTesterTools);toggle.setTextSize(12);toggle.setAllCaps(false);toggle.setMinHeight(dp(48));
        toggle.setOnClickListener(v->{expanded=!expanded;layout();});
        actions.addView(toggle);dock.addView(essential,new LinearLayout.LayoutParams(-1,-2));
        expanded=saved!=null&&saved.getBoolean("phone_tester_tools");
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);layout();
    }
    public void saveState(Bundle state){state.putBoolean("phone_tester_tools",expanded);}
    public void refresh(){layout();}
    public void focusCamera(){if(active&&expanded){expanded=false;layout();}}
    private void activate(){
        if(active)return;active=true;
        if(navigation!=null){navigationVisibility=navigation.getVisibility();navigation.setVisibility(View.GONE);}
        body.removeView(selector);body.removeView(start);controlPane.removeView(controls);
        ((ViewGroup)selector).removeView(previous);((ViewGroup)selector).removeView(next);adjacent.addView(previous);adjacent.addView(next);body.addView(adjacent,0,new LinearLayout.LayoutParams(-1,-2));selected.setMaxLines(2);
        metricsParent.removeView(metrics);metrics.setInlinePresentation(true);body.addView(metrics,0,new LinearLayout.LayoutParams(-1,-2));
        essential.addView(selector,0);actions.addView(start,0);
        dock.addView(controls,new LinearLayout.LayoutParams(-1,0));
        controlPane.addView(dock,new FrameLayout.LayoutParams(-1,-2));
        panes.setOrientation(LinearLayout.VERTICAL);
        camera.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));controlPane.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));
        ViewGroup.LayoutParams p=toolbar.getLayoutParams();p.height=dp(52);toolbar.setLayoutParams(p);
    }
    private void layout(){
        if(closed||root.getWidth()==0||root.getHeight()==0)return;
        View window=root.getRootView();
        if(!ScanViewportPolicy.compact(window.getWidth(),window.getHeight(),context.getResources().getDisplayMetrics().density)){restore();return;}
        activate();boolean landscape=root.getWidth()>root.getHeight();
        if(landscape){
            essential.setOrientation(LinearLayout.HORIZONTAL);
            if(actions.getParent()!=essential){if(actions.getParent()!=null)((ViewGroup)actions.getParent()).removeView(actions);essential.addView(actions);}
            linear(selector,0,-2,1);linear(actions,0,-2,1);
        }else{
            essential.setOrientation(LinearLayout.VERTICAL);
            if(actions.getParent()!=essential){if(actions.getParent()!=null)((ViewGroup)actions.getParent()).removeView(actions);essential.addView(actions);}
            linear(selector,-1,-2,0);linear(actions,-1,-2,0);
        }
        linear(start,0,-2,1);linear(toggle,-2,-2,0);
        int drawerHeight=ScanViewportPolicy.phoneDrawerHeight(root.getHeight(),context.getResources().getDisplayMetrics().density);
        int available=panes.getHeight()-essential.getMeasuredHeight()-dock.getPaddingBottom();
        drawerHeight=Math.min(drawerHeight,Math.max(0,Math.round(available*.40f)));
        linear(controls,-1,expanded?drawerHeight:0,0);controls.setVisibility(expanded?View.VISIBLE:View.GONE);
        String label=context.getString(expanded?R.string.phone_tester_close_tools:R.string.phone_tester_tools);if(!label.contentEquals(toggle.getText()))toggle.setText(label);
        toggle.setSelected(expanded);
    }
    private void linear(View view,int w,int h,float weight){
        ViewGroup.LayoutParams raw=view.getLayoutParams();LinearLayout.LayoutParams p=raw instanceof LinearLayout.LayoutParams?(LinearLayout.LayoutParams)raw:new LinearLayout.LayoutParams(w,h,weight);
        if(raw instanceof LinearLayout.LayoutParams&&p.width==w&&p.height==h&&p.weight==weight)return;
        p.width=w;p.height=h;p.weight=weight;view.setLayoutParams(p);
    }
    private void restore(){
        if(!active)return;active=false;
        essential.removeView(selector);actions.removeView(start);metrics.setInlinePresentation(false);body.removeView(metrics);
        body.removeView(adjacent);adjacent.removeView(previous);adjacent.removeView(next);((ViewGroup)selector).addView(previous,0,previousParams);((ViewGroup)selector).addView(next,nextParams);selected.setMaxLines(selectedLines);
        metricsParent.addView(metrics,Math.min(metricsIndex,metricsParent.getChildCount()),metricsParams);
        body.addView(selector,Math.min(selectorIndex,body.getChildCount()),selectorParams);body.addView(start,Math.min(startIndex,body.getChildCount()),startParams);
        dock.removeView(controls);controlPane.removeView(dock);controlPane.addView(controls,new FrameLayout.LayoutParams(-1,-1));controls.setVisibility(View.VISIBLE);
        panes.setOrientation(panesOrientation);camera.setLayoutParams(new LinearLayout.LayoutParams(cameraParams));controlPane.setLayoutParams(new LinearLayout.LayoutParams(controlParams));
        ViewGroup.LayoutParams p=toolbar.getLayoutParams();p.height=toolbarHeight;toolbar.setLayoutParams(p);
        if(navigation!=null)navigation.setVisibility(navigationVisibility);
    }
    private int dp(int value){return UiUtils.dp(context,value);}
    @Override public void close(){closed=true;root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);restore();}
}
