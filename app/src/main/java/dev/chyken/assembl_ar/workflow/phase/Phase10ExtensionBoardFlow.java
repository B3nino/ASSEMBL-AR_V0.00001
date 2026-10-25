package dev.chyken.assembl_ar.workflow.phase;

import java.util.*;

/** Explicit screens: playback, camera evidence, preparation and physical checks never overlap. */
public final class Phase10ExtensionBoardFlow {
    public enum Screen {
        CABLE_SCAN, EXTENSION_SCAN, STANDOFF_SCREW_PREP, PROTECTOR_SCAN, PROTECTOR_HARDWARE_PREP,
        CABLE_MAIN_DEMO, CABLE_MAIN_CHECK, PROTECTOR_DEMO, PROTECTOR_WORK_SCAN, PROTECTOR_CHECK,
        BOARD_DEMO, BOARD_WORK_SCAN, BOARD_CHECK, CABLE_TOP_DEMO, CABLE_TOP_CHECK;
        public int scanStage(){return this==CABLE_SCAN?1:this==EXTENSION_SCAN?2:this==PROTECTOR_SCAN?3:this==PROTECTOR_WORK_SCAN?4:this==BOARD_WORK_SCAN?5:0;}
        public boolean scan(){return scanStage()!=0;}
        public int videoIndex(){return this==CABLE_MAIN_DEMO?0:this==PROTECTOR_DEMO?1:this==BOARD_DEMO?2:this==CABLE_TOP_DEMO?3:-1;}
        public boolean video(){return videoIndex()>=0;}
        public boolean preparation(){return this==STANDOFF_SCREW_PREP||this==PROTECTOR_HARDWARE_PREP;}
        public boolean check(){return this==CABLE_MAIN_CHECK||this==PROTECTOR_CHECK||this==BOARD_CHECK||this==CABLE_TOP_CHECK;}
        public String label(){return LABELS[ordinal()];}
    }
    private static final String[] LABELS={"10.1A · Scan data cable","10.1B · Scan extension board","10.1B · Saved standoff screw","10.1C · Scan OLED protector","10.1C · Protector screws and nuts","10.2A · Mainboard cable demo","10.2B · Mainboard cable check","10.3A · OLED protector demo","10.3B · Protector assembly scan","10.3C · Protector stability","10.4A · Extension board mounting demo","10.4B · Stacked assembly scan","10.4C · Top board stability","10.5A · Final cable connection demo","10.5B · Final cable check"};
    private Screen screen=Screen.CABLE_SCAN;
    private final EnumSet<Screen> done=EnumSet.noneOf(Screen.class);
    private int generation=1,hits;private boolean paused;private long evidenceStart,lastFrame=Long.MIN_VALUE;
    public Phase10ExtensionBoardFlow(long now){evidenceStart=now;}
    public Screen screen(){return screen;}
    public int step(){return screen.ordinal()+1;}
    public boolean complete(){return done.contains(screen);}
    public boolean inventoryComplete(){return done.containsAll(EnumSet.range(Screen.CABLE_SCAN,Screen.PROTECTOR_HARDWARE_PREP));}
    public boolean allComplete(){return done.size()==Screen.values().length;}
    public boolean scanning(){return !paused&&screen.scan()&&!complete();}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:screen.scan()?screen.scanStage()<=3?PhaseFlow.Mode.SCAN_PARTS:PhaseFlow.Mode.VERIFY:screen.check()?PhaseFlow.Mode.PHYSICAL_CHECK:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.OLED_PROTECTOR_FIT,mode(),generation);}
    public boolean accepts(Object tag,long timestamp){if(!scanning()||!tag().equals(tag)||timestamp<evidenceStart||timestamp<=lastFrame)return false;lastFrame=timestamp;return true;}
    public void observe(boolean accepted){if(scanning()){hits=accepted?hits+1:0;if(hits>=2)done.add(screen);}}
    /** Called only by explicit preparation/demo actions, never by a player callback. */
    public void confirm(){if(screen.preparation()||screen.video())done.add(screen);}
    public void answerSecure(boolean secure){
        if(!screen.check())return;
        Screen prerequisite=Screen.values()[screen.ordinal()-1];
        if(secure&&done.contains(prerequisite))done.add(screen);else done.remove(screen);
    }
    public boolean next(long now){if(!complete()||screen==Screen.CABLE_TOP_CHECK)return false;screen=Screen.values()[screen.ordinal()+1];window(now);return true;}
    public boolean previous(long now){if(screen==Screen.CABLE_SCAN)return false;screen=Screen.values()[screen.ordinal()-1];window(now);return true;}
    public boolean canRescan(){return AssemblyStepNavigationManager.canRescan(screen.scan(),complete());}
    public void rescan(long now){if(!canRescan())return;if(!screen.scan())return;done.remove(screen);if(screen==Screen.PROTECTOR_WORK_SCAN)done.remove(Screen.PROTECTOR_CHECK);if(screen==Screen.BOARD_WORK_SCAN)done.remove(Screen.BOARD_CHECK);window(now);}
    public boolean canFinish(boolean developer){return screen==Screen.CABLE_TOP_CHECK&&complete()&&(developer||allComplete());}
    public void pause(){paused=true;generation++;hits=0;}
    public void resume(long now){paused=false;window(now);}
    private void window(long now){generation++;hits=0;evidenceStart=now;lastFrame=Long.MIN_VALUE;}
    public void devJump(int step,long now){if(step<1||step>Screen.values().length)throw new IllegalArgumentException();screen=Screen.values()[step-1];window(now);}
    public void devSkip(){done.add(screen);if(screen.check())done.add(Screen.values()[screen.ordinal()-1]);}
    public void devComplete(){done.addAll(EnumSet.allOf(Screen.class));screen=Screen.CABLE_TOP_CHECK;}
    public void restart(long now){done.clear();screen=Screen.CABLE_SCAN;window(now);}
    public String snapshot(){StringBuilder bits=new StringBuilder();for(Screen s:Screen.values())bits.append(done.contains(s)?'1':'0');return "1:"+step()+","+bits;}
    public static Phase10ExtensionBoardFlow restore(String state,long now){
        Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(now);if(state==null)return f;
        // Older phases completed sections 14, 15 and 16 separately. Retain completed work
        // without treating one old certificate as proof of the new combined phase.
        if(state.startsWith("legacy:")){try{int section=Integer.parseInt(state.substring(7));
            if(section>=15){f.done.addAll(EnumSet.range(Screen.CABLE_SCAN,Screen.PROTECTOR_HARDWARE_PREP));f.done.addAll(EnumSet.of(Screen.PROTECTOR_DEMO,Screen.PROTECTOR_WORK_SCAN,Screen.PROTECTOR_CHECK));f.screen=Screen.CABLE_MAIN_DEMO;}
            if(section>=16){f.done.addAll(EnumSet.of(Screen.CABLE_MAIN_DEMO,Screen.CABLE_MAIN_CHECK,Screen.CABLE_TOP_DEMO,Screen.CABLE_TOP_CHECK));f.screen=Screen.BOARD_DEMO;}
        }catch(NumberFormatException invalid){return new Phase10ExtensionBoardFlow(now);}return f;}
        try{String[] p=state.split(",",-1);if(p.length!=2||!p[0].startsWith("1:")||!p[1].matches("[01]{15}"))return f;int step=Integer.parseInt(p[0].substring(2));if(step<1||step>15)return f;
            f.screen=Screen.values()[step-1];for(Screen s:Screen.values())if(p[1].charAt(s.ordinal())=='1')f.done.add(s);
            for(Screen s:Screen.values())if(s.check()&&!f.done.contains(Screen.values()[s.ordinal()-1]))f.done.remove(s);
        }catch(RuntimeException invalid){return new Phase10ExtensionBoardFlow(now);}return f;
    }
}
