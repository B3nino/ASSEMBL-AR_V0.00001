package dev.chyken.assembl_ar.model;

import java.util.*;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Swatch;

/** Insulation assignments for the six fixed driver-to-Nano electrical connections. */
public final class DriverWireColorConfig {
    public enum Wire {
        VCC("VCC","VIN",Swatch.RED), GND("GND","GND",Swatch.BLACK),
        A1A("A-IA","D5",Swatch.PURPLE), A1B("A-IB","D9",Swatch.PURPLE),
        B1A("B-IA","D6",Swatch.GREEN), B1B("B-IB","D10",Swatch.GREEN);
        public final String driverPin,nanoPin; public final Swatch fallback;
        Wire(String driver,String nano,Swatch fallback){driverPin=driver;nanoPin=nano;this.fallback=fallback;}
        public String label(){return driverPin.replace('I','1')+" → "+nanoPin;}
        public String spoken(){return driverPin.replace("-I"," one ")+" to Nano "+nanoPin.replace("D","digital pin ");}
        public boolean power(){return ordinal()<2;}
    }
    public final int vccWireColor,gndWireColor,a1aWireColor,a1bWireColor,b1aWireColor,b1bWireColor;
    public DriverWireColorConfig(int vcc,int gnd,int a1a,int a1b,int b1a,int b1b){
        for(int color:new int[]{vcc,gnd,a1a,a1b,b1a,b1b})Swatch.fromArgb(color);
        vccWireColor=vcc;gndWireColor=gnd;a1aWireColor=a1a;a1bWireColor=a1b;b1aWireColor=b1a;b1bWireColor=b1b;
    }
    public static DriverWireColorConfig defaults(){return new DriverWireColorConfig(0xFFFF0000,0xFF000000,0xFF800080,0xFF800080,0xFF008000,0xFF008000);}
    public int color(Wire wire){switch(wire){case VCC:return vccWireColor;case GND:return gndWireColor;case A1A:return a1aWireColor;case A1B:return a1bWireColor;case B1A:return b1aWireColor;default:return b1bWireColor;}}
    public Swatch swatch(Wire wire){return Swatch.fromArgb(color(wire));}
    public DriverWireColorConfig with(Wire wire,Swatch swatch){int[] colors=new int[6];for(Wire w:Wire.values())colors[w.ordinal()]=w==wire?swatch.argb:color(w);return new DriverWireColorConfig(colors[0],colors[1],colors[2],colors[3],colors[4],colors[5]);}
    public String encode(){StringJoiner text=new StringJoiner("/");for(Wire wire:Wire.values())text.add(Integer.toHexString(color(wire)));return text.toString();}
    public static DriverWireColorConfig decode(String text){
        if(text==null)throw new IllegalArgumentException("Missing colors");String[] parts=text.split("/",-1);if(parts.length!=6)throw new IllegalArgumentException("Six colors required");int[] colors=new int[6];
        for(int i=0;i<6;i++){if(!parts[i].matches("[a-fA-F0-9]{8}"))throw new IllegalArgumentException("Invalid wire color");colors[i]=Integer.parseUnsignedInt(parts[i],16);}
        return new DriverWireColorConfig(colors[0],colors[1],colors[2],colors[3],colors[4],colors[5]);
    }
    @Override public boolean equals(Object other){return other instanceof DriverWireColorConfig&&encode().equals(((DriverWireColorConfig)other).encode());}
    @Override public int hashCode(){return encode().hashCode();}
}
