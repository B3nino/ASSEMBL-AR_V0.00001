package dev.chyken.assembl_ar.workflow.phase;

import java.util.*;

/** Explicit learner screens. Playback never navigates or supplies camera evidence. */
public final class Phase9StandoffWheelsFlow {
    public enum Screen {
        STANDOFF_SCAN, HARDWARE_PREP, WHEELS_SCAN, WHEELS_READY,
        STANDOFF_DEMO, STANDOFF_WORK_SCAN, STANDOFF_CHECK,
        WHEELS_DEMO, WHEELS_WORK_SCAN, WHEELS_CHECK;
        public boolean video(){return this==STANDOFF_DEMO||this==WHEELS_DEMO;}
        public boolean scan(){return scanStage()!=0;}
        public int scanStage(){return this==STANDOFF_SCAN?1:this==WHEELS_SCAN?2:this==STANDOFF_WORK_SCAN?3:this==WHEELS_WORK_SCAN?4:0;}
        public boolean preparation(){return this==HARDWARE_PREP||this==WHEELS_READY;}
        public boolean check(){return this==STANDOFF_CHECK||this==WHEELS_CHECK;}
        public String label(){switch(this){
            case STANDOFF_SCAN:return "9.1a · Scan metal standoff";
            case HARDWARE_PREP:return "9.1b · Nut and screwdriver";
            case WHEELS_SCAN:return "9.2a · Scan both wheels";
            case WHEELS_READY:return "9.2b · Confirm both wheels";
            case STANDOFF_DEMO:return "9.3A · Standoff installation demo";
            case STANDOFF_WORK_SCAN:return "9.3B · Verify center standoff";
            case STANDOFF_CHECK:return "9.3C · Nut and top-screw check";
            case WHEELS_DEMO:return "9.4A · Wheel installation demo";
            case WHEELS_WORK_SCAN:return "9.4B · Verify both installed wheels";
            default:return "9.4C · Wheel stability check";
        }}
    }
    private Screen screen=Screen.STANDOFF_SCAN;
    private final EnumSet<Screen> done=EnumSet.noneOf(Screen.class);
    private int generation=1,hits;private boolean paused,nutLocked,topScrewRemoved;
    private long evidenceStart,lastFrame=Long.MIN_VALUE;
    public Phase9StandoffWheelsFlow(long now){evidenceStart=now;}
    public Screen screen(){return screen;}
    public int step(){return screen.ordinal()+1;}
    public boolean complete(){return done.contains(screen);}
    public boolean inventoryComplete(){return done.containsAll(Arrays.asList(Screen.STANDOFF_SCAN,Screen.HARDWARE_PREP,Screen.WHEELS_SCAN,Screen.WHEELS_READY));}
    public boolean scanning(){return !paused&&screen.scan()&&!complete();}
    public boolean allComplete(){return done.size()==Screen.values().length;}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:screen.scan()?screen.scanStage()<=2?PhaseFlow.Mode.SCAN_PARTS:PhaseFlow.Mode.VERIFY:screen.check()?PhaseFlow.Mode.PHYSICAL_CHECK:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.CENTER_STANDOFF,mode(),generation);}
    public boolean accepts(Object tag,long time){if(!scanning()||!tag().equals(tag)||time<evidenceStart||time<=lastFrame)return false;lastFrame=time;return true;}
    /** Every accepted frame must itself contain the complete required component set. */
    public void observe(boolean accepted){if(scanning()){hits=accepted?hits+1:0;if(hits>=2)done.add(screen);}}
    public void confirm(){
        if(screen.preparation()||screen.video())done.add(screen);
    }
    public boolean nutLocked(){return nutLocked;}
    public boolean topScrewRemoved(){return topScrewRemoved;}
    public void answerNut(boolean value){if(screen!=Screen.STANDOFF_CHECK)return;nutLocked=value;refreshStandoffCheck();}
    public void answerTopScrew(boolean value){if(screen!=Screen.STANDOFF_CHECK)return;topScrewRemoved=value;refreshStandoffCheck();}
    private void refreshStandoffCheck(){if(nutLocked&&topScrewRemoved&&done.contains(Screen.STANDOFF_WORK_SCAN))done.add(Screen.STANDOFF_CHECK);else done.remove(Screen.STANDOFF_CHECK);}
    public void answerWheels(boolean secure){if(screen==Screen.WHEELS_CHECK){if(secure&&done.contains(Screen.WHEELS_WORK_SCAN))done.add(screen);else done.remove(screen);}}
    public boolean next(long now){if(!complete()||screen==Screen.WHEELS_CHECK)return false;screen=Screen.values()[screen.ordinal()+1];window(now);return true;}
    public boolean previous(long now){if(screen==Screen.STANDOFF_SCAN)return false;screen=Screen.values()[screen.ordinal()-1];window(now);return true;}
    public void rescan(long now){
        if(!screen.scan())return;done.remove(screen);
        if(screen==Screen.STANDOFF_WORK_SCAN){done.remove(Screen.STANDOFF_CHECK);nutLocked=false;topScrewRemoved=false;}
        if(screen==Screen.WHEELS_WORK_SCAN)done.remove(Screen.WHEELS_CHECK);
        window(now);
    }
    public boolean canFinish(boolean developer){return screen==Screen.WHEELS_CHECK&&complete()&&(developer||allComplete());}
    public void pause(){paused=true;generation++;hits=0;}
    public void resume(long now){paused=false;window(now);}
    private void window(long now){generation++;evidenceStart=now;lastFrame=Long.MIN_VALUE;hits=0;}
    public void devJump(int step,long now){if(step<1||step>Screen.values().length)throw new IllegalArgumentException();screen=Screen.values()[step-1];window(now);}
    public void devSkip(){done.add(screen);if(screen==Screen.STANDOFF_CHECK){nutLocked=true;topScrewRemoved=true;done.add(Screen.STANDOFF_WORK_SCAN);}if(screen==Screen.WHEELS_CHECK)done.add(Screen.WHEELS_WORK_SCAN);}
    public void devComplete(){done.addAll(EnumSet.allOf(Screen.class));nutLocked=true;topScrewRemoved=true;screen=Screen.WHEELS_CHECK;}
    public void restart(long now){done.clear();screen=Screen.STANDOFF_SCAN;nutLocked=false;topScrewRemoved=false;window(now);}
    public String snapshot(){StringBuilder bits=new StringBuilder();for(Screen s:Screen.values())bits.append(done.contains(s)?'1':'0');return "1:"+step()+","+bits+","+(nutLocked?1:0)+(topScrewRemoved?1:0);}
    public static Phase9StandoffWheelsFlow restore(String text,long now){
        Phase9StandoffWheelsFlow f=new Phase9StandoffWheelsFlow(now);if(text==null)return f;
        if("legacyStandoff".equals(text)||"legacyWheels".equals(text)){
            // Older instructions left the top screw attached. Retain the previous installation
            // evidence, but require the new removal checklist before Phase 9 can finish.
            f.done.addAll(Arrays.asList(Screen.STANDOFF_SCAN,Screen.HARDWARE_PREP,Screen.STANDOFF_DEMO,Screen.STANDOFF_WORK_SCAN));
            f.nutLocked=true;f.screen=Screen.WHEELS_SCAN;
            if("legacyWheels".equals(text)){f.done.addAll(Arrays.asList(Screen.WHEELS_SCAN,Screen.WHEELS_READY));f.screen=Screen.STANDOFF_CHECK;}
            return f;
        }
        try{if(!text.startsWith("1:"))return f;String[] p=text.substring(2).split(",",-1);if(p.length!=3||!p[1].matches("[01]{10}")||!p[2].matches("[01]{2}"))return f;
            int step=Integer.parseInt(p[0]);if(step<1||step>10)return f;
            f.screen=Screen.values()[step-1];for(Screen s:Screen.values())if(p[1].charAt(s.ordinal())=='1')f.done.add(s);
            f.nutLocked=p[2].charAt(0)=='1';f.topScrewRemoved=p[2].charAt(1)=='1';
            if(!f.nutLocked||!f.topScrewRemoved||!f.done.contains(Screen.STANDOFF_WORK_SCAN))f.done.remove(Screen.STANDOFF_CHECK);
            if(!f.done.contains(Screen.WHEELS_WORK_SCAN))f.done.remove(Screen.WHEELS_CHECK);
        }catch(RuntimeException invalid){return new Phase9StandoffWheelsFlow(now);}return f;
    }
}
