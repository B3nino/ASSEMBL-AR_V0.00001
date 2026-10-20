package dev.chyken.assembl_ar.workflow.phase;

/** Four resumable Nano checkpoints. Photo evidence never certifies hidden pins or firmness. */
public final class Phase5MicrocontrollerFlow {
    public static final int STEP_COUNT=4;
    private int step=1,generation=1;
    private final boolean[] done=new boolean[5];
    private boolean paused,firm,pins;
    private long evidenceStart,lastFrame=Long.MIN_VALUE;
    public Phase5MicrocontrollerFlow(long now){evidenceStart=now;}
    public int step(){return step;}
    public boolean complete(){return done[step];}
    public boolean firmConfirmed(){return firm;}
    public boolean pinsConfirmed(){return pins;}
    public boolean allComplete(){for(int i=1;i<=4;i++)if(!done[i])return false;return true;}
    public boolean scanning(){return !paused&&!complete()&&(step==1||step==3);}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:step==1?PhaseFlow.Mode.SCAN_PARTS:step==3?PhaseFlow.Mode.VERIFY:step==4?PhaseFlow.Mode.PHYSICAL_CHECK:PhaseFlow.Mode.DEMO;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.MICROCONTROLLER_FIT,mode(),generation);}
    public boolean accepts(Object tag,long capture){
        if(!scanning()||!tag().equals(tag)||capture<evidenceStart||capture<=lastFrame)return false;
        lastFrame=capture;return true;
    }
    public void observe(boolean matched){if(scanning()&&matched)done[step]=true;}
    public void confirmDemo(boolean watched){if(step==2&&watched)done[2]=true;}
    public void confirmFirm(boolean value){if(step==4){firm=value;if(!value)pins=false;done[4]=firm&&pins;}}
    public void confirmPins(boolean value){if(step==4){pins=value;if(!value)firm=false;done[4]=firm&&pins;}}
    public boolean canFinish(boolean developer){return step==4&&complete()&&(developer||allComplete());}
    public boolean next(long now){if(!complete()||step==4)return false;step++;window(now);return true;}
    public boolean previous(long now){if(step==1)return false;step--;window(now);return true;}
    public void rescan(long now){if(step!=1&&step!=3)return;for(int i=step;i<=4;i++)done[i]=false;firm=false;pins=false;window(now);}
    public void pause(){paused=true;generation++;lastFrame=Long.MIN_VALUE;}
    public void resume(long now){paused=false;window(now);}
    private void window(long now){generation++;evidenceStart=now;lastFrame=Long.MIN_VALUE;}
    public String snapshot(){StringBuilder s=new StringBuilder(step+",");for(int i=1;i<=4;i++)s.append(done[i]?'1':'0');return s+","+firm+","+pins;}
    public static Phase5MicrocontrollerFlow restore(String value,long now){
        Phase5MicrocontrollerFlow f=new Phase5MicrocontrollerFlow(now);if(value==null)return f;
        try{
            String[] p=value.split(",",-1);int step=Integer.parseInt(p[0]);
            if(p.length!=4||step<1||step>4||!p[1].matches("[01]{4}")||!p[2].matches("true|false")||!p[3].matches("true|false"))return f;
            f.step=step;for(int i=1;i<=4;i++)f.done[i]=p[1].charAt(i-1)=='1';
            f.firm=Boolean.parseBoolean(p[2]);f.pins=Boolean.parseBoolean(p[3]);f.done[4]=f.firm&&f.pins;
        }catch(RuntimeException bad){return new Phase5MicrocontrollerFlow(now);}return f;
    }
    public void devJump(int step,long now){if(step<1||step>4)throw new IllegalArgumentException();this.step=step;window(now);}
    public void devSkip(){done[step]=true;if(step==4){firm=true;pins=true;}}
    public void devComplete(){java.util.Arrays.fill(done,true);firm=true;pins=true;step=4;}
    public void restart(long now){java.util.Arrays.fill(done,false);firm=false;pins=false;step=1;window(now);}
}
