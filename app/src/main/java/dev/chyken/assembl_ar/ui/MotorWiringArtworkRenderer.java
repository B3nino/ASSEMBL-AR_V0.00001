package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.data.SchematicComponentCatalog;
import dev.chyken.assembl_ar.data.SchematicComponentCatalog.Component;
import dev.chyken.assembl_ar.model.MotorWireColorConfig;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal;
import dev.chyken.assembl_ar.model.SchematicPlacement;
import dev.chyken.assembl_ar.model.SchematicPlacement.Point;

/** Packaged component artwork and real pin anchors, independent of camera/recognition threads. */
final class MotorWiringArtworkRenderer {
    private static final int INK=0xFF283423, MUTED=0xFF516248, GREEN=0xFF3F7D14;
    private static final ExecutorService LOADER=Executors.newSingleThreadExecutor();
    // Two immutable assets shared between the HUD, picker and expanded diagram.
    private static final Map<String,Bitmap> CACHE=new HashMap<>();
    private final Component motor=SchematicComponentCatalog.component(NeobotSeedData.GEAR_MOTOR);
    private final Component driver=SchematicComponentCatalog.component(NeobotSeedData.MOTOR_DRIVER);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix=new Matrix();
    private final Path path=new Path();
    private final PathMeasure measure=new PathMeasure();
    private final float[] position=new float[2];
    private final EnumMap<Terminal,RectF> hits=new EnumMap<>(Terminal.class);
    private final EnumMap<Terminal,Point[]> endpoints=new EnumMap<>(Terminal.class);
    private final EnumMap<Terminal,Point[]> wireSegments=new EnumMap<>(Terminal.class);
    private final float density, textScale;
    private volatile Bitmap motorImage,driverImage;
    private volatile boolean failed;
    private float sceneScale=1, offsetX,offsetY;

    MotorWiringArtworkRenderer(View owner){
        density=owner.getResources().getDisplayMetrics().density;
        textScale=owner.getResources().getDisplayMetrics().scaledDensity/density;
        Context context=owner.getContext().getApplicationContext();
        WeakReference<View> view=new WeakReference<>(owner);
        LOADER.execute(()->{
            try{
                motorImage=load(context,motor);driverImage=load(context,driver);
                failed=motorImage==null||driverImage==null;
            }catch(Exception error){failed=true;}
            View target=view.get();if(target!=null)target.postInvalidate();
        });
    }
    private static Bitmap load(Context context,Component component)throws Exception{
        synchronized(CACHE){
            Bitmap cached=CACHE.get(component.assetPath);if(cached!=null)return cached;
            try(InputStream stream=context.getAssets().open(component.assetPath)){
                BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
                Bitmap image=BitmapFactory.decodeStream(stream,null,options);
                if(image!=null)CACHE.put(component.assetPath,image);return image;
            }
        }
    }
    boolean isReady(){return motorImage!=null&&driverImage!=null;}

    void draw(Canvas canvas,int width,int height,MotorWireColorConfig colors,Terminal selected,
              boolean interactive,float progress,boolean motion,boolean forceFull){
        hits.clear();endpoints.clear();wireSegments.clear();
        if(width<dp(100)||height<dp(70))return;
        if(!isReady()){
            label(canvas,failed?"Component images unavailable":"Loading component images…",dp(12),height/2f,width-dp(24),12,false);return;
        }
        boolean full=forceFull||width>=dp(540)&&height>=dp(300);
        if(full){
            label(canvas,"MOTOR WIRING",dp(14),dp(22),width-dp(28),13,true);
            label(canvas,"Green blocks toward you · six small pins away",dp(14),dp(42),width-dp(28),11,false);
        }else{
            // Explicit wire selectors remain readable even in the smallest floating preview.
            float cell=(width-dp(12))/4f;
            for(Terminal t:Terminal.values()){
                RectF bounds=new RectF(dp(6)+cell*t.ordinal(),dp(4),dp(6)+cell*(t.ordinal()+1)-dp(2),dp(36));
                fill(t==selected?0xFFDCE9D2:0xFFE7EDE3);canvas.drawRoundRect(bounds,dp(5),dp(5),paint);
                label(canvas,t.shortLabel,bounds.left+dp(5),dp(23),bounds.width()-dp(10),10,t==selected);
                fill(colors.color(t));canvas.drawRect(bounds.left+dp(4),bounds.bottom-dp(3),bounds.right-dp(4),bounds.bottom-dp(1),paint);
                hits.put(t,bounds);
            }
            if(height<dp(140)){
                label(canvas,"Expand to inspect the connection",dp(10),height-dp(18),width-dp(20),11,false);
                return;
            }
            label(canvas,"Green blocks toward you · pins away",dp(10),dp(51),width-dp(20),10,false);
        }
        float top=full?dp(52):dp(57),bottom=height-(full?dp(57):dp(31));
        float sceneW=full?960:640,sceneH=full?540:230;
        sceneScale=Math.min((width-dp(12))/sceneW,Math.max(dp(12),bottom-top)/sceneH);
        offsetX=(width-sceneW*sceneScale)/2;offsetY=top+(bottom-top-sceneH*sceneScale)/2;
        int saved=canvas.save();canvas.translate(offsetX,offsetY);canvas.scale(sceneScale,sceneScale);
        if(full)full(canvas,colors,selected,interactive,progress,motion);
        else detail(canvas,colors,selected,progress,motion);
        canvas.restoreToCount(saved);
        String connection=(full?selected.label:selected.shortLabel)+" → "+selected.output;
        label(canvas,connection,dp(10),height-dp(full?34:15),width-dp(20),full?12:10,true);
        if(full)label(canvas,"Inner = toward board center. Ring: tighten screw. Dot: insert wire. Motor tabs are unmarked.",dp(10),height-dp(14),width-dp(20),10,false);
    }

    private void full(Canvas c,MotorWireColorConfig colors,Terminal selected,boolean interactive,float progress,boolean motion){
        SchematicPlacement left=SchematicPlacement.fit(motor,65,45,160,330,1);
        SchematicPlacement right=SchematicPlacement.fit(motor,735,45,160,330,1);
        SchematicPlacement board=SchematicPlacement.fit(driver,350,0,260,348,2);
        image(c,motorImage,left);image(c,motorImage,right);image(c,driverImage,board);
        sceneLabel(c,"LEFT MOTOR",50,400,210);sceneLabel(c,"RIGHT MOTOR",720,400,220);
        for(Terminal terminal:Terminal.values()){
            SchematicPlacement source=terminal.ordinal()<2?left:right;
            Point from=source.map(motor.pin(terminal.motorPinId));
            Point to=board.map(driver.pin(terminal.driverPinPrefix+"_opening"));
            Point screw=board.map(driver.pin(terminal.driverPinPrefix+"_screw"));
            double lane=terminal==Terminal.LEFT_POSITIVE?270:terminal==Terminal.LEFT_NEGATIVE?28:terminal==Terminal.RIGHT_POSITIVE?932:690;
            double base=terminal==Terminal.LEFT_POSITIVE||terminal==Terminal.RIGHT_NEGATIVE?435:480;
            route(from,to,lane,base);wire(c,colors.color(terminal),selected==terminal&&interactive,progress,motion);
            anchors(c,from,to,screw,selected==terminal?GREEN:0xFF354C31);
            endpoints.put(terminal,new Point[]{screen(from),screen(to),screen(screw)});
            wireSegments.put(terminal,screenRoute(from,to,lane,base));
        }
    }
    private void detail(Canvas c,MotorWireColorConfig colors,Terminal selected,float progress,boolean motion){
        SchematicPlacement source=SchematicPlacement.fit(motor,35,5,100,205,1);
        // Enlarge the genuine screw-block region, rather than substituting abstract terminals.
        SchematicPlacement board=SchematicPlacement.fitCrop(driver,280,0,330,151,2,0,0,1,350.0/1024);
        image(c,motorImage,source);image(c,driverImage,board);
        Point from=source.map(motor.pin(selected.motorPinId));
        Point to=board.map(driver.pin(selected.driverPinPrefix+"_opening"));
        Point screw=board.map(driver.pin(selected.driverPinPrefix+"_screw"));
        route(from,to,selected.motorPinId.equals("upper_tab")?170:12,218);
        wire(c,colors.color(selected),true,progress,motion);anchors(c,from,to,screw,GREEN);
        // Pills are the compact interaction targets; image endpoints offer another way to inspect.
        endpoints.put(selected,new Point[]{screen(from),screen(to),screen(screw)});
        wireSegments.put(selected,screenRoute(from,to,selected.motorPinId.equals("upper_tab")?170:12,218));
    }
    private void route(Point from,Point to,double lane,double base){
        path.reset();path.moveTo((float)from.x,(float)from.y);path.lineTo((float)lane,(float)from.y);
        path.lineTo((float)lane,(float)base);path.lineTo((float)to.x,(float)base);path.lineTo((float)to.x,(float)to.y);
    }
    private void wire(Canvas c,int color,boolean active,float progress,boolean motion){
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(active?GREEN:0xFF798974);paint.setStrokeWidth(active?10:8);c.drawPath(path,paint);
        paint.setColor(color);paint.setStrokeWidth(5);c.drawPath(path,paint);
        if(motion){measure.setPath(path,false);measure.getPosTan(measure.getLength()*progress,position,null);
            fill(color==Color.WHITE||color==Color.YELLOW?INK:Color.WHITE);c.drawCircle(position[0],position[1],2.6f,paint);}
    }
    private void anchors(Canvas c,Point from,Point to,Point screw,int color){
        fill(Color.WHITE);c.drawCircle((float)from.x,(float)from.y,7,paint);c.drawCircle((float)to.x,(float)to.y,7,paint);
        fill(color);c.drawCircle((float)from.x,(float)from.y,4,paint);c.drawCircle((float)to.x,(float)to.y,4,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(Color.WHITE);c.drawCircle((float)screw.x,(float)screw.y,12,paint);
        paint.setStrokeWidth(1.5f);paint.setColor(color);c.drawCircle((float)screw.x,(float)screw.y,12,paint);
    }
    private void image(Canvas c,Bitmap bitmap,SchematicPlacement place){
        Point origin=place.map(0,0),x=place.map(1,0),y=place.map(0,1);
        float[] src={0,0,bitmap.getWidth(),0,0,bitmap.getHeight()};
        float[] dst={(float)origin.x,(float)origin.y,(float)x.x,(float)x.y,(float)y.x,(float)y.y};
        matrix.setPolyToPoly(src,0,dst,0,3);int saved=c.save();
        c.clipRect((float)place.left,(float)place.top,(float)(place.left+place.width),(float)(place.top+place.height));
        fill(Color.WHITE);c.drawBitmap(bitmap,matrix,paint);c.restoreToCount(saved);
    }
    private Point screen(Point p){return new Point(offsetX+p.x*sceneScale,offsetY+p.y*sceneScale);}
    private Point[] screenRoute(Point from,Point to,double lane,double base){
        return new Point[]{screen(from),screen(new Point(lane,from.y)),screen(new Point(lane,base)),screen(new Point(to.x,base)),screen(to)};
    }
    Terminal hit(float x,float y){
        for(Map.Entry<Terminal,RectF> entry:hits.entrySet())if(entry.getValue().contains(x,y))return entry.getKey();
        Terminal nearest=null;double distance=dp(24)*dp(24);
        for(Map.Entry<Terminal,Point[]> entry:endpoints.entrySet())for(Point p:entry.getValue()){
            double d=(x-p.x)*(x-p.x)+(y-p.y)*(y-p.y);if(d<distance){distance=d;nearest=entry.getKey();}}
        if(nearest!=null)return nearest;
        distance=dp(10)*dp(10);
        for(Map.Entry<Terminal,Point[]> entry:wireSegments.entrySet()){
            Point[] points=entry.getValue();
            for(int i=1;i<points.length;i++){
                Point a=points[i-1],b=points[i];double dx=b.x-a.x,dy=b.y-a.y,length=dx*dx+dy*dy;
                double t=length==0?0:Math.max(0,Math.min(1,((x-a.x)*dx+(y-a.y)*dy)/length));
                double px=a.x+t*dx,py=a.y+t*dy,d=(x-px)*(x-px)+(y-py)*(y-py);
                if(d<distance){distance=d;nearest=entry.getKey();}
            }
        }
        return nearest;
    }
    private void sceneLabel(Canvas c,String value,float x,float y,float width){
        // Keep labels at a physical readable size despite the fitted scene transform.
        label(c,value,x,y,width,12/sceneScale,true);
    }
    private void label(Canvas c,String text,float x,float y,float width,float sp,boolean bold){
        fill(bold?INK:MUTED);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
        paint.setTextSize(dp(sp)*textScale);String shown=text;
        while(shown.length()>1&&paint.measureText(shown)>width)shown=shown.substring(0,shown.length()-1);
        if(!shown.equals(text)&&shown.length()>1)shown=shown.substring(0,shown.length()-1)+"…";
        c.drawText(shown,x,y,paint);
    }
    private void fill(int color){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setPathEffect(null);}
    private float dp(float value){return value*density;}
}
