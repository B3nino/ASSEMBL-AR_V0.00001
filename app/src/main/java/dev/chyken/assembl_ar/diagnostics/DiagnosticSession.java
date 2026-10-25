package dev.chyken.assembl_ar.diagnostics;
import dev.chyken.assembl_ar.vision.*;import org.opencv.core.*;

/** Shared live collector for assembly and standalone test cameras. */
public final class DiagnosticSession {
    public static final DiagnosticSession INSTANCE=new DiagnosticSession();
    public final AcademicBenchmarkRunner runner=new AcademicBenchmarkRunner();
    public volatile String motion="Static";
    public volatile CheckerboardGroundTruth groundTruth;
    public volatile DiagnosticSample latest;
    public volatile DiagnosticScanTelemetry latestTelemetry;
    private final java.util.concurrent.atomic.AtomicLong analyzed=new java.util.concurrent.atomic.AtomicLong();
    public long analyzedFrame(){return analyzed.incrementAndGet();}
    private volatile DiagnosticLogger logger;
    public void attach(android.content.Context c){logger=DiagnosticLogger.get(c);}
    public String lighting(Mat gray){Mat small=new Mat();try{org.opencv.imgproc.Imgproc.resize(gray,small,new Size(32,20),0,0,org.opencv.imgproc.Imgproc.INTER_AREA);byte[] pixels=new byte[640];small.get(0,0,pixels);return LightingEstimator.estimate(pixels,32,20);}finally{small.release();}}
    public void tracking(RecognitionResult.SpatialAnchor anchor,Mat gray,CameraCalibration calibration,boolean predicted,long time){
        double pixels=Double.NaN,offset=Double.NaN;CheckerboardGroundTruth gt=groundTruth;
        if(anchor!=null&&gt!=null&&runner.running()){
            CheckerboardGroundTruth.Measurement m=gt.measure(gray,anchor.getProjectedCorners(),anchor.getPose(),calibration);
            if(m!=null){pixels=m.errorPx;offset=motion.equals("Static")?m.errorMm:Double.NaN;runner.overlay(pixels,offset);}
        }
        DiagnosticLogger log=logger;if(log!=null)log.recordOverlay(time,anchor!=null&&!predicted,predicted,pixels,offset,motion);
    }
    public void record(RecognitionResult r,RecognitionRequest request,Mat gray){record(r,request,gray,true);}
    public void record(RecognitionResult r,RecognitionRequest request,Mat gray,boolean includeTrial){
        RecognitionResult.DiagnosticMetrics m=r.getDiagnosticMetrics();VerifiedScanEvidence e=m==null?null:m.verified;
        boolean workflow=r.getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT;
        Phase2Observation p=r.getPhase2Observation();if(p!=null&&request!=null&&request.getPhase2Stage()==5)workflow=p.referenceSupported;
        if(m!=null&&m.mismatch!=null)workflow=false;
        boolean pose=e!=null&&e.pose!=null&&e.orientation!=null;
        boolean correct=pose&&workflow&&e.orientation.frontFacing;
        RecognitionRequest.AssemblyExpectation target=request==null?null:request.getExpectation();
        if(correct&&target!=null&&target.getOrientationDegrees()!=null){
            double expected=target.getOrientationDegrees(),delta=Math.abs(e.orientation.yaw-expected)%360;
            correct=Math.min(delta,360-delta)<=(target.getOrientationToleranceDegrees()==null?30:target.getOrientationToleranceDegrees());
        }
        RecognitionResult.Pose fitted=pose?e.pose:null;
        CheckerboardGroundTruth gt=groundTruth;CheckerboardGroundTruth.Measurement measured=gt!=null&&fitted!=null?gt.measure(gray,r.getProjectedCorners(),fitted,request==null?null:request.getCameraCalibration()):null;
        RecognitionResult.Latency latency=r.getLatency();
        DiagnosticScanTelemetry t=new DiagnosticScanTelemetry(e==null?"Unverified":e.componentName,e==null?null:e.source.getReferenceId(),
            e==null?-1:KeypointInventoryManager.INSTANCE.count(e.source.getReferenceId()),
            e==null?Math.max(0,r.getFrameKeypointCount()):e.liveKeypoints,e==null?-1:e.good,e==null?-1:e.total,
            latency.getFeatureExtractionMs(),latency.getMatchingMs(),correct,e!=null,pose,
            pose?e.orientation.label:fallbackOrientation(r.getOrientationClassification()),measured==null?Double.NaN:measured.errorMm,
            e==null||e.projectedOrigin==null?null:new DiagnosticScanTelemetry.Point(e.projectedOrigin.getX(),e.projectedOrigin.getY()),
            e==null||e.observedCentroid==null?null:new DiagnosticScanTelemetry.Point(e.observedCentroid.getX(),e.observedCentroid.getY()),
            fitted==null?Double.NaN:fitted.getReprojectionErrorPx(),analyzed.get());
        RecognitionResult.RejectionEvidence rejected=r.getRejectionEvidence();
        String reason=m!=null&&m.mismatch!=null?m.mismatch:p!=null&&p.rejected?"MISSING_MOTOR_OR_BRACKET":workflow?"ACCEPTED":rejected==null?r.getValidationState().name():rejected.getReason().name();
        DiagnosticSample sample=new DiagnosticSample(System.currentTimeMillis(),r.getFrameTimestampNanos(),request==null?0:request.getAssemblyStepNumber(),String.valueOf(r.getRequestTag()),
            t.componentName,t.referenceId,t.activeKeypoints,t.goodMatches,t.totalMatches,t.liveKeypoints,latency.getFeatureExtractionMs(),latency.getMatchingMs(),lighting(gray),workflow,reason,r.getDetectionMethod().name(),pose,pose?t.isCorrectPose:null,
            t.treMm,t.reprojectionRmsPx,measured!=null&&motion.equals("Static")?measured.errorMm:Double.NaN,
            target==null?"unscoped":request.getAssemblyStepNumber()+":"+target.getComponentId()+":"+dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(request),
            latency.getSpatialValidationMs(),latency.getTotalMs(),m==null?"unspecified":m.preprocessing,t,measured==null?Double.NaN:measured.errorPx);
        if(measured!=null)runner.overlay(measured.errorPx,motion.equals("Static")?measured.errorMm:Double.NaN);
        latestTelemetry=t;latest=sample;if(includeTrial)runner.observe(sample);DiagnosticLogger log=logger;
        if(log!=null){log.record(sample);log.recordOverlay(r.getFrameTimestampNanos(),pose,false,sample.anchorOffsetPx,sample.staticOffsetMm,motion);}
    }
    private static String fallbackOrientation(RecognitionResult.OrientationClassification o){switch(o){case UPRIGHT:return "Upright";case UPSIDE_DOWN:return "Upside Down";case ROTATED_CLOCKWISE:return "Right";case ROTATED_COUNTERCLOCKWISE:return "Left";default:return "Unmeasured";}}
}
