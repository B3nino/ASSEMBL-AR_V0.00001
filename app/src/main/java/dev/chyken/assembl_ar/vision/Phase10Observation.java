package dev.chyken.assembl_ar.vision;

/** Positive photo evidence and actual negative evidence stay distinct from an unmatched view. */
public final class Phase10Observation {
    public enum State { SEARCHING, FRONT_CONFIRMED, ASSEMBLY_CONFIRMED, FLIPPED }
    public final State state;public final double score;
    public Phase10Observation(State state,double score){this.state=state;this.score=score;}
    public boolean accepted(){return state==State.FRONT_CONFIRMED||state==State.ASSEMBLY_CONFIRMED;}
    public boolean flipped(){return state==State.FLIPPED;}
    public static State classify(int stage,double positive,double negative){if(negative>=12&&negative>positive*1.15)return State.FLIPPED;if(positive>=12&&negative<positive*.85)return stage<=3?State.FRONT_CONFIRMED:State.ASSEMBLY_CONFIRMED;return State.SEARCHING;}
}
