package dev.chyken.assembl_ar.vision;

/** Visible sensor surface and board-relative placement; no hidden electrical claims. */
public final class Phase8Observation {
    public enum State { SEARCHING, FRONT, FLIPPED, PLACED, INVALID, INWARD }
    public final State state;public final double score;
    public Phase8Observation(State state,double score){this.state=state;this.score=score;}
    public boolean accepted(int stage){return stage==1?state==State.FRONT:stage==2&&state==State.PLACED;}
    static State classify(int step,double front,double back,double placed,double reversed,double invalid){
        double yes=step==1?front:placed,no=step==1?back:Math.max(reversed,invalid);
        if(yes>=10&&yes>no*1.15)return step==1?State.FRONT:State.PLACED;
        if(no>=12&&no>yes*1.2)return step==1?State.FLIPPED:State.INVALID;
        return State.SEARCHING;
    }
    static State placement(java.util.List<VisionPoint> expected,java.util.List<VisionPoint> actual){
        if(expected.size()!=4||actual.size()!=4)return State.SEARCHING;
        double d=Math.hypot(expected.get(0).getX()-expected.get(2).getX(),expected.get(0).getY()-expected.get(2).getY()),error=0;
        for(int i=0;i<4;i++)error+=Math.hypot(expected.get(i).getX()-actual.get(i).getX(),expected.get(i).getY()-actual.get(i).getY());
        return Double.isFinite(error)&&d>24&&error/(4*d)<.24?State.PLACED:State.INVALID;
    }
}
