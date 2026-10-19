package dev.chyken.assembl_ar.workflow.phase;

import dev.chyken.assembl_ar.model.MotorWireColorConfig;

/** Three manual wiring checkpoints. No video completion or camera match can verify a wire clamp. */
public final class Phase4MotorWiringFlow {
    private int step=1,generation=1;
    private final boolean[] done=new boolean[4];
    private MotorWireColorConfig colors=MotorWireColorConfig.defaults();
    public int step(){return step;}
    public MotorWireColorConfig colors(){return colors;}
    public boolean complete(){return done[step];}
    public boolean allComplete(){return done[1]&&done[2]&&done[3];}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:step==3?PhaseFlow.Mode.PHYSICAL_CHECK:step==2?PhaseFlow.Mode.ASSEMBLE:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.MOTOR_LEADS,mode(),generation);}
    /** Applying edited colors invalidates the dependent physical checks; unchanged colors preserve them. */
    public void applyColors(MotorWireColorConfig selected){
        if(selected==null)throw new IllegalArgumentException("Wire colors required");
        if(!colors.equals(selected)){done[2]=false;done[3]=false;}colors=selected;done[1]=true;step=2;generation++;
    }
    public void confirmWired(){if(step==2&&done[1])done[2]=true;}
    public void confirmPullTest(boolean secure){if(step==3)done[3]=secure;}
    public boolean canFinish(boolean developer){return step==3&&complete()&&(developer||allComplete());}
    public boolean next(){if(step==3||!complete())return false;step++;generation++;return true;}
    public boolean previous(){if(step==1)return false;step--;generation++;return true;}
    public void interrupt(){generation++;}
    public void devJump(int step){if(step<1||step>3)throw new IllegalArgumentException("Phase 4 sub-step");this.step=step;generation++;}
    public void devSkip(){done[step]=true;}
    public void devComplete(){done[1]=done[2]=done[3]=true;step=3;generation++;}
    public void restart(){step=1;done[1]=done[2]=done[3]=false;generation++;}
    public String snapshot(){return step+","+(done[1]?1:0)+(done[2]?1:0)+(done[3]?1:0)+"|"+colors.encode();}
    public static Phase4MotorWiringFlow restore(String value){
        Phase4MotorWiringFlow f=new Phase4MotorWiringFlow();if(value==null)return f;
        try{String[] parts=value.split("\\|",-1),state=parts[0].split(",",-1);int step=Integer.parseInt(state[0]);
            if(parts.length!=2||state.length!=2||step<1||step>3||!state[1].matches("[01]{3}"))return f;
            f.colors=MotorWireColorConfig.decode(parts[1]);f.step=step;for(int i=1;i<=3;i++)f.done[i]=state[1].charAt(i-1)=='1';
        }catch(RuntimeException bad){return new Phase4MotorWiringFlow();}return f;
    }
}
