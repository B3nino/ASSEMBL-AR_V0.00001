package dev.chyken.assembl_ar.model;

import java.util.Objects;

/** Immutable insulation colors. Choosing a color never changes a terminal's electrical role. */
public final class MotorWireColorConfig {
    public enum Swatch {
        BLACK("Black",0xFF000000), RED("Red",0xFFFF0000), BROWN("Brown",0xFF8B4513),
        ORANGE("Orange",0xFFFFA500), YELLOW("Yellow",0xFFFFFF00), GREEN("Green",0xFF008000),
        BLUE("Blue",0xFF0000FF), PURPLE("Purple",0xFF800080), GRAY("Gray",0xFF808080), WHITE("White",0xFFFFFFFF);
        public final String label;
        public final int argb;
        Swatch(String label,int argb){this.label=label;this.argb=argb;}
        public String hex(){return String.format(java.util.Locale.ROOT,"#%06X",argb&0xFFFFFF);}
        public static Swatch fromArgb(int color){for(Swatch s:values())if(s.argb==color)return s;throw new IllegalArgumentException("Unsupported motor wire color");}
    }
    public enum Terminal {
        // Keep enum order and saved color fields stable. The artwork does not establish motor polarity.
        LEFT_POSITIVE("Left motor · inner tab","Left pair · left opening","L inner","upper_tab","a_right"),
        LEFT_NEGATIVE("Left motor · outer tab","Left pair · right opening","L outer","lower_tab","a_left"),
        RIGHT_POSITIVE("Right motor · outer tab","Right pair · right opening","R outer","upper_tab","b_left"),
        RIGHT_NEGATIVE("Right motor · inner tab","Right pair · left opening","R inner","lower_tab","b_right");
        public final String label,output,shortLabel,motorPinId,driverPinPrefix;
        Terminal(String label,String output,String shortLabel,String motorPinId,String driverPinPrefix){this.label=label;this.output=output;this.shortLabel=shortLabel;this.motorPinId=motorPinId;this.driverPinPrefix=driverPinPrefix;}
        public String spoken(){return label.replace(" · ",", ");}
    }
    public final int leftMotorPositiveColor,leftMotorNegativeColor,rightMotorPositiveColor,rightMotorNegativeColor;
    public MotorWireColorConfig(int leftPositive,int leftNegative,int rightPositive,int rightNegative){
        for(int value:new int[]{leftPositive,leftNegative,rightPositive,rightNegative})Swatch.fromArgb(value);
        leftMotorPositiveColor=leftPositive;leftMotorNegativeColor=leftNegative;
        rightMotorPositiveColor=rightPositive;rightMotorNegativeColor=rightNegative;
    }
    public static MotorWireColorConfig defaults(){return new MotorWireColorConfig(Swatch.RED.argb,Swatch.BLACK.argb,Swatch.RED.argb,Swatch.BLACK.argb);}
    public int color(Terminal terminal){switch(Objects.requireNonNull(terminal)){
        case LEFT_POSITIVE:return leftMotorPositiveColor;case LEFT_NEGATIVE:return leftMotorNegativeColor;
        case RIGHT_POSITIVE:return rightMotorPositiveColor;default:return rightMotorNegativeColor;}}
    public Swatch swatch(Terminal terminal){return Swatch.fromArgb(color(terminal));}
    public MotorWireColorConfig with(Terminal terminal,Swatch swatch){
        int[] values={leftMotorPositiveColor,leftMotorNegativeColor,rightMotorPositiveColor,rightMotorNegativeColor};
        values[terminal.ordinal()]=Objects.requireNonNull(swatch).argb;return new MotorWireColorConfig(values[0],values[1],values[2],values[3]);
    }
    public String encode(){StringBuilder s=new StringBuilder();for(Terminal t:Terminal.values()){if(s.length()>0)s.append('/');s.append(Integer.toHexString(color(t)));}return s.toString();}
    public static MotorWireColorConfig decode(String value){
        if(value==null)throw new IllegalArgumentException("Missing wire colors");String[] p=value.split("/",-1);
        if(p.length!=4)throw new IllegalArgumentException("Four wire colors required");int[] colors=new int[4];
        for(int i=0;i<4;i++){if(!p[i].matches("[a-fA-F0-9]{8}"))throw new IllegalArgumentException("Invalid color");colors[i]=Integer.parseUnsignedInt(p[i],16);}
        return new MotorWireColorConfig(colors[0],colors[1],colors[2],colors[3]);
    }
    @Override public boolean equals(Object o){return o instanceof MotorWireColorConfig&&encode().equals(((MotorWireColorConfig)o).encode());}
    @Override public int hashCode(){return Objects.hash(leftMotorPositiveColor,leftMotorNegativeColor,rightMotorPositiveColor,rightMotorNegativeColor);}
}
