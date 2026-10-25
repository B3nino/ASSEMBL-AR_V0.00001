package dev.chyken.assembl_ar;

import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.camera.Phase5AssetCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import java.util.*;

/** Phase 5 UI coordinator; camera recognition, bitmap loading and Android TTS use their workers. */
final class Phase5GuideController implements AutoCloseable {
    private static final String[] HUD={"",
        "Step 5.1: Scan the Arduino Nano Microcontroller.\n• Place the microcontroller flat within the camera frame.\n• Tap 'View Info' for pinout details.",
        "Step 5.2: Board Installation Demo.\n• Align the Nano header pins with the female socket on the Main Board.\n• Ensure the USB port points in the direction shown in the video, then press down firmly.",
        "Step 5.3: Verify Microcontroller Orientation.\n• Keep the entire Nano and nearby PCB labels in view.\n• Point straight down; move back slightly if the USB port reflects light.",
        "Step 5.4: Pin Alignment & Stability Check.\n• Ensure the board is pressed fully flat and does not wobble.\n• Check that no header pins are bent or sticking outside the socket."};
    private static final String[] SPOKEN={"",
        "Step 5 point 1. Please locate and scan your Arduino Nano microcontroller by placing it flat inside the camera frame.",
        "Step 5 point 2. Align all pins with the main board header socket and press down firmly, making sure the USB port faces the correct direction.",
        "Step 5 point 3. Point your camera straight down. Keep the entire Nano and nearby board labels visible. Move back slightly if light reflects off the USB connector.",
        "Step 5 point 4. Confirm the microcontroller is firmly seated and that no pins are sticking outside the header socket."};
    private static final String RESEAT="Gently pull up the Nano, realign all pins over the socket holes, and press down evenly.";
    // Classic Nano facts: https://docs.arduino.cc/hardware/nano/
    private static final String INFO="Arduino Nano Overview:\n• Powered by the ATmega328P microcontroller.\n• Features 30 dual-in-line header pins, in two rows of 15.\n• Note USB port location: The Mini-USB/Type-C port serves as your main orientation marker during board installation.\n\nMatch the connector on your own Nano to the video and guide. This kit's photographed Nano has a Mini-USB connector; compatible boards can use a different connector. The USB end faces the rear power connectors in the supplied installed-board photos.";
    private final AssemblyActivity activity;
    private final CompactGuideCard card;
    private final ComponentPreviewWindow preview;
    private final TargetAlignmentBoxView alignment;
    private final Runnable finish,previousPhase,checkpointChanged;
    private final Handler main=new Handler(Looper.getMainLooper());
    final Phase5MicrocontrollerFlow flow;
    private final DemoClipNarrator narrator;
    private final List<String> clips;
    private Phase2VideoPlayer video;
    private AlertDialog dialog;
    private volatile Plan plan;
    private boolean developer,reviewing,paused,closed,saving,videosReady,videoError,finishing,infoShowing;
    private int previewStep=-1,resumeClip;
    private long resumePosition,sequence;
    private final long createdAt=SystemClock.elapsedRealtimeNanos();
    private String message="",savedState="",completionKey;
    private final Set<String> spokenStatus=new HashSet<>();
    private Phase5Observation.State lastWarning;
    private static final class Plan{final int step;final PhaseFlow.ScanTag tag;Plan(int step,PhaseFlow.ScanTag tag){this.step=step;this.tag=tag;}}

    Phase5GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,String saved,Runnable checkpointChanged){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;this.checkpointChanged=checkpointChanged;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);alignment=activity.findViewById(R.id.targetAlignmentBox);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.microcontrollerState(activity,NeobotSeedData.PROJECT_ID);
        String[] fields=saved==null?new String[0]:saved.split("\\|",-1);
        flow=Phase5MicrocontrollerFlow.restore(fields.length>0?fields[0]:null,createdAt);
        if(fields.length==4)try{videosReady=Boolean.parseBoolean(fields[1]);resumeClip=Math.max(0,Integer.parseInt(fields[2]));resumePosition=Math.max(0,Long.parseLong(fields[3]));}catch(RuntimeException bad){resumeClip=0;resumePosition=0;}
        clips=Phase5AssetCatalog.videos(activity.getAssets());resumeClip=Math.min(resumeClip,Math.max(0,clips.size()-1));
        paused=!activity.isActivityResumed();if(paused)flow.pause();
        narrator=new DemoClipNarrator(new String[]{SPOKEN[2]},new DemoClipNarrator.Voice(){
            public void speak(String text,String key){say(text,key);}public void stop(){activity.voiceGuide().stop();}
        });
        bindControls();
    }
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    private void say(String text,String key){if(!closed&&!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase5:"+key);}
    private void status(String text){message=text;if(spokenStatus.add(text)){say(text,"status:"+(++sequence));}}
    private void bindControls(){
        card.previous.setOnClickListener(v->{if(saving)return;if(flow.step()==1)previousPhase.run();else if(flow.previous(now()))changed();});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.step()==4){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.next(now()))changed();});
        card.action.setOnClickListener(v->{if(saving)return;if(flow.step()==1)viewInfo();else if(flow.step()==2){
            if(videosReady){flow.confirmDemo(true);status("Demo reviewed · Next unlocked");render();save();}
            else if(videoError){previewStep=-1;videoError=false;render();}
        }else if(flow.step()==4)stability();});
        card.rescan.setOnClickListener(v->{if(closed||paused||saving||reviewing||!flow.canRescan())return;dismissDialog();dialog=RescanConfirmationDialog.show(activity,()->{flow.rescan(now());changed();});});
        card.setOnDetailsChanged(text->{if(!reviewing||!paused)say(text.isEmpty()?"Additional information hidden.":text,"details:"+(++sequence));});
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?4:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);finishing=false;completionKey=null;message="";spokenStatus.clear();lastWarning=null;
        dismissDialog();activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.setBoardCorners(null);hud.clearAssemblyTargets();
        // Release the previous preview before preparing the next one; returning reattaches this phase's content.
        if(previewStep!=flow.step())stopVideo();render();save();
        if(flow.step()!=2||videoError)say(flow.complete()?"This step is complete. Review the extra information, then choose Next.":SPOKEN[flow.step()],"step:"+flow.tag());
        if(flow.step()==4&&!flow.complete()&&!paused&&!reviewing)main.post(this::stability);
    }
    boolean scanning(){return plan!=null;}
    RecognitionRequest request(CameraCalibration calibration){Plan p=plan;if(p==null)return null;return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MICROCONTROLLER),calibration,null,null,1,5).withPhase5Stage(p.step).withRequestTag(p.tag);}
    boolean current(RecognitionResult result){return !closed&&result.getFrameTimestampNanos()>=createdAt&&flow.tag().equals(result.getRequestTag());}
    void onResult(RecognitionResult result){
        if(paused||reviewing||infoShowing||saving||!current(result)||!flow.accepts(result.getRequestTag(),result.getFrameTimestampNanos()))return;
        Phase5Observation evidence=result.getPhase5Observation();boolean accepted=evidence!=null&&evidence.accepted(flow.step());flow.observe(accepted);
        boolean warning=evidence!=null&&(evidence.state==Phase5Observation.State.FLIPPED||evidence.state==Phase5Observation.State.BACKWARD||evidence.state==Phase5Observation.State.MISALIGNED);
        if(warning){
            lastWarning=evidence.state;
            status(evidence.state==Phase5Observation.State.FLIPPED?"Arduino Nano is inverted. Flip the board so the USB port and chips face up.":evidence.state==Phase5Observation.State.BACKWARD?"Arduino Nano is installed backward. Rotate it so the USB port faces the rear power connector, then re-seat all pins.":"Microcontroller orientation is incorrect or pins are misaligned. Check the USB port direction and re-seat.");
        }else if(accepted){lastWarning=null;status("Scan successful · Next unlocked");}
        else return; // Keep the entering instruction/correction visible; searching frames must not interrupt its speech.
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.update(accepted?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.SEARCHING,"Arduino Nano",accepted||warning?activity.normalizeCorners(result.getProjectedCorners()):null);
        activity.hideGuidance();render();save();
    }
    private void viewInfo(){
        if(closed||paused||dialog!=null&&dialog.isShowing())return;
        infoShowing=true;flow.pause();plan=null;render();
        TextView text=new TextView(activity);text.setText(INFO);text.setTextSize(16);int pad=UiUtils.dp(activity,24);text.setPadding(pad,pad/2,pad,pad);
        ScrollView scroll=new ScrollView(activity);scroll.addView(text);
        dialog=new AlertDialog.Builder(activity).setTitle("Arduino Nano · View Info").setView(scroll).setPositiveButton("Back to step",null).create();
        dialog.setOnDismissListener(d->{if(dialog!=d)return;infoShowing=false;if(!closed&&!paused){flow.resume(now());render();say(flow.complete()?"Scan successful. Next is unlocked.":SPOKEN[flow.step()],"info-closed:"+(++sequence));}});
        dialog.show();PhysicalCheckUi.style(dialog);say(INFO,"info:"+(++sequence));
    }
    private void stability(){
        if(closed||paused||saving||reviewing||flow.step()!=4||flow.complete()||dialog!=null&&dialog.isShowing())return;
        TextView prompt=PhysicalCheckUi.prompt(activity,"");
        dialog=new AlertDialog.Builder(activity).setTitle("Pin alignment & stability").setView(prompt).setNeutralButton("Check later",null).setNegativeButton("Loose",null).setPositiveButton("Firm",null).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;
            updateChecklist(prompt,false);
            say(SPOKEN[4]+" "+checklistQuestion(),"checklist-open:"+(++sequence));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{if(flow.firmConfirmed())flow.confirmPins(false);else flow.confirmFirm(false);status(RESEAT);updateChecklist(prompt);render();save();});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(!flow.firmConfirmed()){flow.confirmFirm(true);message="";updateChecklist(prompt);render();save();return;}
                flow.confirmPins(true);dialog.dismiss();render();save();
                if(!flow.canFinish(developer)){status("Physical checks complete. Finish the earlier Phase 5 checkpoints before continuing.");return;}
                finishing=true;completionKey="complete:"+(++sequence);say("Phase 5 complete! Microcontroller installed successfully.",completionKey);
                AssemblyVoiceGuide.Status voice=activity.voiceGuide().getStatus();if(voice==AssemblyVoiceGuide.Status.MUTED||voice==AssemblyVoiceGuide.Status.UNAVAILABLE)advance();else main.postDelayed(this::advance,16000);
            });
        });dialog.show();PhysicalCheckUi.style(dialog);
    }
    private String checklistQuestion(){return flow.firmConfirmed()?"Are all 30 pins inside the female headers with none hanging outside?":"Is the Arduino Nano firmly seated without wobbling?";}
    private void updateChecklist(TextView prompt){updateChecklist(prompt,true);}
    private void updateChecklist(TextView prompt,boolean speak){
        String question=checklistQuestion();
        prompt.setText((message.equals(RESEAT)?RESEAT+"\n\n":"")+question);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(flow.firmConfirmed()?"All Pins In":"Firm");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(flow.firmConfirmed()?"Pins Outside":"Loose");
        if(speak&&!message.equals(RESEAT))say(question,"checklist:"+(++sequence));
    }
    private void advance(){if(!closed&&!paused&&!saving&&finishing&&flow.canFinish(developer)){finishing=false;main.removeCallbacksAndMessages(null);finish.run();}}
    void render(){
        if(closed)return;int step=flow.step();plan=!saving&&!reviewing&&!infoShowing&&flow.scanning()?new Plan(step,flow.tag()):null;
        boolean unlocked=flow.complete()&&(step!=4||reviewing||flow.canFinish(developer));
        String details=INFO+"\n\nMatch both header rows before pressing evenly. The camera checks visible Nano placement and orientation; it cannot certify all hidden pins or physical firmness. Confirm those in Step 5.4.";
        card.render(50+step,"Phase 5 · Step 5."+step+" of 4",HUD[step],details,message,unlocked,true,!reviewing&&flow.canRescan());
        card.next.setText(step==4?reviewing?"Return to current phase":"Continue to Phase 6 →":"Next Step →");card.next.setEnabled(unlocked&&!saving);
        card.action.setVisibility(step==1||(step==2||step==4)&&!flow.complete()&&!reviewing?View.VISIBLE:View.GONE);
        card.action.setText(step==1?"View Info":step==2?videoError?"Retry demo":"Ready to Scan":"Check pins & stability");
        card.action.setEnabled(!saving&&(step!=2||videosReady||videoError));card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        alignment.setInstruction(step==1?"Arduino Nano · Top side":"Arduino Nano · Installed top view");alignment.showGuide(plan!=null?Phase5AssetCatalog.guide(activity.getAssets(),step):null);
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.setScanningEnabled(plan!=null);activity.hideGuidance();
        if(previewStep!=step){stopVideo();previewStep=step;if(step==2)startVideo();else preview.showComponent(NeobotSeedData.MICROCONTROLLER,"Arduino Nano");}
        preview.onHudState(flow.complete()?ArHudView.HudState.MATCHED:lastWarning!=null?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.SEARCHING);
        activity.setBanner(flow.complete()?"Step complete · Next unlocked":message.isEmpty()?HUD[step].split("\n")[0]:message,plan!=null,lastWarning!=null?R.drawable.bg_camera_warning:flow.complete()?R.drawable.bg_success:R.drawable.bg_live_pill);

        preview.setSkipPreviewAction(AssemblyStepNavigationManager.canSkipPreview(flow.step()==2,paused,saving)?this::skipPreview:null);
    }
    private void startVideo(){
        if(clips.isEmpty()){videoError=true;status("The Phase 5 installation demo is missing.");main.post(this::render);return;}
        video=new Phase2VideoPlayer(activity,clips,new Phase2VideoPlayer.Listener(){
            public void onClip(int clip){if(!closed&&flow.step()==2){render();save();}}
            public void onPlayback(int clip,boolean playing,boolean seek){if(!closed&&flow.step()==2)narrator.onPlayback(0,playing&&!paused,seek);}
            public void onClipEnded(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(video!=null&&(!narrator.isPending()||s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))video.continueAfterNarration();}
            public void onFinished(){if(closed||flow.step()!=2)return;narrator.stop();videosReady=true;videoError=false;status("Demo complete · Choose Ready to Scan");render();save();}
            public void onError(String error){narrator.stop();videoError=true;status(error);render();}
        },path->0);
        preview.showContent(video.view(),"Nano installation · Tap to pause · Drag to scrub");video.seek(resumeClip,resumePosition);if(!paused)video.play();
    }
    private void stopVideo(){if(video!=null){resumeClip=video.clip();resumePosition=video.position();narrator.stop();video.close();video=null;}}
    String checkpointState(){if(video!=null){resumeClip=video.clip();resumePosition=video.position();}return flow.snapshot()+"|"+videosReady+"|"+resumeClip+"|"+resumePosition;}
    private void save(){String value=checkpointState();if(!value.equals(savedState)){savedState=value;if(!developer&&!reviewing)PhaseProgressStore.saveMicrocontrollerState(activity,NeobotSeedData.PROJECT_ID,value);checkpointChanged.run();}}
    void onSpeechFinished(String key){if(closed||paused)return;if(finishing&&("phase5:"+completionKey).equals(key)){advance();return;}if(video!=null&&narrator.onSpeechFinished(key.startsWith("phase5:")?key.substring(7):key))video.continueAfterNarration();}
    void onVoiceStatusChanged(){if(closed||paused)return;AssemblyVoiceGuide.Status voice=activity.voiceGuide().getStatus();if(finishing&&(voice==AssemblyVoiceGuide.Status.MUTED||voice==AssemblyVoiceGuide.Status.UNAVAILABLE))advance();else if(video!=null){if(voice==AssemblyVoiceGuide.Status.AVAILABLE)narrator.onVoiceAvailable();else if(voice==AssemblyVoiceGuide.Status.MUTED||voice==AssemblyVoiceGuide.Status.UNAVAILABLE)video.continueAfterNarration();}}
    void setSaving(boolean value){boolean failed=saving&&!value;saving=value;if(failed)status("Progress could not be saved. Choose Continue to retry.");render();}
    private void dismissDialog(){if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}if(infoShowing){infoShowing=false;if(!paused)flow.resume(now());}}
    void pause(){paused=true;main.removeCallbacksAndMessages(null);flow.pause();plan=null;narrator.stop();if(video!=null)video.pause();activity.voiceGuide().stop();card.setOnDetailsChanged(null);save();dismissDialog();}
    void resume(){paused=false;flow.resume(now());bindControls();render();if(video!=null&&flow.step()==2)video.play();else say(flow.complete()?"This step is complete. Choose Next to continue.":SPOKEN[flow.step()],"resume:"+flow.tag());if(flow.step()==4&&!flow.complete()&&!reviewing)main.post(this::stability);}
    void prepareForReview(){pause();stopVideo();previewStep=-1;}
    void devJump(int step){developer=true;flow.devJump(step,now());changed();}
    void devSkip(){developer=true;flow.devSkip();render();save();}
    void devComplete(){developer=true;flow.devComplete();render();save();}
    void restart(){flow.restart(now());videosReady=false;resumeClip=0;resumePosition=0;previewStep=-1;changed();}
    @Override public void close(){if(closed)return;pause();closed=true;stopVideo();alignment.showGuide(null);((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(false);}

    private void skipPreview(){if(closed||!AssemblyStepNavigationManager.canSkipPreview(flow.step()==2,paused,saving))return;videosReady=true;flow.confirmDemo(true);if(flow.next(now()))changed();}
}
