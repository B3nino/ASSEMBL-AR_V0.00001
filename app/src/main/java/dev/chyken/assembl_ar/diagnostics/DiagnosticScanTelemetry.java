package dev.chyken.assembl_ar.diagnostics;

import java.util.Locale;

/** Immutable numeric snapshot. NaN/-1 explicitly mean unavailable, never perfect accuracy. */
public final class DiagnosticScanTelemetry {
    public static final float PASS_PERCENT=85;
    public static final class Point {
        public final double x,y;
        public Point(double x,double y){this.x=x;this.y=y;}
        public boolean measured(){return Double.isFinite(x)&&Double.isFinite(y);}
    }
    public final String componentName,referenceId,orientationLabel,outputStatus;
    public final int activeKeypoints,liveKeypoints,goodMatches,totalMatches;
    public final float matchPercentage,treMm,pixelDistance,reprojectionRmsPx;
    public final long extractionTimeMs,matchingTimeMs,totalAnalyzedFrames;
    public final boolean isCorrectPose,candidateVerified,poseMeasured;
    public final Point projectedPoint,observedPoint;
    /** Workflow verification is distinct from the academic ORB/3D-pose gates. */
    public final boolean workflowVerified;
    public final String verificationSource;
    public final double verificationConfidence;
    public DiagnosticScanTelemetry(String name,String reference,int active,int live,int good,int total,
            double extraction,double matching,boolean correct,boolean verified,boolean pose,String orientation,
            double tre,Point projected,Point observed,double rms,long frames){
        this(name,reference,active,live,good,total,extraction,matching,correct,verified,pose,orientation,
                tre,projected,observed,rms,frames,false,null,Double.NaN);
    }
    private DiagnosticScanTelemetry(String name,String reference,int active,int live,int good,int total,
            double extraction,double matching,boolean correct,boolean verified,boolean pose,String orientation,
            double tre,Point projected,Point observed,double rms,long frames,boolean workflow,
            String source,double confidence){
        componentName=verified?name:"Unverified";referenceId=verified?reference:null;
        activeKeypoints=active;liveKeypoints=live;goodMatches=good;totalMatches=total;
        matchPercentage=total>0&&good>=0&&good<=total?100f*good/total:Float.NaN;
        extractionTimeMs=Double.isFinite(extraction)?Math.round(extraction):-1;
        matchingTimeMs=Double.isFinite(matching)?Math.round(matching):-1;
        candidateVerified=verified;poseMeasured=pose;isCorrectPose=verified&&pose&&correct;
        orientationLabel=orientation==null?"Unmeasured":orientation;
        treMm=(float)tre;projectedPoint=projected;observedPoint=observed;
        pixelDistance=projected!=null&&observed!=null&&projected.measured()&&observed.measured()
                ?(float)Math.hypot(projected.x-observed.x,projected.y-observed.y):Float.NaN;
        reprojectionRmsPx=(float)rms;totalAnalyzedFrames=frames;
        workflowVerified=workflow;verificationSource=source;verificationConfidence=confidence;
        outputStatus=workflow?"Match (VERIFIED)":candidateVerified&&Float.isFinite(matchPercentage)&&matchPercentage>=PASS_PERCENT&&isCorrectPose?"Match":"Mismatch";
    }
    /** No confidence score is converted to an ORB match count, ratio, or measured pose. */
    public DiagnosticScanTelemetry withVerification(String name,String source,double confidence){
        if(name==null||name.trim().isEmpty()||source==null||source.trim().isEmpty())throw new IllegalArgumentException("Verified identity and source required");
        return new DiagnosticScanTelemetry(name,referenceId,activeKeypoints,liveKeypoints,goodMatches,totalMatches,
                extractionTimeMs<0?Double.NaN:extractionTimeMs,matchingTimeMs<0?Double.NaN:matchingTimeMs,
                isCorrectPose,true,poseMeasured,orientationLabel,treMm,projectedPoint,observedPoint,
                reprojectionRmsPx,totalAnalyzedFrames,true,source,
                Double.isFinite(confidence)&&confidence>=0&&confidence<=1?confidence:Double.NaN);
    }
    public boolean outputMatches(){return workflowVerified||"Match".equals(outputStatus);}
    private static String n(double value){return Double.isFinite(value)?String.format(Locale.US,"%.2f",value):"Unmeasured";}
    private static String count(long value){return value>=0?Long.toString(value):"Unmeasured";}
    private static String point(Point p){return p!=null&&p.measured()?"("+n(p.x)+", "+n(p.y)+")":"Unmeasured";}
    public String hudText(){return "Scanned component: "+componentName+(workflowVerified?" (VERIFIED)":"")
        +"\nActive keypoints: "+(workflowVerified&&activeKeypoints<0?"Unmeasured ("+verificationSource+")":count(activeKeypoints))
        +"\nLive keypoints: "+count(liveKeypoints)
        +"\nGood/Matches: "+count(goodMatches)+" / "+count(totalMatches)
        +"\n%: "+n(matchPercentage)+(Float.isFinite(matchPercentage)?"%":"")+" ("+(Float.isFinite(matchPercentage)?(matchPercentage>=PASS_PERCENT?"PASS":"FAIL")+(workflowVerified?" · ORB gate":""):workflowVerified?"ORB unmeasured":"FAIL")+")"
        +"\nExtraction time (ms) : "+count(extractionTimeMs)
        +"\nMatching time (ms) : "+count(matchingTimeMs)
        +"\nCorrect Pose: "+(poseMeasured?(isCorrectPose?"Correct":"Incorrect"):"Unmeasured")
        +"\nOrientation: "+orientationLabel
        +"\n% TRE: "+n(treMm)+(Float.isFinite(treMm)?" mm":"")
        +"\nProjected point per frame: "+point(projectedPoint)
        +"\nObserved point per frame: "+point(observedPoint)
        +"\nPixel distance: "+n(pixelDistance)+(Float.isFinite(pixelDistance)?" px":"")
        +"\nTotal frames: "+totalAnalyzedFrames
        +"\nOutput: "+outputStatus+(workflowVerified?" · "+verificationSource+(Double.isFinite(verificationConfidence)?" · verifier confidence "+n(verificationConfidence*100)+"%":""):"");}
}
