package dev.chyken.assembl_ar.model;

import java.util.*;
import dev.chyken.assembl_ar.model.SchematicPlacement.Point;

/** Circuit-style orthogonal routes. Reuse for future wiring diagrams; no curved or diagonal leads. */
public final class CircuitWireRoute {
    private CircuitWireRoute(){}
    public static List<Point> elbow(Point from,Point to,double lane){
        return route(from,new Point(lane,from.y),new Point(lane,to.y),to);
    }
    /** Reach an opposite-side header by going around the component, never through its artwork. */
    public static List<Point> perimeter(Point from,Point to,double lane,double topRail,double outerRail){
        return route(from,new Point(lane,from.y),new Point(lane,topRail),new Point(outerRail,topRail),new Point(outerRail,to.y),to);
    }
    private static List<Point> route(Point... values){
        List<Point> points=new ArrayList<>();
        for(Point point:values){
            if(!Double.isFinite(point.x)||!Double.isFinite(point.y))throw new IllegalArgumentException("Invalid wire anchor");
            if(!points.isEmpty()){
                Point previous=points.get(points.size()-1);if(previous.x==point.x&&previous.y==point.y)continue;
                if(previous.x!=point.x&&previous.y!=point.y)throw new IllegalArgumentException("Wire segments must meet at right angles");
            }points.add(point);
        }
        return Collections.unmodifiableList(points);
    }
    /** Exact segment hit testing, including long rails and all 90-degree elbows. */
    public static double distanceSquared(List<Point> route,double x,double y){
        double best=Double.POSITIVE_INFINITY;
        for(int i=1;i<route.size();i++){
            Point a=route.get(i-1),b=route.get(i);double dx=b.x-a.x,dy=b.y-a.y,length=dx*dx+dy*dy;
            double t=length==0?0:Math.max(0,Math.min(1,((x-a.x)*dx+(y-a.y)*dy)/length));
            double ex=x-(a.x+t*dx),ey=y-(a.y+t*dy);best=Math.min(best,ex*ex+ey*ey);
        }return best;
    }
}

