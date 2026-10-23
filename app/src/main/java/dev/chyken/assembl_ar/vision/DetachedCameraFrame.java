package dev.chyken.assembl_ar.vision;
import android.graphics.Rect;import android.media.Image;import android.graphics.Matrix;import androidx.camera.core.*;import java.nio.ByteBuffer;

/** Owned YUV snapshot: CameraX can release its camera buffer while the detector works. */
final class DetachedCameraFrame implements ImageProxy {
    final long elapsedTimestamp;
    private final int width,height,format;private Rect crop;private final ImageInfo info;private final PlaneProxy[] planes;
    DetachedCameraFrame(ImageProxy source,long elapsedTimestamp){this.elapsedTimestamp=elapsedTimestamp;width=source.getWidth();height=source.getHeight();format=source.getFormat();crop=new Rect(source.getCropRect());
        final ImageInfo original=source.getImageInfo();final long time=original.getTimestamp();final int rotation=original.getRotationDegrees();final Matrix transform=new Matrix(original.getSensorToBufferTransformMatrix());final androidx.camera.core.impl.TagBundle tags=original.getTagBundle();
        info=new ImageInfo(){public long getTimestamp(){return time;}public int getRotationDegrees(){return rotation;}public Matrix getSensorToBufferTransformMatrix(){return new Matrix(transform);}public androidx.camera.core.impl.TagBundle getTagBundle(){return tags;}public void populateExifData(androidx.camera.core.impl.utils.ExifData.Builder b){original.populateExifData(b);}};
        PlaneProxy[] p=source.getPlanes();planes=new PlaneProxy[p.length];for(int i=0;i<p.length;i++){final int row=p[i].getRowStride(),pixel=p[i].getPixelStride();ByteBuffer from=p[i].getBuffer().duplicate();byte[] bytes=new byte[from.remaining()];from.get(bytes);final ByteBuffer data=ByteBuffer.wrap(bytes);planes[i]=new PlaneProxy(){public int getRowStride(){return row;}public int getPixelStride(){return pixel;}public ByteBuffer getBuffer(){return data.duplicate();}};}
    }
    @Override public int getWidth(){return width;}@Override public int getHeight(){return height;}@Override public int getFormat(){return format;}@Override public Rect getCropRect(){return new Rect(crop);}@Override public void setCropRect(Rect r){crop=new Rect(r);}@Override public ImageInfo getImageInfo(){return info;}@Override public PlaneProxy[] getPlanes(){return planes.clone();}@Override public Image getImage(){return null;}@Override public void close(){}
}
