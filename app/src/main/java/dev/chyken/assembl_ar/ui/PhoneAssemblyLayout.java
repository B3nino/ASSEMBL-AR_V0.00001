package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import dev.chyken.assembl_ar.R;
import java.util.*;

/** Phone-only presentation adapter. Existing workflow buttons remain their original instances. */
public final class PhoneAssemblyLayout implements AutoCloseable {
    private final Context context;
    private final FrameLayout root;
    private final LinearLayout toolbar, hidden;
    private final TextView title, steps, more, bannerText;
    private final View banner, legacy;
    private final CompactGuideCard card;
    private final PhaseOneGuideView phaseOne;
    private final ComponentPreviewWindow preview;
    private final TargetAlignmentBoxView reference;
    private final Runnable changed;
    private final List<View> tools = new ArrayList<>();
    private final List<Integer> positions = new ArrayList<>();
    private ViewGroup footerParent;
    private int rescanPosition,returnPosition;
    private ViewGroup.LayoutParams rescanParams,returnParams;
    private final Map<View,FrameLayout.LayoutParams> originalGeometry=new LinkedHashMap<>();
    private final Map<View,float[]> originalTranslations=new LinkedHashMap<>();
    private final CharSequence originalTitle;
    private final float originalTextSize;
    private int originalBottomPadding;
    private int originalCardMaximum,originalPhaseOneMaximum,originalBannerLines,originalBannerWidth;
    private float originalBannerSize;
    private boolean active, cameraFocused, bodyOpen, referenceOpen, previewOpen;
    private String scope;
    private PopupMenu menu;

    public PhoneAssemblyLayout(Context context, FrameLayout root, Bundle state, Runnable changed) {
        this.context=context;this.root=root;this.changed=changed;
        toolbar=root.findViewById(R.id.cameraToolbar);title=root.findViewById(R.id.assemblyToolbarTitle);
        originalTitle=title.getText();originalTextSize=title.getTextSize();
        banner=root.findViewById(R.id.recognitionBanner);bannerText=root.findViewById(R.id.recognitionText);
        legacy=root.findViewById(R.id.instructionPanel);card=root.findViewById(R.id.phaseTwoGuide);phaseOne=root.findViewById(R.id.phaseOneGuide);
        preview=root.findViewById(R.id.componentPreviewWindow);reference=root.findViewById(R.id.targetAlignmentBox);
        hidden=new LinearLayout(context);hidden.setVisibility(View.GONE);
        for(int id:new int[]{R.id.btnAppSettings,R.id.btnTtsMuteToggle,R.id.livePill,R.id.wiringReviewButton,R.id.scanHelpButton,R.id.devButton}){
            View view=root.findViewById(id);if(view!=null){tools.add(view);positions.add(toolbar.indexOfChild(view));}
        }
        steps=control(R.id.phoneScanSteps,context.getString(R.string.phone_scan_steps));
        more=control(R.id.phoneScanTools,"⋮");more.setContentDescription(context.getString(R.string.phone_scan_tools));
        steps.setOnClickListener(v->{bodyOpen=!bodyOpen;changed.run();});
        more.setOnClickListener(v->showTools());
        if(state!=null){scope=state.getString("phone_scan_scope");bodyOpen=state.getBoolean("phone_scan_body");referenceOpen=state.getBoolean("phone_scan_reference");previewOpen=state.getBoolean("phone_scan_preview");cameraFocused=state.getBoolean("phone_scan_focused");}
    }
    private TextView control(int id,String text){TextView view=new TextView(context);view.setId(id);view.setText(text);view.setTextColor(0xFFE4EFDE);view.setTextSize(14);view.setGravity(Gravity.CENTER);view.setPadding(dp(8),0,dp(8),0);view.setMinHeight(dp(48));view.setMinWidth(dp(48));view.setBackgroundResource(android.R.drawable.list_selector_background);view.setFocusable(true);return view;}

    public void setScanContext(boolean focused,String nextScope){
        if(cameraFocused==focused&&Objects.equals(scope,nextScope))return;
        cameraFocused=focused;scope=nextScope;bodyOpen=false;referenceOpen=false;previewOpen=false;
        if(active)changed.run();
    }
    public void saveState(Bundle state){state.putString("phone_scan_scope",scope);state.putBoolean("phone_scan_body",bodyOpen);state.putBoolean("phone_scan_reference",referenceOpen);state.putBoolean("phone_scan_preview",previewOpen);state.putBoolean("phone_scan_focused",cameraFocused);}

    private void activate(){
        if(active)return;active=true;originalBottomPadding=toolbar.getPaddingBottom();
        originalCardMaximum=card.getMaximumHeight();originalPhaseOneMaximum=phaseOne.getMaximumHeight();originalBannerLines=bannerText.getMaxLines();originalBannerWidth=bannerText.getMaxWidth();originalBannerSize=bannerText.getTextSize();
        for(View view:new View[]{banner,legacy,card,phaseOne,preview,reference}){originalGeometry.put(view,new FrameLayout.LayoutParams((FrameLayout.LayoutParams)view.getLayoutParams()));originalTranslations.put(view,new float[]{view.getTranslationX(),view.getTranslationY()});}
        for(View view:tools){toolbar.removeView(view);hidden.addView(view);}
        footerParent=(ViewGroup)card.rescan.getParent();rescanPosition=footerParent.indexOfChild(card.rescan);returnPosition=footerParent.indexOfChild(card.returnToCurrent);
        rescanParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)card.rescan.getLayoutParams());returnParams=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)card.returnToCurrent.getLayoutParams());
        footerParent.removeView(card.rescan);footerParent.removeView(card.returnToCurrent);hidden.addView(card.rescan);hidden.addView(card.returnToCurrent);
        toolbar.addView(hidden,new LinearLayout.LayoutParams(0,0));
        toolbar.addView(steps,new LinearLayout.LayoutParams(-2,dp(48)));toolbar.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
        title.setText(R.string.phone_scan_title);title.setTextSize(16);
        preview.setCompactControls(true);reference.setCompactControls(true);
        toolbar.setPaddingRelative(toolbar.getPaddingStart(),toolbar.getPaddingTop(),toolbar.getPaddingEnd(),dp(2));
    }
    private void showTools(){
        if(menu!=null)menu.dismiss();menu=new PopupMenu(context,more);
        if(cameraFocused){
            menu.getMenu().add(0,1,0,R.string.phone_scan_reference).setCheckable(true).setChecked(referenceOpen);
            menu.getMenu().add(0,2,1,R.string.phone_scan_preview).setCheckable(true).setChecked(previewOpen);
        }
        if(card.getVisibility()==View.VISIBLE){
            if(card.rescan.getVisibility()==View.VISIBLE)menu.getMenu().add(0,3,2,card.rescan.getText()).setEnabled(card.rescan.isEnabled());
            if(card.returnToCurrent.getVisibility()==View.VISIBLE)menu.getMenu().add(0,4,3,card.returnToCurrent.getText()).setEnabled(card.returnToCurrent.isEnabled());
        }
        for(View view:tools){
            if(view.getId()==R.id.livePill||view.getVisibility()!=View.VISIBLE||!view.hasOnClickListeners())continue;
            CharSequence label=view instanceof TextView?((TextView)view).getText():view.getContentDescription();
            menu.getMenu().add(0,view.getId(),10,label).setEnabled(view.isEnabled());
        }
        menu.setOnMenuItemClickListener(item->{
            if(item.getItemId()==1){referenceOpen=!referenceOpen;previewOpen=false;changed.run();return true;}
            if(item.getItemId()==2){previewOpen=!previewOpen;referenceOpen=false;changed.run();return true;}
            if(item.getItemId()==3)return card.rescan.performClick();
            if(item.getItemId()==4)return card.returnToCurrent.performClick();
            View original=root.findViewById(item.getItemId());return original!=null&&original.performClick();
        });menu.show();
    }

    /** Returns true when phone geometry was applied, false to retain the tablet/wiring layout. */
    public boolean layout(Insets bars, boolean phaseOneActive, boolean phaseTwoActive) {
        int width=root.getWidth()-bars.left-bars.right,height=root.getHeight()-bars.top-bars.bottom;
        if(!ScanViewportPolicy.compact(root.getWidth(),root.getHeight(),context.getResources().getDisplayMetrics().density)){restore();return false;}
        activate();steps.setVisibility(cameraFocused?View.VISIBLE:View.GONE);
        String label=context.getString(bodyOpen?R.string.phone_scan_hide_steps:R.string.phone_scan_steps);if(!label.contentEquals(steps.getText()))steps.setText(label);
        steps.setContentDescription(context.getString(bodyOpen?R.string.hide_step_information:R.string.show_step_information));
        boolean showBody=!cameraFocused||bodyOpen;
        card.setCameraBodyVisible(showBody);phaseOne.setCameraBodyVisible(showBody);
        reference.setPresentationSuppressed(!cameraFocused||!referenceOpen);
        preview.setPresentationSuppressed(cameraFocused&&!previewOpen);
        // Wiring already owns a separate diagram workspace; leave its geometry intact.
        if(root.getTag(R.id.wiringWorkspace)!=null)return false;

        int edge=dp(8),top=toolbar.getBottom()+edge;
        boolean landscape=width>height;
        int rail=landscape?ScanViewportPolicy.phoneRailWidth(width,context.getResources().getDisplayMetrics().density):width-2*edge;
        int cameraWidth=landscape?width-rail-3*edge:width-2*edge;
        if(bannerText.getMaxLines()!=2)bannerText.setMaxLines(2);int textWidth=Math.max(dp(90),cameraWidth-dp(58));if(bannerText.getMaxWidth()!=textWidth)bannerText.setMaxWidth(textWidth);bannerText.setTextSize(13);
        place(banner,-2,-2,Gravity.TOP|Gravity.START,bars.left+edge,top,bars.right+edge,0);
        int contentTop=Math.max(top+dp(40),banner.getBottom()+edge);
        View target=phaseTwoActive?card:phaseOneActive?phaseOne:legacy;
        int maximumHeight=landscape?root.getHeight()-bars.bottom-edge-contentTop:
                Math.min(root.getHeight()-bars.bottom-edge-contentTop,Math.round(height*(cameraFocused&&!bodyOpen?.28f:.49f)));
        if(!cameraFocused&&!landscape){int demoHeight=Math.min(dp(240),Math.round(height*.31f));maximumHeight=Math.min(maximumHeight,root.getHeight()-bars.bottom-2*edge-contentTop-demoHeight);}
        maximumHeight=Math.max(dp(64),maximumHeight);
        card.setMaximumHeight(maximumHeight);phaseOne.setMaximumHeight(maximumHeight);
        int panelHeight=target==legacy?maximumHeight:-2;
        int panelGravity=Gravity.BOTTOM|Gravity.END;
        place(target,rail,panelHeight,panelGravity,bars.left+edge,0,bars.right+edge,bars.bottom+edge);
        target.setTranslationX(0);target.setTranslationY(0);

        int aidWidth=landscape?Math.min(dp(240),cameraWidth):Math.min(width-2*edge,dp(320));
        int aidHeight=Math.min(dp(220),Math.max(dp(120),Math.round(height*.29f)));
        if(!cameraFocused){
            aidWidth=landscape?cameraWidth:width-2*edge;
            aidHeight=landscape?root.getHeight()-bars.bottom-edge-contentTop:Math.min(dp(240),Math.round(height*.31f));
        }
        place(preview,aidWidth,aidHeight,Gravity.TOP|Gravity.START,bars.left+edge,contentTop,bars.right+edge,0);
        preview.setTranslationX(0);preview.setTranslationY(0);
        place(reference,aidWidth,aidHeight,Gravity.TOP|Gravity.START,bars.left+edge,contentTop,bars.right+edge,0);
        reference.setTranslationX(0);reference.setTranslationY(0);
        reference.setAllowedBounds(bars.left+edge,contentTop,bars.left+edge+aidWidth,contentTop+aidHeight);
        return true;
    }
    private void place(View view,int width,int height,int gravity,int left,int top,int right,int bottom){
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)view.getLayoutParams();
        if(params.width==width&&params.height==height&&params.gravity==gravity&&params.getMarginStart()==left&&params.topMargin==top&&params.getMarginEnd()==right&&params.bottomMargin==bottom)return;
        params.width=width;params.height=height;params.gravity=gravity;params.setMargins(left,top,right,bottom);params.setMarginStart(left);params.setMarginEnd(right);view.setLayoutParams(params);
    }
    private void restore(){
        if(!active)return;active=false;if(menu!=null)menu.dismiss();
        toolbar.removeView(steps);toolbar.removeView(more);toolbar.removeView(hidden);
        for(int i=0;i<tools.size();i++){View view=tools.get(i);hidden.removeView(view);toolbar.addView(view,Math.min(positions.get(i),toolbar.getChildCount()));}
        hidden.removeView(card.rescan);hidden.removeView(card.returnToCurrent);footerParent.addView(card.rescan,Math.min(rescanPosition,footerParent.getChildCount()),rescanParams);footerParent.addView(card.returnToCurrent,Math.min(returnPosition,footerParent.getChildCount()),returnParams);
        title.setText(originalTitle);title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,originalTextSize);
        toolbar.setPaddingRelative(toolbar.getPaddingStart(),toolbar.getPaddingTop(),toolbar.getPaddingEnd(),originalBottomPadding);
        card.setCameraBodyVisible(true);phaseOne.setCameraBodyVisible(true);preview.setPresentationSuppressed(false);reference.setPresentationSuppressed(false);
        preview.setCompactControls(false);reference.setCompactControls(false);
        card.setMaximumHeight(originalCardMaximum);phaseOne.setMaximumHeight(originalPhaseOneMaximum);
        for(Map.Entry<View,FrameLayout.LayoutParams> entry:originalGeometry.entrySet()){View view=entry.getKey();view.setLayoutParams(new FrameLayout.LayoutParams(entry.getValue()));float[] translation=originalTranslations.get(view);view.setTranslationX(translation[0]);view.setTranslationY(translation[1]);}originalGeometry.clear();originalTranslations.clear();
        bannerText.setMaxLines(originalBannerLines);bannerText.setMaxWidth(originalBannerWidth);bannerText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,originalBannerSize);
    }
    private int dp(int value){return UiUtils.dp(context,value);}
    public boolean isActive(){return active;}
    @Override public void close(){restore();}
}
