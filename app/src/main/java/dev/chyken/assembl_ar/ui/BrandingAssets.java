package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/** Decode the provided logo on a worker before its formation animation starts. */
public final class BrandingAssets {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"branding-assets");t.setDaemon(true);return t;});
    private static volatile Bitmap logo;
    private BrandingAssets(){}
    public static void logo(LogoFormationView view){
        Bitmap cached=logo;if(cached!=null){view.setArtwork(cached);return;}
        Context app=view.getContext().getApplicationContext();WeakReference<LogoFormationView> target=new WeakReference<>(view);
        WORKER.execute(()->{
            try{if(logo==null)try(java.io.InputStream input=app.getAssets().open("branding/assembl_ar_logo.png")){
                BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;logo=BitmapFactory.decodeStream(input,null,options);
            }}catch(java.io.IOException ignored){/* A bundled vector remains a fallback for a damaged asset. */}
            LogoFormationView image=target.get();if(image!=null)image.post(()->{LogoFormationView current=target.get();if(current!=null)current.setArtwork(logo);});
        });
    }
    public static boolean motion(){return android.os.Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled();}
}

