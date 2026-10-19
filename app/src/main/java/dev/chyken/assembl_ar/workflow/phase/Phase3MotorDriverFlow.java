package dev.chyken.assembl_ar.workflow.phase;

/** Six explicit motor-driver checkpoints. Photo matches do not certify hidden nuts or firmness. */
public final class Phase3MotorDriverFlow {
    private int step=1,generation=1;
    private final boolean[] done=new boolean[7];
    private boolean paused;
    private long evidenceStart,lastFrame=Long.MIN_VALUE;
    public Phase3MotorDriverFlow(long now){evidenceStart=now;}
    public int step(){return step;}
    public boolean complete(){return done[step];}
    public boolean allComplete(){for(int i=1;i<=6;i++)if(!done[i])return false;return true;}
    public boolean scanning(){return !paused&&!complete()&&(step==1||step==4||step==5);}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:step==1?PhaseFlow.Mode.SCAN_PARTS:step==4||step==5?PhaseFlow.Mode.VERIFY:step==6?PhaseFlow.Mode.PHYSICAL_CHECK:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT,mode(),generation);}
    public boolean accepts(Object tag,long capture){
        if(!scanning()||!tag().equals(tag)||capture<evidenceStart||capture<=lastFrame)return false;
        lastFrame=capture;return true;
    }
    public void observe(boolean matched){if(scanning()&&matched)done[step]=true;}
    public void confirmHardware(){if(step==2)done[2]=true;}
    public void confirmDemo(boolean watched){if(step==3&&watched)done[3]=true;}
    public void confirmStability(boolean screwsAndNutsPresent,boolean firm){if(step==6)done[6]=screwsAndNutsPresent&&firm;}
    public boolean canFinish(boolean developer){return step==6&&complete()&&(developer||allComplete());}
    public boolean next(long now){if(!complete()||step==6)return false;step++;window(now);return true;}
    public boolean previous(long now){if(step==1)return false;step--;window(now);return true;}
    public void rescan(long now){if(step!=1&&step!=4&&step!=5)return;for(int i=step;i<=6;i++)done[i]=false;window(now);}
    public void pause(){paused=true;generation++;lastFrame=Long.MIN_VALUE;}
    public void resume(long now){paused=false;window(now);}
    private void window(long now){generation++;evidenceStart=now;lastFrame=Long.MIN_VALUE;}
    public String snapshot(){StringBuilder s=new StringBuilder(step+",");for(int i=1;i<=6;i++)s.append(done[i]?'1':'0');return s.toString();}
    public static Phase3MotorDriverFlow restore(String value,long now){
        Phase3MotorDriverFlow f=new Phase3MotorDriverFlow(now);
        if(value==null)return f;
        try{String[] p=value.split(",");int step=Integer.parseInt(p[0]);if(p.length!=2||step<1||step>6||!p[1].matches("[01]{6}"))return f;
            f.step=step;for(int i=1;i<=6;i++)f.done[i]=p[1].charAt(i-1)=='1';
        }catch(RuntimeException invalid){return new Phase3MotorDriverFlow(now);}return f;
    }
    public void devJump(int step,long now){if(step<1||step>6)throw new IllegalArgumentException();this.step=step;window(now);}
    public void devSkip(){done[step]=true;}
    public void devComplete(){java.util.Arrays.fill(done,true);step=6;}
    public void restart(long now){java.util.Arrays.fill(done,false);step=1;window(now);}
}
