package dev.chyken.assembl_ar.diagnostics;
import org.opencv.core.*;import org.opencv.calib3d.Calib3d;import org.opencv.imgproc.Imgproc;import java.util.*;
import dev.chyken.assembl_ar.vision.*;

/** Independent coplanar checkerboard registration. Target coordinates are physically measured
 * from the first inner corner (not inferred from the recognized target or its solvePnP fit). */
public final class CheckerboardGroundTruth {
    public final int columns,rows;public final double squareMm,targetX,targetY;public final RecognitionResult.OrientationClassification orientation;
    public CheckerboardGroundTruth(int c,int r,double square,double x,double y,RecognitionResult.OrientationClassification orientation){if(c<3||r<3||c>20||r>20||square<=0||!Double.isFinite(square+x+y))throw new IllegalArgumentException("Invalid checkerboard measurements");columns=c;rows=r;squareMm=square;targetX=x;targetY=y;this.orientation=orientation;}
    public static final class Measurement {public final double errorPx,errorMm;Measurement(double px,double mm){errorPx=px;errorMm=mm;}}
    public Measurement measure(Mat gray,RecognitionResult result,CameraCalibration calibration){
        return measure(gray,result.getProjectedCorners(),result.getPose(),calibration);
    }
    public Measurement measure(Mat gray,List<VisionPoint> corners,RecognitionResult.Pose pose,CameraCalibration calibration){
        if(corners.size()!=4||pose==null||calibration==null)return null;
        MatOfPoint2f grid=new MatOfPoint2f(),src=new MatOfPoint2f(),dst=new MatOfPoint2f(),target=new MatOfPoint2f(),expected=new MatOfPoint2f();Mat h=null,inv=null;
        try{
            if(!Calib3d.findChessboardCorners(gray,new Size(columns,rows),grid,Calib3d.CALIB_CB_ADAPTIVE_THRESH|Calib3d.CALIB_CB_NORMALIZE_IMAGE|Calib3d.CALIB_CB_FAST_CHECK))return null;
            Imgproc.cornerSubPix(gray,grid,new Size(5,5),new Size(-1,-1),new TermCriteria(TermCriteria.COUNT|TermCriteria.EPS,20,.01));
            Point[] p=grid.toArray();src.fromArray(p[0],p[columns-1],p[(rows-1)*columns+columns-1],p[(rows-1)*columns]);
            dst.fromArray(new Point(0,0),new Point((columns-1)*squareMm,0),new Point((columns-1)*squareMm,(rows-1)*squareMm),new Point(0,(rows-1)*squareMm));
            Point center=projectOrigin(pose,calibration);if(center==null)return null;
            h=Imgproc.getPerspectiveTransform(src,dst);inv=h.inv();double x=center.x,y=center.y;target.fromArray(new Point(targetX,targetY));Core.perspectiveTransform(target,expected,inv);Point e=expected.toArray()[0];double tre=Double.NaN;
            if(calibration!=null&&calibration.getSource()==CameraCalibration.Source.CAMERA2_INTRINSICS){
                MatOfPoint3f object=new MatOfPoint3f();MatOfPoint2f deskewed=new MatOfPoint2f();Mat camera=calibration.toOpenCvCameraMatrix(),rotation=new Mat(),translation=new Mat(),matrix=new Mat();MatOfDouble distortion=new MatOfDouble(calibration.getDistortionCoefficients());
                try{List<Point3> geometry=new ArrayList<>();for(int row=0;row<rows;row++)for(int col=0;col<columns;col++)geometry.add(new Point3(col*squareMm,row*squareMm,0));object.fromList(geometry);List<Point> pixels=new ArrayList<>();for(Point raw:p){VisionPoint q=calibration.deskew(new VisionPoint(raw.x,raw.y));pixels.add(new Point(q.getX(),q.getY()));}deskewed.fromList(pixels);
                    if(Calib3d.solvePnP(object,deskewed,camera,distortion,rotation,translation)){Calib3d.Rodrigues(rotation,matrix);double[] model=pose.getTranslationVector();double sum=0;for(int i=0;i<3;i++){double observed=matrix.get(i,0)[0]*targetX+matrix.get(i,1)[0]*targetY+translation.get(i,0)[0];sum+=(model[i]-observed)*(model[i]-observed);}tre=Math.sqrt(sum);}
                }finally{object.release();deskewed.release();camera.release();rotation.release();translation.release();matrix.release();distortion.release();}
            }
            return new Measurement(Math.hypot(x-e.x,y-e.y),tre);
        }finally{grid.release();src.release();dst.release();target.release();expected.release();if(h!=null)h.release();if(inv!=null)inv.release();}
    }
    /** Project the physical model origin; averaging a perspective quad is not its center. */
    private static Point projectOrigin(RecognitionResult.Pose pose,CameraCalibration c){
        Mat camera=new Mat(3,3,CvType.CV_64F),r=new Mat(3,1,CvType.CV_64F),t=new Mat(3,1,CvType.CV_64F);MatOfPoint3f origin=new MatOfPoint3f(new Point3(0,0,0));MatOfPoint2f projected=new MatOfPoint2f();MatOfDouble d=new MatOfDouble(c.getDistortionCoefficients());
        try{double[] k=c.getCameraMatrixRowMajor(),zero=k.clone();zero[1]=0;camera.put(0,0,zero);r.put(0,0,pose.getRotationVector());t.put(0,0,pose.getTranslationVector());Calib3d.projectPoints(origin,r,t,camera,d,projected);Point p=projected.toArray()[0];return new Point(p.x+k[1]*(p.y-k[5])/k[4],p.y);}
        finally{camera.release();r.release();t.release();origin.release();projected.release();d.release();}
    }
}
