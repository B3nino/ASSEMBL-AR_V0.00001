package dev.chyken.assembl_ar.vision;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Optional recovery input for mixed/side-lit scenes. Coordinates and raw captured frames
 * are unchanged. The normal CLAHE path remains available; geometric gates never change. */
final class IlluminationRecovery {
    static boolean choose(AdaptiveIlluminationPolicy policy,Mat gray){
        long start=android.os.SystemClock.elapsedRealtimeNanos();Mat small=new Mat();
        try{Imgproc.resize(gray,small,new Size(32,20),0,0,Imgproc.INTER_AREA);byte[] sample=new byte[640];small.get(0,0,sample);return policy.choose(sample,32,20);}
        finally{small.release();DiagnosticHammingMatcher.snapshot().featureNs+=android.os.SystemClock.elapsedRealtimeNanos()-start;}
    }
    static void balance(Mat gray,Mat output){
        Mat illumination=new Mat(),flat=new Mat();
        try{
            double sigma=Math.max(5,Math.min(21,Math.min(gray.cols(),gray.rows())/18.0));
            Imgproc.GaussianBlur(gray,illumination,new Size(0,0),sigma);
            Core.max(illumination,new Scalar(24),illumination);
            Core.divide(gray,illumination,flat,128,CvType.CV_8UC1);
            Core.addWeighted(gray,.35,flat,.65,0,output);
        }finally{illumination.release();flat.release();}
    }
}
