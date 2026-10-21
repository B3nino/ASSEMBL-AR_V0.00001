package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.view.ViewCompat;
import com.google.android.material.button.MaterialButton;
import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.model.MotorWireColorConfig;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Swatch;
import dev.chyken.assembl_ar.model.DriverWireColorConfig;
import dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire;

/** Transactional picker: cancel/back discards the draft; only Apply changes the assembly state. */
public final class DriverWireColorPickerDialog extends AppCompatDialog {
    public interface Listener {
        void onApply(DriverWireColorConfig colors);
        void onInstruction(String text);
    }
    private DriverWireColorConfig draft;
    private Wire active=Wire.VCC;
    private final Listener listener;
    private AssemblyDemoView diagram;
    private final MaterialButton[] slots=new MaterialButton[6],swatches=new MaterialButton[10];
    public DriverWireColorPickerDialog(Context context,DriverWireColorConfig initial,Listener listener){super(context);draft=initial;this.listener=listener;}
    public void setInitialWire(Wire terminal){active=terminal;}
    public Wire selectedWire(){return active;}
    public DriverWireColorConfig draftColors(){return draft;}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);supportRequestWindowFeature(Window.FEATURE_NO_TITLE);setContentView(R.layout.dialog_driver_wire_color_picker);
        diagram=findViewById(R.id.wirePickerDiagram);diagram.setOnDriverWireSelected(this::select);
        GridLayout terminals=findViewById(R.id.motorWireSlots),palette=findViewById(R.id.motorWireSwatches);
        for(Wire t:Wire.values()){
            MaterialButton button=button(t.label());slots[t.ordinal()]=button;button.setTextSize(12);button.setOnClickListener(v->select(t));terminals.addView(button,cell(66));
        }
        for(Swatch s:Swatch.values()){
            MaterialButton button=button(s.label);swatches[s.ordinal()]=button;button.setTextSize(10);button.setPadding(0,dp(5),0,dp(3));
            GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(s.argb);dot.setStroke(dp(1),0xFFB5BFB4);dot.setSize(dp(23),dp(23));
            button.setIcon(dot);button.setIconTint(null);button.setIconSize(dp(23));button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);button.setIconPadding(dp(3));
            button.setOnClickListener(v->{draft=draft.with(active,s);render();listener.onInstruction(active.spoken()+" wire set to "+s.label+".");});palette.addView(button,cell(66));
        }
        findViewById(R.id.previousMotorWire).setOnClickListener(v->select(Wire.values()[(active.ordinal()+5)%6]));
        findViewById(R.id.nextMotorWire).setOnClickListener(v->select(Wire.values()[(active.ordinal()+1)%6]));
        findViewById(R.id.resetWireColors).setOnClickListener(v->{draft=DriverWireColorConfig.defaults();render();listener.onInstruction("Wire colors reset. Match the six assignments to your physical jumper wires.");});
        findViewById(R.id.cancelWireColors).setOnClickListener(v->cancel());
        findViewById(R.id.applyWireColors).setOnClickListener(v->{listener.onApply(draft);dismiss();});
        render();
    }
    private int dp(int n){return UiUtils.dp(getContext(),n);}
    private MaterialButton button(String text){
        MaterialButton b=new MaterialButton(getContext());b.setText(text);b.setTextColor(0xFFEAF0E6);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
        b.setInsetTop(0);b.setInsetBottom(0);b.setCornerRadius(dp(8));b.setStrokeWidth(dp(1));return b;
    }
    private GridLayout.LayoutParams cell(int height){
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=0;p.height=dp(height);p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1,1f);p.setMargins(dp(2),dp(3),dp(2),dp(3));return p;
    }
    private void select(Wire terminal){active=terminal;render();listener.onInstruction("Selected "+active.spoken()+". Current wire color is "+draft.swatch(active).label+".");}
    private void render(){
        ((TextView)findViewById(R.id.activeMotorWire)).setText(active.label()+" · "+draft.swatch(active).label);
        for(Wire t:Wire.values()){
            MaterialButton b=slots[t.ordinal()];b.setText(t.label()+"\n"+draft.swatch(t).label);highlight(b,t==active);
            b.setContentDescription(t.spoken()+", "+draft.swatch(t).label);ViewCompat.setStateDescription(b,t==active?"Selected terminal":"");
        }
        for(Swatch s:Swatch.values()){MaterialButton b=swatches[s.ordinal()];highlight(b,draft.color(active)==s.argb);b.setContentDescription(s.label+", "+s.hex());ViewCompat.setStateDescription(b,draft.color(active)==s.argb?"Selected color":"");}
        diagram.setDriverWireColors(draft);diagram.setSelectedDriverWire(active);
    }
    private void highlight(MaterialButton button,boolean selected){button.setSelected(selected);button.setBackgroundTintList(ColorStateList.valueOf(selected?0xFF293B24:0xFF232823));button.setStrokeColor(ColorStateList.valueOf(selected?0xFF8CBC63:0xFF465044));button.setStrokeWidth(dp(selected?2:1));}
    @Override protected void onStart(){
        super.onStart();android.util.DisplayMetrics metrics=getContext().getResources().getDisplayMetrics();
        int width=Math.min(metrics.widthPixels-dp(24),dp(1000)),height=Math.min(Math.round(metrics.heightPixels*.88f),dp(720));
        Window window=getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setLayout(width,height);}
        boolean wide=width>=dp(720);LinearLayout columns=findViewById(R.id.wirePickerColumns);columns.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        LinearLayout.LayoutParams image=(LinearLayout.LayoutParams)diagram.getLayoutParams();image.width=wide?0:-1;image.height=wide?dp(310):Math.round(Math.min(dp(280),(width-dp(40))*.61f));image.weight=wide?1:0;diagram.setLayoutParams(image);
        View controls=findViewById(R.id.wirePickerControls);LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)controls.getLayoutParams();p.width=wide?dp(320):-1;p.setMarginStart(wide?dp(16):0);controls.setLayoutParams(p);diagram.resume();
    }
    @Override protected void onStop(){diagram.pause();super.onStop();}
}
