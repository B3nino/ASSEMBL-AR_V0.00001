package dev.chyken.assembl_ar.vision;

import java.util.List;

/** A sensor may swivel, but its mounting screw must still meet an end of that sensor's PCB. */
public final class Phase7MountGeometry {
    private Phase7MountGeometry(){}
    public static int distinctMounts(boolean[][] slots){
        if(slots[0][0]&&slots[1][1]||slots[0][1]&&slots[1][0])return 2;
        return slots[0][0]||slots[0][1]||slots[1][0]||slots[1][1]?1:0;
    }
    public static boolean fits(List<VisionPoint> sensor,VisionPoint screw,boolean verticalSource){
        VisionPoint p=PlanarCoordinateMapper.unprojectUnitSquare(sensor,screw);if(p==null)return false;
        double along=verticalSource?p.getY():p.getX();
        return p.getX()>=-.15&&p.getX()<=1.15&&p.getY()>=-.15&&p.getY()<=1.15
                &&Math.min(Math.abs(along),Math.abs(1-along))<.35;
    }
}
