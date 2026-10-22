package dev.chyken.assembl_ar.vision;

public final class Phase11Observation {
    public enum State { SEARCHING, BOARD_LOCATED, COMPONENT, ASSEMBLY, FLIPPED, MISALIGNED }
    public final State state;public final double score;
    public final int boardInliers;
    public Phase11Observation(State state,double score){this(state,score,0);}
    public Phase11Observation(State state,double score,int boardInliers){this.state=state;this.score=score;this.boardInliers=boardInliers;}
    public boolean accepted(){return state==State.COMPONENT||state==State.ASSEMBLY;}
    public boolean warning(){return state==State.FLIPPED||state==State.MISALIGNED;}
    public static State classify(int stage,double front,double back,double placed,double reversed){if(stage<=2){if(back>=12&&back>front*1.15)return State.FLIPPED;return front>=12&&back<front*.85?State.COMPONENT:State.SEARCHING;}if(reversed>placed*1.15&&reversed>=10)return State.MISALIGNED;return placed>=10&&reversed<placed*.85?State.ASSEMBLY:State.SEARCHING;}
}
