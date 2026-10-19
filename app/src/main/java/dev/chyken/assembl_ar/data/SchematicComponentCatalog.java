package dev.chyken.assembl_ar.data;

import java.util.*;

/** Reusable, image-native component/pin definitions. All coordinates refer to the untouched artwork. */
public final class SchematicComponentCatalog {
    public enum PinKind { MOTOR_TAB, WIRE_OPENING, SCREW_HEAD, CONTROL_HEADER, POWER_HEADER, GROUND_HEADER }
    public static final class Pin {
        public final String id,label;
        public final PinKind kind;
        public final double x,y;
        private Pin(String id,String label,PinKind kind,double x,double y){this.id=id;this.label=label;this.kind=kind;this.x=x;this.y=y;}
    }
    public static final class Component {
        public final String id,assetPath;
        public final int width,height;
        private final Map<String,Pin> pins;
        private Component(String id,String asset,int width,int height,Map<String,Pin> pins){this.id=id;assetPath=asset;this.width=width;this.height=height;this.pins=Collections.unmodifiableMap(pins);}
        public Pin pin(String id){Pin pin=pins.get(id);if(pin==null)throw new IllegalArgumentException("Unknown pin "+id+" on "+this.id);return pin;}
        public Collection<Pin> pins(){return pins.values();}
    }
    private static final Map<String,Component> COMPONENTS;
    static {
        Map<String,Component> result=new LinkedHashMap<>();
        Builder motor=new Builder(NeobotSeedData.GEAR_MOTOR,"tt_gear_motor.png",605,291);
        motor.pin("upper_tab","Upper copper tab",PinKind.MOTOR_TAB,27,71).pin("lower_tab","Lower copper tab",PinKind.MOTOR_TAB,27,220);
        result.put(motor.id,motor.build());
        Builder driver=new Builder(NeobotSeedData.MOTOR_DRIVER,"dual_motor_driver.png",765,1024);
        String[] names={"b_left","b_right","a_left","a_right"};double[] xs={110,290,485,665};
        for(int i=0;i<4;i++){
            String pair=i<2?"MOTOR B":"MOTOR A",side=i%2==0?"left":"right";
            driver.pin(names[i]+"_opening",pair+" "+side+" wire opening",PinKind.WIRE_OPENING,xs[i],57)
                    .pin(names[i]+"_screw",pair+" "+side+" screw",PinKind.SCREW_HEAD,xs[i],218);
        }
        String[] headers={"B-IA","B-IB","GND","VCC","A-IA","A-IB"};
        for(int i=0;i<headers.length;i++)driver.pin(headers[i],headers[i],i==2?PinKind.GROUND_HEADER:i==3?PinKind.POWER_HEADER:PinKind.CONTROL_HEADER,185+i*85,946);
        result.put(driver.id,driver.build());COMPONENTS=Collections.unmodifiableMap(result);
    }
    private SchematicComponentCatalog(){}
    public static Component component(String id){Component c=COMPONENTS.get(id);if(c==null)throw new IllegalArgumentException("Unknown schematic component "+id);return c;}
    public static Collection<Component> components(){return COMPONENTS.values();}
    /** New artwork is registered once here; views consume its named anchors and dimensions. */
    private static final class Builder {
        final String id,asset;final int width,height;final Map<String,Pin> pins=new LinkedHashMap<>();
        Builder(String id,String asset,int width,int height){this.id=id;this.asset=asset;this.width=width;this.height=height;}
        Builder pin(String id,String label,PinKind kind,double x,double y){
            if(x<0||x>width||y<0||y>height||pins.containsKey(id))throw new IllegalArgumentException("Invalid schematic anchor "+id);
            pins.put(id,new Pin(id,label,kind,x/width,y/height));return this;
        }
        Component build(){return new Component(id,"schematics/components/"+asset,width,height,pins);}
    }
}
