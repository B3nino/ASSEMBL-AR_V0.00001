package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import dev.chyken.assembl_ar.data.*;
import dev.chyken.assembl_ar.data.SchematicComponentCatalog.Component;
import dev.chyken.assembl_ar.model.*;
import dev.chyken.assembl_ar.model.ComponentWiringColorConfig.Wire;
import dev.chyken.assembl_ar.model.SchematicPlacement.Point;

/** Reusable photo-based pin diagrams. Every route is rectilinear; crossings have no junction dots. */
final class ComponentSchematicRenderer {
    private static final ExecutorService IMAGES=Executors.newSingleThreadExecutor();
    private final Map<String,Bitmap> images=new ConcurrentHashMap<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix=new Matrix();private final Path path=new Path();private final PathMeasure measure=new PathMeasure();private final float[] pulse=new float[2];
    private final EnumMap<Wire,List<Point>> hits=new EnumMap<>(Wire.class);
    private final float density,scaledDensity;private volatile boolean failed;
    ComponentSchematicRenderer(View owner){density=owner.getResources().getDisplayMetrics().density;scaledDensity=owner.getResources().getDisplayMetrics().scaledDensity;Context app=owner.getContext().getApplicationContext();WeakReference<View> target=new WeakReference<>(owner);IMAGES.execute(()->{try{for(String id:new String[]{NeobotSeedData.MICROCONTROLLER,NeobotSeedData.DISTANCE_SENSOR,NeobotSeedData.LINE_TRACKER,"power_rail","ground_signal_rail"})images.put(id,SchematicArtworkCache.load(app,SchematicComponentCatalog.component(id)));}catch(Exception error){failed=true;}View v=target.get();if(v!=null)v.postInvalidate();});}
    boolean ready(){return images.size()==5;}
    void draw(Canvas canvas,int width,int height,ComponentWiringColorConfig colors,int section,Wire selected,float progress,boolean motion){
        hits.clear();if(width<100||height<80)return;
        text(canvas,section==1?"DISTANCE SENSOR → NANO":section==2?"LED & SPEAKER → NANO":"DUAL LINE TRACKERS · PHYSICAL LEFT / RIGHT",12*density,22*density,13,true);
        text(canvas,selected.label()+" · "+colors.swatch(selected).label,12*density,43*density,12,false);
        if(!ready()){text(canvas,failed?"Component artwork unavailable":"Loading component artwork…",12*density,height/2f,12,false);return;}
        float scale=Math.max(.01f,Math.min((width-24*density)/1100f,(height-110*density)/730f));float ox=(width-1100*scale)/2,oy=55*density;
        int save=canvas.save();canvas.translate(ox,oy);canvas.scale(scale,scale);
        SchematicPlacement nano=placement(NeobotSeedData.MICROCONTROLLER,790,35,255,550,3,.034,.024,.979,.948);drawImage(canvas,nano);
        Map<Wire,Point> from=new EnumMap<>(Wire.class),to=new EnumMap<>(Wire.class);
        if(section==1){
            SchematicPlacement sensor=placement(NeobotSeedData.DISTANCE_SENSOR,30,120,490,275,0,.06,0,.98,.98);drawImage(canvas,sensor);
            from.put(Wire.TRIGGER,sensor.map(sensor.component.pin("Trigger")));from.put(Wire.ECHO,sensor.map(sensor.component.pin("Echo")));
        }else if(section==2){
            SchematicPlacement rail=placement("ground_signal_rail",20,160,660,210,0,.02,.20,.99,.81);drawImage(canvas,rail);
            from.put(Wire.LED,rail.map(rail.component.pin("LED")));from.put(Wire.SPEAKER,rail.map(rail.component.pin("SPKR")));
        }else{
            SchematicPlacement left=placement(NeobotSeedData.LINE_TRACKER,30,50,190,420,0,.32,.035,.68,.98),right=placement(NeobotSeedData.LINE_TRACKER,300,50,190,420,0,.32,.035,.68,.98);drawImage(canvas,left);drawImage(canvas,right);
            SchematicPlacement power=placement("power_rail",20,600,500,125,0,.05,.2,.95,.76),ground=placement("ground_signal_rail",550,600,530,125,0,.02,.2,.99,.81);drawImage(canvas,power);drawImage(canvas,ground);
            from.put(Wire.LEFT_VCC,left.map(left.component.pin("V+")));from.put(Wire.LEFT_GND,left.map(left.component.pin("G")));from.put(Wire.LEFT_SIGNAL,left.map(left.component.pin("S")));
            from.put(Wire.RIGHT_VCC,right.map(right.component.pin("V+")));from.put(Wire.RIGHT_GND,right.map(right.component.pin("G")));from.put(Wire.RIGHT_SIGNAL,right.map(right.component.pin("S")));
            to.put(Wire.LEFT_VCC,power.map(power.component.pin("5V_1")));to.put(Wire.RIGHT_VCC,power.map(power.component.pin("5V_2")));to.put(Wire.LEFT_GND,ground.map(ground.component.pin("GND_1")));to.put(Wire.RIGHT_GND,ground.map(ground.component.pin("GND_2")));
            label(canvas,"LEFT · A2",35,25);label(canvas,"RIGHT · A3",305,25);
        }
        for(Wire wire:from.keySet())if(!to.containsKey(wire))to.put(wire,nano.map(nano.component.pin(wire.target)));
        List<Wire> order=new ArrayList<>(from.keySet());order.remove(selected);if(from.containsKey(selected))order.add(selected);
        for(Wire wire:order){Point a=from.get(wire),b=to.get(wire);int local=section==3?wire.ordinal()-4:wire.ordinal()%2;
            List<Point> route;
            if(section==3&&(wire==Wire.LEFT_VCC||wire==Wire.RIGHT_VCC||wire==Wire.LEFT_GND||wire==Wire.RIGHT_GND)){
                double laneY=500+local*14;route=Arrays.asList(a,new Point(a.x,laneY),new Point(b.x,laneY),b);
            }else{
                double laneX=section==3?600+(wire==Wire.LEFT_SIGNAL?0:60):610+local*55,laneY=section==3?540+local*10:440+local*35;
                if(b.x>nano.left+nano.width*.5){
                    // Analog pins are on the far header row. Approach from outside that edge,
                    // rather than drawing a wire through the Nano artwork to reach the socket.
                    double topLane=local%2==0?0:14,rightLane=local%2==0?1080:1064;
                    route=Arrays.asList(a,new Point(a.x,laneY),new Point(laneX,laneY),new Point(laneX,topLane),new Point(rightLane,topLane),new Point(rightLane,b.y),b);
                }else route=Arrays.asList(a,new Point(a.x,laneY),new Point(laneX,laneY),new Point(laneX,b.y),b);
            }
            boolean active=wire==selected;path.reset();List<Point> screen=new ArrayList<>();for(int i=0;i<route.size();i++){Point p=route.get(i);if(i==0)path.moveTo((float)p.x,(float)p.y);else path.lineTo((float)p.x,(float)p.y);screen.add(new Point(ox+p.x*scale,oy+p.y*scale));}hits.put(wire,screen);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.BUTT);paint.setStrokeJoin(Paint.Join.MITER);paint.setColor(0xFFF1F6F1);paint.setStrokeWidth(active?17:12);canvas.drawPath(path,paint);paint.setColor(active?0xFF294A33:0xFF768C7B);paint.setStrokeWidth(active?12:8);canvas.drawPath(path,paint);paint.setColor(colors.color(wire));paint.setStrokeWidth(active?7:5);canvas.drawPath(path,paint);
            if(motion){measure.setPath(path,false);measure.getPosTan(measure.getLength()*progress,pulse,null);fill(colors.color(wire)==Color.WHITE||colors.color(wire)==Color.YELLOW?0xFF203327:Color.WHITE);canvas.drawCircle(pulse[0],pulse[1],3.5f,paint);}
            for(Point p:new Point[]{a,b}){fill(0xFFF8FBF7);canvas.drawCircle((float)p.x,(float)p.y,10,paint);fill(active?0xFF2D5A35:0xFF637A69);canvas.drawCircle((float)p.x,(float)p.y,5,paint);}
        }
        canvas.restoreToCount(save);
        if(from.containsKey(selected)){callout(canvas,selected.source,from.get(selected),ox,oy,scale,width);callout(canvas,selected.target,to.get(selected),ox,oy,scale,width);}
        text(canvas,section==1?"VCC and GND connect through the PCB · No extra jumpers":section==2?"Rail socket 11: SPKR · Socket 12: LED · Grounds supplied by PCB":"Bottom view mirrors sides: screen-left = physical right (A3)",12*density,height-16*density,10,false);
    }
    private SchematicPlacement placement(String id,double x,double y,double w,double h,int turns,double l,double t,double r,double b){return SchematicPlacement.fitCrop(SchematicComponentCatalog.component(id),x,y,w,h,turns,l,t,r,b);}
    private void drawImage(Canvas c,SchematicPlacement p){SchematicArtworkCache.draw(c,images.get(p.component.id),p,paint,matrix);}
    private void label(Canvas c,String text,float x,float y){fill(0xFF2D4934);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(24);c.drawText(text,x,y,paint);}
    private void callout(Canvas c,String text,Point p,float ox,float oy,float scale,int width){float x=ox+(float)p.x*scale,y=oy+(float)p.y*scale;fill(0xFFF8FBF7);paint.setTextSize(12*scaledDensity);paint.setTypeface(Typeface.DEFAULT_BOLD);float w=paint.measureText(text)+12*density,left=Math.max(3*density,Math.min(width-w-3*density,x-w/2));c.drawRoundRect(left,y-32*density,left+w,y-8*density,4*density,4*density,paint);fill(0xFF294735);c.drawText(text,left+6*density,y-15*density,paint);}
    Wire hit(float x,float y){Wire best=null;double distance=24*density*24*density;for(Map.Entry<Wire,List<Point>> e:hits.entrySet()){double d=CircuitWireRoute.distanceSquared(e.getValue(),x,y);if(d<distance){distance=d;best=e.getKey();}}return best;}
    private void text(Canvas c,String value,float x,float y,int sp,boolean bold){fill(0xFF2D4934);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);paint.setTextSize(sp*scaledDensity);float available=Math.max(0,c.getWidth()-x-12*density);if(paint.measureText(value)>available){int count=paint.breakText(value,true,Math.max(0,available-paint.measureText("…")),null);value=value.substring(0,count)+"…";}c.drawText(value,x,y,paint);}
    private void fill(int color){paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);paint.setColor(color);}
}
