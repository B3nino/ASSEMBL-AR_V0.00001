package dev.chyken.assembl_ar;

/** Process-owned startup; reference preparation never blocks an Activity or camera. */
public final class AssemblArApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        dev.chyken.assembl_ar.vision.VisionPreloadManager.get(this).start();
    }
    @Override public void onLowMemory(){super.onLowMemory();dev.chyken.assembl_ar.ui.AssetImageLoader.trimMemory();}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level==TRIM_MEMORY_RUNNING_LOW||level==TRIM_MEMORY_RUNNING_CRITICAL||level>=TRIM_MEMORY_BACKGROUND)dev.chyken.assembl_ar.ui.AssetImageLoader.trimMemory();}
}
