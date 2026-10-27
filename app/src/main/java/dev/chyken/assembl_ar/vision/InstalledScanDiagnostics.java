package dev.chyken.assembl_ar.vision;

import java.util.List;
import android.util.Log;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Opt-in failure-stage trace. Image quality describes captured pixels only; it
 * never supplies recognition evidence or changes a matching threshold. */
final class InstalledScanDiagnostics {
    static final String TAG="InstalledScan";
    private InstalledScanDiagnostics() { }
    static void log(int phase,int stage,Mat gray,String state,List<VisionPoint> body,boolean boardSeen,boolean bodySeen,long began){
        if(!Log.isLoggable(TAG,Log.DEBUG))return;
        Mat sample=new Mat();
        try{
            Imgproc.resize(gray,sample,new Size(32,20),0,0,Imgproc.INTER_AREA);byte[] pixels=new byte[640];sample.get(0,0,pixels);
            double sum=0,gradient=0;int dark=0,clipped=0;
            for(int i=0;i<pixels.length;i++){int v=pixels[i]&255;sum+=v;if(v<12)dark++;if(v>245)clipped++;if(i%32>0)gradient+=Math.abs(v-(pixels[i-1]&255));}
            double width=Double.NaN,height=Double.NaN;
            if(body.size()==4){width=Math.hypot(body.get(1).getX()-body.get(0).getX(),body.get(1).getY()-body.get(0).getY());height=Math.hypot(body.get(3).getX()-body.get(0).getX(),body.get(3).getY()-body.get(0).getY());}
            Log.d(TAG,"phase="+phase+" stage="+stage+" state="+state+" board="+boardSeen+" body="+bodySeen+" frame="+gray.cols()+"x"+gray.rows()+" bodyPx="+width+"x"+height+" meanY="+sum/640+" darkFraction="+dark/640.0+" clippedFraction="+clipped/640.0+" gradient="+gradient/620+" structural="+DiagnosticHammingMatcher.snapshot().structuralInput+" fast="+DiagnosticHammingMatcher.snapshot().fastLive+" ms="+(android.os.SystemClock.elapsedRealtimeNanos()-began)/1e6);
        }finally{sample.release();}
    }
}
