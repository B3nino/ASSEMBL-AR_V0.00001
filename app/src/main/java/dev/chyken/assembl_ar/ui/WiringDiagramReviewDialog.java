package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import dev.chyken.assembl_ar.workflow.phase.WiringDiagramState;
import java.util.function.Consumer;

/** Read-only library available from every assembly phase, independent of camera preparation. */
public final class WiringDiagramReviewDialog extends Dialog {
    private final AssemblyDemoView diagram;private final WiringDiagramState colors;private final Consumer<String> speak;
    private final MaterialButton choose;private final SchematicZoomFrame zoom;
    public static final String[] TITLES={"Phase 4 · Motor leads","Phase 6 · Nano and motor driver","Phase 8.3 · Trigger and Echo","Phase 8.4 · LED and speaker","Phase 8.5 · Both line trackers"};
    public WiringDiagramReviewDialog(Activity activity,WiringDiagramState colors,int phase,Consumer<String> speak){
        super(activity);this.colors=colors;this.speak=speak;requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setBackgroundColor(0xFFF1F6F1);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);choose=new MaterialButton(activity);choose.setTextSize(14);header.addView(choose,new LinearLayout.LayoutParams(0,UiUtils.dp(activity,54),1));
        MaterialButton close=new MaterialButton(activity);close.setText("Close");header.addView(close,new LinearLayout.LayoutParams(-2,UiUtils.dp(activity,52)));close.setOnClickListener(v->dismiss());body.addView(header);
        TextView note=new TextView(activity);note.setText("Review only · Progress is preserved. Pinch to zoom; drag to inspect. Unconfigured diagrams use default colors.");note.setTextColor(0xFF45604F);note.setTextSize(12);note.setPadding(16,4,16,8);body.addView(note);
        diagram=new AssemblyDemoView(activity);diagram.setExpandedSchematic(true);zoom=new SchematicZoomFrame(activity);zoom.addView(diagram,new FrameLayout.LayoutParams(-1,-1));body.addView(zoom,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout tools=new LinearLayout(activity);String[] names={"Zoom out","Reset view","Zoom in"};
        for(int i=0;i<3;i++){final int action=i;MaterialButton button=new MaterialButton(activity);button.setText(names[i]);button.setOnClickListener(v->{if(action==1)zoom.resetZoom();else zoom.zoomBy(action==0?.8f:1.25f);});tools.addView(button,new LinearLayout.LayoutParams(0,-2,1));}body.addView(tools);
        choose.setOnClickListener(v->{PopupMenu menu=new PopupMenu(activity,choose);for(int i=0;i<TITLES.length;i++)menu.getMenu().add(0,i,i,TITLES[i]);menu.setOnMenuItemClickListener(item->{select(item.getItemId());return true;});menu.show();});
        setContentView(body);ViewCompat.setOnApplyWindowInsetsListener(body,(v,insets)->{Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());body.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        select(phase>=8?2:phase>=6?1:0);setOnShowListener(d->{getWindow().setBackgroundDrawableResource(android.R.color.white);getWindow().setLayout(-1,-1);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);ViewCompat.requestApplyInsets(body);});
    }
    private void select(int entry){
        diagram.pause();choose.setText(TITLES[entry]+" ▾");zoom.resetZoom();
        if(entry==0){diagram.setMotorWireColors(colors.motor);diagram.setOnMotorWireSelected(w->{diagram.setSelectedMotorWire(w);speak.accept(w.spoken()+", "+colors.motor.swatch(w).label+" wire to "+w.output+".");});}
        else if(entry==1){diagram.setDriverWireColors(colors.driver);diagram.setDriverWireFocus(0);diagram.setOnDriverWireSelected(w->{diagram.setSelectedDriverWire(w);speak.accept(w.spoken()+", "+colors.driver.swatch(w).label+" wire.");});}
        else{diagram.setComponentWireColors(colors.components,entry-1);diagram.setOnComponentWireSelected(w->{diagram.setSelectedComponentWire(w);speak.accept(w.label()+", "+colors.components.swatch(w).label+" wire.");});}
        if(isShowing()){diagram.resume();speak.accept(TITLES[entry]+". Review the saved wire colors. Pinch to zoom and drag to inspect.");}
    }
    @Override protected void onStart(){super.onStart();diagram.resume();}
    @Override protected void onStop(){diagram.pause();super.onStop();}
}
