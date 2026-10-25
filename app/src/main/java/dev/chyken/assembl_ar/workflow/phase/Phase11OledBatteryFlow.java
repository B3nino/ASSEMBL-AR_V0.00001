package dev.chyken.assembl_ar.workflow.phase;

import java.util.*;

/** Explicit screens: playback, camera evidence, preparation and physical checks never overlap. */
public final class Phase11OledBatteryFlow {
    public enum Screen {
        OLED_SCAN, BATTERY_SCAN, BATTERY_HARDWARE_PREP, OLED_DEMO, OLED_WORK_SCAN, OLED_CHECK, BATTERY_DEMO, BATTERY_CHECK, CELEBRATION;
        public int scanStage(){return this==OLED_SCAN?1:this==BATTERY_SCAN?2:this==OLED_WORK_SCAN?3:0;}
        public boolean scan(){return scanStage()!=0;}
        public int videoIndex(){return this==OLED_DEMO?0:this==BATTERY_DEMO?1:-1;}
        public boolean video(){return videoIndex()>=0;}
        public boolean preparation(){return this==BATTERY_HARDWARE_PREP;}
        public boolean check(){return this==OLED_CHECK||this==BATTERY_CHECK;}
        public String label(){return LABELS[ordinal()];}
    }
    private static final String[] LABELS={"11.1 · Scan OLED display","11.2A · Scan battery pack","11.2B · Battery securing materials","11.3A · OLED insertion demo","11.3B · Verify OLED placement","11.3C · OLED seating check","11.4A · Battery placement demo","11.4B · Battery and power check","11.5 · Assembly complete"};
    private Screen screen=Screen.OLED_SCAN;
    private final EnumSet<Screen> done=EnumSet.noneOf(Screen.class);
    private int generation=1,hits;private boolean paused;private long evidenceStart,lastFrame=Long.MIN_VALUE;
    public Phase11OledBatteryFlow(long now){evidenceStart=now;}
    public Screen screen(){return screen;}
    public int step(){return screen.ordinal()+1;}
    public boolean complete(){return screen==Screen.CELEBRATION?allComplete():done.contains(screen);}
    public boolean inventoryComplete(){return done.containsAll(EnumSet.range(Screen.OLED_SCAN,Screen.BATTERY_HARDWARE_PREP));}
    public boolean allComplete(){return done.containsAll(EnumSet.range(Screen.OLED_SCAN,Screen.BATTERY_CHECK));}
    public boolean scanning(){return !paused&&screen.scan()&&!complete();}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:screen.scan()?screen.scanStage()<=2?PhaseFlow.Mode.SCAN_PARTS:PhaseFlow.Mode.VERIFY:screen.check()?PhaseFlow.Mode.PHYSICAL_CHECK:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.OLED_FIT,mode(),generation);}
    public boolean accepts(Object tag,long timestamp){if(!scanning()||!tag().equals(tag)||timestamp<evidenceStart||timestamp<=lastFrame)return false;lastFrame=timestamp;return true;}
    public void observe(boolean accepted){if(scanning()){hits=accepted?hits+1:0;if(hits>=2)done.add(screen);}}
    /** Called only by explicit preparation/demo actions, never by a player callback. */
    public void confirm(){if(screen.preparation()||screen.video())done.add(screen);}
    public void answerSecure(boolean secure){
        if(!screen.check())return;
        Screen prerequisite=Screen.values()[screen.ordinal()-1];
        if(secure&&done.contains(prerequisite))done.add(screen);else done.remove(screen);
    }
    public boolean next(long now){if(!complete()||screen==Screen.CELEBRATION)return false;screen=Screen.values()[screen.ordinal()+1];window(now);return true;}
    public boolean previous(long now){if(screen==Screen.OLED_SCAN)return false;screen=Screen.values()[screen.ordinal()-1];window(now);return true;}
    public boolean canRescan(){return AssemblyStepNavigationManager.canRescan(screen.scan(),complete());}
    public void rescan(long now){if(!canRescan())return;if(!screen.scan())return;done.remove(screen);if(screen==Screen.OLED_WORK_SCAN)done.remove(Screen.OLED_CHECK);window(now);}
    public boolean canFinish(boolean developer){return screen==Screen.CELEBRATION&&(developer||allComplete());}
    public void pause(){paused=true;generation++;hits=0;}
    public void resume(long now){paused=false;window(now);}
    private void window(long now){generation++;hits=0;evidenceStart=now;lastFrame=Long.MIN_VALUE;}
    public void devJump(int step,long now){if(step<1||step>Screen.values().length)throw new IllegalArgumentException();screen=Screen.values()[step-1];window(now);}
    public void devSkip(){done.add(screen);if(screen.check())done.add(Screen.values()[screen.ordinal()-1]);}
    public void devComplete(){done.addAll(EnumSet.allOf(Screen.class));screen=Screen.CELEBRATION;}
    public void restart(long now){done.clear();screen=Screen.OLED_SCAN;window(now);}
    public String snapshot(){StringBuilder bits=new StringBuilder();for(Screen s:Screen.values())bits.append(done.contains(s)?'1':'0');return "1:"+step()+","+bits;}
    public static Phase11OledBatteryFlow restore(String state,long now){
        Phase11OledBatteryFlow f=new Phase11OledBatteryFlow(now);if(state==null)return f;
        if("legacyBattery".equals(state)){f.done.addAll(EnumSet.of(Screen.OLED_SCAN,Screen.OLED_DEMO,Screen.OLED_WORK_SCAN,Screen.OLED_CHECK));f.screen=Screen.BATTERY_SCAN;return f;}
        try{String[] p=state.split(",",-1);if(p.length!=2||!p[0].startsWith("1:")||!p[1].matches("[01]{9}"))return f;int step=Integer.parseInt(p[0].substring(2));if(step<1||step>9)return f;
            f.screen=Screen.values()[step-1];for(Screen s:Screen.values())if(p[1].charAt(s.ordinal())=='1')f.done.add(s);
            for(Screen s:Screen.values())if(s.check()&&!f.done.contains(Screen.values()[s.ordinal()-1]))f.done.remove(s);
        }catch(RuntimeException invalid){return new Phase11OledBatteryFlow(now);}return f;
    }
}
