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
import dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire;
import dev.chyken.assembl_ar.model.SchematicPlacement.Point;

/** Actual six-pin driver and Nano artwork. Named anchors also drive hit-testing at every size. */
final class DriverSchematicRenderer {
    private static final ExecutorService IMAGES=Executors.newSingleThreadExecutor();
    private final Component driver=SchematicComponentCatalog.component(NeobotSeedData.MOTOR_DRIVER),nano=SchematicComponentCatalog.component(NeobotSeedData.MICROCONTROLLER);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix=new Matrix();private final Path path=new Path();private final PathMeasure measure=new PathMeasure();private final float[] point=new float[2];
    private final EnumMap<Wire,List<Point>> samples=new EnumMap<>(Wire.class);
    private volatile Bitmap driverImage,nanoImage;private volatile boolean failed;
    private final float density,scaledDensity;
    DriverSchematicRenderer(View owner){
        density=owner.getResources().getDisplayMetrics().density;scaledDensity=owner.getResources().getDisplayMetrics().scaledDensity;
        Context app=owner.getContext().getApplicationContext();WeakReference<View> target=new WeakReference<>(owner);
        IMAGES.execute(()->{try{driverImage=SchematicArtworkCache.load(app,driver);nanoImage=SchematicArtworkCache.load(app,nano);}catch(Exception error){failed=true;}View view=target.get();if(view!=null)view.postInvalidate();});
    }
    boolean ready(){return driverImage!=null&&nanoImage!=null;}
    void draw(Canvas canvas,int width,int height,DriverWireColorConfig colors,Wire selected,int focus,float progress,boolean motion){
        samples.clear();if(width<100||height<80)return;
        text(canvas,"DRIVER → ARDUINO NANO",12*density,22*density,width-24*density,13,true);
        text(canvas,selected.label()+" · "+colors.swatch(selected).label,12*density,42*density,width-24*density,12,false);
        if(!ready()){text(canvas,failed?"Component artwork unavailable":"Loading component artwork…",12*density,height/2f,width-24*density,12,false);return;}
        float top=52*density,bottom=height-35*density,scale=Math.max(.01f,Math.min((width-12*density)/940f,(bottom-top)/600f));
        float ox=(width-940*scale)/2,oy=top+(bottom-top-600*scale)/2;
        int save=canvas.save();canvas.translate(ox,oy);canvas.scale(scale,scale);
        SchematicPlacement board=SchematicPlacement.fit(driver,40,145,350,265,3);
        SchematicPlacement target=SchematicPlacement.fitCrop(nano,620,20,230,560,3,.034,.024,.979,.948);
        SchematicArtworkCache.draw(canvas,driverImage,board,paint,matrix);SchematicArtworkCache.draw(canvas,nanoImage,target,paint,matrix);
        // Source order is physical; draw the inspected wire last so crossings remain unambiguous.
        List<Wire> order=new ArrayList<>(Arrays.asList(Wire.values()));order.remove(selected);order.add(selected);
        for(Wire wire:order){
            Point from=board.map(driver.pin(wire.driverPin)),to=target.map(nano.pin(wire.nanoPin));
            boolean relevant=focus==0||focus==1&&wire.power()||focus==2&&!wire.power(),active=wire==selected;
            if(!relevant&&!active)continue;
            // Each net has a separate vertical lane; VIN runs around the Nano's perimeter.
            List<Point> route=wire==Wire.VCC?CircuitWireRoute.perimeter(from,to,412,0,900)
                :CircuitWireRoute.elbow(from,to,wire==Wire.GND?446:wire==Wire.A1A?480:wire==Wire.A1B?514:wire==Wire.B1A?548:582);
            path.reset();path.moveTo((float)route.get(0).x,(float)route.get(0).y);
            List<Point> hits=new ArrayList<>();
            for(int i=0;i<route.size();i++){Point p=route.get(i);if(i>0)path.lineTo((float)p.x,(float)p.y);hits.add(new Point(ox+p.x*scale,oy+p.y*scale));}
            samples.put(wire,hits);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.BUTT);paint.setStrokeJoin(Paint.Join.MITER);
            paint.setColor(0xFFF1F6F1);paint.setAlpha(255);paint.setStrokeWidth(active?15:11);canvas.drawPath(path,paint);
            paint.setColor(active?0xFF263C2D:0xFF809486);paint.setAlpha(relevant?255:100);paint.setStrokeWidth(active?11:7);canvas.drawPath(path,paint);
            paint.setColor(colors.color(wire));paint.setAlpha(relevant?255:100);paint.setStrokeWidth(active?6:4);canvas.drawPath(path,paint);
            measure.setPath(path,false);
            if(motion&&relevant){measure.getPosTan(measure.getLength()*progress,point,null);fill(colors.color(wire)==Color.WHITE||colors.color(wire)==Color.YELLOW?0xFF203327:Color.WHITE);canvas.drawCircle(point[0],point[1],3,paint);}
            for(Point anchor:new Point[]{from,to}){fill(Color.WHITE);canvas.drawCircle((float)anchor.x,(float)anchor.y,active?12:8,paint);fill(active?0xFF315F35:0xFF617366);canvas.drawCircle((float)anchor.x,(float)anchor.y,active?6:4,paint);}
        }
        canvas.restoreToCount(save);
        Point selectedFrom=board.map(driver.pin(selected.driverPin)),selectedTo=target.map(nano.pin(selected.nanoPin));
        callout(canvas,selected.driverPin.replace('I','1'),ox+(float)selectedFrom.x*scale,oy+(float)selectedFrom.y*scale,width);
        callout(canvas,selected.nanoPin,ox+(float)selectedTo.x*scale,oy+(float)selectedTo.y*scale,width);
        text(canvas,"USB connector ↓ · A-1A / B-1A are printed A-IA / B-IA",12*density,height-13*density,width-24*density,10,false);
    }
    Wire hit(float x,float y){
        Wire best=null;double distance=24*density*24*density;
        for(Map.Entry<Wire,List<Point>> entry:samples.entrySet()){double d=CircuitWireRoute.distanceSquared(entry.getValue(),x,y);if(d<distance){distance=d;best=entry.getKey();}}
        return best;
    }
    private void callout(Canvas canvas,String label,float x,float y,int width){
        fill(0xFF253E2F);paint.setTextSize(12*scaledDensity);paint.setTypeface(Typeface.DEFAULT_BOLD);
        float w=paint.measureText(label)+12*density,h=24*density,left=Math.max(4*density,Math.min(width-w-4*density,x-w/2)),top=y-34*density;
        fill(0xFFF7FCF6);canvas.drawRoundRect(left,top,left+w,top+h,5*density,5*density,paint);
        fill(0xFF253E2F);canvas.drawText(label,left+6*density,top+17*density,paint);
    }
    private void fill(int color){paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);paint.setColor(color);}
    private void text(Canvas canvas,String value,float x,float y,float maxWidth,int sp,boolean bold){fill(0xFF293D32);paint.setTextSize(sp*scaledDensity);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);String shown=value;while(shown.length()>1&&paint.measureText(shown)>maxWidth)shown=shown.substring(0,shown.length()-1);if(!shown.equals(value)&&shown.length()>1)shown=shown.substring(0,shown.length()-1)+"…";canvas.drawText(shown,x,y,paint);}
}
