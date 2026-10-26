package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import dev.chyken.assembl_ar.R;

/** Bottom-anchored accessible modal sheet, retaining AlertDialog's existing button/listener contract.
 * Body scrolls independently; explicit answer buttons remain reachable. No swipe can confirm a check. */
public final class PhysicalChecklistBottomSheetDialog extends AlertDialog {
    private final Button[] answers=new Button[3];
    private final LinearLayout shell,content;
    private final TextView heading;
    private final ScrollView scroll;
    private PhysicalChecklistBottomSheetDialog(Builder b){
        super(b.context);Context c=b.context;int pad=UiUtils.dp(c,20);
        shell=new LinearLayout(c);shell.setOrientation(LinearLayout.VERTICAL);shell.setPadding(pad,pad,pad,pad);
        GradientDrawable surface=new GradientDrawable();surface.setColor(0xF51E2228);surface.setCornerRadius(UiUtils.dp(c,24));shell.setBackground(surface);
        LinearLayout titleRow=new LinearLayout(c);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(c);icon.setImageResource(iconFor(b.title));icon.setImageTintList(ColorStateList.valueOf(0xFFBCDCCB));titleRow.addView(icon,new LinearLayout.LayoutParams(UiUtils.dp(c,30),UiUtils.dp(c,30)));
        heading=new TextView(c);heading.setText(b.title);heading.setTextSize(20);heading.setTextColor(0xFFD7EEE1);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(UiUtils.dp(c,12),0,0,0);titleRow.addView(heading,new LinearLayout.LayoutParams(0,-2,1));shell.addView(titleRow);
        TextView hint=new TextView(c);hint.setText("Inspect the physical build before confirming.");hint.setTextColor(0xFFB9C9C1);hint.setTextSize(13);hint.setPadding(0,UiUtils.dp(c,8),0,UiUtils.dp(c,8));shell.addView(hint);
        content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);
        View body=b.body;if(body==null)body=PhysicalCheckUi.prompt(c,b.message==null?"":b.message.toString());
        if(body.getParent()!=null)throw new IllegalArgumentException("Checklist content already attached");content.addView(body,new LinearLayout.LayoutParams(-1,-2));
        scroll=new ScrollView(c);scroll.setFillViewport(false);scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        addAnswer(BUTTON_POSITIVE,b.positive,b.yes,true);addAnswer(BUTTON_NEGATIVE,b.negative,b.no,false);addAnswer(BUTTON_NEUTRAL,b.neutral,b.later,false);
        setView(shell);setCanceledOnTouchOutside(false);
        recolor(content);
    }
    private static int slot(int which){return which==BUTTON_POSITIVE?0:which==BUTTON_NEGATIVE?1:2;}
    @Override public Button getButton(int which){return answers[slot(which)];}
    private void addAnswer(int which,CharSequence label,DialogInterface.OnClickListener listener,boolean primary){
        if(label==null)return;Context c=getContext();MaterialButton button=new MaterialButton(c);button.setText(label);button.setAllCaps(false);button.setTextSize(15);button.setMinHeight(UiUtils.dp(c,52));button.setCornerRadius(UiUtils.dp(c,26));
        button.setBackgroundTintList(ColorStateList.valueOf(primary?0xFF326A32:0xFF2B3339));button.setTextColor(primary?0xFFFFFFFF:0xFFD7EEE1);button.setStrokeWidth(UiUtils.dp(c,1));button.setStrokeColor(ColorStateList.valueOf(0xFF77978A));
        shell.addView(button,new LinearLayout.LayoutParams(-1,-2));answers[slot(which)]=button;
        button.setOnClickListener(v->{if(listener!=null)listener.onClick(this,which);dismiss();});
    }
    @Override protected void onStart(){super.onStart();Window w=getWindow();if(w==null)return;w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.45f);
        int width=Math.min(UiUtils.dp(getContext(),640),getContext().getResources().getDisplayMetrics().widthPixels-UiUtils.dp(getContext(),24));
        int height=Math.min((int)(getContext().getResources().getDisplayMetrics().heightPixels*.88f),UiUtils.dp(getContext(),690));w.setLayout(width,height);}
    private static int iconFor(CharSequence title){String s=String.valueOf(title).toLowerCase(java.util.Locale.ROOT);return s.contains("wire")||s.contains("cable")||s.contains("power")||s.contains("connection")?R.drawable.ic_check_wiring:s.contains("pin")||s.contains("alignment")||s.contains("board")?R.drawable.ic_check_alignment:R.drawable.ic_check_fastener;}
    private static void recolor(View v){
        if(v instanceof TextView){((TextView)v).setTextColor(0xFFE0E9E4);if(v.getBackground()!=null&&!(v instanceof Button))v.setBackgroundColor(0xFF27352E);if(v instanceof CompoundButton)((CompoundButton)v).setButtonTintList(ColorStateList.valueOf(0xFF91C9A7));}
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)recolor(((ViewGroup)v).getChildAt(i));
    }
    public static final class Builder {
        private final Context context;private CharSequence title,message,positive,negative,neutral;private View body;private DialogInterface.OnClickListener yes,no,later;
        public Builder(Context c){context=c;}
        public Builder setTitle(CharSequence v){title=v;return this;}public Builder setTitle(int v){return setTitle(context.getString(v));}
        public Builder setMessage(CharSequence v){message=v;return this;}public Builder setMessage(int v){return setMessage(context.getString(v));}
        public Builder setView(View v){body=v;return this;}
        public Builder setPositiveButton(CharSequence s,DialogInterface.OnClickListener l){positive=s;yes=l;return this;}public Builder setPositiveButton(int s,DialogInterface.OnClickListener l){return setPositiveButton(context.getString(s),l);}
        public Builder setNegativeButton(CharSequence s,DialogInterface.OnClickListener l){negative=s;no=l;return this;}public Builder setNegativeButton(int s,DialogInterface.OnClickListener l){return setNegativeButton(context.getString(s),l);}
        public Builder setNeutralButton(CharSequence s,DialogInterface.OnClickListener l){neutral=s;later=l;return this;}
        public PhysicalChecklistBottomSheetDialog create(){return new PhysicalChecklistBottomSheetDialog(this);}
        public PhysicalChecklistBottomSheetDialog show(){PhysicalChecklistBottomSheetDialog d=create();d.show();return d;}
    }
}
