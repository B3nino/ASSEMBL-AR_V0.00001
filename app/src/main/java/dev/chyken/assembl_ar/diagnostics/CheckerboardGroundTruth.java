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
        if(corners.size()!=4||pose==null)return null;
        MatOfPoint2f grid=new MatOfPoint2f(),src=new MatOfPoint2f(),dst=new MatOfPoint2f(),actual=new MatOfPoint2f(),metric=new MatOfPoint2f(),target=new MatOfPoint2f(),expected=new MatOfPoint2f();Mat h=null,inv=null;
        try{
            if(!Calib3d.findChessboardCorners(gray,new Size(columns,rows),grid,Calib3d.CALIB_CB_ADAPTIVE_THRESH|Calib3d.CALIB_CB_NORMALIZE_IMAGE|Calib3d.CALIB_CB_FAST_CHECK))return null;
            Imgproc.cornerSubPix(gray,grid,new Size(5,5),new Size(-1,-1),new TermCriteria(TermCriteria.COUNT|TermCriteria.EPS,20,.01));
            Point[] p=grid.toArray();src.fromArray(p[0],p[columns-1],p[(rows-1)*columns+columns-1],p[(rows-1)*columns]);
            dst.fromArray(new Point(0,0),new Point((columns-1)*squareMm,0),new Point((columns-1)*squareMm,(rows-1)*squareMm),new Point(0,(rows-1)*squareMm));
            h=Imgproc.getPerspectiveTransform(src,dst);inv=h.inv();double x=0,y=0;for(VisionPoint v:corners){x+=v.getX()/4;y+=v.getY()/4;}actual.fromArray(new Point(x,y));Core.perspectiveTransform(actual,metric,h);target.fromArray(new Point(targetX,targetY));Core.perspectiveTransform(target,expected,inv);Point m=metric.toArray()[0],e=expected.toArray()[0];double tre=Double.NaN;
            if(calibration!=null&&calibration.getSource()==CameraCalibration.Source.CAMERA2_INTRINSICS){
                MatOfPoint3f object=new MatOfPoint3f();Mat camera=new Mat(3,3,CvType.CV_64F),rotation=new Mat(),translation=new Mat(),matrix=new Mat();MatOfDouble distortion=new MatOfDouble(calibration.getDistortionCoefficients());
                try{List<Point3> geometry=new ArrayList<>();for(int row=0;row<rows;row++)for(int col=0;col<columns;col++)geometry.add(new Point3(col*squareMm,row*squareMm,0));object.fromList(geometry);camera.put(0,0,calibration.getCameraMatrixRowMajor());
                    if(Calib3d.solvePnP(object,grid,camera,distortion,rotation,translation)){Calib3d.Rodrigues(rotation,matrix);double[] model=pose.getTranslationVector();double sum=0;for(int i=0;i<3;i++){double observed=matrix.get(i,0)[0]*targetX+matrix.get(i,1)[0]*targetY+translation.get(i,0)[0];sum+=(model[i]-observed)*(model[i]-observed);}tre=Math.sqrt(sum);}
                }finally{object.release();camera.release();rotation.release();translation.release();matrix.release();distortion.release();}
            }
            return new Measurement(Math.hypot(x-e.x,y-e.y),tre);
        }finally{grid.release();src.release();dst.release();actual.release();metric.release();target.release();expected.release();if(h!=null)h.release();if(inv!=null)inv.release();}
    }
}
