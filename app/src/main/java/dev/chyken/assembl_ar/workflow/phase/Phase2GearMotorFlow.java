package dev.chyken.assembl_ar.workflow.phase;

import java.util.*;

/** Six explicit checkpoints. Reviewing and playback never manufacture camera evidence. */
public final class Phase2GearMotorFlow {
    private int step=1,generation=1,brackets,motors,hardwareViewIndex;
    private static final List<String> HARDWARE_ORDER=Arrays.asList("top","left","right");
    private final boolean[] done=new boolean[7];
    private long evidenceStart,lastFrame=Long.MIN_VALUE;
    private final Set<String> hardwareViews=new HashSet<>();
    private boolean paused;
    public Phase2GearMotorFlow(long now){evidenceStart=now;}
    public int step(){return step;}
    public int bracketCount(){return brackets;}
    public int motorCount(){return motors;}
    public boolean complete(){return done[step];}
    public boolean allComplete(){for(int i=1;i<=6;i++)if(!done[i])return false;return true;}
    public boolean partsComplete(){return done[1]&&done[2];}
    public boolean scanning(){return !paused&&!complete()&&(step==1||step==2||step==5&&!currentHardwareViewComplete());}
    public Set<String> hardwareViews(){return Collections.unmodifiableSet(new HashSet<>(hardwareViews));}
    public String expectedHardwareView(){
        return HARDWARE_ORDER.get(hardwareViewIndex);
    }
    public int hardwareViewNumber(){return hardwareViewIndex+1;}
    public boolean currentHardwareViewComplete(){return hardwareViews.contains(expectedHardwareView());}
    public boolean canNext(){return step==5?currentHardwareViewComplete():complete();}
    public boolean canFinish(boolean developer){return step==6&&complete()&&(developer||allComplete());}
    public PhaseFlow.Mode mode(){if(allComplete())return PhaseFlow.Mode.COMPLETE;return step<=2?PhaseFlow.Mode.SCAN_PARTS:step==5?PhaseFlow.Mode.VERIFY
            :step==6?PhaseFlow.Mode.PHYSICAL_CHECK:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES,mode(),generation);}
    public boolean accepts(Object tag,long timestamp){
        if(!scanning()||!tag().equals(tag)||timestamp<evidenceStart||timestamp<=lastFrame)return false;
        lastFrame=timestamp;return true;
    }
    public void observeCount(int visible,boolean valid){
        if(!scanning()||step>2)return;
        // The recognizer already checks confidence, convex geometry and distinct instances.
        // Commit the complete live set once; requiring another three consecutive frames
        // strands users at 4/4 or 2/2 when a single outline flickers during camera movement.
        if(!valid)return;
        if(step==1)brackets=Math.max(brackets,Math.min(4,Math.max(0,visible)));
        else motors=Math.max(motors,Math.min(2,Math.max(0,visible)));
        int required=step==1?4:2;
        // A historical partial count never substitutes for a simultaneous full set.
        if(visible>=required)done[step]=true;
    }
    public void observeHardware(String view,boolean valid){
        if(!scanning()||step!=5)return;
        if(!valid||!Objects.equals(view,expectedHardwareView()))return;
        // One geometrically accepted photo match confirms this view. The cursor advances only on Next.
        hardwareViews.add(view);
        done[5]=hardwareViews.containsAll(HARDWARE_ORDER);
    }
    public void confirmTools(){if(step==3)done[3]=true;}
    public void confirmAssembly(boolean videosReviewed){if(step==4&&videosReviewed)done[4]=true;}
    public void confirmFirmness(boolean left,boolean right){if(step==6)done[6]=left&&right;}
    public boolean next(long now){
        if(!canNext()||step==6)return false;
        if(step==5&&hardwareViewIndex<2)hardwareViewIndex++;else step++;
        openWindow(now);return true;
    }
    public boolean previous(long now){
        if(step==1)return false;
        if(step==5&&hardwareViewIndex>0)hardwareViewIndex--;else step--;
        openWindow(now);return true;
    }
    public boolean canRescan(){return AssemblyStepNavigationManager.canRescan(step==1||step==2||step==5,canNext());}
    public void rescan(long now){if(!canRescan())return;
        if(step!=1&&step!=2&&step!=5)return;
        done[step]=false;done[5]=false;done[6]=false;
        if(step==5)hardwareViews.removeAll(HARDWARE_ORDER.subList(hardwareViewIndex,3));
        else {hardwareViews.clear();hardwareViewIndex=0;}
        if(step==1)brackets=0;if(step==2)motors=0;
        openWindow(now);
    }
    public void pause(){paused=true;generation++;lastFrame=Long.MIN_VALUE;}
    public void resume(long now){paused=false;openWindow(now);}
    public void restartEvidence(long now){openWindow(now);}
    private void openWindow(long now){generation++;evidenceStart=now;lastFrame=Long.MIN_VALUE;}
    public String snapshot(){
        StringBuilder s=new StringBuilder(step+","+brackets+","+motors+",");
        for(int i=1;i<=6;i++)s.append(done[i]?'1':'0');
        s.append(',').append(hardwareViews.size()).append(',').append(hardwareViewIndex);return s.toString();
    }
    public static Phase2GearMotorFlow restore(String saved,long now){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(now);
        if(saved==null)return f;
        try {
            String[] v=saved.split(",");int step=Integer.parseInt(v[0]),b=Integer.parseInt(v[1]),m=Integer.parseInt(v[2]);
            if((v.length<4||v.length>6)||step<1||step>6||b<0||b>4||m<0||m>2||!v[3].matches("[01]{6}"))return f;
            int views=v.length>=5?Integer.parseInt(v[4]):0;
            if(views<0||views>3||views==3&&v[3].charAt(4)!='1')return f;
            f.step=step;f.brackets=b;f.motors=m;
            for(int i=1;i<=6;i++)f.done[i]=v[3].charAt(i-1)=='1';
            // Old versions advanced automatically; retain their next unscanned view on migration.
            if(f.done[5])views=3;
            int index=v.length==6?Integer.parseInt(v[5]):Math.min(views,2);
            if(index<0||index>2||index>views)return new Phase2GearMotorFlow(now);
            f.hardwareViewIndex=index;
            f.hardwareViews.addAll(HARDWARE_ORDER.subList(0,views));
        } catch(RuntimeException invalid){return new Phase2GearMotorFlow(now);}
        return f;
    }
    /** Developer-only controls do not write learner progress. */
    public void devJump(int selected,long now){if(selected<1||selected>6)throw new IllegalArgumentException();step=selected;openWindow(now);}
    public void devCompleteStep(){done[step]=true;if(step==5)hardwareViews.addAll(HARDWARE_ORDER);}
    public void devCompletePhase(){Arrays.fill(done,true);hardwareViews.addAll(HARDWARE_ORDER);hardwareViewIndex=2;step=6;}
    public void restart(long now){Arrays.fill(done,false);step=1;brackets=motors=hardwareViewIndex=0;hardwareViews.clear();openWindow(now);}
}
