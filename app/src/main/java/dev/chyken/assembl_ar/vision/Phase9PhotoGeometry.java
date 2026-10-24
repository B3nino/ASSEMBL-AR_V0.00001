package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase9AssetCatalog;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Source-photo metadata: isolate each physical part, not the unchanged whole chassis. */
final class Phase9PhotoGeometry {
    static final int VERSION=3;
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes,java.io.File cache){
        if(dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(source.getJpegAssetPath())!=null)return source;
        java.io.File sidecar=cache==null?null:new java.io.File(cache.getPath()+".regions2");List<List<VisionPoint>> regions=ReferenceRegionCache.read(sidecar);
        if(regions!=null)try{return withRegions(source,regions);}catch(IllegalArgumentException corrupt){/* Rebuild from the original photo. */}
        ComponentReferenceSpec resolved=resolve(source,bytes);ReferenceRegionCache.write(sidecar,resolved.getFeatureRegionsNormalized());return resolved;
    }
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes){
        if(dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(source.getJpegAssetPath())!=null)return source;
        MatOfByte encoded=new MatOfByte(bytes);Mat bgr=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);
        try{
            if(bgr.empty())throw new IllegalArgumentException("Unreadable Phase 9 reference");
            double s=Math.min(1,1280.0/Math.max(bgr.cols(),bgr.rows()));if(s<1)Imgproc.resize(bgr,bgr,new Size(),s,s,Imgproc.INTER_AREA);
            String role=Phase9AssetCatalog.referenceRole(source.getReferenceId());List<Rect> yellow=colored(bgr,new Scalar(12,80,65),new Scalar(42,255,255),9);
            List<List<VisionPoint>> regions=new ArrayList<>();
            if(Phase9AssetCatalog.installed(role)){
                Rect left=null,right=null;double mid=bgr.cols()*.5;
                for(Rect r:yellow)if(r.area()>bgr.total()*.012&&r.height>bgr.rows()*.15){if(r.x+r.width*.5<mid&&left==null)left=r;else if(r.x+r.width*.5>=mid&&right==null)right=r;}
                if(left==null||right==null)throw new IllegalArgumentException("Both motor bodies must be visible in source: "+source.getJpegAssetPath());
                Rect l=wheelSide(bgr,left,true),r=wheelSide(bgr,right,false);
                double cx=(left.x+left.width+right.x)*.5,cy=(left.y+right.y)*.5+(left.height+right.height)*.05;
                Rect stand=null;double rank=-1;
                for(Rect part:yellow){if(part.width>bgr.cols()*.10||part.height>bgr.rows()*.12||part.area()<20||part.x<left.x+left.width||part.x+part.width>right.x)continue;
                    double value=part.area()/(1+Math.hypot(part.x+part.width*.5-cx,part.y+part.height*.5-cy));if(value>rank){rank=value;stand=part;}}
                if(stand==null)throw new IllegalArgumentException("Center brass standoff not visible in source: "+source.getJpegAssetPath());
                regions.add(normalized(l,bgr));regions.add(normalized(r,bgr));regions.add(normalized(pad(stand,.65,bgr),bgr));
                regions.add(rect(.02,.02,.98,.98));
            }else{
                if(role.equals("standoff")){
                    Rect best=null;for(Rect r:yellow)if(r.area()>bgr.total()*.001&&Math.max(r.width,r.height)>Math.min(r.width,r.height)*2.5){best=r;break;}
                    if(best==null)throw new IllegalArgumentException("Brass standoff not localized: "+source.getJpegAssetPath());
                    regions.add(normalized(pad(best,.22,bgr),bgr));
                }else{
                    List<Rect> wheels=new ArrayList<>();for(Rect r:yellow)if(r.area()>bgr.total()*.008&&r.width/(double)r.height>.55&&r.width/(double)r.height<1.8){wheels.add(pad(r,.18,bgr));if(wheels.size()==(role.equals("pair")?2:1))break;}
                    if(wheels.size()!=(role.equals("pair")?2:1))throw new IllegalArgumentException("Wheel hub not localized: "+source.getJpegAssetPath());
                    wheels.sort(Comparator.comparingInt(r->r.x));for(Rect r:wheels)regions.add(normalized(r,bgr));
                }
            }
            return withRegions(source,regions);
        }finally{encoded.release();bgr.release();}
    }
    private static ComponentReferenceSpec withRegions(ComponentReferenceSpec source,List<List<VisionPoint>> regions){
        String role=Phase9AssetCatalog.referenceRole(source.getReferenceId());boolean installed=Phase9AssetCatalog.installed(role);int expected=installed?4:"pair".equals(role)?2:1;
        if(regions.size()!=expected)throw new IllegalArgumentException("Incorrect Phase 9 region count");
        double l=1,t=1,r=0,b=0;for(List<VisionPoint> region:regions){if(region.size()!=4)throw new IllegalArgumentException("Expected rectangular region");for(VisionPoint p:region){l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}}
        List<VisionPoint> plane=installed?rect(0,0,1,1):rect(l,t,r,b);
        return ComponentReferenceSpec.builder(source.getReferenceId(),source.getComponentId(),source.getDisplayName(),source.getPhysicalWidthMm(),source.getPhysicalHeightMm())
            .jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(9).maximumImageDimension(1280)
            .planarReferenceCornersNormalized(plane.get(0),plane.get(1),plane.get(2),plane.get(3)).featureRegionsNormalized(regions).polarityAnchorsVerified(false).build();
    }
    static List<Rect> colored(Mat bgr,Scalar low,Scalar high,int size){
        Mat hsv=new Mat(),mask=new Mat(),hierarchy=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(size,size));List<MatOfPoint> contours=new ArrayList<>();
        try{Imgproc.cvtColor(bgr,hsv,Imgproc.COLOR_BGR2HSV);Core.inRange(hsv,low,high,mask);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            List<Rect> result=new ArrayList<>();for(MatOfPoint c:contours)if(Imgproc.contourArea(c)>8)result.add(Imgproc.boundingRect(c));result.sort((a,b)->Double.compare(b.area(),a.area()));return result;
        }finally{for(Mat c:contours)c.release();hsv.release();mask.release();hierarchy.release();kernel.release();}
    }
    private static Rect wheelSide(Mat bgr,Rect motor,boolean left){
        int edge=(int)(left?motor.x+motor.width*.15:motor.x+motor.width*.85);
        Rect search=new Rect(left?0:edge,Math.max(0,motor.y-motor.height/3),left?Math.max(1,edge):Math.max(1,bgr.cols()-edge),Math.min(bgr.rows(),motor.y+motor.height+motor.height/4)-Math.max(0,motor.y-motor.height/3));
        Mat crop=bgr.submat(search);Rect best=null;
        try{for(Rect r:colored(crop,new Scalar(0,0,0),new Scalar(180,255,115),21))if(r.height>bgr.rows()*.18&&r.area()>bgr.total()*.006&&r.height>r.width){best=new Rect(r.x+search.x,r.y+search.y,r.width,r.height);break;}}finally{crop.release();}
        if(best!=null)return pad(best,.04,bgr);
        // A missing wheel is a legitimate negative reference. Its predicted outer footprint
        // supplies context metadata only and is never registered as a positive wheel body.
        return bounded(left?motor.x-bgr.cols()*.20:motor.x+motor.width*.85,motor.y-motor.height*.10,left?motor.x+motor.width*.15:motor.x+motor.width+bgr.cols()*.20,motor.y+motor.height*1.10,bgr);
    }
    static Rect pad(Rect r,double amount,Mat image){return bounded(r.x-r.width*amount,r.y-r.height*amount,r.x+r.width*(1+amount),r.y+r.height*(1+amount),image);}
    static Rect bounded(double l,double t,double r,double b,Mat image){int x=Math.max(0,(int)l),y=Math.max(0,(int)t),right=Math.min(image.cols(),(int)Math.ceil(r)),bottom=Math.min(image.rows(),(int)Math.ceil(b));return new Rect(x,y,Math.max(1,right-x),Math.max(1,bottom-y));}
    private static List<VisionPoint> normalized(Rect r,Mat im){return rect(r.x/(double)im.cols(),r.y/(double)im.rows(),(r.x+r.width)/(double)im.cols(),(r.y+r.height)/(double)im.rows());}
    private static List<VisionPoint> rect(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}
}
