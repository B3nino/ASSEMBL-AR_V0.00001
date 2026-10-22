package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase7AssetCatalog;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Source-only localization: live acceptance still requires distributed, geometric ORB evidence. */
final class Phase7PhotoGeometry {
    static final int VERSION=6;
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source, byte[] bytes, java.io.File cache) {
        java.io.File geometry = cache == null ? null : new java.io.File(cache.getPath() + ".regions1");
        List<List<VisionPoint>> regions = ReferenceRegionCache.read(geometry);
        if (regions != null) {
            try { return withRegions(source, regions); }
            catch (IllegalArgumentException corrupt) { /* Rebuild from the source photo below. */ }
        }
        ComponentReferenceSpec resolved = resolve(source, bytes);
        ReferenceRegionCache.write(geometry, resolved.getFeatureRegionsNormalized());
        return resolved;
    }

    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes){
        String role=Phase7AssetCatalog.referenceRole(source.getReferenceId());boolean installed=Phase7AssetCatalog.installed(role),negative=Phase7AssetCatalog.negative(role);
        MatOfByte encoded=new MatOfByte(bytes);Mat bgr=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR),gray=new Mat(),hsv=new Mat(),blue=new Mat(),mask=new Mat(),hierarchy=new Mat();
        List<MatOfPoint> contours=new ArrayList<>();List<Rect> trackers=new ArrayList<>();Rect ball=null;double ballScore=0;
        try{
            if(bgr.empty())throw new IllegalArgumentException("Unreadable Phase 7 photo");
            double scale=Math.min(1,1280.0/Math.max(bgr.cols(),bgr.rows()));if(scale<1)Imgproc.resize(bgr,bgr,new Size(),scale,scale,Imgproc.INTER_AREA);
            Imgproc.cvtColor(bgr,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.cvtColor(bgr,hsv,Imgproc.COLOR_BGR2HSV);Core.inRange(hsv,new Scalar(85,60,30),new Scalar(135,255,255),blue);
            for(int threshold:new int[]{80,110,140,170}){
                Imgproc.threshold(gray,mask,threshold,255,Imgproc.THRESH_BINARY_INV);
                Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));try{Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);}finally{kernel.release();}
                for(Mat c:contours)c.release();contours.clear();Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
                for(MatOfPoint c:contours){
                    Rect r=Imgproc.boundingRect(c);double area=Imgproc.contourArea(c),aspect=Math.max(r.width,r.height)/(double)Math.max(1,Math.min(r.width,r.height));
                    if(area<bgr.total()*.0006||area>bgr.total()*.28)continue;
                    if(aspect<2&&area/r.area()>.4&&(!installed||
                            (r.x+r.width*.5>bgr.cols()*.25&&r.x+r.width*.5<bgr.cols()*.75
                            &&r.y+r.height*.5<bgr.rows()*.45&&r.width<bgr.cols()*.18&&r.height<bgr.rows()*.18))){
                        double cx=(r.x+r.width*.5)/bgr.cols(),cy=(r.y+r.height*.5)/bgr.rows();
                        double score=area/(1+3*Math.abs(cx-.5)+3*Math.abs(cy-(installed?.22:.53)));
                        if(score>ballScore){ballScore=score;ball=r;}
                    }
                    if(aspect>1.8&&aspect<10&&area>bgr.total()*.001){
                        Mat patch=blue.submat(r);int n;try{n=Core.countNonZero(patch);}finally{patch.release();}
                        if(n>=8||negative&&!installed)trackers.add(r);
                    }
                }
            }
            List<List<VisionPoint>> regions=new ArrayList<>();
            if("trackerwork".equals(role))regions.addAll(Phase7TrackerPhotoGeometry.regions(bgr));
            else if(installed&&negative)regions.add(rect(.04,.04,.96,.96));
            else if(role.startsWith("caster")){
                if(ball!=null)regions.add(installed?casterPlate(ball,bgr.cols(),bgr.rows()):box(ball,bgr.cols(),bgr.rows(),.12));
                else regions.add(installed?rect(0,0,.002,.002):rect(.20,.20,.80,.82));
            }else{
                trackers.sort((a,b)->Double.compare(b.area(),a.area()));List<Rect> selected=new ArrayList<>();
                for(Rect r:trackers){boolean overlaps=false;for(Rect used:selected)if(overlap(r,used)>.30){overlaps=true;break;}if(!overlaps){selected.add(r);regions.add(box(r,bgr.cols(),bgr.rows(),.01));}if(selected.size()==2)break;}
                if(regions.isEmpty())regions.add(rect(0,0,.002,.002)); // fail closed when a required part cannot be localized
            }
            return withRegions(source, regions);
        }finally{for(Mat c:contours)c.release();encoded.release();bgr.release();gray.release();hsv.release();blue.release();mask.release();hierarchy.release();}
    }
    private static ComponentReferenceSpec withRegions(ComponentReferenceSpec source, List<List<VisionPoint>> regions) {
        return ComponentReferenceSpec.builder(source.getReferenceId(), source.getComponentId(),
                source.getDisplayName(), source.getPhysicalWidthMm(), source.getPhysicalHeightMm())
                .jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(7).maximumImageDimension(1280)
                .featureRegionsNormalized(regions).polarityAnchorsVerified(false).build();
    }

    private static double overlap(Rect a,Rect b){double intersection=Math.max(0,Math.min(a.x+a.width,b.x+b.width)-Math.max(a.x,b.x))*Math.max(0,Math.min(a.y+a.height,b.y+b.height)-Math.max(a.y,b.y));return intersection/Math.max(1,Math.min(a.area(),b.area()));}
    private static List<VisionPoint> casterPlate(Rect ball,int width,int height){
        // Include the plate and nut positions: a reflective steel ball alone has few stable
        // descriptors as the viewpoint changes. Keep the surrounding board outside this gate.
        return rect(Math.max(0,(ball.x-ball.width*.85)/width),Math.max(0,(ball.y-ball.height*.20)/height),
                Math.min(1,(ball.x+ball.width*1.85)/width),Math.min(1,(ball.y+ball.height*1.20)/height));
    }
    private static List<VisionPoint> box(Rect r,int width,int height,double pad){return rect(Math.max(0,(r.x-pad*r.width)/width),Math.max(0,(r.y-pad*r.height)/height),Math.min(1,(r.x+(1+pad)*r.width)/width),Math.min(1,(r.y+(1+pad)*r.height)/height));}
    private static List<VisionPoint> rect(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}
}

