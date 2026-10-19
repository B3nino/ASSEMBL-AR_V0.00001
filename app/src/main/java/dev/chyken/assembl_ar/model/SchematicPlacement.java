package dev.chyken.assembl_ar.model;

import dev.chyken.assembl_ar.data.SchematicComponentCatalog.Component;
import dev.chyken.assembl_ar.data.SchematicComponentCatalog.Pin;

/** One transform for artwork and anchors, including cropped detail views and quarter turns. */
public final class SchematicPlacement {
    public static final class Point { public final double x,y;public Point(double x,double y){this.x=x;this.y=y;} }
    public final Component component;
    public final double left,top,width,height,cropLeft,cropTop,cropRight,cropBottom;
    public final int turns;
    private SchematicPlacement(Component c,double left,double top,double width,double height,int turns,double l,double t,double r,double b){
        component=c;this.left=left;this.top=top;this.width=width;this.height=height;this.turns=turns;cropLeft=l;cropTop=t;cropRight=r;cropBottom=b;
    }
    public static SchematicPlacement fit(Component c,double left,double top,double width,double height,int turns){return fitCrop(c,left,top,width,height,turns,0,0,1,1);}
    public static SchematicPlacement fitCrop(Component c,double left,double top,double width,double height,int turns,double l,double t,double r,double b){
        if(width<=0||height<=0||turns<0||turns>3||l<0||t<0||r>1||b>1||r<=l||b<=t)throw new IllegalArgumentException("Invalid schematic placement");
        double sourceW=c.width*(r-l),sourceH=c.height*(b-t),rotW=turns%2==0?sourceW:sourceH,rotH=turns%2==0?sourceH:sourceW;
        double scale=Math.min(width/rotW,height/rotH),w=rotW*scale,h=rotH*scale;
        return new SchematicPlacement(c,left+(width-w)/2,top+(height-h)/2,w,h,turns,l,t,r,b);
    }
    public Point map(Pin pin){return map(pin.x,pin.y);}
    public Point map(double x,double y){
        double u=(x-cropLeft)/(cropRight-cropLeft),v=(y-cropTop)/(cropBottom-cropTop),rx=u,ry=v;
        switch(turns){case 1:rx=1-v;ry=u;break;case 2:rx=1-u;ry=1-v;break;case 3:rx=v;ry=1-u;break;}
        return new Point(left+rx*width,top+ry*height);
    }
}
