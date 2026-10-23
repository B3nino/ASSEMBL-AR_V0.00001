package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase2AssetCatalog;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.util.*;

/** Scene descriptors exclude the surrounding tabletop; top views include the whole board. */
final class Phase2PhotoGeometry {
    static final int CACHE_VERSION=6;
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes,File cache) throws IOException {
        File roiFile=cache==null?null:new File(cache.getPath()+".p2roi"+CACHE_VERSION);
        File appearanceFile=cache==null?null:new File(cache.getPath()+".p2silhouette"+CACHE_VERSION);
        List<List<VisionPoint>> regions=new ArrayList<>();
        if(roiFile!=null && roiFile.isFile()) try(DataInputStream in=new DataInputStream(new FileInputStream(roiFile))) {
            int count=in.readInt(); if(count<1||count>8) throw new IOException("Invalid ROI cache");
            for(int i=0;i<count;i++) regions.add(rect(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble()));
            if("top".equals(Phase2AssetCatalog.referenceRole(source.getReferenceId()))&&!Phase2AppearanceTemplates.load(source.getReferenceId(),appearanceFile))throw new IOException("Bracket template needs preparation");
            return build(source,regions);
        } catch(IOException|IllegalArgumentException stale) { regions.clear(); }
        MatOfByte data=new MatOfByte(bytes);
        Mat bgr=Imgcodecs.imdecode(data,Imgcodecs.IMREAD_COLOR),hsv=new Mat(),mask=new Mat(),hierarchy=new Mat();
        List<MatOfPoint> contours=new ArrayList<>();
        try {
            if(bgr.empty()) throw new IOException("Unreadable Phase 2 photo");
            double scale=Math.min(1,960.0/Math.max(bgr.cols(),bgr.rows()));
            if(scale<1) Imgproc.resize(bgr,bgr,new Size(),scale,scale,Imgproc.INTER_AREA);
            String role=Phase2AssetCatalog.referenceRole(source.getReferenceId());
            if("brackets".equals(role)) {
                Imgproc.cvtColor(bgr,hsv,Imgproc.COLOR_BGR2GRAY);
                Imgproc.threshold(hsv,mask,90,255,Imgproc.THRESH_BINARY_INV);
            } else {
                Imgproc.cvtColor(bgr,hsv,Imgproc.COLOR_BGR2HSV);
                Core.inRange(hsv,"top".equals(role)?new Scalar(18,110,95):new Scalar(15,65,45),"top".equals(role)?new Scalar(42,255,255):new Scalar(43,255,255),mask);
            }
            Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(5,5));
            try { Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel); } finally {kernel.release();}
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            List<Rect> boxes=new ArrayList<>();
            for(MatOfPoint contour:contours) {
                Rect r=Imgproc.boundingRect(contour);
                if(Imgproc.contourArea(contour)<bgr.total()*.0015 || r.area()>bgr.total()*.85) continue;
                boxes.add(r);
            }
            boxes.sort((a,b)->Double.compare(b.area(),a.area()));
            int maximum="brackets".equals(role)?4:("left".equals(role)||"right".equals(role))?1:2;
            for(Rect box:boxes.subList(0,Math.min(maximum,boxes.size()))) {
                if("left".equals(role)||"right".equals(role)){
                    // The old padding included unchanged PCB printing. Side evidence must
                    // describe the motor itself, not the board surrounding its mounting site.
                    regions.add(rect(box.x/(double)bgr.cols(),box.y/(double)bgr.rows(),(box.x+box.width)/(double)bgr.cols(),(box.y+box.height)/(double)bgr.rows()));continue;
                }
                double pad=Math.max(box.width,box.height)*.40;
                regions.add(rect(Math.max(0,(box.x-pad)/bgr.cols()),Math.max(0,(box.y-pad)/bgr.rows()),
                        Math.min(1,(box.x+box.width+pad)/bgr.cols()),Math.min(1,(box.y+box.height+pad)/bgr.rows())));
            }
            if("top".equals(role)) {
                // Yellow gearbox bounds locate the hardware in each actual photo. No fixed
                // whole-photo rectangle may stand in for motor evidence.
                if(boxes.size()<2) throw new IOException("Top reference does not expose both motor housings");
                regions.clear();
                for(Rect box:boxes.subList(0,2)) {
                    regions.add(rect(box.x/(double)bgr.cols(),box.y/(double)bgr.rows(),(box.x+box.width)/(double)bgr.cols(),(box.y+box.height)/(double)bgr.rows()));
                    double pad=Math.min(box.width,box.height)*.35;
                    if(box.height>=box.width){
                        double top=(box.y+box.height*.15)/bgr.rows(),bottom=(box.y+box.height*.85)/bgr.rows();
                        regions.add(rect(Math.max(0,(box.x-pad)/bgr.cols()),top,box.x/(double)bgr.cols(),bottom));
                        regions.add(rect((box.x+box.width)/(double)bgr.cols(),top,Math.min(1,(box.x+box.width+pad)/bgr.cols()),bottom));
                    }else{
                        double left=(box.x+box.width*.15)/bgr.cols(),right=(box.x+box.width*.85)/bgr.cols();
                        regions.add(rect(left,Math.max(0,(box.y-pad)/bgr.rows()),right,box.y/(double)bgr.rows()));
                        regions.add(rect(left,(box.y+box.height)/(double)bgr.rows(),right,Math.min(1,(box.y+box.height+pad)/bgr.rows())));
                    }
                }
            }
            if("top".equals(role))for(int i=0;i<regions.size();i++)if(i%3!=0)regions.set(i,bracketBounds(bgr,regions.get(i)));
            if("top".equals(role))Phase2AppearanceTemplates.capture(source.getReferenceId(),bgr,regions,appearanceFile);
            if(regions.isEmpty()) regions.add(rect(.08,.08,.92,.92)); // negative empty-board scenes remain diagnostic
        } finally { for(Mat c:contours)c.release(); bgr.release();hsv.release();mask.release();hierarchy.release();data.release(); }
        ComponentReferenceSpec result=build(source,regions);
        if(roiFile!=null) try(DataOutputStream out=new DataOutputStream(new FileOutputStream(roiFile))) {
            out.writeInt(regions.size()); for(List<VisionPoint> r:regions) {
                out.writeDouble(r.get(0).getX());out.writeDouble(r.get(0).getY());
                out.writeDouble(r.get(2).getX());out.writeDouble(r.get(2).getY());
            }
        } catch(IOException ignored) { }
        return result;
    }
    /** Tight dark plastic bounds exclude neighboring PCB lettering from bracket evidence. */
    private static List<VisionPoint> bracketBounds(Mat image,List<VisionPoint> region)throws IOException {
        int x=Math.max(0,(int)(region.get(0).getX()*image.cols())),y=Math.max(0,(int)(region.get(0).getY()*image.rows()));
        int right=Math.min(image.cols(),(int)Math.ceil(region.get(2).getX()*image.cols())),bottom=Math.min(image.rows(),(int)Math.ceil(region.get(2).getY()*image.rows()));
        Rect roi=new Rect(x,y,right-x,bottom-y);Mat crop=image.submat(roi),gray=new Mat(),mask=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        try{Imgproc.cvtColor(crop,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.threshold(gray,mask,110,255,Imgproc.THRESH_BINARY_INV);Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            Rect best=null;double area=0;for(MatOfPoint contour:contours){Rect b=Imgproc.boundingRect(contour);double a=Imgproc.contourArea(contour);if(a>roi.area()*.02&&Math.max(b.width,b.height)>Math.max(roi.width,roi.height)*.2&&a>area){best=b;area=a;}}
            if(best==null)throw new IOException("Top reference does not expose a bracket silhouette");
            return rect((x+best.x)/(double)image.cols(),(y+best.y)/(double)image.rows(),(x+best.x+best.width)/(double)image.cols(),(y+best.y+best.height)/(double)image.rows());
        }finally{for(Mat c:contours)c.release();crop.release();gray.release();mask.release();hierarchy.release();}
    }
    private static ComponentReferenceSpec build(ComponentReferenceSpec s,List<List<VisionPoint>> regions) {
        return ComponentReferenceSpec.builder(s.getReferenceId(),s.getComponentId(),s.getDisplayName(),
                s.getPhysicalWidthMm(),s.getPhysicalHeightMm()).jpegAssetPath(s.getJpegAssetPath())
                .assemblyStepNumber(2).maximumImageDimension(960).featureRegionsNormalized(regions)
                .polarityAnchorsVerified(false).build();
    }
    private static List<VisionPoint> rect(double x,double y,double r,double b) {
        if(!Double.isFinite(x+y+r+b)||x<0||y<0||r>1||b>1||r<=x||b<=y) throw new IllegalArgumentException("Invalid ROI");
        return Arrays.asList(new VisionPoint(x,y),new VisionPoint(r,y),new VisionPoint(r,b),new VisionPoint(x,b));
    }
}
