package dev.chyken.assembl_ar.vision;

/** Accepted photo geometry, independent of a rendered AR pose or a hardware-tightness claim. */
public final class Phase3Observation {
    public enum State{SEARCHING,FRONT,FLIPPED,PLACED,BACKWARD,MISALIGNED}
    public final State state;
    public final double score;
    public Phase3Observation(State state,double score){this.state=state;this.score=score;}
    public boolean accepted(int step){return step==1?state==State.FRONT:step==4&&state==State.PLACED;}
    /** Compare semantic corner order after independently mapping the main board. Rotation cancels out. */
    public static State placement(java.util.List<VisionPoint> expected,java.util.List<VisionPoint> actual){
        if(expected.size()!=4||actual.size()!=4)return State.SEARCHING;
        double diagonal=Math.hypot(expected.get(0).getX()-expected.get(2).getX(),expected.get(0).getY()-expected.get(2).getY());
        if(!Double.isFinite(diagonal)||diagonal<24)return State.SEARCHING;
        double aligned=0,reversed=0;
        for(int i=0;i<4;i++){
            VisionPoint e=expected.get(i),a=actual.get(i),b=actual.get((i+2)%4);
            aligned+=Math.hypot(e.getX()-a.getX(),e.getY()-a.getY());
            reversed+=Math.hypot(e.getX()-b.getX(),e.getY()-b.getY());
        }
        aligned/=4*diagonal;reversed/=4*diagonal;
        if(reversed<.28&&reversed+.12<aligned)return State.BACKWARD;
        return aligned<.28?State.PLACED:State.MISALIGNED;
    }
}
