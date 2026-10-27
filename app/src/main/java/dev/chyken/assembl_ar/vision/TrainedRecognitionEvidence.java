package dev.chyken.assembl_ar.vision;

/** Model identity evidence, kept separate from reference-image fits and metric pose. */
public final class TrainedRecognitionEvidence {
    public final int modelPhase,confirmations,instances;
    public final long sourceTimestampNanos;
    public final double confidence,inferenceMs;
    public final boolean identityAccepted;
    TrainedRecognitionEvidence(int phase,int confirmations,int instances,long source,double confidence,double inferenceMs,boolean accepted){
        modelPhase=phase;this.confirmations=confirmations;this.instances=instances;sourceTimestampNanos=source;
        this.confidence=confidence;this.inferenceMs=inferenceMs;identityAccepted=accepted;
    }
}
