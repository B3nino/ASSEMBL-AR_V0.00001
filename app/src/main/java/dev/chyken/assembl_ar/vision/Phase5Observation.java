package dev.chyken.assembl_ar.vision;

/** Nano identity and board-relative photo evidence; physical pin checks remain separate. */
public final class Phase5Observation {
    public enum State{SEARCHING,FRONT,FLIPPED,PLACED,BACKWARD,MISALIGNED}
    public final State state;
    public final double score;
    public Phase5Observation(State state,double score){this.state=state;this.score=score;}
    public boolean accepted(int step){return step==1?state==State.FRONT:step==3&&state==State.PLACED;}
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
        if(!Double.isFinite(aligned+reversed))return State.SEARCHING;
        if(reversed<.16&&reversed+.12<aligned)return State.BACKWARD;
        // A narrow two-row connector needs a tighter placement gate than the driver footprint.
        // This still does not certify individual hidden pins; Step 5.4 checks those explicitly.
        return aligned<.08?State.PLACED:State.MISALIGNED;
    }
    /** Rotation-invariant local matches alone cannot label a reversed installation as correct. */
    static State classify(int step,double front,double back,double placed,double reversed,double invalid){
        if(step==1){
            if(back>=12&&back>front*1.15)return State.FLIPPED;
            if(front>=12&&front>back*1.15)return State.FRONT;
        }else if(step==3){
            double rejection=Math.max(reversed,invalid);
            if(rejection>=10&&rejection>placed*1.15)return reversed>=invalid?State.BACKWARD:State.MISALIGNED;
            if(placed>=10&&placed>rejection*1.15)return State.PLACED;
        }
        return State.SEARCHING;
    }
}
