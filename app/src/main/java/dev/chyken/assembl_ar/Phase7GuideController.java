package dev.chyken.assembl_ar;

import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.camera.Phase7AssetCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import java.util.*;

/** Phase 7 coordinates explicit hardware answers, narrated media and isolated photo checks. */
final class Phase7GuideController implements AutoCloseable {
    private static final String[] HUD={"",
        "Step 7.1a: Scan the Caster Wheel.\n• Place the Caster Wheel flat in frame with its top side visible.\n• After the scan, choose Next to prepare its mounting hardware.",
        "Step 7.2a: Scan both Line Trackers.\n• Place BOTH Line Tracker IR modules in frame, with sensors visible.\n• After the scan, choose Next to prepare their mounting hardware.",
        "Step 7.3: Assemble and verify the caster wheel.",
        "Step 7.4: Assemble and verify both line trackers."};
    private static final String[] SPOKEN={"",
        "Step 7 point 1. Scan your caster wheel unit. Once identified, choose Next to prepare its mounting hardware.",
        "Step 7 point 2. Scan both line tracker modules together. Once identified, choose Next to prepare their mounting hardware.",
        "Step 7 point 3. Follow the caster wheel assembly demos, then scan the bottom view and check the caster's stability.",
        "Step 7 point 4. Follow the line tracker assembly demos, then scan the bottom view and check both modules."};
    private static final String[] LINES={
        "1. Locate the two mounting holes at the front of the main board and insert 2x 20mm screws from the top.",
        "2. Thread 2x nuts underneath as standoff spacers, slide the Caster Wheel onto the screws, and lock with 2x outer nuts.",
        "1. Locate the left and right sensor mounting holes adjacent to the caster wheel.",
        "2. Place nylon spacers between the board and modules, insert 10mm screws from top, and tighten M3 nuts underneath."};
    private static final String[] NARRATION={
        "First, locate the two front mounting holes and insert two 20 millimeter screws from the top side of the board.",
        "Next, thread two nuts underneath to act as spacers, slide the caster wheel on, and lock it with the final two nuts.",
        "Locate the sensor mounting holes next to the caster wheel.",
        "Place nylon spacers between the board and modules, insert 10 millimeter screws from top, and secure with M3 nuts underneath."};
    private final AssemblyActivity activity;private final CompactGuideCard card;private final ComponentPreviewWindow preview;private final TargetAlignmentBoxView alignment;
    private final Runnable finish,previousPhase,checkpointChanged;private final Handler main=new Handler(Looper.getMainLooper());
    final Phase7CasterTrackerFlow flow;
    private final DemoClipNarrator narrator;
    private Phase2VideoPlayer video;private AlertDialog dialog;private volatile Plan plan;
    private boolean developer,reviewing,paused,closed,saving,modal,videoError,finishing,warning;
    private final int[] maxLine={-1,-1},resumeClip={0,0};private final long[] resumePosition={0,0};
    private int previewKey=-1,videoStep;private String message="",savedState="",completionKey;private long sequence;
    private final long createdAt=SystemClock.elapsedRealtimeNanos();
    private final Set<String> spoken=new HashSet<>();
    private static final class Plan{final int step;final PhaseFlow.ScanTag tag;Plan(int step,PhaseFlow.ScanTag tag){this.step=step;this.tag=tag;}}
    Phase7GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,String saved,Runnable checkpointChanged){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;this.checkpointChanged=checkpointChanged;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);alignment=activity.findViewById(R.id.targetAlignmentBox);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.casterTrackerState(activity,NeobotSeedData.PROJECT_ID);
        String[] fields=saved==null?new String[0]:saved.split("\\|",-1);
        flow=Phase7CasterTrackerFlow.restore(fields.length>0?fields[0]:null,createdAt);
        if(fields.length==7)try{for(int i=0;i<2;i++){maxLine[i]=Math.max(-1,Math.min(1,Integer.parseInt(fields[1+i*3])));resumeClip[i]=Math.max(0,Math.min(1,Integer.parseInt(fields[2+i*3])));resumePosition[i]=Math.max(0,Long.parseLong(fields[3+i*3]));}}catch(RuntimeException bad){Arrays.fill(maxLine,-1);Arrays.fill(resumeClip,0);Arrays.fill(resumePosition,0);}
        paused=!activity.isActivityResumed();if(paused)flow.pause();
        narrator=new DemoClipNarrator(NARRATION,new DemoClipNarrator.Voice(){public void speak(String text,String key){say(text,key);}public void stop(){activity.voiceGuide().stop();}});
        bind();
    }
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    private void say(String text,String key){if(!closed&&!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase7:"+key);}
    private void status(String text){message=text;if(spoken.add(text))say(text,"status:"+(++sequence));}
    private String instruction(){return flow.preparingHardware()?flow.step()==1
        ?"Step 7.1b: Prepare Caster Wheel Hardware.\n• Gather 2x screws (20mm) and 4x M3 nuts.\n• Compare them with the labeled, rotatable models above.\n• Tap I Have These when everything is ready."
        :"Step 7.2b: Prepare Line Tracker Hardware.\n• Gather 2x screws (10mm), 2x nylon spacers, and 2x M3 nuts.\n• Compare them with the labeled, rotatable models above.\n• Tap I Have These when everything is ready.":HUD[flow.step()];}
    private String spokenInstruction(){return flow.preparingHardware()?flow.step()==1
        ?"Prepare the caster wheel hardware. Gather two 20 millimeter screws and four M3 nuts. Inspect the models, then tap I Have These."
        :"Prepare the line tracker hardware. Gather two 10 millimeter screws, two nylon spacers, and two M3 nuts. Inspect the models, then tap I Have These.":SPOKEN[flow.step()];}
    private void bind(){
        card.previous.setOnClickListener(v->{if(saving)return;if(flow.step()==1&&!flow.preparingHardware())previousPhase.run();else {stopVideo();if(flow.previous(now())){if(flow.viewingDemo()){int g=flow.step()-3;resumeClip[g]=0;resumePosition[g]=0;}changed();}}});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.step()==4){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.nextStage(now()))changed();});
        card.action.setOnClickListener(v->{if(saving)return;if(flow.step()<=2)confirmHardware();else if(videoError){stopVideo();startVideo();render();}
            else if(flow.demoReady()&&(flow.viewingDemo()||!flow.scanned())){stopVideo();previewKey=-1;flow.armScan(now());status(scanInstruction());activity.resetAnalyzerTracking();render();save();if(flow.scanned()&&!flow.complete())main.post(this::checklist);}
            else if(flow.scanned())checklist();
        });
        card.rescan.setOnClickListener(v->requestRescan());
        card.setOnDetailsChanged(text->{
            // Extra information must not replace an outstanding clip utterance and leave the
            // playlist waiting forever for that cancelled utterance's completion callback.
            if(video!=null){video.pause();narrator.stop();}
            say(text.isEmpty()?"Additional information hidden.":text,"details:"+(++sequence));
        });
    }
    private void requestRescan(){
        if(closed||paused||saving||!flow.canRescan())return;
        dismiss();modal=true;flow.pause();render();
        dialog=RescanConfirmationDialog.show(activity,()->{
            stopVideo();previewKey=-1;flow.rescan(now());changed();
        });
        dialog.setOnDismissListener(d->{
            dialog=null;
            if(modal){modal=false;if(!closed&&!paused){flow.resume(now());render();}}
        });
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?4:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);finishing=false;message="";warning=false;spoken.clear();dismiss();stopVideo();previewKey=-1;videoError=false;
        activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.clearAssemblyTargets();hud.setBoardCorners(null);
        render();save();
        if(flow.viewingDemo()&&!paused)startVideo();else say(flow.stageComplete()?"This step is complete. Review the information, then choose Next.":flow.step()>=3&&flow.scanning()?scanInstruction():spokenInstruction(),"step:"+flow.tag());
        if(flow.step()>=3&&flow.scanned()&&!flow.viewingDemo()&&!flow.complete()&&!paused)main.post(this::checklist);
    }
    boolean scanning(){return plan!=null;}
    RecognitionRequest request(CameraCalibration calibration){Plan p=plan;if(p==null)return null;String target=p.step==1?NeobotSeedData.CASTER_WHEEL:p.step==2?NeobotSeedData.LINE_TRACKER:NeobotSeedData.MAIN_BOARD;
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(target),calibration,null,null,p.step==2?2:1,7).withPhase7Stage(p.step).withRequestTag(p.tag);}
    boolean current(RecognitionResult result){return !closed&&result.getFrameTimestampNanos()>=createdAt&&flow.tag().equals(result.getRequestTag());}
    void onResult(RecognitionResult result){
        if(paused||saving||modal||!current(result)||!flow.accepts(result.getRequestTag(),result.getFrameTimestampNanos()))return;
        Phase7Observation evidence=result.getPhase7Observation();if(evidence==null)return;boolean wasWarning=warning;warning=evidence.invalid;flow.observe(evidence);
        if(evidence.invalid)status(flow.step()==1?"Caster wheel position invalid. Flip it over to its top side.":flow.step()==2?"Line tracker orientation invalid. Lay both modules flat with IR sensors visible.":flow.step()==3?"Caster wheel is missing or misaligned. Check hardware placement and re-scan.":"Line trackers are missing or improperly installed. Mount both trackers and re-scan.");
        else if(flow.scanned()&&evidence.trackerInstallation)status(evidence.imageConfirmedNuts==2?"Both trackers and mounting nuts found · Confirm hardware and stability":"Both trackers located at their mounts · Confirm the screws, spacers, nuts and stability");
        else if(flow.scanned())status(flow.step()<=2?"Scan successful · Next: prepare mounting hardware":"Scan successful · Complete the stability check");
        else if(evidence.trackerInstallation)status(evidence.visibleTrackers<2?
                evidence.visibleTrackers+"/2 trackers found. Keep BOTH complete modules in view, including their blue adjustment knobs and sensor ends.":
                !evidence.boardContext?"Both trackers found. Keep some printed board markings around their mounts in view.":
                "Both trackers found · "+evidence.verifiedMounts+"/2 board mounts aligned. Keep the module ends and nearby board markings visible.");
        else if(flow.step()==2&&evidence.count>0)status("1 of 2 line trackers found. Keep both modules visible together.");
        else {if(wasWarning){message="";render();}return;}
        ArHudView hud=activity.findViewById(R.id.arHudView);
        hud.update(evidence.invalid?ArHudView.HudState.WRONG_ORIENTATION:flow.scanned()?ArHudView.HudState.MATCHED:ArHudView.HudState.SEARCHING,"Phase 7",activity.normalizeCorners(result.getProjectedCorners()));
        if(evidence.trackerInstallation){
            List<ArHudView.DetectedPart> parts=new ArrayList<>();
            for(RecognitionResult.InstanceObservation instance:result.getInstanceObservations()){
                float[] corners=activity.normalizeCorners(instance.getProjectedCorners());
                if(corners!=null&&corners.length==8)parts.add(new ArHudView.DetectedPart("Tracker "+(parts.size()+1),corners,ArHudView.PartStatus.ACCEPTED));
            }
            hud.setDetectedParts(parts);
        }
        render();save();if(flow.step()>=3&&flow.scanned())main.post(this::checklist);
    }
    private void confirmHardware(){
        if(closed||paused||saving||!flow.preparingHardware()||!flow.scanned())return;
        flow.confirmHardware();status("Hardware ready · Next unlocked");render();save();
    }
    private String scanInstruction(){return flow.step()==3?"Turn the board over. Align the bottom view within the recommended scanning frame to verify the caster wheel.":"Show the front half of the board from underneath, with BOTH trackers and their mounting nuts visible. The modules may swivel around their screws; the whole board does not need to fit in frame.";}
    private void checklist(){
        if(closed||paused||saving||flow.step()<3||flow.viewingDemo()||!flow.scanned()||flow.complete()||dialog!=null&&dialog.isShowing())return;
        TextView prompt=PhysicalCheckUi.prompt(activity,question());
        dialog=new AlertDialog.Builder(activity).setTitle(flow.step()==3?"Caster stability":"Line tracker stability").setView(prompt).setNeutralButton("Check later",null).setNegativeButton("Loose",null).setPositiveButton("Secure",null).create();
        dialog.setOnShowListener(d->{PhysicalCheckUi.style(dialog);checkLabels();say(question(),"check:"+(++sequence));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{
                if(flow.step()==3){if(flow.sandwiched())flow.confirmSwivels(false);else flow.confirmSandwiched(false);}else flow.confirmStable(false);
                String correction=flow.step()==3?"Please tighten both outer nuts against the caster plate using your screwdriver.":"Check both 10 millimeter screws, nylon spacers and M3 nuts, then tighten the hardware until the trackers hold their position.";
                status(correction);prompt.setText(correction+"\n\n"+question());checkLabels();render();save();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(flow.step()==3&&!flow.sandwiched()){flow.confirmSandwiched(true);prompt.setText(question());checkLabels();say(question(),"check:"+(++sequence));render();save();return;}
                if(flow.step()==3)flow.confirmSwivels(true);else flow.confirmStable(true);dialog.dismiss();status("Installation and stability complete · Next unlocked");render();save();
                if(!reviewing&&flow.step()==4&&flow.canFinish(developer)){finishing=true;completionKey="complete:"+(++sequence);say("Phase 7 complete! Excellent work installing the caster wheel and line trackers.",completionKey);
                    AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE)advance();else main.postDelayed(this::advance,16000);}
            });
        });dialog.show();
    }
    private String question(){return flow.step()==4?"Are both trackers secured with their 10mm screws, nylon spacers and M3 nuts, with no loose wobble?":flow.sandwiched()?"Does the caster wheel swivel freely without jiggling?":"Is the caster wheel firmly sandwiched between the two sets of M3 nuts?";}
    private void checkLabels(){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(flow.step()==4?"Stable & Secure":flow.sandwiched()?"Swivels Firmly":"Sandwiched & Secure");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(flow.step()==4?"Wobbling Loose":flow.sandwiched()?"Jiggling":"Loose");}
    private void advance(){if(!closed&&!paused&&!saving&&finishing&&flow.canFinish(developer)){finishing=false;main.removeCallbacksAndMessages(null);finish.run();}}
    void render(){
        // Review owns an isolated flow. Only an explicitly confirmed Rescan clears its scan
        // gate; its results never overwrite the active phase checkpoint (see save()).
        if(closed)return;int step=flow.step();plan=!saving&&!modal&&video==null&&flow.scanning()?new Plan(step,flow.tag()):null;
        boolean unlocked=flow.stageComplete()&&(step!=4||reviewing||flow.canFinish(developer));StringBuilder summary=new StringBuilder(instruction());
        if(step==2&&!flow.preparingHardware())summary.append("\n").append(flow.scanned()?2:flow.trackerCount()).append("/2 trackers identified");
        if(step>=3){
            int group=step-3,base=group*2;
            for(int i=0;i<=maxLine[group];i++)summary.append("\n\n").append(LINES[base+i]);
            if(!flow.viewingDemo())summary.append("\n\n").append(scanInstruction()).append(flow.scanned()?"\nBottom-view scan verified.":"");
        }
        String previewHelp=step<=2?flow.preparingHardware()?"The labeled hardware models rotate together. Confirm the quantities listed above; these fasteners do not need a camera scan.":"Touch and drag the component model to rotate it.":"Tap the preview title to choose a component or fastener model. Touch and drag the model to rotate it.";
        String details=summary+"\n\n"+previewHelp+"\n\nLeave the battery disconnected while assembling. Camera verification checks visible placement; the hands-on questions confirm nut retention, swivel movement and stable mounting.";
        String heading="Phase 7 · Step 7."+step+(step<=2?flow.preparingHardware()?"b · Hardware":"a · Component scan":" of 4");
        card.render(700+flow.screenIndex(),heading,summary.toString(),details,message,unlocked,true,flow.canRescan());
        card.rescan.setEnabled(!saving&&!modal);
        card.next.setText(step==4?reviewing?"Return to current phase":"Continue to Phase 8 →":step<=2&&!flow.preparingHardware()?"Next: Prepare hardware →":"Next Step →");card.next.setEnabled(unlocked&&!saving);
        card.action.setVisibility(step<=2?flow.preparingHardware()?View.VISIBLE:View.GONE:(!flow.complete()||flow.viewingDemo())?View.VISIBLE:View.GONE);
        card.action.setText(step<=2?flow.hardwareReady()?"Hardware confirmed":"I Have These":videoError?"Retry demo":flow.viewingDemo()&&flow.scanned()?"Return to verification":flow.scanned()?"Check stability":"Scan Work");
        card.action.setEnabled(!saving&&(step<=2?flow.preparingHardware()&&flow.scanned()&&!flow.hardwareReady():videoError||flow.scanned()||flow.demoReady()&&plan==null));
        card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        alignment.setInstruction(step<=2?"Phase 7 · "+(step==1?"Caster wheel":"Both line trackers"):"Bottom view · "+(step==3?"Caster wheel":"Front half · Both trackers and mounting nuts"));
        alignment.showGuide(plan!=null?Phase7AssetCatalog.guide(activity.getAssets(),step):null,
                step==4?new android.graphics.RectF(.24f,.10f,.78f,.53f):null);
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.setScanningEnabled(plan!=null);activity.hideGuidance();
        if(video==null&&previewKey!=flow.screenIndex()&&(!flow.viewingDemo()||videoError)){previewKey=flow.screenIndex();showModels();}
        preview.onHudState(warning?ArHudView.HudState.WRONG_ORIENTATION:flow.stageComplete()?ArHudView.HudState.MATCHED:ArHudView.HudState.SEARCHING);
        activity.setBanner(message.isEmpty()?instruction().split("\n")[0]:message,plan!=null,warning?R.drawable.bg_camera_warning:flow.stageComplete()?R.drawable.bg_success:R.drawable.bg_live_pill);
    }
    private void showModels(){
        if(flow.preparingHardware()){preview.showPhase7Hardware(flow.step()==1);return;}
        if(flow.step()<=2){preview.showComponent(flow.step()==1?NeobotSeedData.CASTER_WHEEL:NeobotSeedData.LINE_TRACKER,flow.step()==1?"Caster wheel":"Line tracker");return;}
        boolean caster=flow.step()==1||flow.step()==3;
        String[] ids=caster?new String[]{NeobotSeedData.CASTER_WHEEL,NeobotSeedData.SCREW_20MM,NeobotSeedData.NUT}:new String[]{NeobotSeedData.LINE_TRACKER,NeobotSeedData.SCREW_SHORT,NeobotSeedData.PLASTIC_SPACER,NeobotSeedData.NUT};
        String[] names=caster?new String[]{"Caster wheel","20mm screw","M3 nut"}:new String[]{"Line tracker","10mm screw","Nylon spacer","M3 nut"};
        preview.showModelChoices(ids,names,0,text->say(text,"model:"+(++sequence)));
        if(flow.step()>=3)preview.setContentAction("Replay assembly demos",()->{if(!paused&&!saving){dismiss();int group=flow.step()-3;resumeClip[group]=0;resumePosition[group]=0;startVideo();if(video!=null)video.play();}});
    }
    private void startVideo(){
        if(closed||paused||flow.step()<3)return;stopVideo();int step=flow.step(),group=step-3,base=group*2;
        List<String> clips=Phase7AssetCatalog.videos(activity.getAssets(),step);
        if(clips.size()!=2){videoError=true;status("The two assembly demo clips are missing. Check the Phase 7 video folder.");render();return;}
        videoError=false;flow.suspendScan(now());plan=null;previewKey=-1;
        videoStep=step;video=new Phase2VideoPlayer(activity,clips,new Phase2VideoPlayer.Listener(){
            public void onClip(int clip){if(closed||flow.step()!=step)return;maxLine[group]=Math.max(maxLine[group],clip-base);render();save();}
            public void onPlayback(int clip,boolean playing,boolean seek){if(!closed&&flow.step()==step)narrator.onPlayback(clip,playing&&!paused,seek);}
            public void onClipEnded(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(video!=null&&(!narrator.isPending()||s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))video.continueAfterNarration();}
            public void onFinished(){if(closed||flow.step()!=step)return;flow.demoFinished();maxLine[group]=1;narrator.stop();status(flow.scanned()?"Demo complete · Return to verification when ready":"Demo complete · Scan Work is ready");render();save();}
            public void onError(String error){narrator.stop();videoError=true;status(error);render();}
        },Phase7AssetCatalog::clipOrder);
        preview.showContent(video.view(),step==3?"Caster assembly · Tap to pause":"Tracker assembly · Tap to pause");video.seek(resumeClip[group],resumePosition[group]);render();video.play();
    }
    private void stopVideo(){if(video!=null){int group=Math.max(0,Math.min(1,videoStep-3));resumeClip[group]=video.clip();resumePosition[group]=video.position();narrator.stop();video.close();video=null;}}
    String checkpointState(){if(video!=null){int group=videoStep-3;resumeClip[group]=video.clip();resumePosition[group]=video.position();}String text=flow.snapshot();for(int i=0;i<2;i++)text+="|"+maxLine[i]+"|"+resumeClip[i]+"|"+resumePosition[i];return text;}
    private void save(){String state=checkpointState();if(!state.equals(savedState)){savedState=state;if(!developer&&!reviewing)PhaseProgressStore.saveCasterTrackerState(activity,NeobotSeedData.PROJECT_ID,state);checkpointChanged.run();}}
    void onSpeechFinished(String key){if(closed||paused)return;if(finishing&&("phase7:"+completionKey).equals(key)){advance();return;}if(video!=null&&narrator.onSpeechFinished(key.startsWith("phase7:")?key.substring(7):key))video.continueAfterNarration();}
    void onVoiceStatusChanged(){if(closed||paused)return;AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(finishing&&(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))advance();else if(video!=null){if(s==AssemblyVoiceGuide.Status.AVAILABLE)narrator.onVoiceAvailable();else if(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE)video.continueAfterNarration();}}
    void setSaving(boolean value){boolean failed=saving&&!value;saving=value;if(failed)status("Progress could not be saved. Choose Continue to retry.");render();}
    private void dismiss(){if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}if(modal){modal=false;if(!paused)flow.resume(now());}}
    void pause(){paused=true;main.removeCallbacksAndMessages(null);flow.pause();plan=null;narrator.stop();if(video!=null)video.pause();activity.voiceGuide().stop();card.setOnDetailsChanged(null);save();dismiss();}
    void resume(){paused=false;flow.resume(now());bind();render();if(video!=null)video.play();else if(flow.viewingDemo())startVideo();else say(flow.stageComplete()?"Step complete. Next is unlocked.":flow.step()>=3&&flow.scanning()?scanInstruction():spokenInstruction(),"resume:"+flow.tag());}
    void prepareForReview(){pause();stopVideo();previewKey=-1;}
    void devJump(int screen){developer=true;flow.devJumpScreen(screen-1,now());changed();}
    void devSkip(){developer=true;flow.devSkipStage(now());changed();}
    void devComplete(){developer=true;flow.devComplete();stopVideo();previewKey=-1;render();save();}
    void restart(){stopVideo();flow.restart(now());Arrays.fill(maxLine,-1);Arrays.fill(resumeClip,0);Arrays.fill(resumePosition,0);changed();}
    @Override public void close(){if(closed)return;pause();stopVideo();closed=true;alignment.showGuide(null);}
}

