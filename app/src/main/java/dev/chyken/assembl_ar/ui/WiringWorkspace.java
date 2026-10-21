package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import java.util.function.Consumer;
import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.model.*;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal;
import dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire;

/** Large reference pane on the left, assignment legend above the instruction card on the right. */
public final class WiringWorkspace extends LinearLayout implements AutoCloseable {
    private final Activity activity;private final FrameLayout root;private final ComponentPreviewWindow preview;
    private final AssemblyDemoView diagram;private final ScrollView legend;private final GridLayout slots;
    private final Consumer<String> instruction;
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener layout=this::fit;
    private MotorWireColorConfig motor;private DriverWireColorConfig driver;
    private Terminal selectedMotor=Terminal.LEFT_POSITIVE;private Wire selectedDriver=Wire.VCC;
    private Dialog full;private AssemblyDemoView fullDiagram;private boolean closed,legendOnPreview,paused;private int focus;
    public WiringWorkspace(Activity activity,Consumer<String> instruction){
        super(activity);this.activity=activity;this.instruction=instruction;root=activity.findViewById(R.id.assemblyRoot);preview=activity.findViewById(R.id.componentPreviewWindow);
        setOrientation(VERTICAL);setPadding(dp(8),dp(8),dp(8),dp(8));setElevation(dp(6));setBackground(round(0xFFF1F6F1,0xFFD0DDD2,14));setVisibility(GONE);
        diagram=new AssemblyDemoView(activity);diagram.setExpandedSchematic(true);diagram.setOnClickListener(v->expand());addView(diagram,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=new TextView(activity);hint.setText("Tap diagram for full screen");hint.setTextColor(0xFF45604F);hint.setTextSize(12);hint.setGravity(Gravity.CENTER);hint.setMinHeight(dp(38));hint.setOnClickListener(v->expand());hint.setFocusable(true);addView(hint,new LinearLayout.LayoutParams(-1,-2));
        legend=new ScrollView(activity);slots=new GridLayout(activity);slots.setColumnCount(2);slots.setPadding(dp(6),dp(2),dp(6),dp(4));legend.addView(slots,new ScrollView.LayoutParams(-1,-2));
        root.addView(this,new FrameLayout.LayoutParams(1,1));root.getViewTreeObserver().addOnGlobalLayoutListener(layout);
    }
    public static WiringWorkspace active(Activity activity){View root=activity.findViewById(R.id.assemblyRoot);Object tag=root==null?null:root.getTag(R.id.wiringWorkspace);return tag instanceof WiringWorkspace&&((WiringWorkspace)tag).getVisibility()==VISIBLE?(WiringWorkspace)tag:null;}
    public static int railWidth(android.content.Context context,int width){return Math.min(Math.max(UiUtils.dp(context,300),Math.round(width*.34f)),Math.round(width*.44f));}
    public void setMotorColors(MotorWireColorConfig colors){motor=colors;driver=null;diagram.setMotorWireColors(colors);diagram.setSelectedMotorWire(selectedMotor);legend();}
    public void setDriverColors(DriverWireColorConfig colors,int focus){driver=colors;motor=null;this.focus=focus;
        if(focus==2&&selectedDriver.power())selectedDriver=Wire.A1A;if(focus==1&&!selectedDriver.power())selectedDriver=Wire.VCC;
        diagram.setDriverWireColors(colors);diagram.setDriverWireFocus(focus);diagram.setSelectedDriverWire(selectedDriver);legend();}
    public void show(){if(closed)return;WiringWorkspace other=active(activity);if(other!=null&&other!=this)other.hide();root.setTag(R.id.wiringWorkspace,this);setVisibility(VISIBLE);activity.findViewById(R.id.recognitionBanner).setVisibility(GONE);fit();if(!paused)diagram.resume();}
    public void hide(){setVisibility(GONE);diagram.pause();if(root.getTag(R.id.wiringWorkspace)==this){root.setTag(R.id.wiringWorkspace,null);activity.findViewById(R.id.recognitionBanner).setVisibility(VISIBLE);}if(full!=null)full.dismiss();}
    private void legend(){
        slots.removeAllViews();
        if(driver!=null)for(Wire wire:Wire.values())slot(wire.label(),driver.swatch(wire).label,driver.color(wire),wire==selectedDriver,()->{selectedDriver=wire;diagram.setSelectedDriverWire(wire);legend();instruction.accept(wire.spoken()+", "+driver.swatch(wire).label+" wire.");});
        else if(motor!=null)for(Terminal terminal:Terminal.values())slot(terminal.shortLabel+" tab",motor.swatch(terminal).label,motor.color(terminal),terminal==selectedMotor,()->{selectedMotor=terminal;diagram.setSelectedMotorWire(terminal);legend();instruction.accept(terminal.spoken()+", "+motor.swatch(terminal).label+" wire to "+terminal.output+".");});
    }
    private void slot(String label,String colorName,int color,boolean selected,Runnable click){
        TextView view=new TextView(activity);view.setText(label+"\n"+colorName);view.setTextSize(12);view.setTextColor(0xFF263C30);view.setPadding(dp(8),dp(6),dp(5),dp(6));view.setMinHeight(dp(52));view.setGravity(Gravity.CENTER_VERTICAL);view.setBackground(round(selected?0xFFDDEBDD:0xFFF5F8F4,selected?0xFF527D57:0xFFD1DBD1,7));
        GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(color);dot.setStroke(dp(1),0xFF8A998E);dot.setBounds(0,0,dp(13),dp(13));view.setCompoundDrawables(dot,null,null,null);view.setCompoundDrawablePadding(dp(6));view.setOnClickListener(v->click.run());view.setFocusable(true);view.setSelected(selected);ViewCompat.setStateDescription(view,selected?"Selected connection":"");
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=-2;p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f);p.setMargins(dp(3),dp(3),dp(3),dp(3));slots.addView(view,p);
    }
    private void fit(){
        if(closed||getVisibility()!=VISIBLE||root.getWidth()==0)return;
        WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(root);Insets bars=insets==null?Insets.NONE:insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
        int width=root.getWidth()-bars.left-bars.right,edge=dp(12),top=activity.findViewById(R.id.cameraToolbar).getBottom()+edge;
        boolean wide=width>=dp(640);int paneWidth=wide?width-railWidth(activity,width)-3*edge:width-2*edge;
        int paneHeight=wide?root.getHeight()-bars.bottom-edge-top:Math.min(Math.round((root.getHeight()-bars.top-bars.bottom)*.52f),root.getHeight()-bars.bottom-top-dp(190));paneHeight=Math.max(dp(150),paneHeight);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)getLayoutParams();if(p.width!=paneWidth||p.height!=paneHeight||p.leftMargin!=bars.left+edge||p.topMargin!=top){p.width=paneWidth;p.height=paneHeight;p.gravity=Gravity.TOP|Gravity.LEFT;p.leftMargin=bars.left+edge;p.topMargin=top;setLayoutParams(p);}
        int columns=!wide&&driver!=null?3:2;if(slots.getColumnCount()!=columns)slots.setColumnCount(columns);
        if(wide){
            if(!legendOnPreview||legend.getParent()==null){detachLegend();preview.showContent(legend,"Assigned wire colors");preview.setLegendMode(true);legendOnPreview=true;}
            preview.setVisibility(VISIBLE);
        }else{
            if(legendOnPreview||legend.getParent()==null){detachLegend();addView(legend,new LinearLayout.LayoutParams(-1,dp(120)));legendOnPreview=false;}
            preview.setVisibility(GONE);
        }
    }
    private void detachLegend(){if(legend.getParent() instanceof ViewGroup)((ViewGroup)legend.getParent()).removeView(legend);}
    public void expand(){
        if(closed||paused||full!=null&&full.isShowing())return;
        fullDiagram=new AssemblyDemoView(activity);fullDiagram.setExpandedSchematic(true);
        if(driver!=null){fullDiagram.setDriverWireColors(driver);fullDiagram.setDriverWireFocus(focus);fullDiagram.setSelectedDriverWire(selectedDriver);fullDiagram.setOnDriverWireSelected(wire->{selectedDriver=wire;diagram.setSelectedDriverWire(wire);legend();fullDiagram.setSelectedDriverWire(wire);instruction.accept(wire.spoken()+", "+driver.swatch(wire).label+" wire.");});}
        else{fullDiagram.setMotorWireColors(motor);fullDiagram.setSelectedMotorWire(selectedMotor);fullDiagram.setOnMotorWireSelected(terminal->{selectedMotor=terminal;diagram.setSelectedMotorWire(terminal);legend();fullDiagram.setSelectedMotorWire(terminal);instruction.accept(terminal.spoken()+", "+motor.swatch(terminal).label+" wire to "+terminal.output+".");});}
        LinearLayout body=new LinearLayout(activity);body.setOrientation(VERTICAL);body.setBackgroundColor(0xFFF1F6F1);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);TextView title=new TextView(activity);title.setText("Wiring diagram · Pinch to zoom");title.setTextSize(16);title.setTextColor(0xFF253D30);title.setPadding(dp(16),0,0,0);header.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
        com.google.android.material.button.MaterialButton close=new com.google.android.material.button.MaterialButton(activity);close.setText("Close");header.addView(close,new LinearLayout.LayoutParams(-2,dp(52)));body.addView(header);
        SchematicZoomFrame zoom=new SchematicZoomFrame(activity);zoom.addView(fullDiagram,new FrameLayout.LayoutParams(-1,-1));body.addView(zoom,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout tools=new LinearLayout(activity);
        String[] labels={"Zoom out","Reset view","Zoom in"};
        for(int i=0;i<3;i++){final int action=i;com.google.android.material.button.MaterialButton button=new com.google.android.material.button.MaterialButton(activity);button.setText(labels[i]);button.setTextSize(12);button.setMinHeight(dp(48));button.setOnClickListener(v->{if(action==1)zoom.resetZoom();else zoom.zoomBy(action==0?.8f:1.25f);});tools.addView(button,new LinearLayout.LayoutParams(0,-2,1));}
        body.addView(tools,new LinearLayout.LayoutParams(-1,-2));
        full=new Dialog(activity);full.requestWindowFeature(Window.FEATURE_NO_TITLE);full.setContentView(body);close.setOnClickListener(v->full.dismiss());
        ViewCompat.setOnApplyWindowInsetsListener(body,(view,insets)->{Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());body.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        full.setOnDismissListener(d->{fullDiagram.pause();if(!paused&&isShown())diagram.resume();});full.show();full.getWindow().setBackgroundDrawableResource(android.R.color.white);full.getWindow().setLayout(-1,-1);WindowCompat.setDecorFitsSystemWindows(full.getWindow(),false);ViewCompat.requestApplyInsets(body);diagram.pause();fullDiagram.resume();instruction.accept("Diagram expanded. Pinch to zoom and drag to inspect the pins.");
    }
    public void pause(){paused=true;diagram.pause();if(full!=null)full.dismiss();}
    public void resume(){paused=false;if(isShown())diagram.resume();fit();}
    @Override public void close(){if(closed)return;pause();hide();closed=true;root.getViewTreeObserver().removeOnGlobalLayoutListener(layout);detachLegend();root.removeView(this);}
    private GradientDrawable round(int fill,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setStroke(dp(1),stroke);d.setCornerRadius(dp(radius));return d;}
    private int dp(int value){return UiUtils.dp(activity,value);}
}
