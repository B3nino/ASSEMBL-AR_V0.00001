package dev.chyken.assembl_ar.diagnostics;

/** Immutable measurements. NaN/-1 mean unmeasured; a shape score is never an ORB ratio. */
public final class DiagnosticSample {
    public final DiagnosticScanTelemetry telemetry;
    public final double anchorOffsetPx;
    public final long wallMillis,frameNanos;public final int phase,keypoints,assigned,good,total;
    public final String step,candidate,reference,lighting,reason,method;
    public final String target;
    public final double geometryMs,verificationMs;public final String preprocessing;
    public final double extractionMs,matchingMs,reprojectionPx,treMm,staticOffsetMm;
    public final boolean pass,computedPose;public final Boolean correctPose;
    public DiagnosticSample(long wall,long frame,int phase,String step,String candidate,String reference,int assigned,int good,int total,int keypoints,double extraction,double matching,String lighting,boolean pass,String reason,String method,boolean pose,Boolean correct,double tre,double reprojection,double offset,String target){this(wall,frame,phase,step,candidate,reference,assigned,good,total,keypoints,extraction,matching,lighting,pass,reason,method,pose,correct,tre,reprojection,offset,target,Double.NaN,Double.NaN,"unspecified");}
    public DiagnosticSample(long wall,long frame,int phase,String step,String candidate,String reference,int assigned,int good,int total,int keypoints,double extraction,double matching,String lighting,boolean pass,String reason,String method,boolean pose,Boolean correct,double tre,double reprojection,double offset,String target,double geometry,double verification,String preprocessing){this(wall,frame,phase,step,candidate,reference,assigned,good,total,keypoints,extraction,matching,lighting,pass,reason,method,pose,correct,tre,reprojection,offset,target,geometry,verification,preprocessing,null,Double.NaN);}
    public DiagnosticSample(long wall,long frame,int phase,String step,String candidate,String reference,int assigned,int good,int total,int keypoints,double extraction,double matching,String lighting,boolean pass,String reason,String method,boolean pose,Boolean correct,double tre,double reprojection,double offset,String target,double geometry,double verification,String preprocessing,DiagnosticScanTelemetry telemetry,double anchorOffset){this.telemetry=telemetry;anchorOffsetPx=anchorOffset;geometryMs=geometry;verificationMs=verification;this.preprocessing=preprocessing;this.target=target;this.wallMillis=wall;frameNanos=frame;this.phase=phase;this.step=step;this.candidate=candidate;this.reference=reference;this.assigned=assigned;this.good=good;this.total=total;this.keypoints=keypoints;extractionMs=extraction;matchingMs=matching;this.lighting=lighting;this.pass=pass;this.reason=reason;this.method=method;computedPose=pose;correctPose=correct;treMm=tre;reprojectionPx=reprojection;staticOffsetMm=offset;}
    public double accuracy(){return total>0&&good>=0&&good<=total?good*100.0/total:Double.NaN;}
    public static final String HEADER="wall_time_ms,frame_ns,phase,step,target,candidate,reference,assigned_keypoints,good_matches,total_matches,match_percentage,live_keypoints,extraction_ms,matching_ms,lighting,outcome,reason,method,computed_pose,correct_pose,tre_mm,reprojection_rms_px,static_offset_mm,geometry_ms,verification_ms,preprocessing,projected_x,projected_y,observed_x,observed_y,center_to_inlier_centroid_px,checkerboard_anchor_px,total_analyzed_frames,pose_measured,diagnostic_output,workflow_verified,verification_source,verifier_confidence";
    public String csv(){return wallMillis+","+frameNanos+","+phase+","+q(step)+","+q(target)+","+q(candidate)+","+q(reference)+","+assigned+","+good+","+total+","+number(accuracy())+","+keypoints+","+number(extractionMs)+","+number(matchingMs)+","+q(lighting)+","+(pass?"PASS":"FAIL")+","+q(reason)+","+q(method)+","+computedPose+","+(correctPose==null?"":correctPose)+","+number(treMm)+","+number(reprojectionPx)+","+number(staticOffsetMm)+","+number(geometryMs)+","+number(verificationMs)+","+q(preprocessing)+telemetryCsv();}
    public String hudText(){
        if(telemetry!=null)return telemetry.hudText();
        String ratio=Double.isFinite(accuracy())?String.format(java.util.Locale.US,"%.2f%%",accuracy()):"unmeasured";
        return "P"+phase+" · "+candidate+"\nAssigned kp: "+assigned+" · Live kp: "+keypoints
            +"\nLowe pairs (candidate): "+good+"/"+total+"\nORB pair yield: "+ratio
            +"\nWorkflow: "+(pass?"VERIFIED":"NOT VERIFIED")+" · "+reason
            +"\nLight estimate: "+lighting+" (auto)"
            +String.format(java.util.Locale.US,"\nFeature prep %.1f · compare %.1f ms\nGeometry %.1f · total verify %.1f ms",extractionMs,matchingMs,geometryMs,verificationMs);
    }
    private String telemetryCsv(){DiagnosticScanTelemetry t=telemetry;if(t==null)return ",,,,,,"+number(anchorOffsetPx)+",,,,,,";
        return ","+number(t.projectedPoint==null?Double.NaN:t.projectedPoint.x)+","+number(t.projectedPoint==null?Double.NaN:t.projectedPoint.y)
            +","+number(t.observedPoint==null?Double.NaN:t.observedPoint.x)+","+number(t.observedPoint==null?Double.NaN:t.observedPoint.y)
            +","+number(t.pixelDistance)+","+number(anchorOffsetPx)+","+t.totalAnalyzedFrames+","+t.poseMeasured+","+q(t.outputStatus)
            +","+t.workflowVerified+","+q(t.verificationSource)+","+number(t.verificationConfidence);}
    private static String number(double n){return Double.isFinite(n)?String.format(java.util.Locale.US,"%.4f",n):"";}
    private static String q(String s){s=s==null?"":s;/* Avoid spreadsheet formula execution in exported labels. */if(s.matches("^[=+@-].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
}
