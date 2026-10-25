package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Complementary measured geometry after distributed PCB photo verification.
 * Never estimates nut torque, axle grip or hidden standoff screw engagement. */
final class MountedHardwareGeometryVerifier {
    /** Localize the solid frame inside the photo-proposed neighborhood. Parallax
     * of the raised protector must not leave the outline on the flat PCB plane. */
    static List<VisionPoint> locateProtector(Mat gray,List<VisionPoint> expected,boolean crossbarAtTop){
        if(expected.size()!=4)return Collections.emptyList();
        double cx=0,cy=0;for(VisionPoint p:expected){cx+=p.getX()/4;cy+=p.getY()/4;}
        List<VisionPoint> expanded=new ArrayList<>();for(VisionPoint p:expected)expanded.add(new VisionPoint(cx+(p.getX()-cx)*1.30,cy+(p.getY()-cy)*1.30));
        Mat patch=rectify(gray,expanded,200,200);if(patch==null)return Collections.emptyList();
        Mat mask=new Mat(),hierarchy=new Mat(),map=null;MatOfPoint2f from=new MatOfPoint2f(),to=new MatOfPoint2f(),input=new MatOfPoint2f(),output=new MatOfPoint2f();List<MatOfPoint> contours=new ArrayList<>();
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
        try{
            Imgproc.threshold(patch,mask,0,255,Imgproc.THRESH_BINARY_INV|Imgproc.THRESH_OTSU);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            from.fromArray(new Point(0,0),new Point(199,0),new Point(199,199),new Point(0,199));Point[] p=new Point[4];for(int i=0;i<4;i++)p[i]=new Point(expanded.get(i).getX(),expanded.get(i).getY());to.fromArray(p);map=Imgproc.getPerspectiveTransform(from,to);
            contours.sort((a,b)->Double.compare(Imgproc.contourArea(b),Imgproc.contourArea(a)));
            for(MatOfPoint contour:contours){Rect b=Imgproc.boundingRect(contour);if(b.width<90||b.height<90||b.width>190||b.height>190||b.x<2||b.y<2||b.x+b.width>=198||b.y+b.height>=198)continue;
                input.fromArray(new Point(b.x,b.y),new Point(b.x+b.width,b.y),new Point(b.x+b.width,b.y+b.height),new Point(b.x,b.y+b.height));Core.perspectiveTransform(input,output,map);List<VisionPoint> actual=new ArrayList<>();for(Point v:output.toArray())actual.add(new VisionPoint(v.x,v.y));
                if(protector(gray,actual,crossbarAtTop))return actual;
            }return Collections.emptyList();
        }finally{patch.release();mask.release();hierarchy.release();kernel.release();if(map!=null)map.release();from.release();to.release();input.release();output.release();for(Mat c:contours)c.release();}
    }
    static boolean protector(Mat gray,List<VisionPoint> quad,boolean crossbarAtTop){
        Mat patch=rectify(gray,quad,160,160);if(patch==null)return false;
        Mat mask=new Mat(),labels=new Mat(),stats=new Mat(),centroids=new Mat(),circles=new Mat(),smooth=new Mat();
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
        try{
            if(crossbarAtTop)Core.rotate(patch,patch,Core.ROTATE_180);
            double paper=mean(patch,58,47,102,107),cutoff=paper-20;if(paper<45)return false;
            Imgproc.threshold(patch,mask,cutoff,255,Imgproc.THRESH_BINARY_INV);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);
            if(coverage(mask,16,40,51,139,true)<.70||coverage(mask,109,40,144,139,true)<.70||coverage(mask,29,128,133,158,false)<.70)return false;
            if(mean(mask,59,48,101,104)>255*.28)return false;
            Imgproc.connectedComponentsWithStats(mask,labels,stats,centroids);
            int a=(int)labels.get(100,32)[0],b=(int)labels.get(100,128)[0],c=(int)labels.get(143,80)[0];
            if(a==0||a!=b||a!=c)return false;
            // Both real mounting heads need a bright metallic annulus on a dark
            // housing. Empty black PCB holes do not satisfy this test.
            Imgproc.GaussianBlur(patch,smooth,new Size(3,3),.8);
            Imgproc.HoughCircles(smooth,circles,Imgproc.HOUGH_GRADIENT,1,15,60,9,3,18);
            boolean left=false,right=false;
            for(int i=0;i<circles.cols();i++){double[] p=circles.get(0,i);if(p[1]>53||p[0]>64&&p[0]<96)continue;int contrast=0,valid=0;
                for(int n=0;n<12;n++){double angle=n*Math.PI/6;int ix=(int)Math.round(p[0]+Math.cos(angle)*p[2]*.65),iy=(int)Math.round(p[1]+Math.sin(angle)*p[2]*.65),ox=(int)Math.round(p[0]+Math.cos(angle)*p[2]*1.35),oy=(int)Math.round(p[1]+Math.sin(angle)*p[2]*1.35);
                    if(ix<0||iy<0||ox<0||oy<0||ix>=160||iy>=160||ox>=160||oy>=160)continue;valid++;if(patch.get(iy,ix)[0]>patch.get(oy,ox)[0]+12)contrast++;
                }if(valid>=8&&contrast>=4){if(p[0]<64)left=true;else if(p[0]>96)right=true;}
            }return left&&right;
        }finally{patch.release();mask.release();labels.release();stats.release();centroids.release();circles.release();smooth.release();kernel.release();}
    }
    static boolean dualWheels(Mat gray,List<VisionPoint> left,List<VisionPoint> right){return wheel(gray,left)&&wheel(gray,right);}
    private static boolean wheel(Mat gray,List<VisionPoint> quad){
        double cx=0,cy=0;for(VisionPoint p:quad){cx+=p.getX()/4;cy+=p.getY()/4;}List<VisionPoint> expanded=new ArrayList<>();for(VisionPoint p:quad)expanded.add(new VisionPoint(cx+(p.getX()-cx)*1.35,cy+(p.getY()-cy)*1.35));
        Mat patch=rectify(gray,expanded,80,144);if(patch==null)return false;
        Mat mask=new Mat(),hierarchy=new Mat(),edges=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        try{
            // Local Otsu adapts to illumination; rounded tire footprint and tread
            // edges remain necessary independently on both sides of the PCB.
            Imgproc.threshold(patch,mask,0,255,Imgproc.THRESH_BINARY_INV|Imgproc.THRESH_OTSU);
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            for(MatOfPoint contour:contours){double area=Imgproc.contourArea(contour);Rect box=Imgproc.boundingRect(contour);
                if(area<80*144*.40||box.width<80*.62||box.height<144*.68||area/box.area()>.98)continue;
                Imgproc.Canny(patch,edges,20,60);Mat inside=edges.submat(new Rect(10,18,60,108));try{if(Core.countNonZero(inside)<100)continue;}finally{inside.release();}
                return true;
            }return false;
        }finally{patch.release();mask.release();hierarchy.release();edges.release();for(Mat c:contours)c.release();}
    }
    private static double coverage(Mat mask,int l,int t,int r,int b,boolean rows){int good=0,total=rows?b-t:r-l;
        for(int i=0;i<total;i++){double value=rows?mean(mask,l,t+i,r,t+i+1):mean(mask,l+i,t,l+i+1,b);if(value>=255*.35)good++;}return good/(double)total;
    }
    private static double mean(Mat m,int l,int t,int r,int b){Mat roi=m.submat(t,b,l,r);try{return Core.mean(roi).val[0];}finally{roi.release();}}
    private static Mat rectify(Mat gray,List<VisionPoint> quad,int w,int h){
        if(quad.size()!=4)return null;MatOfPoint2f source=new MatOfPoint2f(),target=new MatOfPoint2f();Mat transform=null,patch=new Mat();boolean transferred=false;
        try{Point[] p=new Point[4];for(int i=0;i<4;i++){VisionPoint v=quad.get(i);if(!Double.isFinite(v.getX()+v.getY())||v.getX()<0||v.getY()<0||v.getX()>=gray.cols()||v.getY()>=gray.rows())return null;p[i]=new Point(v.getX(),v.getY());}
            source.fromArray(p);target.fromArray(new Point(0,0),new Point(w-1,0),new Point(w-1,h-1),new Point(0,h-1));transform=Imgproc.getPerspectiveTransform(source,target);Imgproc.warpPerspective(gray,patch,transform,new Size(w,h));transferred=true;return patch;
        }finally{source.release();target.release();if(transform!=null)transform.release();if(!transferred)patch.release();}
    }
}
