package dev.chyken.assembl_ar;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.camera.PlanFReferenceCatalog;
import dev.chyken.assembl_ar.ui.PlanFReferenceImageView;
import dev.chyken.assembl_ar.ui.UiUtils;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.workflow.PlanFSession;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Passive scan assistance. No phase controller, recognizer or progress writer is passed in. */
final class PlanFScanHelpController implements AutoCloseable {
    private final Activity activity;
    private final Context context;
    private final LongSupplier clock;
    private final View helpButton;
    private final Supplier<List<ComponentReferenceSpec>> catalog;
    private final PlanFSession session = new PlanFSession();
    private AlertDialog dialog;
    private PlanFReferenceImageView image;
    private RecognitionRequest request;
    private String scope, componentName;
    private Object attempt;
    private boolean ready, closed;

    PlanFScanHelpController(Activity activity, View helpButton,
            Supplier<List<ComponentReferenceSpec>> catalog) {
        this(activity,activity,helpButton,catalog,SystemClock::elapsedRealtime);
    }

    PlanFScanHelpController(Activity activity, Context context, View helpButton,
            Supplier<List<ComponentReferenceSpec>> catalog, LongSupplier clock) {
        this.activity = activity; this.context = context; this.helpButton = helpButton; this.catalog = catalog; this.clock = clock;
        helpButton.setOnClickListener(v -> open());
    }

    void update(String nextScope, Object nextAttempt, RecognitionRequest nextRequest,
            String name, boolean enabled, boolean scanningReady, boolean verified) {
        if (closed) return;
        boolean changed = !Objects.equals(scope, nextScope) || !Objects.equals(attempt, nextAttempt);
        scope = nextScope; attempt = nextAttempt; request = nextRequest; componentName = name;
        ready = enabled && scanningReady && !verified && nextRequest != null;
        if (changed || !ready) dismiss();
        helpButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (session.update(nextScope, nextAttempt, enabled, ready, verified, clock.getAsLong())) open();
    }

    private void open() {
        if (closed || !ready || dialog != null || activity.isFinishing() || activity.isDestroyed()) return;
        session.markHelpOpened();
        List<ComponentReferenceSpec> photos = PlanFReferenceCatalog.select(catalog.get(), request);
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        int p = UiUtils.dp(context, 18); body.setPadding(p, p, p, p);
        body.setBackgroundColor(context.getColor(R.color.canvas));
        TextView name = text(body, PlanFReferenceCatalog.targetName(request, componentName), 18);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        image = new PlanFReferenceImageView(context); image.setId(R.id.planFReferenceImage);
        image.setContentDescription(context.getString(R.string.plan_f_reference_description));
        int height = Math.min(UiUtils.dp(context, 240), context.getResources().getDisplayMetrics().heightPixels / 3);
        body.addView(image, new LinearLayout.LayoutParams(-1, height));
        TextView photoLabel = text(body, "", 13); photoLabel.setId(R.id.planFReferenceLabel);
        TextView explanation = text(body, context.getString(R.string.plan_f_question), 16);
        explanation.setId(R.id.planFInstructions);
        text(body, context.getString(R.string.plan_f_no_bypass), 13);
        int[] index = {0};
        Runnable bind = () -> {
            if (photos.isEmpty()) {
                image.setVisibility(View.GONE); photoLabel.setText(R.string.plan_f_no_reference);
            } else {
                image.showReference(photos.get(index[0]));
                photoLabel.setText(context.getString(R.string.plan_f_reference_number, index[0] + 1, photos.size()));
            }
        };
        bind.run();
        ScrollView scroll = new ScrollView(context); scroll.addView(body);
        dialog = new HelpDialog(context, scroll);
        AlertDialog shown = dialog;
        shown.setOnDismissListener(d -> {
            if (dialog == shown) { if (image != null) image.showReference(null); image = null; dialog = null; }
        });
        shown.show();
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dismiss());
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            explanation.setText(PlanFReferenceCatalog.tip(request));
            shown.setTitle(R.string.plan_f_guidance_title);
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
        });
        Button another = shown.getButton(AlertDialog.BUTTON_NEUTRAL);
        another.setEnabled(photos.size() > 1);
        another.setOnClickListener(v -> { if(!photos.isEmpty()){index[0] = (index[0] + 1) % photos.size(); bind.run();} });
    }

    /** Independently scrolling photo/tips with a fixed, stacked action area at large fonts. */
    private static final class HelpDialog extends AlertDialog {
        private final Button[] actions = new Button[3];
        private final TextView heading;
        HelpDialog(Context c, ScrollView content) {
            super(c);
            LinearLayout shell = new LinearLayout(c); shell.setOrientation(LinearLayout.VERTICAL);
            int padding = UiUtils.dp(c, 18); shell.setPadding(padding, padding, padding, padding);
            android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
            surface.setColor(c.getColor(R.color.canvas)); surface.setCornerRadius(UiUtils.dp(c, 22)); shell.setBackground(surface);
            heading = new TextView(c); heading.setText(R.string.plan_f_title); heading.setTextSize(20);
            heading.setTextColor(c.getColor(R.color.ink)); heading.setTypeface(null,android.graphics.Typeface.BOLD);
            heading.setPadding(0,0,0,UiUtils.dp(c,10)); shell.addView(heading,new LinearLayout.LayoutParams(-1,-2));
            shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
            int[] labels={R.string.plan_f_yes,R.string.plan_f_keep_scanning,R.string.plan_f_another_view};
            for(int index:new int[]{0,2,1}){
                com.google.android.material.button.MaterialButton button=new com.google.android.material.button.MaterialButton(c);
                button.setText(labels[index]);button.setAllCaps(false);button.setTextSize(15);button.setMinHeight(UiUtils.dp(c,48));
                button.setCornerRadius(UiUtils.dp(c,24));
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(index==0?c.getColor(R.color.primary):0xFFE0EEE5));
                button.setTextColor(index==0?0xFFFFFFFF:c.getColor(R.color.ink));
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=UiUtils.dp(c,4);
                shell.addView(button,params);actions[index]=button;
            }
            setView(shell);
        }
        @Override public Button getButton(int which){return actions[which==BUTTON_POSITIVE?0:which==BUTTON_NEGATIVE?1:2];}
        @Override public void setTitle(CharSequence title){if(heading!=null)heading.setText(title);}
        @Override public void setTitle(int title){setTitle(getContext().getString(title));}
        @Override protected void onStart(){
            super.onStart();Window window=getWindow();if(window==null)return;
            window.setBackgroundDrawableResource(android.R.color.transparent);window.setDimAmount(.12f);
            android.util.DisplayMetrics display=getContext().getResources().getDisplayMetrics();
            if(display.widthPixels>display.heightPixels)window.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            window.setLayout(Math.min(UiUtils.dp(getContext(),560),display.widthPixels-UiUtils.dp(getContext(),24)),
                    Math.min(UiUtils.dp(getContext(),640),(int)(display.heightPixels*.88f)));
        }
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size);
        view.setTextColor(context.getColor(R.color.ink)); view.setPadding(0, 0, 0, UiUtils.dp(context, 10));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }

    private void dismiss() {
        // Detach synchronously: Android posts OnDismissListener later. Rapid navigation
        // must not keep the old photo/dialog owner alive until that message is delivered.
        AlertDialog previous = dialog; dialog = null;
        if (image != null) image.showReference(null);
        image = null;
        if (previous != null) previous.dismiss();
    }
    void suspend() { ready = false; session.suspend(); helpButton.setVisibility(View.GONE); dismiss(); }
    @Override public void close() { closed = true; suspend(); helpButton.setOnClickListener(null); }
}
