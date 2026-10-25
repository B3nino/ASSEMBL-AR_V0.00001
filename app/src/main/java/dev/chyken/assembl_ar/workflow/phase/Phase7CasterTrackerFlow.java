package dev.chyken.assembl_ar.workflow.phase;

import dev.chyken.assembl_ar.vision.Phase7Observation;

/** Separate scan/hardware pages, caster demo/checklist, then tracker demo/combined scan/check. */
public final class Phase7CasterTrackerFlow {
    public static final int STEP_COUNT=4;
    public static final int SCREEN_COUNT=6;
    private int step=1,generation=1,trackers;
    private final boolean[] scan=new boolean[5],hardware=new boolean[3],demo=new boolean[5];
    private boolean sandwiched,swivels,stable,paused,armed,demoView,hardwareView;
    private long evidenceStart,lastFrame=Long.MIN_VALUE;
    public Phase7CasterTrackerFlow(long now){evidenceStart=now;}
    public int step(){return step;}
    public int trackerCount(){return trackers;}
    public boolean scanned(){return scan[step];}
    public boolean hardwareReady(){return step<=2&&hardware[step];}
    public boolean preparingHardware(){return step<=2&&hardwareView;}
    public boolean stageComplete(){return step<=2&&!hardwareView?scanned():complete();}
    public int screenIndex(){return step<=2?(step-1)*2+(hardwareView?1:0):step+1;}
    public boolean demoReady(){return demo[step];}
    /** Rescan requires actual prior scan evidence, including when reviewing a demo. */
    public boolean canRescan(){return AssemblyStepNavigationManager.canRescan(step!=3&&!preparingHardware(),scanned());}
    public boolean checklistReady(){return step==3?demo[3]&&!demoView:step==4&&scan[4]&&!demoView;}
    public boolean viewingDemo(){return step>=3&&demoView;}
    public boolean sandwiched(){return sandwiched;}
    public boolean swivels(){return swivels;}
    public boolean complete(){return done(step);}
    private boolean done(int s){return s<=2?scan[s]&&hardware[s]:s==3?demo[3]&&sandwiched&&swivels:demo[4]&&scan[4]&&stable;}
    public boolean allComplete(){for(int i=1;i<=4;i++)if(!done(i))return false;return true;}
    public boolean scanning(){return !paused&&step!=3&&!preparingHardware()&&!scan[step]&&(step<=2||armed&&demo[step]);}
    public PhaseFlow.Mode mode(){return allComplete()?PhaseFlow.Mode.COMPLETE:scanning()?(step<=2?PhaseFlow.Mode.SCAN_PARTS:PhaseFlow.Mode.VERIFY):viewingDemo()?PhaseFlow.Mode.DEMO:PhaseFlow.Mode.PHYSICAL_CHECK;}
    public PhaseFlow.ScanTag tag(){return new PhaseFlow.ScanTag(AssemblyPhaseCatalog.CASTER_FIT,mode(),generation);}
    public boolean accepts(Object tag,long time){if(!scanning()||!tag().equals(tag)||time<evidenceStart||time<=lastFrame)return false;lastFrame=time;return true;}
    public void observe(Phase7Observation observation){if(!scanning()||observation==null)return;if(step==2)trackers=Math.max(trackers,observation.invalid?0:observation.count);if(observation.accepted(step)){scan[step]=true;armed=false;}}
    public void confirmHardware(){if(preparingHardware()&&scanned())hardware[step]=true;}
    public boolean nextStage(long now){if(step<=2&&!hardwareView){if(!scanned())return false;hardwareView=true;window(now);return true;}return next(now);}
    public void demoFinished(){if(step>=3)demo[step]=true;}
    /** Explicit acknowledgement exits the caster demo to its checklist, or starts the final scan. */
    public void armScan(long now){if(step>=3&&demo[step]){demoView=false;armed=step==4&&!scan[step];window(now);}}
    public void confirmSandwiched(boolean value){if(step==3&&checklistReady()){sandwiched=value;if(!value)swivels=false;}}
    public void confirmSwivels(boolean value){if(step==3&&checklistReady()){swivels=value;if(!value)sandwiched=false;}}
    public void confirmStable(boolean value){if(step==4&&scan[4])stable=value;}
    public boolean canFinish(boolean developer){return step==4&&complete()&&(developer||allComplete());}
    public boolean next(long now){if(!complete()||step==4||step<=2&&!hardwareView)return false;step++;hardwareView=false;armed=false;demoView=step>=3;window(now);return true;}
    public boolean previous(long now){if(preparingHardware()){hardwareView=false;window(now);return true;}if(step>=3&&!demoView){replayDemo(now);return true;}if(step==1)return false;step--;hardwareView=step<=2;armed=false;demoView=step>=3;window(now);return true;}
    public void rescan(long now){if(!canRescan())return;if(step==3)return;scan[step]=false;hardwareView=false;demoView=false;armed=step>=3&&demo[step];if(step==2)trackers=0;if(step==3)sandwiched=swivels=false;if(step==4)stable=false;window(now);}
    public void replayDemo(long now){if(step>=3){demoView=true;armed=false;window(now);}}
    public void suspendScan(long now){replayDemo(now);}
    public void pause(){paused=true;window(evidenceStart);}
    public void resume(long now){paused=false;window(now);}
    private void window(long now){generation++;evidenceStart=now;lastFrame=Long.MIN_VALUE;}
    public String snapshot(){StringBuilder bits=new StringBuilder();for(int i=1;i<=4;i++)bits.append(scan[i]?'1':'0');bits.append(hardware[1]?'1':'0').append(hardware[2]?'1':'0').append(demo[3]?'1':'0').append(demo[4]?'1':'0').append(sandwiched?'1':'0').append(swivels?'1':'0').append(stable?'1':'0');return "4,"+step+","+bits+","+trackers+","+armed+","+demoView+","+hardwareView;}
    public static Phase7CasterTrackerFlow restore(String text,long now){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(now);if("legacyCaster".equals(text)){f.scan[1]=f.hardware[1]=f.scan[3]=f.demo[3]=f.sandwiched=f.swivels=true;f.step=2;return f;}
        if(text==null)return f;
        try{String[] p=text.split(",",-1);boolean old=p.length==5&&p[0].equals("1"),current=p.length==7&&(p[0].equals("3")||p[0].equals("4"));if(!(old||p.length==6&&p[0].equals("2")||current)||!p[2].matches("[01]{11}")||!p[4].matches("true|false")||!old&&!p[5].matches("true|false")||current&&!p[6].matches("true|false"))return f;
            int step=Integer.parseInt(p[1]),count=Integer.parseInt(p[3]);if(step<1||step>4||count<0||count>2)return f;f.step=step;f.trackers=count;String b=p[2];
            for(int i=1;i<=4;i++)f.scan[i]=b.charAt(i-1)=='1';f.hardware[1]=b.charAt(4)=='1';f.hardware[2]=b.charAt(5)=='1';f.demo[3]=b.charAt(6)=='1';f.demo[4]=b.charAt(7)=='1';f.sandwiched=b.charAt(8)=='1';f.swivels=b.charAt(9)=='1';f.stable=b.charAt(10)=='1';f.armed=Boolean.parseBoolean(p[4])&&step>=3&&f.demo[step]&&!f.scan[step];f.demoView=step>=3&&(old?!f.armed&&!f.scan[step]:Boolean.parseBoolean(p[5]));if(f.demoView||step==3)f.armed=false;
            f.hardwareView=step<=2&&f.scan[step]&&(current?Boolean.parseBoolean(p[6]):true);
        }catch(RuntimeException bad){return new Phase7CasterTrackerFlow(now);}return f;
    }
    public void devJump(int value,long now){if(value<1||value>4)throw new IllegalArgumentException();step=value;hardwareView=false;armed=false;demoView=step>=3;window(now);}
    public void devJumpScreen(int index,long now){if(index<0||index>=SCREEN_COUNT)throw new IllegalArgumentException();devJump(index<4?index/2+1:index-1,now);if(index<4&&(index&1)==1){scan[step]=true;hardwareView=true;if(step==2)trackers=2;window(now);}}
    public void devSkipStage(long now){if(step<=2&&!hardwareView){scan[step]=true;hardwareView=true;if(step==2)trackers=2;window(now);}else devSkip();}
    public void devSkip(){scan[step]=true;if(step<=2){hardware[step]=true;hardwareView=true;if(step==2)trackers=2;}else{demo[step]=true;if(step==3)sandwiched=swivels=true;else stable=true;}armed=false;demoView=false;}
    public void devComplete(){for(int i=1;i<=4;i++){step=i;devSkip();}step=4;hardwareView=false;}
    public void restart(long now){java.util.Arrays.fill(scan,false);java.util.Arrays.fill(hardware,false);java.util.Arrays.fill(demo,false);sandwiched=swivels=stable=armed=demoView=hardwareView=false;trackers=0;step=1;window(now);}
}

