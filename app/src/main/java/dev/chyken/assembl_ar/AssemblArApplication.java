package dev.chyken.assembl_ar;

/** Process-owned startup; reference preparation never blocks an Activity or camera. */
public final class AssemblArApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        dev.chyken.assembl_ar.vision.VisionPreloadManager.get(this).start();
    }
}
