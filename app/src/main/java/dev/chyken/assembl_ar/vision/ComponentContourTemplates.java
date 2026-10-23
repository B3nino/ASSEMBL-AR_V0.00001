package dev.chyken.assembl_ar.vision;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.camera.*;

/** Display-only rigid-body maps. Source polygons come from the reviewed per-photo
 * geometry, never a guessed rectangle in the live camera. These masks do not change
 * recognition descriptors or verification thresholds. */
public final class ComponentContourTemplates {
    public enum Body { BOARD, MOTOR, BRACKET, SENSOR, WHEEL, BATTERY, CABLE, FASTENER }
    private static final Map<String,Body> PARTS=new HashMap<>();
    private static final Map<String,List<List<VisionPoint>>> REGISTERED=new ConcurrentHashMap<>();
    static {
        PARTS.put(NeobotSeedData.MAIN_BOARD,Body.BOARD);PARTS.put(NeobotSeedData.EXTENSION_BOARD,Body.BOARD);
        PARTS.put(NeobotSeedData.MOTOR_DRIVER,Body.BOARD);PARTS.put(NeobotSeedData.MICROCONTROLLER,Body.BOARD);
        PARTS.put(NeobotSeedData.GEAR_MOTOR,Body.MOTOR);PARTS.put(NeobotSeedData.MOTOR_BRACKET,Body.BRACKET);
        PARTS.put(NeobotSeedData.DISTANCE_SENSOR,Body.SENSOR);PARTS.put(NeobotSeedData.LINE_TRACKER,Body.SENSOR);
        PARTS.put(NeobotSeedData.OLED_DISPLAY,Body.BOARD);PARTS.put(NeobotSeedData.OLED_PROTECTOR,Body.BOARD);
        PARTS.put(NeobotSeedData.BATTERY_PACK,Body.BATTERY);PARTS.put("drive_wheel",Body.WHEEL);
        PARTS.put(NeobotSeedData.CASTER_WHEEL,Body.WHEEL);PARTS.put("data_extension_cable",Body.CABLE);
    }
    private ComponentContourTemplates(){}
    public static Body body(String id){return PARTS.getOrDefault(id,Body.FASTENER);}
    public static void register(ComponentReferenceSpec spec){REGISTERED.put(spec.getReferenceId(),maps(spec));}
    public static List<List<VisionPoint>> forReference(ComponentReferenceSpec spec){
        List<List<VisionPoint>> value=maps(spec);REGISTERED.put(spec.getReferenceId(),value);return value;
    }
    public static String name(String id,String fallback){
        if(id==null)return "the requested component";
        if(id.equals(NeobotSeedData.MAIN_BOARD))return "Main Board";
        if(id.equals(NeobotSeedData.MOTOR_DRIVER))return "Motor Driver";
        if(id.equals(NeobotSeedData.MICROCONTROLLER))return "Arduino Nano";
        if(id.equals(NeobotSeedData.GEAR_MOTOR))return "TT Gear Motor";
        if(id.equals(NeobotSeedData.MOTOR_BRACKET))return "Motor Bracket";
        if(id.equals(NeobotSeedData.DISTANCE_SENSOR))return "Distance Sensor";
        if(id.equals(NeobotSeedData.LINE_TRACKER))return "Line Tracker";
        if(id.equals(NeobotSeedData.OLED_DISPLAY))return "OLED Display";
        if(id.equals(NeobotSeedData.EXTENSION_BOARD))return "Extension Board";
        if(id.equals(NeobotSeedData.BATTERY_PACK))return "Battery Pack";
        return (fallback==null?id:fallback).replace('_',' ');
    }
    private static List<List<VisionPoint>> maps(ComponentReferenceSpec spec){
        List<List<VisionPoint>> source=spec.getFeatureRegionsNormalized(),result=new ArrayList<>();String id=spec.getReferenceId();
        String p2=Phase2AssetCatalog.referenceRole(id),p7=Phase7AssetCatalog.referenceRole(id),p9=Phase9AssetCatalog.referenceRole(id);
        if("top".equals(p2)&&source.size()==6){result.add(union(source.subList(0,3)));result.add(union(source.subList(3,6)));}
        else if(p2!=null&&!source.isEmpty())result.addAll(source.subList(0,Math.min(4,source.size())));
        else if(p7!=null&&source.size()>=6){result.add(source.get(0));result.add(source.get(1));}
        else if("pair".equals(p9)&&source.size()>=2)result.addAll(source.subList(0,2));
        else if(p9!=null&&Phase9AssetCatalog.installed(p9)&&source.size()>=3)result.addAll(source.subList(0,3));
        // Installed Nano/driver/sensor/OLED maps already locate the component itself.
        // Their broad PCB feature masks are context only and must not become its outline.
        else result.add(spec.getPlanarCornersNormalized());
        List<List<VisionPoint>> immutable=new ArrayList<>();for(List<VisionPoint> p:result)immutable.add(Collections.unmodifiableList(new ArrayList<>(p)));
        return Collections.unmodifiableList(immutable);
    }
    private static List<VisionPoint> union(List<List<VisionPoint>> regions){
        double l=1,t=1,r=0,b=0;for(List<VisionPoint> region:regions)for(VisionPoint p:region){l=Math.min(l,p.getX());r=Math.max(r,p.getX());t=Math.min(t,p.getY());b=Math.max(b,p.getY());}
        return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));
    }
}
