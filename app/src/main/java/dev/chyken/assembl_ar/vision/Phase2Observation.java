package dev.chyken.assembl_ar.vision;

/** Photo evidence is distinct from object count, direction, and the learner's firmness check. */
public final class Phase2Observation {
    public final String view;
    public final double positiveScore,negativeScore,competingViewScore;
    public final boolean referenceSupported,rejected,viewAmbiguous;
    public final int visibleMotors;
    public Phase2Observation(String view,double positive,double negative) {
        this(view,positive,negative,-1);
    }
    public Phase2Observation(String view,double positive,double negative,int visibleMotors) {
        this(view,positive,negative,visibleMotors,0);
    }
    public Phase2Observation(String view,double positive,double negative,int visibleMotors,double competingViewScore) {
        this.competingViewScore=competingViewScore;
        this.visibleMotors=visibleMotors;
        this.view=view; positiveScore=positive; negativeScore=negative;
        // Invalid-scan datasets no longer veto recognition or erase a confirmed view.
        rejected=false;
        viewAmbiguous=competingViewScore>0 && (positive<competingViewScore*1.15||positive-competingViewScore<3);
        referenceSupported=positive>=16 && !viewAmbiguous;
    }
    public Phase2Observation withVisibleMotors(int count){return new Phase2Observation(view,positiveScore,negativeScore,count,competingViewScore);}
}
