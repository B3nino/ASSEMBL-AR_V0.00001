package dev.chyken.assembl_ar.vision;

import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;

/** Structural recovery descriptors. Never compare these rows to luminance descriptors. */
public final class EdgeGradientDescriptorExtractor {
    public static final int VERSION = 1;
    private EdgeGradientDescriptorExtractor() { }
    static ORB createOrb(){return ORB.create(600,1.2f,8,16,0,2,ORB.HARRIS_SCORE,31,10);}

    /** One detector-owned workspace. All intermediate native allocations are reusable and
     * released under the analyzer ownership lock on pause/close. Reference preparation keeps
     * the original unbounded-coordinate API below; existing cached descriptors remain valid. */
    public static final class Workspace implements AutoCloseable {
        private final Mat sample=new Mat(),normalized=new Mat(),log=new Mat(),field=new Mat(),reflectance=new Mat(),linear=new Mat();
        private final Mat flat=new Mat(),blur=new Mat(),gx=new Mat(),gy=new Mat(),magnitude=new Mat(),illumination=new Mat();
        private final Mat detectorImage=new Mat(),detectorMask=new Mat(),empty=new Mat(),rows=new Mat();
        private final MatOfKeyPoint detected=new MatOfKeyPoint();
        private final Mat cachedRaw=new Mat(),cachedLum=new Mat(),cachedEdge=new Mat();
        private final Mat cachedLumLow=new Mat(),cachedEdgeLow=new Mat();
        private final Mat sceneImage=new Mat(),sceneDescriptors=new Mat();
        private final MatOfKeyPoint sceneKeys=new MatOfKeyPoint();
        private final KeyPoint[] scenePoints=new KeyPoint[4*LiveVisionPolicy.MAX_ROI_KEYPOINTS];
        private final byte[] sceneBytes=new byte[scenePoints.length*32],tileBytes=new byte[LiveVisionPolicy.MAX_ROI_KEYPOINTS*32];
        private final long[] sceneSeen=new long[4096];
        private Mat lastOutput,lastLow,lastFull;
        private boolean lumReady,edgeReady,cachedRecovery;
        public void beginFrame(){lumReady=edgeReady=false;}
        private void sample(Mat src,Mat dst){
            double scale=Math.min(1,LiveVisionPolicy.MAX_ANALYSIS_EDGE/(double)Math.max(src.cols(),src.rows()));
            if(scale==1)src.copyTo(dst);else Imgproc.resize(src,dst,new Size(Math.round(src.cols()*scale),Math.round(src.rows()*scale)),0,0,Imgproc.INTER_AREA);
        }
        public void normalize(Mat gray,Mat output,org.opencv.imgproc.CLAHE clahe,boolean structural,boolean recovery){
            boolean same=!cachedRaw.empty()&&cachedRaw.size().equals(gray.size())&&Core.norm(gray,cachedRaw,Core.NORM_INF)==0;
            if(!same){gray.copyTo(cachedRaw);lumReady=edgeReady=false;}
            Mat cache=structural?cachedEdge:cachedLum;
            if(structural? !edgeReady: !lumReady||cachedRecovery!=recovery){
                sample(gray,sample);
                if(structural){
                    sample.convertTo(log,CvType.CV_32F);Core.add(log,Scalar.all(1),log);Core.log(log,log);
                    double sigma=Math.max(5,Math.min(21,Math.min(sample.cols(),sample.rows())/18.0));
                    Imgproc.GaussianBlur(log,field,new Size(0,0),sigma);Core.subtract(log,field,reflectance);
                    Core.min(reflectance,Scalar.all(1.4),reflectance);Core.max(reflectance,Scalar.all(-1.4),reflectance);Core.exp(reflectance,linear);linear.convertTo(flat,CvType.CV_8U,96);
                    Imgproc.GaussianBlur(flat,blur,new Size(3,3),.8);Imgproc.Sobel(blur,gx,CvType.CV_32F,1,0,3);Imgproc.Sobel(blur,gy,CvType.CV_32F,0,1,3);
                    Core.magnitude(gx,gy,magnitude);Core.normalize(magnitude,normalized,0,255,Core.NORM_MINMAX,CvType.CV_8UC1);edgeReady=true;
                }else{
                    if(recovery){double sigma=Math.max(5,Math.min(21,Math.min(sample.cols(),sample.rows())/18.0));Imgproc.GaussianBlur(sample,illumination,new Size(0,0),sigma);Core.max(illumination,Scalar.all(24),illumination);Core.divide(sample,illumination,flat,128,CvType.CV_8UC1);Core.addWeighted(sample,.35,flat,.65,0,flat);clahe.apply(flat,normalized);}
                    else clahe.apply(sample,normalized);
                    lumReady=true;cachedRecovery=recovery;
                }
                normalized.copyTo(structural?cachedEdgeLow:cachedLumLow);
                if(normalized.size().equals(gray.size()))normalized.copyTo(cache);else Imgproc.resize(normalized,cache,gray.size(),0,0,Imgproc.INTER_LINEAR);
            }
            cache.copyTo(output);
            lastOutput=output;lastLow=structural?cachedEdgeLow:cachedLumLow;lastFull=cache;
        }
        /** ORB runs on <=640 px, <=300 rows. Coordinates return to the caller's original
         * full-resolution frame before homography, PnP, pose metrics or overlay rendering. */
        public void extract(Mat src,Mat mask,ORB orb,MatOfKeyPoint keys,Mat descriptors){
            extract(src,mask,orb,keys,descriptors,LiveVisionPolicy.MAX_ROI_KEYPOINTS);
        }
        public void extract(Mat src,Mat mask,ORB orb,MatOfKeyPoint keys,Mat descriptors,int budget){
            // Avoid upsample/downsample blur between normalization and extraction. The
            // comparison also prevents reuse if a caller changed normalized pixels in place.
            if(src==lastOutput&&lastLow!=null&&!lastLow.empty()&&Core.norm(src,lastFull,Core.NORM_INF)==0)lastLow.copyTo(detectorImage);
            else sample(src,detectorImage);
            Mat useMask=empty;
            if(mask!=null&&!mask.empty()){Imgproc.resize(mask,detectorMask,detectorImage.size(),0,0,Imgproc.INTER_NEAREST);useMask=detectorMask;}
            int configured=orb.getMaxFeatures();
            int limit=Math.min(configured,budget);
            try{orb.setMaxFeatures(limit);orb.detectAndCompute(detectorImage,useMask,detected,rows);}
            finally{orb.setMaxFeatures(configured);}
            KeyPoint[] all=detected.toArray();int count=Math.min(limit,all.length);
            KeyPoint[] selected=java.util.Arrays.copyOf(all,count);double sx=src.cols()/(double)detectorImage.cols(),sy=src.rows()/(double)detectorImage.rows();
            for(KeyPoint k:selected){k.pt.x*=sx;k.pt.y*=sy;k.size*=Math.sqrt(sx*sy);}keys.fromArray(selected);
            if(count==0){descriptors.release();return;}
            Mat view=rows.rowRange(0,count);try{view.copyTo(descriptors);}finally{view.release();}
        }
        public void extractProposal(Mat src,Rect bounds,ORB orb,MatOfKeyPoint keys,Mat descriptors){
            int l=Math.max(0,bounds.x),t=Math.max(0,bounds.y),r=Math.min(src.cols(),bounds.x+bounds.width),b=Math.min(src.rows(),bounds.y+bounds.height);
            if(r-l<16||b-t<16){extract(src,empty,orb,keys,descriptors);return;}
            Mat crop=src.submat(t,b,l,r);try{extract(crop,empty,orb,keys,descriptors);KeyPoint[] points=keys.toArray();for(KeyPoint p:points){p.pt.x+=l;p.pt.y+=t;}keys.fromArray(points);}finally{crop.release();}
        }
        /** Separate physical parts each receive local sampling. Keep a small whole-scene
         * sample for independent board/context checks; overlapping windows add no duplicate points. */
        public void extractProposals(Mat src,java.util.List<Rect> windows,ORB orb,MatOfKeyPoint keys,Mat descriptors){
            extractProposals(src,windows,orb,keys,descriptors,true);
        }
        public void extractProposals(Mat src,java.util.List<Rect> windows,ORB orb,MatOfKeyPoint keys,Mat descriptors,boolean includeContext){
            int count=0,limit=Math.min(6,windows.size()),regions=limit+(includeContext?1:0),capacity=regions*LiveVisionPolicy.MAX_ROI_KEYPOINTS;
            KeyPoint[] combined=new KeyPoint[capacity];byte[] bytes=new byte[capacity*32];
            java.util.Set<Long> seen=new java.util.HashSet<>();MatOfKeyPoint localKeys=new MatOfKeyPoint();Mat localRows=new Mat();
            try{
                for(int region=0;region<regions;region++){
                    if(region==limit)extract(src,empty,orb,localKeys,localRows);
                    else extractProposal(src,windows.get(region),orb,localKeys,localRows);
                    KeyPoint[] points=localKeys.toArray();if(localRows.empty())continue;
                    byte[] values=new byte[localRows.rows()*32];localRows.get(0,0,values);
                    for(int i=0;i<points.length&&count<capacity;i++){
                        KeyPoint p=points[i];long id=((long)Math.round(p.pt.x*4)<<32)|(Math.round(p.pt.y*4)&0xffffffffL);
                        if(!seen.add(id))continue;combined[count]=p;System.arraycopy(values,i*32,bytes,count*32,32);count++;
                    }
                }
                keys.fromArray(java.util.Arrays.copyOf(combined,count));
                if(count==0)descriptors.release();else{descriptors.create(count,32,CvType.CV_8UC1);descriptors.put(0,0,bytes);}
            }finally{localKeys.release();localRows.release();}
        }
        /** Mounted scenes contain several independent small bodies. Distribute the budget
         * across four spatial crops instead of allowing one textured PCB corner to consume
         * it all. Each ROI retains the 300-row limit; duplicate physical points are removed. */
        public void extractScene(Mat src,ORB orb,MatOfKeyPoint keys,Mat descriptors){
            if(src==lastOutput&&lastLow!=null&&!lastLow.empty()&&Core.norm(src,lastFull,Core.NORM_INF)==0)lastLow.copyTo(sceneImage);else sample(src,sceneImage);
            java.util.Arrays.fill(sceneSeen,Long.MIN_VALUE);int count=0;
            double sx=src.cols()/(double)sceneImage.cols(),sy=src.rows()/(double)sceneImage.rows();
            for(int y=0;y<2;y++)for(int x=0;x<2;x++){
                int l=Math.max(0,x*sceneImage.cols()/2-16),t=Math.max(0,y*sceneImage.rows()/2-16);
                int r=Math.min(sceneImage.cols(),(x+1)*sceneImage.cols()/2+16),b=Math.min(sceneImage.rows(),(y+1)*sceneImage.rows()/2+16);
                Mat tile=sceneImage.submat(t,b,l,r);
                try{
                    extract(tile,empty,orb,sceneKeys,sceneDescriptors);KeyPoint[] points=sceneKeys.toArray();
                    if(!sceneDescriptors.empty())sceneDescriptors.get(0,0,tileBytes);
                    for(int i=0;i<points.length;i++){
                        KeyPoint p=points[i];double px=p.pt.x+l,py=p.pt.y+t;
                        long identity=((long)Math.round(px*4)<<32)|(Math.round(py*4)&0xffffffffL);
                        int slot=(int)((identity^(identity>>>33))*0xff51afd7ed558ccdL)&(sceneSeen.length-1);
                        while(sceneSeen[slot]!=Long.MIN_VALUE&&sceneSeen[slot]!=identity)slot=(slot+1)&(sceneSeen.length-1);
                        if(sceneSeen[slot]==identity)continue;sceneSeen[slot]=identity;
                        p.pt.x=px*sx;p.pt.y=py*sy;p.size*=Math.sqrt(sx*sy);scenePoints[count]=p;
                        System.arraycopy(tileBytes,i*32,sceneBytes,count*32,32);count++;
                    }
                }finally{tile.release();}
            }
            keys.fromArray(java.util.Arrays.copyOf(scenePoints,count));
            if(count==0)descriptors.release();else{descriptors.create(count,32,CvType.CV_8UC1);descriptors.put(0,0,sceneBytes);}
            java.util.Arrays.fill(scenePoints,null);
        }
        @Override public void close(){for(Mat m:new Mat[]{sample,normalized,log,field,reflectance,linear,flat,blur,gx,gy,magnitude,illumination,detectorImage,detectorMask,empty,rows,detected,cachedRaw,cachedLum,cachedEdge,cachedLumLow,cachedEdgeLow,sceneImage,sceneDescriptors,sceneKeys})m.release();java.util.Arrays.fill(scenePoints,null);lumReady=edgeReady=false;lastOutput=lastLow=lastFull=null;}
    }

    /** Identical reference/live preprocessing; CV_8UC1 input and output, no pixel rescaling. */
    public static void normalize(Mat gray, Mat output) {
        if (gray.empty() || gray.type() != CvType.CV_8UC1)
            throw new IllegalArgumentException("An 8-bit grayscale frame is required");
        Mat flat = new Mat(), blur = new Mat(), gx = new Mat(), gy = new Mat(), magnitude = new Mat();
        try {
            IlluminationRecovery.logNormalize(gray, flat);
            Imgproc.GaussianBlur(flat, blur, new Size(3, 3), .8);
            Imgproc.Sobel(blur, gx, CvType.CV_32F, 1, 0, 3);
            Imgproc.Sobel(blur, gy, CvType.CV_32F, 0, 1, 3);
            Core.magnitude(gx, gy, magnitude);
            Core.normalize(magnitude, output, 0, 255, Core.NORM_MINMAX, CvType.CV_8UC1);
        } finally {
            flat.release(); blur.release(); gx.release(); gy.release(); magnitude.release();
        }
    }

    /** Caller owns keys/descriptors. The same ORB configuration is used for both sides. */
    public static void extract(Mat gray, Mat mask, ORB orb, MatOfKeyPoint keys, Mat descriptors) {
        Mat edges = new Mat();
        try { normalize(gray, edges); orb.detectAndCompute(edges, mask, keys, descriptors); }
        finally { edges.release(); }
    }
    static void extractRoi(Mat gray,Rect roi,ORB orb,MatOfKeyPoint keys,Mat descriptors){
        if(roi==null){Mat empty=new Mat();try{extract(gray,empty,orb,keys,descriptors);}finally{empty.release();}return;}
        Mat edges=new Mat(),crop=null,empty=new Mat();
        try{
            normalize(gray,edges);crop=edges.submat(roi);orb.detectAndCompute(crop,empty,keys,descriptors);
            KeyPoint[] points=keys.toArray();for(KeyPoint k:points){k.pt.x+=roi.x;k.pt.y+=roi.y;}keys.fromArray(points);
        }finally{if(crop!=null)crop.release();edges.release();empty.release();}
    }
}
