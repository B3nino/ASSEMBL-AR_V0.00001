package dev.chyken.assembl_ar.ui;

import android.animation.*;
import android.app.Activity;
import android.view.*;
import android.widget.*;

/** The supplied logo forms first, then pulses until the first complete robot frame is ready. */
public final class BrandLaunchOverlay extends FrameLayout implements AutoCloseable {
    private final StartupRevealGate gate=new StartupRevealGate(true);
    private final LogoFormationView logo;
    private final TextView status;
    private final View menu;
    private final ProgressBar progress;
    private final com.google.android.material.button.MaterialButton retry;
    private Runnable onRevealed;
    private boolean preparing=true,failed;
    private final int oldAccessibility;
    private ObjectAnimator pulse;
    private boolean closed,paused;
    private final Runnable delayed=this::showDelayed;
    private void showDelayed(){if(!closed&&!preparing&&!failed)status.setText("Preparing the robot preview…");}
    public BrandLaunchOverlay(Activity activity,View menu){
        super(activity);this.menu=menu;oldAccessibility=menu.getImportantForAccessibility();menu.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setBackgroundColor(0xFFFFFFFF);setClickable(true);setFocusable(true);setElevation(UiUtils.dp(activity,30));
        LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER);
        int size=Math.min(UiUtils.dp(activity,400),activity.getResources().getDisplayMetrics().widthPixels-UiUtils.dp(activity,48));
        logo=new LogoFormationView(activity);column.addView(logo,new LinearLayout.LayoutParams(size,Math.round(size*.94f)));
        status=new TextView(activity);status.setText("Preparing your workspace");status.setTextColor(0xFF52665A);status.setTextSize(15);status.setGravity(Gravity.CENTER);status.setPadding(0,UiUtils.dp(activity,18),0,0);column.addView(status);
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);column.addView(progress,new LinearLayout.LayoutParams(Math.min(size,UiUtils.dp(activity,300)),UiUtils.dp(activity,8)));
        retry=new com.google.android.material.button.MaterialButton(activity);retry.setText("Retry preparation");retry.setVisibility(GONE);column.addView(retry);
        addView(column,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        logo.startFormation(1900,()->{
            if(closed)return;
            gate.introFinished();
            if(BrandingAssets.motion()){
                pulse=ObjectAnimator.ofFloat(logo,View.ALPHA,1,.78f,1);pulse.setDuration(1700);pulse.setRepeatCount(ValueAnimator.INFINITE);if(!paused)pulse.start();
            }
            reveal();
        });
        postDelayed(delayed,8000);
    }
    public void modelReady(){if(closed)return;gate.frameReady();reveal();}
    public void setOnRevealed(Runnable ready){onRevealed=ready;}
    public void referenceProgress(int done,int total){if(closed||!preparing||failed)return;progress.setIndeterminate(total==0);progress.setMax(Math.max(1,total));progress.setProgress(done);status.setText(total==0?"Preparing component references…":"Preparing component references · "+done+" / "+total);}
    public void referencesReady(){if(closed)return;preparing=false;failed=false;progress.setVisibility(GONE);retry.setVisibility(GONE);status.setText("Preparing the robot preview…");gate.referencesReady();reveal();}
    public void preparationFailed(Runnable action){if(closed)return;failed=true;progress.setVisibility(GONE);status.setText("Component references could not be prepared. Your assembly progress is saved.");retry.setVisibility(VISIBLE);retry.setOnClickListener(v->{failed=false;preparing=true;retry.setVisibility(GONE);progress.setVisibility(VISIBLE);action.run();});}
    private void reveal(){
        if(closed||paused||!gate.takeReveal())return;
        removeCallbacks(delayed);if(pulse!=null)pulse.cancel();
        menu.setImportantForAccessibility(oldAccessibility);
        Runnable complete=()->{Runnable ready=onRevealed;onRevealed=null;close();if(ready!=null)ready.run();};
        if(BrandingAssets.motion())animate().alpha(0).setDuration(300).withEndAction(complete).start();else complete.run();
    }
    public void pause(){if(closed)return;paused=true;logo.pauseFormation();if(pulse!=null)pulse.pause();}
    public void resume(){if(closed)return;paused=false;logo.resumeFormation();if(pulse!=null){if(pulse.isStarted())pulse.resume();else pulse.start();}reveal();}
    @Override public void close(){if(closed)return;closed=true;removeCallbacks(delayed);animate().cancel();logo.cancelFormation();if(pulse!=null)pulse.cancel();menu.setImportantForAccessibility(oldAccessibility);if(getParent() instanceof ViewGroup)((ViewGroup)getParent()).removeView(this);}
}

