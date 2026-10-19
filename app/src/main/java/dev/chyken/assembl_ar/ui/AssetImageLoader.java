package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AssetImageLoader {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    private AssetImageLoader() {
    }

    public static void load(ImageView target, @Nullable String assetPath) {
        loadBounded(target,assetPath,0);
    }
    public static void loadBounded(ImageView target,@Nullable String assetPath,int maximumDimension) {
        if (assetPath == null || assetPath.isEmpty()) {
            target.setTag(null);
            target.setImageDrawable(null);
            return;
        }
        String key=maximumDimension>0?assetPath+"#"+maximumDimension:assetPath;
        target.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }

        Context applicationContext = target.getContext().getApplicationContext();
        // Never show the previous step's component while its replacement is loading.
        target.setImageDrawable(null);
        WeakReference<ImageView> targetReference = new WeakReference<>(target);
        EXECUTOR.execute(() -> {
            Bitmap bitmap = maximumDimension>0?decodeBounded(applicationContext,assetPath,maximumDimension):decode(applicationContext, assetPath);
            if (bitmap == null) {
                return;
            }
            CACHE.put(key, bitmap);
            ImageView imageView = targetReference.get();
            if (imageView != null) {
                imageView.post(() -> {
                    if (key.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }
    @Nullable private static Bitmap decodeBounded(Context context,String path,int maximum){
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        try(InputStream stream=context.getAssets().open(path)){BitmapFactory.decodeStream(stream,null,options);}catch(IOException failure){return null;}
        options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>maximum)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;
        try(InputStream stream=context.getAssets().open(path)){return BitmapFactory.decodeStream(stream,null,options);}catch(IOException failure){return null;}
    }

    @Nullable
    public static Bitmap decode(Context context, String assetPath) {
        String normalized = assetPath.startsWith("assets/")
                ? assetPath.substring("assets/".length())
                : assetPath;
        try (InputStream stream = context.getAssets().open(normalized)) {
            return BitmapFactory.decodeStream(stream);
        } catch (IOException ignored) {
            return null;
        }
    }
}
