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
        result.put(driver.id,driver.build());
        Builder nano=new Builder(NeobotSeedData.MICROCONTROLLER,"arduino_nano.png",1672,940);
        String[] top={"D12","D11","D10","D9","D8","D7","D6","D5","D4","D3","D2","GND","RST_TOP","RX","TX"};
        String[] bottom={"D13","3V3","AREF","A0","A1","A2","A3","A4","A5","A6","A7","5V","RST_BOTTOM","GND_BOTTOM","VIN"};
        for(int i=0;i<15;i++){
            double x=246+i*(1193.0/14);
            nano.pin(top[i],top[i].replace("_TOP",""),top[i].equals("GND")?PinKind.GROUND_HEADER:PinKind.CONTROL_HEADER,x,127);
            String name=bottom[i];PinKind kind=name.equals("GND_BOTTOM")?PinKind.GROUND_HEADER:name.equals("VIN")||name.equals("5V")||name.equals("3V3")?PinKind.POWER_HEADER:PinKind.CONTROL_HEADER;
            nano.pin(name,name.replace("_BOTTOM",""),kind,x,819);
        }
        result.put(nano.id,nano.build());        Builder sensor=new Builder(NeobotSeedData.DISTANCE_SENSOR,"distance_sensor.png",1743,902);
        String[] sensorPins={"VCC","Trigger","Echo","GND"};for(int i=0;i<4;i++)sensor.pin(sensorPins[i],sensorPins[i],i==0?PinKind.POWER_HEADER:i==3?PinKind.GROUND_HEADER:PinKind.CONTROL_HEADER,747+i*83.3,850);result.put(sensor.id,sensor.build());
        Builder tracker=new Builder(NeobotSeedData.LINE_TRACKER,"line_tracker.png",1026,1533);
        tracker.pin("G","G",PinKind.GROUND_HEADER,441,1440).pin("V+","V+",PinKind.POWER_HEADER,514,1440).pin("S","S",PinKind.CONTROL_HEADER,586,1440);result.put(tracker.id,tracker.build());
        Builder power=new Builder("power_rail","power_rail.png",2152,731);for(int i=1;i<=10;i++)power.pin("5V_"+i,"5V "+i,PinKind.POWER_HEADER,289+(i-1)*174.8,344);result.put(power.id,power.build());
        Builder ground=new Builder("ground_signal_rail","ground_signal_rail.png",2146,733);for(int i=1;i<=12;i++)ground.pin(i==11?"SPKR":i==12?"LED":"GND_"+i,i==11?"SPKR":i==12?"LED":"GND "+i,i<=10?PinKind.GROUND_HEADER:PinKind.CONTROL_HEADER,221+(i-1)*154.7,338);result.put(ground.id,ground.build());
        COMPONENTS=Collections.unmodifiableMap(result);
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
