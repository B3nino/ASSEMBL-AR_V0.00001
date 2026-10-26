package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.view.*;
import android.widget.*;

/** Progress-driven assembly cube, gated by prepared references and the first complete robot frame. */
public final class BrandLaunchOverlay extends FrameLayout implements AutoCloseable {
    private final StartupRevealGate gate=new StartupRevealGate(true);
    private final AssemblyLoadingCubeView cube;
    private final TextView status;
    private final View menu;
    private final ProgressBar progress;
    private final com.google.android.material.button.MaterialButton retry;
    private Runnable onRevealed;
    private boolean preparing=true,failed;
    private final int oldAccessibility;
    private boolean closed,paused,revealing;
    private final Runnable delayed=this::showDelayed;
    private void showDelayed(){if(!closed&&!preparing&&!failed)status.setText("Preparing the robot preview…");}
    public BrandLaunchOverlay(Activity activity,View menu){
        super(activity);this.menu=menu;oldAccessibility=menu.getImportantForAccessibility();menu.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setBackgroundColor(activity.getColor(dev.chyken.assembl_ar.R.color.canvas));setClickable(true);setFocusable(true);setElevation(UiUtils.dp(activity,30));setOutlineProvider(null);
        LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER);
        int size=Math.min(UiUtils.dp(activity,400),activity.getResources().getDisplayMetrics().widthPixels-UiUtils.dp(activity,48));
        TextView brand=new TextView(activity);brand.setText("ASSEMBL-AR");brand.setTextColor(activity.getColor(dev.chyken.assembl_ar.R.color.ink));brand.setTextSize(25);brand.setTypeface(null,android.graphics.Typeface.BOLD);column.addView(brand);
        cube=new AssemblyLoadingCubeView(activity);column.addView(cube,new LinearLayout.LayoutParams(size,Math.round(size*.72f)));
        status=new TextView(activity);status.setText("Preparing your workspace");status.setTextColor(activity.getColor(dev.chyken.assembl_ar.R.color.ink_soft));status.setTextSize(15);status.setGravity(Gravity.CENTER);status.setPadding(UiUtils.dp(activity,16),UiUtils.dp(activity,18),UiUtils.dp(activity,16),UiUtils.dp(activity,12));column.addView(status);
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);column.addView(progress,new LinearLayout.LayoutParams(Math.min(size,UiUtils.dp(activity,300)),UiUtils.dp(activity,8)));
        retry=new com.google.android.material.button.MaterialButton(activity);retry.setText("Retry preparation");retry.setVisibility(GONE);column.addView(retry);
        addView(column,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        // The real reference/model gates remain authoritative; animation never manufactures readiness.
        gate.introFinished();
        postDelayed(delayed,8000);
    }
    public void modelReady(){if(closed)return;gate.frameReady();reveal();}
    public void setOnRevealed(Runnable ready){onRevealed=ready;}
    public void referenceProgress(int done,int total){if(closed||!preparing||failed)return;cube.setProgress(done,total);progress.setIndeterminate(total==0);progress.setMax(Math.max(1,total));progress.setProgress(done);String label=total==0?"Discovering reference library…":"Pre-compiling References... "+Math.min(100,Math.max(0,(int)(100L*done/total)))+"% · "+done+" / "+total;if(!android.text.TextUtils.equals(status.getText(),label))status.setText(label);}
    public void referencesReady(){if(closed)return;preparing=false;failed=false;progress.setVisibility(GONE);retry.setVisibility(GONE);status.setText("Preparing the robot preview…");gate.referencesReady();reveal();}
    public void preparationFailed(Runnable action){if(closed)return;failed=true;progress.setVisibility(GONE);status.setText("Component references could not be prepared. Your assembly progress is saved.");retry.setVisibility(VISIBLE);retry.setOnClickListener(v->{failed=false;preparing=true;retry.setVisibility(GONE);progress.setVisibility(VISIBLE);action.run();});}
    private void reveal(){
        if(closed||paused||!revealing&&!gate.takeReveal())return;revealing=true;
        removeCallbacks(delayed);cube.pauseAnimation();
        menu.setImportantForAccessibility(oldAccessibility);
        Runnable complete=()->{Runnable ready=onRevealed;onRevealed=null;close();if(ready!=null)ready.run();};
        if(BrandingAssets.motion())animate().alpha(0).setDuration(300).withEndAction(complete).start();else complete.run();
    }
    public void pause(){if(closed)return;paused=true;cube.pauseAnimation();animate().cancel();if(revealing)setAlpha(1);}
    public void resume(){if(closed)return;paused=false;cube.resumeAnimation();reveal();}
    @Override public void close(){if(closed)return;closed=true;removeCallbacks(delayed);animate().cancel();cube.close();menu.setImportantForAccessibility(oldAccessibility);if(getParent() instanceof ViewGroup)((ViewGroup)getParent()).removeView(this);}
}

