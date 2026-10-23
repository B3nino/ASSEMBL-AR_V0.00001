package dev.chyken.assembl_ar.vision;
import org.opencv.core.*;import org.opencv.imgproc.Imgproc;import org.opencv.imgproc.CLAHE;import java.util.*;import java.util.concurrent.ConcurrentHashMap;import java.io.*;

/** Tiny precompiled bracket silhouettes supplement textureless plastic; never motor identity. */
final class Phase2AppearanceTemplates {
    private static final Map<String,List<Mat>> READY=new ConcurrentHashMap<>();
    private Phase2AppearanceTemplates(){}
    static boolean load(String id,File file){if(READY.containsKey(id))return true;if(file==null||!file.isFile())return false;List<Mat> patches=new ArrayList<>();
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))){if(in.readInt()!=6)throw new IOException("Invalid bracket template");for(int i=0;i<6;i++){byte[] b=new byte[2048];in.readFully(b);Mat p=new Mat(64,32,CvType.CV_8UC1);p.put(0,0,b);patches.add(p);}install(id,patches);return true;}catch(IOException|RuntimeException corrupt){for(Mat p:patches)p.release();return false;}}
    static void capture(String id,Mat bgr,List<List<VisionPoint>> regions,File file)throws IOException{
        Mat gray=new Mat();CLAHE clahe=Imgproc.createCLAHE(2,new Size(8,8));List<Mat> patches=new ArrayList<>();
        try{Imgproc.cvtColor(bgr,gray,Imgproc.COLOR_BGR2GRAY);clahe.apply(gray,gray);
            for(List<VisionPoint> r:regions){int l=(int)(r.get(0).getX()*gray.cols()),t=(int)(r.get(0).getY()*gray.rows()),right=Math.min(gray.cols(),(int)Math.ceil(r.get(2).getX()*gray.cols())),bottom=Math.min(gray.rows(),(int)Math.ceil(r.get(2).getY()*gray.rows()));Mat region=gray.submat(new Rect(l,t,right-l,bottom-t)),patch=new Mat();try{Imgproc.resize(region,patch,new Size(32,64),0,0,Imgproc.INTER_AREA);patches.add(patch);}finally{region.release();}}
            if(file!=null)try(DataOutputStream out=new DataOutputStream(new FileOutputStream(file))){out.writeInt(patches.size());for(Mat p:patches){byte[] b=new byte[2048];p.get(0,0,b);out.write(b);}}
            install(id,patches);patches=null;
        }finally{gray.release();clahe.clear();if(patches!=null)for(Mat p:patches)p.release();}
    }
    private static void install(String id,List<Mat> patches){List<Mat> old=READY.putIfAbsent(id,Collections.unmodifiableList(patches));if(old!=null)for(Mat p:patches)p.release();}
    static boolean supports(String id,int index,Mat live){List<Mat> patches=READY.get(id);if(patches==null||index>=patches.size())return false;Mat reference=patches.get(index),padded=new Mat(),score=new Mat();MatOfDouble mean=new MatOfDouble(),sd=new MatOfDouble();
        try{Core.meanStdDev(live,mean,sd);double liveSd=sd.toArray()[0],liveMean=mean.toArray()[0];Core.meanStdDev(reference,mean,sd);double refSd=sd.toArray()[0],refMean=mean.toArray()[0];
            if(refSd<4)return liveSd<15&&liveMean<130&&Math.abs(liveMean-refMean)<45;
            if(liveSd<4)return false;
            Core.copyMakeBorder(reference,padded,3,3,3,3,Core.BORDER_REPLICATE);Imgproc.matchTemplate(padded,live,score,Imgproc.TM_CCOEFF_NORMED);double value=Core.minMaxLoc(score).maxVal;return Double.isFinite(value)&&value>=.35;
        }finally{padded.release();score.release();mean.release();sd.release();}
    }
}
