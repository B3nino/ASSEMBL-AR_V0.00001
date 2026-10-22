package dev.chyken.assembl_ar.vision;

/** Visible identity/placement only. Axle grip, nut torque and screw removal need physical answers. */
public final class Phase9Observation {
    public final boolean standoff,leftWheel,rightWheel,boardContext,invalid;
    public final int looseWheels;public final double score;
    public Phase9Observation(boolean standoff,int looseWheels,boolean leftWheel,boolean rightWheel,boolean boardContext,boolean invalid,double score){
        this.standoff=standoff;this.looseWheels=Math.max(0,Math.min(2,looseWheels));this.leftWheel=leftWheel;this.rightWheel=rightWheel;this.boardContext=boardContext;this.invalid=invalid;this.score=score;
    }
    public boolean accepted(int stage){return !invalid&&(stage==1?standoff:stage==2?looseWheels==2:stage==3?boardContext&&standoff:stage==4&&boardContext&&standoff&&leftWheel&&rightWheel);}
}
