package dev.chyken.assembl_ar.vision;

import java.util.*;

/** Manually inspected 640x400 tablet views. Body, outline and stationary PCB
 * context are separate: motor wires and adjacent connectors are not driver pixels. */
public final class Phase3ReferenceAnnotations {
    public static final int VERSION=4;
    private static final String ROOT="references/Phase3/STEP3_5ASSEMBLYSCAN/";
    private static final Map<String,Regions> VIEWS=new HashMap<>();
    static {
        add("TABLET_DRIVER_PLACEMENT_20261024.jpg",
            quad(264,165,346,165,346,267,263,267),quad(267,168,344,167,344,236,265,236),
            new double[]{275,181,333,181},5,
            rect(220,187,257,264),rect(357,174,377,268),rect(266,277,344,326),rect(265,330,345,395));
        add("TABLET_DRIVER_PLACEMENT_ANGLE_20261024.jpg",
            quad(221,164,314,166,310,275,212,272),quad(224,168,311,170,307,237,216,234),
            new double[]{238,181,298,183},5,
            rect(174,186,209,272),rect(327,174,346,277),rect(218,281,309,329),rect(214,335,311,395));
        add("TABLET_DRIVER_PLACEMENT_CLOSE_20261024.jpg",
            quad(224,139,339,156,317,267,186,249),quad(229,147,335,161,324,222,213,205),
            new double[]{225,164,320,176},6,
            quad(175,144,231,153,210,243,160,230),quad(351,157,371,162,351,273,332,267),
            quad(201,270,303,284,291,331,189,314),quad(194,337,281,348,272,397,179,397));
        add("TABLET_DRIVER_PLACEMENT_FAR_20261024.jpg",
            quad(414,84,486,84,487,182,413,182),quad(419,88,482,88,483,151,415,150),
            new double[]{426,99,475,100},4,
            rect(378,102,409,177),rect(498,88,518,184),rect(416,188,485,234),rect(416,242,493,389));
        // Actual tablet captures of the backward installation. Context stops before
        // the Nano socket: a later installed Nano must not remove the orientation cue.
        backward("TABLET_DRIVER_BACKWARD_WIDE_20261024.jpg",
            quad(437,183,535,183,533,298,433,298),quad(440,217,528,217,527,292,438,292),
            new double[]{445,223,520,223,446,280,518,279},4,
            rect(446,116,504,180),rect(392,207,432,302),rect(542,186,574,314),rect(434,303,535,337),rect(572,103,639,190));
        backward("TABLET_DRIVER_BACKWARD_NEAR_20261024.jpg",
            quad(250,130,389,130,394,307,253,307),quad(259,182,386,181,388,297,259,297),
            new double[]{274,194,376,195,271,271,379,271},6,
            rect(280,42,367,130),rect(177,181,246,306),rect(407,143,449,317),rect(251,309,395,352),rect(448,23,575,143));
    }
    private Phase3ReferenceAnnotations(){}
    public static Regions forPath(String path){return path==null?null:VIEWS.get(path);}
    public static final class Regions {
        public final List<VisionPoint> outline,identity,holes;
        public final List<List<VisionPoint>> context;
        public final double holeRadius;
        private Regions(List<VisionPoint> outline,List<VisionPoint> identity,List<VisionPoint> holes,double radius,List<List<VisionPoint>> context){
            this.outline=outline;this.identity=identity;this.holes=holes;holeRadius=radius/640;
            this.context=Collections.unmodifiableList(new ArrayList<>(context));
        }
        public List<List<VisionPoint>> features(){List<List<VisionPoint>> result=new ArrayList<>();result.add(identity);result.addAll(context);return Collections.unmodifiableList(result);}
    }
    @SafeVarargs private static void add(String name,List<VisionPoint> outline,List<VisionPoint> identity,double[] holes,double radius,List<VisionPoint>... context){
        addPath(ROOT+name,outline,identity,holes,radius,context);
    }
    @SafeVarargs private static void backward(String name,List<VisionPoint> outline,List<VisionPoint> identity,double[] holes,double radius,List<VisionPoint>... context){
        addPath("references/Phase3/STEP3_1MOTORDRIVER_INVALID/"+name,outline,identity,holes,radius,context);
    }
    @SafeVarargs private static void addPath(String path,List<VisionPoint> outline,List<VisionPoint> identity,double[] holes,double radius,List<VisionPoint>... context){
        List<VisionPoint> points=new ArrayList<>();for(int i=0;i<holes.length;i+=2)points.add(new VisionPoint(holes[i]/640,holes[i+1]/400));
        VIEWS.put(path,new Regions(outline,identity,Collections.unmodifiableList(points),radius,Arrays.asList(context)));
    }
    private static List<VisionPoint> rect(double l,double t,double r,double b){return quad(l,t,r,t,r,b,l,b);}
    private static List<VisionPoint> quad(double... values){List<VisionPoint> result=new ArrayList<>();for(int i=0;i<values.length;i+=2)result.add(new VisionPoint(values[i]/640,values[i+1]/400));return Collections.unmodifiableList(result);}
    public static boolean contains(List<VisionPoint> polygon,double x,double y){
        double sign=0;for(int i=0;i<polygon.size();i++){VisionPoint a=polygon.get(i),b=polygon.get((i+1)%polygon.size());double cross=(b.getX()-a.getX())*(y-a.getY())-(b.getY()-a.getY())*(x-a.getX());if(Math.abs(cross)<1e-10)continue;if(sign==0)sign=Math.signum(cross);else if(sign*cross<0)return false;}return sign!=0;
    }
    public static double[] bounds(List<VisionPoint> points){double l=1,t=1,r=0,b=0;for(VisionPoint p:points){l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}return new double[]{l,t,r,b};}
}
