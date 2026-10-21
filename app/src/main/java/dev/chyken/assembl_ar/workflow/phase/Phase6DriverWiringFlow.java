package dev.chyken.assembl_ar.workflow.phase;

import dev.chyken.assembl_ar.model.DriverWireColorConfig;

/** Four wiring checkpoints; Nano identity was already verified in Phase 5. */
public final class Phase6DriverWiringFlow {
    public static final int STEP_COUNT=4;
    private int step=1,generation=1;
    private final boolean[] done=new boolean[STEP_COUNT+1];
    private DriverWireColorConfig colors=DriverWireColorConfig.defaults();
    private boolean seated,correct;
    public Phase6DriverWiringFlow(long now){}
    public int step(){return step;}
    public DriverWireColorConfig colors(){return colors;}
    public boolean complete(){return done[step];}
    public boolean seated(){return seated;}
    public boolean correct(){return correct;}
    public boolean allComplete(){for(int i=1;i<=STEP_COUNT;i++)if(!done[i])return false;return true;}
    public boolean scanning(){return false;}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:step==2||step==4?PhaseFlow.Mode.PHYSICAL_CHECK:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.DRIVER_WIRING,mode(),generation);}
    /** This phase never accepts camera evidence, including delayed frames from Phase 5. */
    public boolean accepts(Object tag,long capture){return false;}
    public void applyColors(DriverWireColorConfig value,long now){
        if(value==null)throw new IllegalArgumentException();
        if(!value.equals(colors)){for(int i=2;i<=STEP_COUNT;i++)done[i]=false;seated=correct=false;}
        colors=value;done[1]=true;step=2;generation++;
    }
    public void confirmPower(boolean firm){if(step==2){done[2]=firm;if(!firm){done[3]=done[4]=false;seated=correct=false;}}}
    public void confirmSignals(){if(step==3)done[3]=true;}
    public void confirmSeated(boolean value){if(step==4){seated=value;if(!value)correct=false;done[4]=seated&&correct;}}
    public void confirmCorrect(boolean value){if(step==4){correct=value;if(!value)seated=false;done[4]=seated&&correct;}}
    public boolean canFinish(boolean developer){return step==STEP_COUNT&&complete()&&(developer||allComplete());}
    public boolean next(long now){if(!complete()||step==STEP_COUNT)return false;step++;generation++;return true;}
    public boolean previous(long now){if(step==1)return false;step--;generation++;return true;}
    public void pause(){generation++;}
    public void resume(long now){generation++;}
    /** Version 2 removes the redundant scan; old five-step snapshots are migrated below. */
    public String snapshot(){StringBuilder bits=new StringBuilder();for(int i=1;i<=STEP_COUNT;i++)bits.append(done[i]?'1':'0');return "2:"+step+","+bits+","+colors.encode()+","+seated+","+correct;}
    public static Phase6DriverWiringFlow restore(String text,long now){
        Phase6DriverWiringFlow result=new Phase6DriverWiringFlow(now);if(text==null)return result;
        try{
            boolean legacy=!text.startsWith("2:");String[] p=(legacy?text:text.substring(2)).split(",",-1);
            if(p.length!=5||!p[1].matches(legacy?"[01]{5}":"[01]{4}")||!p[3].matches("true|false")||!p[4].matches("true|false"))return result;
            int savedStep=Integer.parseInt(p[0]);if(savedStep<1||savedStep>(legacy?5:STEP_COUNT))return result;
            result.colors=DriverWireColorConfig.decode(p[2]);result.step=legacy?Math.max(1,savedStep-1):savedStep;
            for(int i=1;i<=STEP_COUNT;i++)result.done[i]=p[1].charAt(legacy?i:i-1)=='1';
            result.seated=Boolean.parseBoolean(p[3]);result.correct=Boolean.parseBoolean(p[4]);result.done[4]=result.seated&&result.correct;
        }catch(RuntimeException invalid){return new Phase6DriverWiringFlow(now);}return result;
    }
    public void devJump(int step,long now){if(step<1||step>STEP_COUNT)throw new IllegalArgumentException();this.step=step;generation++;}
    public void devSkip(){done[step]=true;if(step==STEP_COUNT)seated=correct=true;}
    public void devComplete(){java.util.Arrays.fill(done,true);seated=correct=true;step=STEP_COUNT;}
    public void restart(long now){java.util.Arrays.fill(done,false);seated=correct=false;step=1;generation++;}
}

