package dev.chyken.assembl_ar.vision;

/** Euler ZYX of actual * expected-front^T, in OpenCV camera axes.
 * The nominal front includes Rx(180): model +Y up, camera +Y down. */
public final class PoseOrientation {
    public final double pitch,roll,yaw;public final String label;public final boolean frontFacing;
    private PoseOrientation(double p,double r,double y,String label,boolean front){pitch=p;roll=r;yaw=y;this.label=label;frontFacing=front;}
    public static PoseOrientation fromRotation(double[] actual,double[] expected){
        if(actual.length!=9||expected!=null&&expected.length!=9)throw new IllegalArgumentException("3x3 rotation required");
        double[] e=expected==null?new double[]{1,0,0,0,-1,0,0,0,-1}:expected,d=new double[9];
        for(double v:actual)if(!Double.isFinite(v))throw new IllegalArgumentException("Finite rotation required");
        for(int r=0;r<3;r++)for(int c=0;c<3;c++)for(int k=0;k<3;k++)d[r*3+c]+=actual[r*3+k]*e[c*3+k];
        double sy=Math.hypot(d[0],d[3]);boolean singular=sy<1e-6;
        double roll=Math.toDegrees(Math.atan2(singular?-d[5]:d[7],singular?d[4]:d[8]));
        double pitch=Math.toDegrees(Math.atan2(-d[6],sy));double yaw=singular?0:Math.toDegrees(Math.atan2(d[3],d[0]));
        String label=Math.abs(roll)>135||d[8]<0?"Upside Down":Math.abs(yaw)<=30?"Forward":Math.abs(yaw)>=150?"Backward":yaw>=60&&yaw<=120?"Right":yaw<=-60&&yaw>=-120?"Left":"Upright";
        return new PoseOrientation(pitch,roll,yaw,label,d[8]>0);
    }
}
