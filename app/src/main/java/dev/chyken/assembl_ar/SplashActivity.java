package dev.chyken.assembl_ar;

import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import dev.chyken.assembl_ar.ui.*;

/** Startup recovery for a camera Activity restored into a new process. */
public final class SplashActivity extends AppCompatActivity {
    private StartupPreparationCoordinator startup;
    @Override public void onCreate(Bundle state){super.onCreate(state);UiUtils.configureStandardWindow(this);setContentView(R.layout.activity_splash);FrameLayout root=findViewById(R.id.startupRoot);FrameLayout background=findViewById(R.id.startupBackdrop);BrandLaunchOverlay overlay=new BrandLaunchOverlay(this,background);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));overlay.setOnRevealed(()->{setResult(RESULT_OK);finish();});overlay.modelReady();startup=new StartupPreparationCoordinator(this,overlay);}
    @Override public void onResume(){super.onResume();if(startup!=null)startup.resume();}
    @Override public void onPause(){if(startup!=null)startup.pause();super.onPause();}
    @Override public void onDestroy(){if(startup!=null)startup.close();super.onDestroy();}
}
