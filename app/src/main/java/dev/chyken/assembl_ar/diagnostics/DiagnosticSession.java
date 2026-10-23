package dev.chyken.assembl_ar.diagnostics;
import dev.chyken.assembl_ar.vision.*;import org.opencv.core.*;

/** Shared live collector for assembly and standalone test cameras. */
public final class DiagnosticSession {
    public static final DiagnosticSession INSTANCE=new DiagnosticSession();
    public final AcademicBenchmarkRunner runner=new AcademicBenchmarkRunner();
    public volatile String motion="Static";
    public volatile CheckerboardGroundTruth groundTruth;
    public volatile DiagnosticSample latest;
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
    public void record(RecognitionResult r,RecognitionRequest request,Mat gray){
        RecognitionResult.DiagnosticMetrics m=r.getDiagnosticMetrics();boolean orb=r.getDetectionMethod()==RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY||r.getDetectionMethod()==RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY||m!=null&&m.totalMatches>=0;
        boolean pass=r.getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT;
        Phase2Observation p=r.getPhase2Observation();if(p!=null&&request!=null&&request.getPhase2Stage()==5)pass=p.referenceSupported;
        if(m!=null&&m.mismatch!=null)pass=false;
        CheckerboardGroundTruth gt=groundTruth;CheckerboardGroundTruth.Measurement measured=gt!=null&&runner.running()?gt.measure(gray,r,request==null?null:request.getCameraCalibration()):null;
        Boolean correct=measured==null||r.getOrientationClassification()==RecognitionResult.OrientationClassification.UNKNOWN?null:r.getOrientationClassification()==gt.orientation;
        RecognitionResult.RejectionEvidence rejection=r.getRejectionEvidence();
        String reason=m!=null&&m.mismatch!=null?m.mismatch:p!=null&&p.rejected?"MISSING_MOTOR_OR_BRACKET":pass?"ACCEPTED":rejection==null?r.getValidationState().name():rejection.getReason().name();
        RecognitionResult.Latency l=r.getLatency();DiagnosticSample s=new DiagnosticSample(System.currentTimeMillis(),r.getFrameTimestampNanos(),request==null?0:request.getAssemblyStepNumber(),String.valueOf(r.getRequestTag()),m!=null&&m.probeReference!=null?KeypointInventoryManager.INSTANCE.name(m.probeReference):r.getDisplayName()==null?r.getComponentId():r.getDisplayName(),m!=null&&m.probeReference!=null?m.probeReference:r.getReferenceId(),m==null?KeypointInventoryManager.INSTANCE.count(r.getReferenceId()):m.referenceKeypoints,orb?m!=null&&m.probeGood>=0?m.probeGood:r.getGoodMatchCount():-1,orb&&m!=null?m.totalMatches:-1,r.getFrameKeypointCount(),l.getFeatureExtractionMs(),l.getMatchingMs(),lighting(gray),pass,reason,orb&&!r.getDetectionMethod().name().startsWith("ORB")?"ORB_PROBE+"+r.getDetectionMethod().name():r.getDetectionMethod().name(),r.getPose()!=null,correct,measured==null?Double.NaN:measured.errorMm,measured==null?Double.NaN:measured.errorPx,measured!=null&&motion.equals("Static")?measured.errorMm:Double.NaN,request==null||request.getExpectation()==null?"unscoped":request.getAssemblyStepNumber()+":"+request.getExpectation().getComponentId()+":"+dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(request),l.getSpatialValidationMs(),l.getTotalMs(),m==null?"unspecified":m.preprocessing);
        if(measured!=null)runner.overlay(measured.errorPx,motion.equals("Static")?measured.errorMm:Double.NaN);
        latest=s;runner.observe(s);DiagnosticLogger log=logger;if(log!=null){log.record(s);log.recordOverlay(r.getFrameTimestampNanos(),r.getPose()!=null,false,s.reprojectionPx,s.staticOffsetMm,motion);}
    }
}
