package dev.chyken.assembl_ar;

import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.camera.Phase3AssetCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import java.util.*;

/** Phase 3 orchestration on the UI thread. Native vision and speech use independent workers. */
final class Phase3GuideController implements AutoCloseable {
    private static final String[] HUD={"",
        "Step 3.1: Scan the Motor Driver module.\n• Lay the motor driver flat inside the camera frame.\n• System detects orientation automatically.",
        "Step 3.2: Gather hardware.\n• Locate 2x Short Screws (10mm) and 2x M3 Nuts.\n• Tap 'I Have These' to proceed.",
        "Step 3.3: Watch the placement demo.\n• Align and seat the motor driver on its headers.\n• Leave screws out until the placement scan passes.",
        "Step 3.4: Verify Module Placement (NO SCREWS YET).\n• Green screw terminals face the board center; the six-pin header faces the red LED.\n• Seat the driver on its headers and keep nearby board markings visible.",
        "Step 3.5: Watch the fastening demo.\n• Insert 2x 10mm screws and secure both M3 nuts underneath.\n• Replay or scrub the demo, then continue to the physical screw check.",
        "Step 3.6: Joint Stability Test.\n• Confirm both screws and nuts are fitted.\n• Lightly press and wiggle the motor driver with your finger."};
    private static final String[] SPOKEN={"",
        "Step 3 point 1. Please locate and scan the Motor Driver module by placing it flat inside the camera frame.",
        "Step 3 point 2. Gather two 10 millimeter screws and two M3 nuts, then tap I Have These once ready.",
        "Step 3 point 3. Watch how to seat the motor driver. Leave the screws out until the placement scan passes.",
        "Step 3 point 4. Seat the motor driver without screws. Its green screw terminals face the center of the main board, and its six pin header faces the red LED. Keep the driver and nearby board markings in view.",
        "Step 3 point 5. Watch how to fit both 10 millimeter screws and M3 nuts. Then continue to the physical screw and stability check. No further camera scan is needed.",
        "Step 3 point 6. Lightly press on the motor driver to check if it moves or jiggles."};
    private static final String[] LINES={"1. Align the Motor Driver pins with the corresponding header slots on the Main Board.",
        "2. After the placement scan, insert 2x 10mm screws into the mounting holes and secure with M3 nuts underneath."};
    private static final String[] NARRATION={"Step 3 point 3. Align and seat the motor driver pins in the header slots on the main board. Leave screws out until the placement scan passes.",
        "Step 3 point 5. Insert two 10 millimeter screws into the mounting holes and secure them underneath with M3 nuts. Then continue to the physical screw and stability check."};
    private final AssemblyActivity activity;
    private final CompactGuideCard card;
    private final ComponentPreviewWindow preview;
    private final TargetAlignmentBoxView alignment;
    private final Runnable finish,previousPhase,checkpointChanged;
    private final Handler main=new Handler(Looper.getMainLooper());
    final Phase3MotorDriverFlow flow;
    private final DemoClipNarrator narrator;
    private final List<String> clips;
    private Phase2VideoPlayer video;
    private AlertDialog dialog;
    private volatile Plan plan;
    private boolean developer,reviewing,paused,closed,saving,videoError,finishing;
    private int previewStep=-1,playingDemo=-1;
    private final boolean[] watched=new boolean[2];
    private final long[] positions=new long[2];
    private final long createdAt=SystemClock.elapsedRealtimeNanos();
    private String message="",savedState="",completionKey;
    private Phase3Observation.State lastWarning;
    private static final class Plan{final int step;final PhaseFlow.ScanTag tag;Plan(int step,PhaseFlow.ScanTag tag){this.step=step;this.tag=tag;}}
    Phase3GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,String saved,Runnable checkpointChanged){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;this.checkpointChanged=checkpointChanged;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);alignment=activity.findViewById(R.id.targetAlignmentBox);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.driverState(activity,NeobotSeedData.PROJECT_ID);
        String[] p=saved==null?new String[0]:saved.split("\\|");flow=Phase3MotorDriverFlow.restore(p.length>0?p[0]:null,createdAt);
        if(p.length==5)try{
            if(p[0].startsWith("v2:")){watched[0]=Boolean.parseBoolean(p[1]);watched[1]=Boolean.parseBoolean(p[2]);positions[0]=Math.max(0,Long.parseLong(p[3]));positions[1]=Math.max(0,Long.parseLong(p[4]));}
            else {watched[0]=Boolean.parseBoolean(p[2])||flow.complete(3);watched[1]=flow.step()==6;int oldClip=Integer.parseInt(p[3]);if(oldClip==0)positions[0]=Math.max(0,Long.parseLong(p[4]));}
        }catch(RuntimeException ignored){}
        paused=!activity.isActivityResumed();if(paused)flow.pause();clips=Phase3AssetCatalog.videos(activity.getAssets());
        narrator=new DemoClipNarrator(NARRATION,new DemoClipNarrator.Voice(){public void speak(String text,String key){say(text,key);}public void stop(){activity.voiceGuide().stop();}});
        bindControls();
    }
    private void bindControls(){
        card.previous.setOnClickListener(v->{if(flow.step()==1)previousPhase.run();else if(flow.previous(now()))changed();});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.step()==6){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.next(now()))changed();});
        card.action.setOnClickListener(v->{if(saving)return;if(flow.step()==2)hardware();else if(demoStep()){
            if(videoError){previewStep=-1;videoError=false;render();}
            else if(watched[demoIndex()]){flow.confirmDemo(true);if(flow.next(now()))changed();}
        }else if(flow.step()==6)stability();});
        card.rescan.setOnClickListener(v->{if(dialog!=null)dialog.dismiss();dialog=RescanConfirmationDialog.show(activity,()->{flow.rescan(now());changed();});});
    }
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?6:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void say(String text,String key){if(!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase3:"+key);}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);finishing=false;completionKey=null;message="";lastWarning=null;
        if(dialog!=null){dialog.dismiss();dialog=null;}activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.setBoardCorners(null);hud.clearAssemblyTargets();render();
        if(!demoStep())say(flow.complete()?"This step is complete. Review the instructions, then choose Next.":SPOKEN[flow.step()],"step:"+flow.tag());
        save();if(flow.step()==6&&!flow.complete()&&!paused)main.post(this::stability);
    }
    boolean scanning(){return plan!=null;}
    RecognitionRequest request(CameraCalibration calibration){Plan p=plan;if(p==null)return null;return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MOTOR_DRIVER),calibration,null,null,1,3).withPhase3Stage(p.step).withRequestTag(p.tag);}
    boolean current(RecognitionResult result){return !closed&&result.getFrameTimestampNanos()>=createdAt&&flow.tag().equals(result.getRequestTag());}
    void onResult(RecognitionResult result){
        if(!flow.accepts(result.getRequestTag(),result.getFrameTimestampNanos()))return;
        Phase3Observation evidence=result.getPhase3Observation();boolean accepted=evidence!=null&&evidence.accepted(flow.step());flow.observe(accepted);
        boolean warning=evidence!=null&&(evidence.state==Phase3Observation.State.FLIPPED||evidence.state==Phase3Observation.State.BACKWARD||evidence.state==Phase3Observation.State.MISALIGNED);
        if(warning){
            message=evidence.state==Phase3Observation.State.FLIPPED?"Motor driver is upside down. Flip it so the chips and terminal blocks face up.":evidence.state==Phase3Observation.State.MISALIGNED?"The motor driver is not aligned with its socket. Lift it gently, align all header pins, and re-seat it before adding screws.":"Motor driver is inserted backward. Rotate it 180 degrees before adding screws.";
            if(lastWarning!=evidence.state){say(message,"warning:"+evidence.state+":"+flow.tag());lastWarning=evidence.state;}
        }else if(accepted){message="Scan successful · Next unlocked";say(message, "matched:"+flow.tag());lastWarning=null;}
        else message=flow.step()==1?"Keep the front face visible inside the recommended frame.":"Keep the driver and nearby board visible. Move loose wires aside if they cover the chips or header row; avoid glare.";
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.update(accepted?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.SEARCHING,"Motor Driver",accepted||warning?activity.normalizeCorners(result.getProjectedCorners()):null);
        activity.hideGuidance();render();save();
    }
    private void hardware(){
        if(dialog!=null)dialog.dismiss();say(SPOKEN[2],"hardware:"+flow.tag());
        dialog=new AlertDialog.Builder(activity).setTitle("Fasteners").setMessage("2 × 10mm short screws\n2 × M3 nuts\nPhillips screwdriver")
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton("I Have These",(d,w)->{flow.confirmHardware();message="Hardware ready · Next unlocked";say(message,"hardware-ready:"+flow.tag());render();save();}).show();
    }
    private void stability(){
        if(closed||paused||flow.step()!=6||flow.complete()||dialog!=null&&dialog.isShowing())return;
        LinearLayout body=PhysicalCheckUi.body(activity);
        TextView prompt=PhysicalCheckUi.prompt(activity,"Lightly press and wiggle the motor driver. Is it firmly screwed in?");prompt.setPadding(0,0,0,UiUtils.dp(activity,16));body.addView(prompt);
        CheckBox hardware=new CheckBox(activity);hardware.setText("Both 10mm screws and both M3 nuts are installed");body.addView(hardware);
        dialog=new AlertDialog.Builder(activity).setTitle("Fasteners & joint stability").setView(body).setNeutralButton("Check later",null)
                .setNegativeButton("Still Jiggling",null).setPositiveButton("Firm & Secure",null).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{message="Please tighten both 10 millimeter screws and nuts using your screwdriver.";prompt.setText(message);say(message,"tighten:"+now());});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(!hardware.isChecked()){say("Please confirm that both screws and both nuts are installed.","confirm-hardware:"+now());return;}
                flow.confirmStability(true,true);dialog.dismiss();render();save();
                if(!flow.canFinish(developer)){say("Joint check complete. Finish the remaining phase checkpoints before continuing.","missing-checkpoints");return;}
                finishing=true;completionKey="complete:"+now();say("Phase 3 complete! Outstanding work.",completionKey);
                AssemblyVoiceGuide.Status status=activity.voiceGuide().getStatus();
                if(status==AssemblyVoiceGuide.Status.MUTED||status==AssemblyVoiceGuide.Status.UNAVAILABLE)advance();else main.postDelayed(this::advance,16000);
            });
        });dialog.show();PhysicalCheckUi.style(dialog);
    }
    private void advance(){if(!closed&&!paused&&!saving&&finishing&&flow.canFinish(developer)){finishing=false;main.removeCallbacksAndMessages(null);finish.run();}}
    void render(){
        if(closed)return;int step=flow.step();plan=!saving&&flow.scanning()?new Plan(step,flow.tag()):null;
        String summary=HUD[step];if(demoStep())summary+="\n\n"+LINES[demoIndex()];
        String details=HUD[step]+"\n\nThe driver controls the two motors. Align its header row with the board sockets. Check placement before adding screws. The camera checks the module and board orientation; the final hands-on check confirms the screws, hidden nuts and firmness.";
        boolean unlocked=flow.complete()&&(step!=6||flow.canFinish(developer)||reviewing);
        card.render(30+step,"Phase 3 · Step 3."+step+" of 6",summary,details,message,unlocked,true,flow.complete()&&(step==1||step==4));
        card.next.setText(step==6?reviewing?"Return to current phase":"Continue to Phase 4 →":"Next Step →");card.next.setEnabled(unlocked&&!saving);
        card.next.setVisibility(demoStep()&&!flow.complete()?View.GONE:View.VISIBLE);
        card.action.setVisibility((step==2||demoStep()||step==6)&&!flow.complete()?View.VISIBLE:View.GONE);
        card.action.setText(step==2?"I Have These":demoStep()?videoError?"Retry demo":step==3?"Ready for Placement Scan":"Ready for Screw Check":"Check screws & stability");card.action.setEnabled(!saving&&(!demoStep()||watched[demoIndex()]||videoError));
        card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        ((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(plan!=null);
        alignment.setInstruction(step==1?"Motor Driver · Front face":"Motor Driver · Top view");alignment.showGuide(flow.scanning()?Phase3AssetCatalog.guide(activity.getAssets(),step):null);
        if(previewStep!=step){stopVideo();previewStep=step;if(step==2)preview.showFasteners(true);else if(demoStep())startVideo();else preview.showComponent(NeobotSeedData.MOTOR_DRIVER,"Motor Driver");}
        preview.onHudState(flow.complete()?ArHudView.HudState.MATCHED:lastWarning!=null?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.SEARCHING);
        activity.setBanner(flow.complete()?"Step complete · Next unlocked":message.isEmpty()?HUD[step].split("\n")[0]:message,flow.scanning(),lastWarning!=null?R.drawable.bg_camera_warning:flow.complete()?R.drawable.bg_success:R.drawable.bg_live_pill);
    }
    private void startVideo(){
        final int step=flow.step(),index=demoIndex();List<String> selected=new ArrayList<>();for(String clip:clips)if(Phase3AssetCatalog.clipOrder(clip)==index)selected.add(clip);
        if(selected.isEmpty()){videoError=true;message="The Phase 3 "+(index==0?"placement":"fastening")+" demo is missing.";say(message,"missing-demo:"+index);main.post(this::render);return;}
        playingDemo=index;
        video=new Phase2VideoPlayer(activity,selected,new Phase2VideoPlayer.Listener(){
            public void onClip(int clip){if(!closed&&flow.step()==step)save();}
            public void onPlayback(int clip,boolean playing,boolean seek){if(!closed&&flow.step()==step)narrator.onPlayback(index,playing&&!paused,seek);}
            public void onClipEnded(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(video!=null&&(!narrator.isPending()||s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))video.continueAfterNarration();}
            public void onFinished(){if(closed||flow.step()!=step)return;narrator.stop();watched[index]=true;videoError=false;message=index==0?"Demo complete · Choose Ready for Placement Scan":"Demo complete · Choose Ready for Screw Check";say(message,"video-ready:"+flow.tag());render();save();}
            public void onError(String error){narrator.stop();videoError=true;message=error;say(error,"video-error");render();}
        },path->index);
        preview.showContent(video.view(),(index==0?"Placement":"Fastening")+" demo · Tap to pause · Drag to scrub");video.seek(0,positions[index]);if(!paused)video.play();
    }
    private boolean demoStep(){return flow.step()==3||flow.step()==5;}
    private int demoIndex(){return flow.step()==5?1:0;}
    private void stopVideo(){if(video!=null){if(playingDemo>=0)positions[playingDemo]=video.position();narrator.stop();video.close();video=null;playingDemo=-1;}videoError=false;}
    String checkpointState(){if(video!=null&&playingDemo>=0)positions[playingDemo]=video.position();return flow.snapshot()+"|"+watched[0]+"|"+watched[1]+"|"+positions[0]+"|"+positions[1];}
    private void save(){String state=checkpointState();if(!state.equals(savedState)){savedState=state;if(!developer)PhaseProgressStore.saveDriverState(activity,NeobotSeedData.PROJECT_ID,state);checkpointChanged.run();}}
    void onSpeechFinished(String key){if(closed||paused)return;if(finishing&&("phase3:"+completionKey).equals(key)){advance();return;}if(video!=null&&narrator.onSpeechFinished(key.startsWith("phase3:")?key.substring(7):key))video.continueAfterNarration();}
    void onVoiceStatusChanged(){if(closed||paused)return;AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(finishing&&(s==AssemblyVoiceGuide.Status.UNAVAILABLE||s==AssemblyVoiceGuide.Status.MUTED))advance();else if(video!=null){if(s==AssemblyVoiceGuide.Status.AVAILABLE)narrator.onVoiceAvailable();else if(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE)video.continueAfterNarration();}}
    void setSaving(boolean value){saving=value;render();}
    void pause(){paused=true;main.removeCallbacksAndMessages(null);flow.pause();plan=null;narrator.stop();if(video!=null)video.pause();save();if(dialog!=null){dialog.dismiss();dialog=null;}}
    void resume(){paused=false;flow.resume(now());bindControls();render();if(video!=null&&demoStep())video.play();if(!demoStep())say(flow.complete()?"This step is complete. Choose Next to continue.":SPOKEN[flow.step()],"resume:"+flow.tag());if(flow.step()==6&&!flow.complete())main.post(this::stability);}
    void prepareForReview(){pause();previewStep=-1;}
    void devJump(int step){developer=true;flow.devJump(step,now());changed();}
    void devSkip(){developer=true;flow.devSkip();render();save();}
    void devComplete(){developer=true;flow.devComplete();render();save();}
    void restart(){stopVideo();previewStep=-1;flow.restart(now());Arrays.fill(watched,false);Arrays.fill(positions,0);changed();}
    public void close(){if(closed)return;pause();closed=true;stopVideo();alignment.showGuide(null);}
}
