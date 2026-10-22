package dev.chyken.assembl_ar.workflow.phase;

import java.util.*;
import dev.chyken.assembl_ar.model.ComponentWiringColorConfig;

/** Explicit screens: playback callbacks can never navigate, verify work, or start the camera. */
public final class Phase8SensorWiringFlow {
    public enum Screen {
        SENSOR_SCAN, HARDWARE_PREP, MOUNT_DEMO, MOUNT_SCAN,
        DISTANCE_PREP, DISTANCE_COLORS, DISTANCE_WIRING,
        INDICATOR_PREP, INDICATOR_COLORS, INDICATOR_WIRING,
        ROUTING_DEMO, TRACKER_PREP, TRACKER_COLORS, TRACKER_WIRING;
        public boolean video(){return this==MOUNT_DEMO||this==ROUTING_DEMO;}
        public boolean scan(){return this==SENSOR_SCAN||this==MOUNT_SCAN;}
        public boolean prep(){return this==HARDWARE_PREP||this==DISTANCE_PREP||this==INDICATOR_PREP||this==TRACKER_PREP;}
        public boolean picker(){return this==DISTANCE_COLORS||this==INDICATOR_COLORS||this==TRACKER_COLORS;}
        public boolean wiring(){return this==DISTANCE_WIRING||this==INDICATOR_WIRING||this==TRACKER_WIRING;}
        public int section(){return ordinal()>=TRACKER_PREP.ordinal()?3:ordinal()>=INDICATOR_PREP.ordinal()?2:ordinal()>=DISTANCE_PREP.ordinal()?1:0;}
        public String label(){switch(this){case SENSOR_SCAN:return "8.1 · Scan distance sensor";case HARDWARE_PREP:return "8.1 · Jumper wire preparation";case MOUNT_DEMO:return "8.2A · Installation demo";case MOUNT_SCAN:return "8.2B · Verify installation";case ROUTING_DEMO:return "8.5A · Wire routing demo";default:return "8."+(section()+2)+" · "+(prep()?"Prepare wires":picker()?"Choose wire colors":"Connect and check wires");}}
    }
    private Screen screen=Screen.SENSOR_SCAN;
    private final EnumSet<Screen> done=EnumSet.noneOf(Screen.class);
    private ComponentWiringColorConfig colors=ComponentWiringColorConfig.defaults();
    private int generation=1,hits;private long evidenceStart,lastFrame=Long.MIN_VALUE;private boolean paused;
    public Phase8SensorWiringFlow(long now){evidenceStart=now;}
    public Screen screen(){return screen;}
    public int step(){return screen.ordinal()+1;}
    public boolean complete(){return done.contains(screen);}
    public boolean allComplete(){return done.size()==Screen.values().length;}
    public ComponentWiringColorConfig colors(){return colors;}
    public boolean scanning(){return !paused&&screen.scan()&&!complete();}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:screen.scan()?screen==Screen.SENSOR_SCAN?PhaseFlow.Mode.SCAN_PARTS:PhaseFlow.Mode.VERIFY:screen.wiring()?PhaseFlow.Mode.PHYSICAL_CHECK:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT,mode(),generation);}
    public boolean accepts(Object tag,long time){if(!scanning()||!tag().equals(tag)||time<evidenceStart||time<=lastFrame)return false;lastFrame=time;return true;}
    /** Two independent positive frames; a negative view cancels pending evidence, never hides a completed gate. */
    public void observe(boolean accepted){if(scanning()){hits=accepted?hits+1:0;if(hits>=2)done.add(screen);}}
    public void confirm(){if(!screen.scan()&&!screen.picker())done.add(screen);}
    public void applyColors(ComponentWiringColorConfig value,long now){
        if(!screen.picker()||value==null)throw new IllegalStateException();
        int section=screen.section();boolean changed=false;for(ComponentWiringColorConfig.Wire w:ComponentWiringColorConfig.Wire.values())if(w.section==section&&colors.color(w)!=value.color(w))changed=true;
        if(changed){done.remove(Screen.values()[screen.ordinal()+1]);done.remove(Screen.TRACKER_WIRING);}colors=value;done.add(screen);next(now);
    }
    public boolean next(long now){if(!complete()||screen==Screen.TRACKER_WIRING)return false;screen=Screen.values()[screen.ordinal()+1];window(now);return true;}
    public boolean previous(long now){if(screen==Screen.SENSOR_SCAN)return false;screen=Screen.values()[screen.ordinal()-1];window(now);return true;}
    /** A guarded rescan resets only this camera gate; earlier work and chosen colors survive. */
    public void rescan(long now){if(!screen.scan())return;done.remove(screen);window(now);}
    public boolean canFinish(boolean developer){return screen==Screen.TRACKER_WIRING&&complete()&&(developer||allComplete());}
    public void pause(){paused=true;generation++;hits=0;}
    public void resume(long now){paused=false;window(now);}
    private void window(long now){generation++;evidenceStart=now;lastFrame=Long.MIN_VALUE;hits=0;}
    public void devJump(int step,long now){if(step<1||step>Screen.values().length)throw new IllegalArgumentException();screen=Screen.values()[step-1];window(now);}
    public void devSkip(){done.add(screen);}
    public void devComplete(){done.addAll(EnumSet.allOf(Screen.class));screen=Screen.TRACKER_WIRING;}
    public void restart(long now){done.clear();screen=Screen.SENSOR_SCAN;window(now);}
    public String snapshot(){StringBuilder b=new StringBuilder();for(Screen s:Screen.values())b.append(done.contains(s)?'1':'0');return "1:"+step()+","+b+","+colors.encode();}
    public static Phase8SensorWiringFlow restore(String text,long now){
        Phase8SensorWiringFlow f=new Phase8SensorWiringFlow(now);if(text==null)return f;
        if(text.equals("legacyMount")){f.done.addAll(Arrays.asList(Screen.SENSOR_SCAN,Screen.HARDWARE_PREP,Screen.MOUNT_DEMO,Screen.MOUNT_SCAN));f.screen=Screen.DISTANCE_PREP;return f;}
        try{if(!text.startsWith("1:"))return f;String[] p=text.substring(2).split(",",-1);if(p.length!=3||!p[1].matches("[01]{14}"))return f;int step=Integer.parseInt(p[0]);if(step<1||step>14)return f;f.colors=ComponentWiringColorConfig.decode(p[2]);f.screen=Screen.values()[step-1];for(Screen s:Screen.values())if(p[1].charAt(s.ordinal())=='1')f.done.add(s);}catch(RuntimeException bad){return new Phase8SensorWiringFlow(now);}return f;
    }
}
