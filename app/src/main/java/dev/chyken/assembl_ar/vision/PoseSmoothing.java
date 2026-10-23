package dev.chyken.assembl_ar.vision;

/** Shortest-arc quaternion interpolation avoids Rodrigues wraparound at +/- pi. */
public final class PoseSmoothing {
    private PoseSmoothing(){}
    public static double[] rotation(double[] before,double[] after,double alpha){
        double[] a=q(before),b=q(after);double dot=0;for(int i=0;i<4;i++)dot+=a[i]*b[i];if(dot<0)for(int i=0;i<4;i++)b[i]=-b[i];
        double[] c=new double[4];double norm=0;for(int i=0;i<4;i++){c[i]=a[i]+alpha*(b[i]-a[i]);norm+=c[i]*c[i];}norm=Math.sqrt(norm);for(int i=0;i<4;i++)c[i]/=norm;
        double angle=2*Math.atan2(Math.sqrt(c[0]*c[0]+c[1]*c[1]+c[2]*c[2]),c[3]);double v=Math.sqrt(c[0]*c[0]+c[1]*c[1]+c[2]*c[2]);if(v<1e-9)return new double[3];return new double[]{c[0]*angle/v,c[1]*angle/v,c[2]*angle/v};
    }
    private static double[] q(double[] r){double a=Math.sqrt(r[0]*r[0]+r[1]*r[1]+r[2]*r[2]);if(a<1e-10)return new double[]{0,0,0,1};double k=Math.sin(a/2)/a;return new double[]{r[0]*k,r[1]*k,r[2]*k,Math.cos(a/2)};}
}
