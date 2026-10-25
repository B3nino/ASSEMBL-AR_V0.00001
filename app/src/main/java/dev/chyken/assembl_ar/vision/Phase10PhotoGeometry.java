package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase10AssetCatalog;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;
import java.io.*;

/** Source-only ROIs: standalone part, PCB, protector, and separate chassis wheel footprints. */
final class Phase10PhotoGeometry {
    static final int VERSION=4;
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes,File cache)throws IOException{
        if(dev.chyken.assembl_ar.camera.Phase1011VideoReferences.annotation(source.getJpegAssetPath())!=null)return source;
        File sidecar=cache==null?null:new File(cache.getPath()+".p10regions1");List<List<VisionPoint>> saved=ReferenceRegionCache.read(sidecar);
        if(saved!=null)try{return withRegions(source,saved);}catch(IllegalArgumentException corrupt){/* Rebuild source metadata. */}
        String role=Phase10AssetCatalog.referenceRole(source.getReferenceId());boolean board=role.equals("extension")||role.equals("extensionbad")||role.equals("protected")||role.equals("stacked");
        MatOfByte encoded=new MatOfByte(bytes);Mat gray=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_GRAYSCALE);
        try{
            if(gray.empty())throw new IOException("Unreadable Phase 10 photo: "+source.getJpegAssetPath());double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));if(scale<1)Imgproc.resize(gray,gray,new Size(),scale,scale,Imgproc.INTER_AREA);
            List<List<VisionPoint>> regions=new ArrayList<>();List<Rect> wheels=new ArrayList<>();Rect pcb=null;
            if(role.equals("stacked"))for(boolean left:new boolean[]{true,false}){
                Rect search=bounded(left?0:gray.cols()*.75,0,left?gray.cols()*.30:gray.cols(),gray.rows(),gray);Mat crop=gray.submat(search);Rect wheel;
                try{wheel=darkPart(crop,gray.cols()*.04,gray.rows()*.23,false);}finally{crop.release();}
                if(wheel==null)throw new IOException("Chassis context not localized in "+source.getJpegAssetPath());wheel.x+=search.x;wheel.y+=search.y;wheels.add(wheel);
            }
            if(board){
                Rect search=wheels.isEmpty()?new Rect(0,0,gray.cols(),gray.rows()):bounded(wheels.get(0).x+wheels.get(0).width*.8,0,wheels.get(1).x+wheels.get(1).width*.2,gray.rows(),gray);
                pcb=printingBounds(gray,search);regions.add(normalized(pcb,gray));
            }else regions.add(normalized(foreground(gray,role.equals("cable")),gray));
            if(role.equals("protected")||role.equals("stacked"))regions.add(normalized(protector(gray,pcb),gray));
            for(Rect wheel:wheels)regions.add(normalized(pad(wheel,.03,gray),gray));
            ComponentReferenceSpec result=withRegions(source,regions);ReferenceRegionCache.write(sidecar,regions);return result;
        }finally{gray.release();encoded.release();}
    }
    /** Dark silkscreen bounds remain observable when rounded white PCB edges blend into paper. */
    private static Rect printingBounds(Mat gray,Rect search)throws IOException{
        Mat crop=gray.submat(search),mask=new Mat(),hierarchy=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(31,31));List<MatOfPoint> contours=new ArrayList<>();Rect union=null;
        try{Imgproc.threshold(crop,mask,90,255,Imgproc.THRESH_BINARY_INV);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            for(MatOfPoint c:contours){Rect r=Imgproc.boundingRect(c);if(Imgproc.contourArea(c)<gray.total()*.001||r.y<=2||r.y+r.height>=gray.rows()-2)continue;r.x+=search.x;r.y+=search.y;union=union==null?r:bounded(Math.min(union.x,r.x),Math.min(union.y,r.y),Math.max(union.x+union.width,r.x+r.width),Math.max(union.y+union.height,r.y+r.height),gray);}
            if(union==null)throw new IOException("Extension PCB printing not localized");return pad(union,.02,gray);
        }finally{for(Mat c:contours)c.release();crop.release();mask.release();hierarchy.release();kernel.release();}
    }
    private static Rect protector(Mat gray,Rect pcb)throws IOException{
        Rect search=bounded(pcb.x+pcb.width*.22,pcb.y+pcb.height*.42,pcb.x+pcb.width*.82,pcb.y+pcb.height*.94,gray);Mat crop=gray.submat(search),mask=new Mat(),hierarchy=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));List<MatOfPoint> contours=new ArrayList<>();Rect union=null;
        try{Imgproc.threshold(crop,mask,60,255,Imgproc.THRESH_BINARY_INV);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            for(MatOfPoint c:contours){Rect r=Imgproc.boundingRect(c);double ratio=r.width/(double)r.height;if(r.width<=pcb.width*.09||r.height<=pcb.height*.12||ratio<=.25||ratio>=2)continue;r.x+=search.x;r.y+=search.y;union=union==null?r:bounded(Math.min(union.x,r.x),Math.min(union.y,r.y),Math.max(union.x+union.width,r.x+r.width),Math.max(union.y+union.height,r.y+r.height),gray);}
            if(union==null)throw new IOException("OLED protector not localized");return pad(union,.04,gray);
        }finally{for(Mat c:contours)c.release();crop.release();mask.release();hierarchy.release();kernel.release();}
    }
    private static Rect foreground(Mat gray,boolean cable)throws IOException{
        Mat mask=new Mat(),hierarchy=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(cable?19:5,5));List<MatOfPoint> contours=new ArrayList<>();Rect union=null;
        try{if(cable)Imgproc.threshold(gray,mask,0,255,Imgproc.THRESH_BINARY_INV|Imgproc.THRESH_OTSU);else Imgproc.threshold(gray,mask,110,255,Imgproc.THRESH_BINARY_INV);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            for(MatOfPoint c:contours){Rect r=Imgproc.boundingRect(c);if(Imgproc.contourArea(c)<gray.total()*.001||r.x<3||r.y<3||r.x+r.width>gray.cols()-3||r.y+r.height>gray.rows()-3)continue;
                if(union==null)union=r;else union=bounded(Math.min(union.x,r.x),Math.min(union.y,r.y),Math.max(union.x+union.width,r.x+r.width),Math.max(union.y+union.height,r.y+r.height),gray);
            }if(union==null||union.area()>gray.total()*.90)throw new IOException("Standalone Phase 10 object not localized");return pad(union,.04,gray);
        }finally{for(Mat c:contours)c.release();mask.release();hierarchy.release();kernel.release();}
    }
    private static Rect darkPart(Mat gray,double minWidth,double minHeight,boolean protector){
        Mat mask=new Mat(),hierarchy=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(5,5));List<MatOfPoint> contours=new ArrayList<>();Rect best=null;double area=0;
        try{Imgproc.threshold(gray,mask,100,255,Imgproc.THRESH_BINARY_INV);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            for(MatOfPoint c:contours){Rect r=Imgproc.boundingRect(c);double ratio=r.width/(double)r.height;if(r.width<minWidth||r.height<minHeight||protector&&(ratio<.6||ratio>1.8))continue;if(r.area()>area){best=r;area=r.area();}}return best;
        }finally{for(Mat c:contours)c.release();mask.release();hierarchy.release();kernel.release();}
    }
    private static ComponentReferenceSpec withRegions(ComponentReferenceSpec source,List<List<VisionPoint>> regions){String role=Phase10AssetCatalog.referenceRole(source.getReferenceId());int expected=role.equals("stacked")?4:role.equals("protected")?2:1;if(regions.size()!=expected)throw new IllegalArgumentException("Phase 10 region count");List<VisionPoint> c=regions.get(0);
        return ComponentReferenceSpec.builder(source.getReferenceId(),source.getComponentId(),source.getDisplayName(),source.getPhysicalWidthMm(),source.getPhysicalHeightMm()).jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(10).maximumImageDimension(1280).planarReferenceCornersNormalized(c.get(0),c.get(1),c.get(2),c.get(3)).featureRegionsNormalized(regions).polarityAnchorsVerified(false).build();
    }
    private static Rect bounded(double l,double t,double r,double b,Mat im){int x=Math.max(0,(int)l),y=Math.max(0,(int)t),right=Math.min(im.cols(),(int)Math.ceil(r)),bottom=Math.min(im.rows(),(int)Math.ceil(b));return new Rect(x,y,Math.max(1,right-x),Math.max(1,bottom-y));}
    private static Rect pad(Rect r,double p,Mat im){return bounded(r.x-r.width*p,r.y-r.height*p,r.x+r.width*(1+p),r.y+r.height*(1+p),im);}
    private static List<VisionPoint> normalized(Rect r,Mat im){double l=r.x/(double)im.cols(),t=r.y/(double)im.rows(),right=(r.x+r.width)/(double)im.cols(),b=(r.y+r.height)/(double)im.rows();return Arrays.asList(new VisionPoint(l,t),new VisionPoint(right,t),new VisionPoint(right,b),new VisionPoint(l,b));}
}
