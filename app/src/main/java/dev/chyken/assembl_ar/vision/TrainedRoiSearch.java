package dev.chyken.assembl_ar.vision;

import android.os.SystemClock;
import android.util.Log;
import java.util.function.Supplier;

/** Runs the original verifier on proposed features, then its full search on any miss.
 * Search assistance is recorded separately from accepted model identity and ORB fits. */
final class TrainedRoiSearch {
    static RecognitionResult inspect(RecognitionRequest request,Supplier<RecognitionResult> verifier){
        DiagnosticHammingMatcher.State state=DiagnosticHammingMatcher.snapshot();
        if(state.trainedHints==null||!TrainedModelPolicy.assisted(request)
                ||state.trainedHints.windows(state.trainedHints.width,state.trainedHints.height).isEmpty())return verifier.get();
        boolean recovery=state.recovery,structural=state.structuralInput,edge=state.edgeWinner;
        DiagnosticHammingMatcher.discardCandidateEvidence();
        long start=SystemClock.elapsedRealtimeNanos();boolean verified=false;
        try{
            state.trainedPass=true;
            state.structuralInput=false;
            RecognitionResult proposed=verifier.get();
            verified=VerifiedScanEvidence.workflowAccepted(proposed,request)
                    ||proposed.hasLocatedComponent()&&proposed.getValidationState()==RecognitionResult.ValidationState.WRONG_ORIENTATION;
            if(verified){
                if(proposed.getTrainedEvidence()==null){
                    int count=0,confirmations=3;double confidence=1;
                    for(TrainedDetection d:state.trainedHints.detections)if(TrainedModelPolicy.proposes(request,d.component)){
                        count++;confirmations=Math.min(confirmations,d.confirmations);confidence=Math.min(confidence,d.confidence);
                    }
                    if(count>0)proposed=proposed.withTrainedEvidence(new TrainedRecognitionEvidence(TrainedModelPolicy.modelPhase(request),confirmations,count,
                            state.trainedHints.sourceNanos,confidence,state.trainedHints.inferenceMs,false));
                }
                return proposed;
            }
        }catch(RuntimeException unavailable){Log.w("TrainedAssist","Proposal search failed; retrying original verifier",unavailable);}
        finally{
            state.trainedPass=false;
            if(state.trainedOwner!=null)state.trainedOwner.outcome(verified);
            Log.i("TrainedAssist","phase="+request.getAssemblyStepNumber()+" proposalVerifyMs="+(SystemClock.elapsedRealtimeNanos()-start)/1e6
                    +" inferenceMs="+state.trainedHints.inferenceMs+" sourceFrame="+state.trainedHints.sourceNanos+" fallback="+!verified+" authority=phase-verifier");
        }
        DiagnosticHammingMatcher.discardCandidateEvidence();
        state.recovery=recovery;state.structuralInput=structural;state.edgeWinner=edge;
        return verifier.get();
    }
    private TrainedRoiSearch(){}
}
