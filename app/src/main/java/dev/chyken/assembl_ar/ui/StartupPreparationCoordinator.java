package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import dev.chyken.assembl_ar.vision.VisionPreloadManager;
import java.lang.ref.WeakReference;

/** One startup gate shared by home and process-restored camera screens. */
public final class StartupPreparationCoordinator implements AutoCloseable {
    private final VisionPreloadManager preload;
    private final BrandLaunchOverlay overlay;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private boolean closed,paused;
    private final Runnable tick=new Runnable(){public void run(){if(closed||paused)return;overlay.referenceProgress(preload.completedCount(),preload.totalCount());if(!preload.isReady())ui.postDelayed(this,250);}};
    public StartupPreparationCoordinator(Activity activity,BrandLaunchOverlay overlay){this.overlay=overlay;preload=VisionPreloadManager.get(activity);start();}
    private void start(){
        if(closed)return;ui.removeCallbacks(tick);ui.post(tick);
        WeakReference<StartupPreparationCoordinator> owner=new WeakReference<>(this);
        preload.start().whenComplete((refs,error)->{
            StartupPreparationCoordinator current=owner.get();if(current==null)return;
            current.ui.post(()->{StartupPreparationCoordinator c=owner.get();if(c==null||c.closed)return;c.ui.removeCallbacks(c.tick);if(error==null)c.overlay.referencesReady();else c.overlay.preparationFailed(c::start);});
        });
    }
    public void resume(){paused=false;overlay.resume();ui.removeCallbacks(tick);ui.post(tick);}
    public void pause(){paused=true;ui.removeCallbacks(tick);overlay.pause();}
    @Override public void close(){closed=true;ui.removeCallbacksAndMessages(null);overlay.close();}
}
