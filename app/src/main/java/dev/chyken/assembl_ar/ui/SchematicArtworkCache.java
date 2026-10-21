package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.util.*;
import dev.chyken.assembl_ar.data.SchematicComponentCatalog.Component;
import dev.chyken.assembl_ar.model.SchematicPlacement;
import dev.chyken.assembl_ar.model.SchematicPlacement.Point;

/** Immutable packaged images; call load on an image worker, never during Canvas drawing. */
final class SchematicArtworkCache {
    private static final Map<String,Bitmap> CACHE=new HashMap<>();
    static synchronized Bitmap load(Context context,Component component)throws IOException{
        Bitmap bitmap=CACHE.get(component.assetPath);if(bitmap!=null)return bitmap;
        try(InputStream input=context.getAssets().open(component.assetPath)){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
            bitmap=BitmapFactory.decodeStream(input,null,options);if(bitmap==null)throw new IOException("Unreadable component artwork");CACHE.put(component.assetPath,bitmap);return bitmap;
        }
    }
    static void draw(Canvas canvas,Bitmap bitmap,SchematicPlacement p,Paint paint,Matrix matrix){
        Point a=p.map(0,0),b=p.map(1,0),c=p.map(0,1);
        matrix.setPolyToPoly(new float[]{0,0,bitmap.getWidth(),0,0,bitmap.getHeight()},0,new float[]{(float)a.x,(float)a.y,(float)b.x,(float)b.y,(float)c.x,(float)c.y},0,3);
        int saved=canvas.save();canvas.clipRect((float)p.left,(float)p.top,(float)(p.left+p.width),(float)(p.top+p.height));paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);canvas.drawBitmap(bitmap,matrix,paint);canvas.restoreToCount(saved);
    }
}
