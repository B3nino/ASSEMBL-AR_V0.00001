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
    public DiagnosticScanTelemetry(String name,String reference,int active,int live,int good,int total,
            double extraction,double matching,boolean correct,boolean verified,boolean pose,String orientation,
            double tre,Point projected,Point observed,double rms,long frames){
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
        outputStatus=candidateVerified&&Float.isFinite(matchPercentage)&&matchPercentage>=PASS_PERCENT&&isCorrectPose?"Match":"Mismatch";
    }
    private static String n(double value){return Double.isFinite(value)?String.format(Locale.US,"%.2f",value):"Unmeasured";}
    private static String count(long value){return value>=0?Long.toString(value):"Unmeasured";}
    private static String point(Point p){return p!=null&&p.measured()?"("+n(p.x)+", "+n(p.y)+")":"Unmeasured";}
    public String hudText(){return "Scanned component: "+componentName
        +"\nActive keypoints: "+count(activeKeypoints)
        +"\nLive keypoints: "+count(liveKeypoints)
        +"\nGood/Matches: "+count(goodMatches)+" / "+count(totalMatches)
        +"\n%: "+n(matchPercentage)+(Float.isFinite(matchPercentage)?"%":"")+" ("+(Float.isFinite(matchPercentage)&&matchPercentage>=PASS_PERCENT?"PASS":"FAIL")+")"
        +"\nExtraction time (ms) : "+count(extractionTimeMs)
        +"\nMatching time (ms) : "+count(matchingTimeMs)
        +"\nCorrect Pose: "+(isCorrectPose?"Correct":"Incorrect")
        +"\nOrientation: "+orientationLabel
        +"\n% TRE: "+n(treMm)+(Float.isFinite(treMm)?" mm":"")
        +"\nProjected point per frame: "+point(projectedPoint)
        +"\nObserved point per frame: "+point(observedPoint)
        +"\nPixel distance: "+n(pixelDistance)+(Float.isFinite(pixelDistance)?" px":"")
        +"\nTotal frames: "+totalAnalyzedFrames
        +"\nOutput: "+outputStatus;}
}
