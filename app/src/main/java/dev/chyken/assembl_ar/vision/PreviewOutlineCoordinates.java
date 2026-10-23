package dev.chyken.assembl_ar.vision;
import java.util.List;
/** Exact fill-center mapping for the dimensions carried by EACH camera callback. */
public final class PreviewOutlineCoordinates {
    private PreviewOutlineCoordinates(){}
    public static float[] normalize(List<VisionPoint> points,int width,int height,int viewWidth,int viewHeight){
        if(points==null||points.size()<4||width<1||height<1)return null;
        viewWidth=Math.max(1,viewWidth);viewHeight=Math.max(1,viewHeight);
        double scale=Math.max(viewWidth/(double)width,viewHeight/(double)height),x=(viewWidth-width*scale)/2,y=(viewHeight-height*scale)/2;
        float[] output=new float[points.size()*2];for(int i=0;i<points.size();i++){output[i*2]=(float)((points.get(i).getX()*scale+x)/viewWidth);output[i*2+1]=(float)((points.get(i).getY()*scale+y)/viewHeight);}return output;
    }
}
