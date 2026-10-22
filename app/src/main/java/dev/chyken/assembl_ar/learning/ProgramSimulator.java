package dev.chyken.assembl_ar.learning;
import java.util.*;
/** Deterministic block simulation, separate from arbitrary C++ editing and physical hardware. */
public final class ProgramSimulator {
    private final List<NeobotProgram.Block> blocks;private int index,elapsed,left,right;private boolean led;private double x=150,y=150,heading=-Math.PI/2;
    public ProgramSimulator(List<NeobotProgram.Block> blocks){this.blocks=new ArrayList<>(blocks);}
    public boolean finished(){return index>=blocks.size();}public int index(){return index;}public double x(){return x;}public double y(){return y;}public double heading(){return heading;}public boolean led(){return led;}
    public int distanceCm(){double dx=Math.cos(heading),dy=Math.sin(heading);double t=300;if(dx>1e-6)t=Math.min(t,(300-x)/dx);else if(dx< -1e-6)t=Math.min(t,-x/dx);if(dy>1e-6)t=Math.min(t,(300-y)/dy);else if(dy< -1e-6)t=Math.min(t,-y/dy);return Math.max(0,(int)t);}
    public int trackerLeft(){return Math.abs(x-150)<10?900:100;}public int trackerRight(){return Math.abs(x-150)<10?900:100;}
    public void advance(int millis){if(millis<0||millis>1000)throw new IllegalArgumentException();int remaining=millis;while(remaining>0&&!finished()){
        NeobotProgram.Block b=blocks.get(index);if(elapsed==0)switch(b.kind){case FORWARD:left=right=b.pwm;break;case BACKWARD:left=right=-b.pwm;break;case LEFT:left=-b.pwm;right=b.pwm;break;case RIGHT:left=b.pwm;right=-b.pwm;break;case STOP:left=right=0;break;case LED_ON:led=true;break;case LED_OFF:led=false;break;default:break;}
        int dt=Math.min(remaining,b.durationMs-elapsed);double seconds=dt/1000.0;heading+=(left-right)/255.0*1.5*seconds;double velocity=(left+right)/510.0*65;
        x=Math.max(10,Math.min(290,x+Math.cos(heading)*velocity*seconds));y=Math.max(10,Math.min(290,y+Math.sin(heading)*velocity*seconds));remaining-=dt;elapsed+=dt;
        if(elapsed==b.durationMs){index++;elapsed=0;}
    }if(finished())left=right=0;}
}
