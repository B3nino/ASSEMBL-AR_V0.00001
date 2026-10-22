package dev.chyken.assembl_ar.model;

import java.util.*;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Swatch;

/** User insulation colors; electrical destinations never change with the selected swatch. */
public final class ComponentWiringColorConfig {
    public enum Wire {
        TRIGGER(1,"Trigger","D2",Swatch.BLUE), ECHO(1,"Echo","D4",Swatch.PURPLE),
        LED(2,"LED · rail 12","A1",Swatch.GREEN), SPEAKER(2,"SPKR · rail 11","D3",Swatch.ORANGE),
        LEFT_VCC(3,"Left V+","5V rail 1",Swatch.RED), LEFT_GND(3,"Left G","GND rail 1",Swatch.BLACK), LEFT_SIGNAL(3,"Left S","A2",Swatch.YELLOW),
        RIGHT_VCC(3,"Right V+","5V rail 2",Swatch.RED), RIGHT_GND(3,"Right G","GND rail 2",Swatch.BLACK), RIGHT_SIGNAL(3,"Right S","A3",Swatch.BLUE);
        public final int section; public final String source,target; public final Swatch fallback;
        Wire(int section,String source,String target,Swatch fallback){this.section=section;this.source=source;this.target=target;this.fallback=fallback;}
        public String label(){return source+" → "+target;}
    }
    private final int[] colors;
    public final int triggerWireColor,echoWireColor,ledWireColor,speakerWireColor,leftTrackerVccColor,leftTrackerGndColor,leftTrackerSignalColor,rightTrackerVccColor,rightTrackerGndColor,rightTrackerSignalColor;
    public ComponentWiringColorConfig(int... values){
        if(values.length!=10)throw new IllegalArgumentException("Ten wire colors required");colors=values.clone();for(int c:colors)Swatch.fromArgb(c);
        triggerWireColor=colors[0];echoWireColor=colors[1];ledWireColor=colors[2];speakerWireColor=colors[3];leftTrackerVccColor=colors[4];leftTrackerGndColor=colors[5];leftTrackerSignalColor=colors[6];rightTrackerVccColor=colors[7];rightTrackerGndColor=colors[8];rightTrackerSignalColor=colors[9];
    }
    public static ComponentWiringColorConfig defaults(){return new ComponentWiringColorConfig(Arrays.stream(Wire.values()).mapToInt(w->w.fallback.argb).toArray());}
    public int color(Wire wire){return colors[wire.ordinal()];}
    public Swatch swatch(Wire wire){return Swatch.fromArgb(color(wire));}
    public ComponentWiringColorConfig with(Wire wire,Swatch swatch){int[] c=colors.clone();c[wire.ordinal()]=swatch.argb;return new ComponentWiringColorConfig(c);}
    public String encode(){StringJoiner s=new StringJoiner("/");for(int c:colors)s.add(Integer.toHexString(c));return s.toString();}
    public static ComponentWiringColorConfig decode(String value){String[] p=value.split("/",-1);if(p.length!=10)throw new IllegalArgumentException();int[] c=new int[10];for(int i=0;i<10;i++){if(!p[i].matches("[a-fA-F0-9]{8}"))throw new IllegalArgumentException();c[i]=Integer.parseUnsignedInt(p[i],16);}return new ComponentWiringColorConfig(c);}
    @Override public boolean equals(Object o){return o instanceof ComponentWiringColorConfig&&Arrays.equals(colors,((ComponentWiringColorConfig)o).colors);}
    @Override public int hashCode(){return Arrays.hashCode(colors);}
}
