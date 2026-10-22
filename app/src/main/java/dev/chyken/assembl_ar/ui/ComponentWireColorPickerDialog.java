package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatDialog;
import com.google.android.material.button.MaterialButton;
import dev.chyken.assembl_ar.model.*;
import dev.chyken.assembl_ar.model.ComponentWiringColorConfig.Wire;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Swatch;
import java.util.*;

/** Two- or six-net transactional picker. The preparation screen always precedes this view. */
public final class ComponentWireColorPickerDialog extends AppCompatDialog {
    public interface Listener {void onApply(ComponentWiringColorConfig value);void onInstruction(String text);}
    private ComponentWiringColorConfig draft;private Wire selected;private final int section;private final Listener listener;
    private final Map<Wire,MaterialButton> slots=new EnumMap<>(Wire.class);private final Map<Swatch,MaterialButton> swatches=new EnumMap<>(Swatch.class);private AssemblyDemoView diagram;
    public ComponentWireColorPickerDialog(Context context,ComponentWiringColorConfig colors,int section,Wire selected,Listener listener){super(context);if(section<1||section>3)throw new IllegalArgumentException();draft=colors;this.section=section;this.selected=selected.section==section?selected:section==1?Wire.TRIGGER:section==2?Wire.LED:Wire.LEFT_VCC;this.listener=listener;}
    public ComponentWiringColorConfig draftColors(){return draft;}public Wire selectedWire(){return selected;}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=new LinearLayout(getContext());body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(16),dp(20),dp(16));body.setBackgroundColor(0xFFF1F6F1);
        TextView title=new TextView(getContext());title.setText("Wire colors · Section "+section);title.setTextSize(20);title.setTextColor(0xFF263D30);body.addView(title);
        TextView hint=new TextView(getContext());hint.setText("Select a connection, then choose its physical wire color. Colors are optional visual labels.");hint.setTextSize(14);hint.setTextColor(0xFF526454);body.addView(hint);
        diagram=new AssemblyDemoView(getContext());diagram.setOnComponentWireSelected(w->{selected=w;render();listener.onInstruction(w.label()+", "+draft.swatch(w).label+" wire.");});body.addView(diagram,new LinearLayout.LayoutParams(-1,dp(240)));
        GridLayout nets=new GridLayout(getContext());nets.setColumnCount(2);body.addView(nets);
        for(Wire w:Wire.values())if(w.section==section){MaterialButton b=button(w.label());slots.put(w,b);b.setOnClickListener(v->{selected=w;render();listener.onInstruction(w.label()+". Choose a color.");});nets.addView(b,cell(56));}
        GridLayout palette=new GridLayout(getContext());palette.setColumnCount(5);body.addView(palette);
        for(Swatch s:Swatch.values()){MaterialButton b=button(s.label);b.setTextSize(11);GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(s.argb);dot.setStroke(dp(1),0xFF839587);b.setIcon(dot);b.setIconTint(null);b.setIconSize(dp(18));b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);b.setIconPadding(dp(2));b.setPadding(dp(3),dp(2),dp(3),dp(2));b.setContentDescription(s.label+" wire color");b.setOnClickListener(v->{draft=draft.with(selected,s);render();listener.onInstruction(selected.label()+" set to "+s.label+".");});palette.addView(b,cell(54));swatches.put(s,b);}
        LinearLayout actions=new LinearLayout(getContext());MaterialButton cancel=button("Cancel"),apply=button("Apply Colors");cancel.setOnClickListener(v->cancel());apply.setOnClickListener(v->{listener.onApply(draft);dismiss();});actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(52),1));actions.addView(apply,new LinearLayout.LayoutParams(0,dp(52),1));body.addView(actions);
        ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(true);scroll.addView(body);setContentView(scroll);render();
    }
    private void render(){for(Map.Entry<Wire,MaterialButton> e:slots.entrySet()){e.getValue().setText(e.getKey().label()+"\n"+draft.swatch(e.getKey()).label);highlight(e.getValue(),e.getKey()==selected);}for(Map.Entry<Swatch,MaterialButton> e:swatches.entrySet())highlight(e.getValue(),draft.color(selected)==e.getKey().argb);diagram.setComponentWireColors(draft,section);diagram.setSelectedComponentWire(selected);}
    private void highlight(MaterialButton b,boolean selected){b.setBackgroundTintList(ColorStateList.valueOf(selected?0xFFD8E9D7:0xFFF8FBF7));b.setStrokeColor(ColorStateList.valueOf(selected?0xFF46734A:0xFFBECDBE));b.setStrokeWidth(dp(selected?2:1));b.setSelected(selected);}
    private MaterialButton button(String text){MaterialButton b=new MaterialButton(getContext());b.setText(text);b.setTextColor(0xFF263D30);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setCornerRadius(dp(8));b.setInsetTop(0);b.setInsetBottom(0);return b;}
    private GridLayout.LayoutParams cell(int height){GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(height);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f);p.setMargins(dp(3),dp(4),dp(3),dp(4));return p;}
    private int dp(int n){return UiUtils.dp(getContext(),n);}
    @Override protected void onStart(){super.onStart();getWindow().setLayout(Math.min(dp(920),getContext().getResources().getDisplayMetrics().widthPixels-dp(24)),Math.round(getContext().getResources().getDisplayMetrics().heightPixels*.88f));diagram.resume();}
    @Override protected void onStop(){diagram.pause();super.onStop();}
}
