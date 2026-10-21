package dev.chyken.assembl_ar;

import android.os.SystemClock;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.*;
import dev.chyken.assembl_ar.camera.Phase2AssetCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

/** Owns Phase 2 UI/playback on the main thread; camera requests are immutable snapshots. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class Phase2GuideController implements AutoCloseable {
    private static final String[] TEXT={"",
        "Step 2.1: Find and scan the 4x Motor Brackets.\n• Lay all 4 motor brackets flat inside the camera frame.\n• Keep camera steady until all 4 brackets are recognized.",
        "Step 2.2: Find and scan the 2x TT Gear Motors.\n• Place BOTH gear motors side-by-side within the camera frame.",
        "Step 2.3: Prepare fasteners and tools.\n• Locate 4x Long Screws (30mm) and 4x M3 Nuts.\n• Grab a Phillips screwdriver for easier assembly.\n• Tap 'I Have These' to proceed.",
        "Step 2.4: Assemble the gear motors.\nWatch the three clips. Tap the video to pause; drag horizontally or use the timeline to scrub. Rewatch before tapping Done Assembling.",
        "Step 2.5: Verify your motor assembly.\n• Scan the top view, then the left motor side, then the right motor side.\n• Match the current guide, keeping the brackets, screws and motor visible.\n• After all three scans, continue to the Joint Stability Check.",
        "Step 2.6: Joint Stability Check.\n• Lightly wiggle both installed gear motors with your fingers.\n• Are both motor joints firm, or is either side slightly jiggling?"};
    private static final String[] LINES={
        "1. Slot the 4x motor brackets into the designated mounting slots on both sides of the main board.",
        "2. Insert the TT gear motors between brackets. IMPORTANT: Ensure motor wire terminals face INWARD toward the board center, NOT outward.",
        "3. Pass 30mm screws through bracket holes and lock tightly with M3 nuts using a screwdriver. Repeat for both sides."};
    private final AssemblyActivity activity;
    private final CompactGuideCard card;
    private final ComponentPreviewWindow preview;
    private final TargetAlignmentBoxView alignment;
    private final Runnable finish;
    private final Runnable previousPhase;
    private final Runnable checkpointChanged;
    final Phase2GearMotorFlow flow;
    private volatile Plan plan;
    private Phase2VideoPlayer video;
    private final DemoClipNarrator narrator;
    private AlertDialog dialog;
    private int previewStep=-1,maxLine=-1,resumeClip;
    private long resumePosition,createdAt=SystemClock.elapsedRealtimeNanos();
    private boolean videosReady,videoError,developer,saving,closed,paused,reviewing;
    private String message="",savedState="";
    private final List<String> clips;
    private static final class Plan {
        final int stage;final PhaseFlow.ScanTag tag;
        Plan(int stage,PhaseFlow.ScanTag tag){this.stage=stage;this.tag=tag;}
    }
    Phase2GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase){
        this(activity,developer,finish,previousPhase,null,()->{});
    }
    Phase2GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,
                          String restoredState,Runnable checkpointChanged){
        this.activity=activity;this.developer=developer;this.finish=finish;
        this.checkpointChanged=checkpointChanged;
        narrator=new DemoClipNarrator(LINES,new DemoClipNarrator.Voice(){
            public void speak(String text,String key){activity.voiceGuide().speak(text,key);}
            public void stop(){activity.voiceGuide().stop();}
        });
        this.previousPhase=previousPhase;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);
        card.returnToCurrent.setVisibility(View.GONE);
        alignment=activity.findViewById(R.id.targetAlignmentBox);
        String saved=restoredState!=null&&!restoredState.isEmpty()?restoredState:developer?null:PhaseProgressStore.gearMotorState(activity,NeobotSeedData.PROJECT_ID);
        String[] parts=saved==null?new String[0]:saved.split("\\|");
        flow=Phase2GearMotorFlow.restore(parts.length>0?parts[0]:null,createdAt);
        paused=!activity.isActivityResumed();if(paused)flow.pause();
        if(parts.length==5)try{maxLine=Math.max(-1,Math.min(2,Integer.parseInt(parts[1])));videosReady=Boolean.parseBoolean(parts[2]);resumeClip=Math.max(0,Math.min(2,Integer.parseInt(parts[3])));resumePosition=Math.max(0,Long.parseLong(parts[4]));}catch(RuntimeException ignored){}
        clips=Phase2AssetCatalog.videos(activity.getAssets());
        card.previous.setOnClickListener(v->{if(flow.step()==1){previousPhase.run();return;}if(flow.previous(now()))changed();});
        card.next.setOnClickListener(v->{if(saving||!flow.canNext())return;if(flow.step()==6){if(flow.canFinish(developer))finish.run();}else if(flow.next(now()))changed();});
        card.action.setOnClickListener(v->action());
        card.rescan.setOnClickListener(v->requestRescan());
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devCompletePhase();flow.devJump(atEnd?6:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    private void changed(){
        if(closed)return;
        if(activity.trialLog()!=null)activity.trialLog().event("mode",flow.tag()+" gear-step="+flow.step());
        activity.resetAnalyzerTracking();activity.voiceGuide().stop();message="";
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.setBoardCorners(null);hud.clearAssemblyTargets();activity.hideGuidance();
        if(dialog!=null){dialog.dismiss();dialog=null;}
        render();
        announce();
        save();
    }
    boolean scanning(){return plan!=null;}
    private void announce(){
        if(activity.isActivityResumed()&&flow.step()!=4)activity.voiceGuide().speak(flow.step()==5?hardwareInstruction():flow.complete()
                ?"This step is complete. Review the instructions or choose Rescan to check it again.":TEXT[flow.step()],"phase2:"+flow.tag());
    }
    private void requestRescan(){
        if(dialog!=null)dialog.dismiss();
        dialog=RescanConfirmationDialog.show(activity,()->{flow.rescan(now());changed();});
    }
    RecognitionRequest request(CameraCalibration calibration){
        Plan p=plan;if(p==null)return null;
        String component=p.stage==1?MOTOR_BRACKET:p.stage==2?GEAR_MOTOR:MAIN_BOARD;
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(component),calibration,null,
                null,p.stage==1?4:p.stage==2?2:1,2).withPhase2Stage(p.stage).withRequestTag(p.tag);
    }
    boolean current(RecognitionResult result){return !closed&&result.getFrameTimestampNanos()>=createdAt&&flow.tag().equals(result.getRequestTag());}
    void onResult(RecognitionResult result){
        if(!flow.accepts(result.getRequestTag(),result.getFrameTimestampNanos()))return;
        int step=flow.step();Phase2Observation evidence=result.getPhase2Observation();
        boolean wasComplete=flow.complete();
        if(step<=2){
            String component=step==1?MOTOR_BRACKET:GEAR_MOTOR;
            boolean valid=component.equals(result.getComponentId())
                    && result.getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT
                    && (result.getDetectionMethod()==RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK
                    || result.getDetectionMethod()==RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY);
            int count=valid?result.getDetectedInstanceCount(component):0;
            flow.observeCount(count,valid);
            message=flow.complete()?"Scan successful · Next step unlocked"
                    :(step==1?count+"/4 brackets visible":count+"/2 motors visible")
                    +" · "+(step==1?"Show all four together.":"Show both together.");
        }else if(step==5){
            String before=flow.expectedHardwareView();
            flow.observeHardware(evidence==null?null:evidence.view,evidence!=null&&evidence.referenceSupported);
            boolean confirmed=flow.currentHardwareViewComplete();
            if(confirmed&&activity.trialLog()!=null)activity.trialLog().event("verification-view",before+" confirmed; awaiting Next");
            message=confirmed?viewName(before)+" successfully scanned · Next unlocked"
                    :evidence!=null&&evidence.viewAmbiguous?"View unclear. Match the current guide and keep screws and brackets visible."
                    :evidence!=null&&evidence.referenceSupported&&!Objects.equals(evidence.view,before)?"That is the "+viewName(evidence.view)+". Please show the "+viewName(before)+"."
                    :"Align the "+viewName(before)+" with the guide to identify the board.";
            if(confirmed&&activity.isActivityResumed())
                activity.voiceGuide().speak(hardwareInstruction(),"phase2:view:"+flow.tag());
        }
        ArHudView hud=activity.findViewById(R.id.arHudView);
        String expected=step==1?MOTOR_BRACKET:step==2?GEAR_MOTOR:MAIN_BOARD;
        boolean located=step<=2?expected.equals(result.getComponentId())&&result.getDetectedInstanceCount(expected)>0
                &&result.getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT
                :flow.currentHardwareViewComplete();
        boolean wrong=step<=2&&result.hasLocatedComponent()&&!expected.equals(result.getComponentId());
        hud.update(flow.canNext()?ArHudView.HudState.MATCHED:wrong?ArHudView.HudState.MISMATCH:located?ArHudView.HudState.TRACKING:ArHudView.HudState.SEARCHING,
                step==1?"Motor brackets":step==2?"TT gear motors":"Motor assembly",located||wrong?activity.normalizeCorners(result.getProjectedCorners()):null);
        if(!wasComplete&&flow.complete()){
            if(activity.trialLog()!=null)activity.trialLog().event(step<=2?"inventory":"verification","gear-step="+step+" confirmed");
            if(step<=2&&activity.isActivityResumed())activity.voiceGuide().speak("Scan successful. Step complete. The next step is unlocked.","phase2:scan-success:"+flow.tag());
        }
        render();save();
    }
    private static String viewName(String view){return "top".equals(view)?"top view":"left".equals(view)?"left motor side":"right".equals(view)?"right motor side":"requested view";}
    private String hardwareInstruction(){
        if(flow.complete())return "Top, left and right views scanned. Continue to the Joint Stability Check.";
        if(flow.currentHardwareViewComplete())return viewName(flow.expectedHardwareView())+" successfully scanned. Tap Next to scan the "+(flow.hardwareViewNumber()==1?"left":"right")+" motor side.";
        return "Scan "+flow.hardwareViewNumber()+" of 3: "+viewName(flow.expectedHardwareView())+".\n"
                +("top".equals(flow.expectedHardwareView())?"Show both installed motors from above.":"Move to the indicated side. Show the motor, both bracket screws and the board edge.")
                +" Match the guide and keep the full assembly area visible.";
    }
    private String hardwareChecklist(){
        StringBuilder text=new StringBuilder();
        for(String view:Arrays.asList("top","left","right")){
            if(text.length()>0)text.append("\n");
            text.append(flow.complete()||flow.hardwareViews().contains(view)?"✓ ":view.equals(flow.expectedHardwareView())?"→ ":"○ ")
                    .append(viewName(view)).append(flow.complete()||flow.hardwareViews().contains(view)?" — scanned":"");
        }
        return text.toString();
    }
    private void action(){
        if(saving)return;
        switch(flow.step()){
            case 3: tools();break;
            case 4: if(videosReady){flow.confirmAssembly(true);render();save();}else if(videoError){previewStep=-1;videoError=false;render();}break;
            case 6: firmness();break;
            default:break;
        }
    }
    private void tools(){
        if(dialog!=null)dialog.dismiss();
        dialog=new AlertDialog.Builder(activity).setTitle("Fasteners and tools")
                .setMessage("4× 30mm long screws\n4× M3 nuts\nPhillips screwdriver")
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton("I Have These",(d,w)->{flow.confirmTools();render();save();}).show();
    }
    private void firmness(){
        if(dialog!=null)dialog.dismiss();
        LinearLayout body=PhysicalCheckUi.body(activity);
        TextView introduction=PhysicalCheckUi.prompt(activity,"Lightly wiggle each installed motor. Check both sides before continuing.");introduction.setPadding(0,0,0,UiUtils.dp(activity,12));body.addView(introduction);
        RadioGroup[] answers=new RadioGroup[2];
        for(int i=0;i<2;i++){
            TextView label=new TextView(activity);label.setTextSize(16);label.setTextColor(0xFF263B30);label.setText(i==0?"Left motor joint":"Right motor joint");body.addView(label);
            answers[i]=PhysicalCheckUi.choices(activity,i==0?"Left motor joint":"Right motor joint");
            body.addView(answers[i]);
        }
        dialog=new AlertDialog.Builder(activity).setTitle("Joint Stability Check").setView(body)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton("Check joints",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(answers[0].getCheckedRadioButtonId()==-1||answers[1].getCheckedRadioButtonId()==-1){Toast.makeText(activity,"Check both joints first",Toast.LENGTH_SHORT).show();return;}
            boolean left=answers[0].getCheckedRadioButtonId()==answers[0].getChildAt(0).getId();
            boolean right=answers[1].getCheckedRadioButtonId()==answers[1].getChildAt(0).getId();
            flow.confirmFirmness(left,right);dialog.dismiss();
            message=left&&right?"Both motor joints are firm.":"Tighten the 30mm screws with the screwdriver, then re-check both joints.";
            if(activity.isActivityResumed())activity.voiceGuide().speak(message,"phase2:joints:"+now());
            render();save();
        }));dialog.show();PhysicalCheckUi.style(dialog);
    }
    void render(){
        if(closed)return;
        int step=flow.step();
        plan=!saving&&flow.scanning()?new Plan(step,flow.tag()):null;
        StringBuilder more=new StringBuilder(TEXT[step]);
        if(step==4){more.append("\n\nThe brackets support each motor on both sides. Position the terminals toward the centre before fastening, then compare both sides. The video timeline remains available for review.");}
        else more.append("\n\n").append(step==1?"Four slotted brackets hold the two motors against the main board. Keep their full outlines visible and separate."
                :step==2?"The yellow gearbox reduces speed and increases torque. Both complete motor bodies must be visible together."
                :step==3?"Use the specified 30mm screws for the bracket holes. Keep the four matching M3 nuts nearby."
                :step==5?"Identify the board from the top, left and right reference photos. Each successful scan unlocks Next for the following view. Joints are checked separately."
                :"The camera cannot measure joint firmness. Check each side with your fingers and re-tighten any movement.");
        String summary=TEXT[step];
        if(step==5)summary=hardwareInstruction()+"\n\n"+hardwareChecklist();
        if(step==4&&maxLine>=0){StringBuilder history=new StringBuilder();for(int i=0;i<=maxLine;i++){if(i>0)history.append("\n\n");history.append(LINES[i]);}summary=history.toString();}
        boolean unlocked=flow.canNext()&&(step!=6||flow.canFinish(developer));
        card.render(step,"Phase 2 · Step 2."+step+" of 6",summary,more.toString(),
                flow.complete()&&!unlocked?"Sub-step complete. Finish the remaining phase checkpoints to continue.":message,
                unlocked,true,(step==1||step==2||step==5)&&flow.canNext());
        card.action.setVisibility((step==3||step==4||step==6)&&!flow.complete()?View.VISIBLE:View.GONE);
        card.action.setText(step==3?"I Have These":step==4?(videoError?"Retry demo":"Done Assembling"):"Check joint stability");
        card.action.setEnabled(!saving&&(step!=4||videosReady||videoError));
        if(saving){card.next.setEnabled(false);card.next.setText("Saving…");plan=null;}
        if(step==6){card.next.setEnabled(!saving&&flow.canFinish(developer));if(!reviewing)card.next.setText("Continue to Phase 3 →");}
        card.returnToCurrent.setVisibility(reviewing&&!(step==6&&flow.allComplete())?View.VISIBLE:View.GONE);
        if(reviewing&&step==6)card.next.setText("Return to current phase");
        ((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(plan!=null);
        alignment.setInstruction(step==5&&!flow.currentHardwareViewComplete()?"Scan "+flow.hardwareViewNumber()+" / 3 · "+viewName(flow.expectedHardwareView()):null);
        alignment.showGuide(flow.scanning()?Phase2AssetCatalog.guide(activity.getAssets(),2,step,flow.expectedHardwareView()):null);
        if(step==5)card.next.setText(flow.hardwareViewNumber()==1?"Next: Scan left side →":flow.hardwareViewNumber()==2?"Next: Scan right side →":"Joint Stability Check →");
        if(previewStep!=step){
            stopVideo();previewStep=step;
            if(step==1)preview.showComponent(MOTOR_BRACKET,"Motor bracket");
            else if(step==2)preview.showComponent(GEAR_MOTOR,"TT gear motor");
            else if(step==3)preview.showFasteners(false);
            else if(step==4)startVideo();
            else preview.showComponent(MAIN_BOARD,"Main Board · motor assembly");
        }
        preview.onHudState(flow.canNext()?ArHudView.HudState.MATCHED:ArHudView.HudState.SEARCHING);
        activity.setBanner(step==5&&flow.currentHardwareViewComplete()?viewName(flow.expectedHardwareView())+" scanned · Next unlocked":flow.complete()?"Step complete · Next step unlocked":step==5?"Scan "+flow.hardwareViewNumber()+" / 3 · "+viewName(flow.expectedHardwareView()):TEXT[step].split("\n")[0],
                flow.scanning(),flow.canNext()?R.drawable.bg_success:R.drawable.bg_live_pill);
    }
    private void showToolsPreview(){
        LinearLayout row=new LinearLayout(activity);row.setGravity(android.view.Gravity.CENTER);
        for(String id:new String[]{SCREW_30MM,NUT,PHILLIPS_SCREWDRIVER}){
            LinearLayout item=new LinearLayout(activity);item.setOrientation(1);item.setGravity(android.view.Gravity.CENTER);
            Component part=activity.component(id);ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(image,new LinearLayout.LayoutParams(-1,0,1));
            if(part!=null)AssetImageLoader.loadBounded(image,activity.partReferenceAsset(part),512);
            TextView text=new TextView(activity);text.setText(id.equals(SCREW_30MM)?"30mm screw":id.equals(NUT)?"M3 nut":"Phillips driver");text.setTextColor(0xFF26332D);text.setTextSize(11);item.addView(text);
            row.addView(item,new LinearLayout.LayoutParams(0,-1,1));
        }
        preview.showContent(row,"Fasteners & tools");
    }
    private void startVideo(){
        boolean[] roles=new boolean[3];for(String clip:clips){int role=Phase2AssetCatalog.clipOrder(clip);if(role<3)roles[role]=true;}
        if(!roles[0]||!roles[1]||!roles[2]){message="Demo clips missing. Add the bracket, inward motor and screw-position clips.";card.action.setEnabled(false);return;}
        video=new Phase2VideoPlayer(activity,clips,new Phase2VideoPlayer.Listener(){
            public void onClip(int clip){if(closed||flow.step()!=4||clip>2)return;maxLine=Math.max(maxLine,clip);render();save();}
            public void onPlayback(int clip,boolean playing,boolean seek){
                if(closed||flow.step()!=4)return;
                narrator.onPlayback(clip,playing&&!paused&&activity.isActivityResumed(),seek);
            }
            public void onClipEnded(){
                AssemblyVoiceGuide.Status status=activity.voiceGuide().getStatus();
                if(video!=null&&(!narrator.isPending()||status==AssemblyVoiceGuide.Status.MUTED||status==AssemblyVoiceGuide.Status.UNAVAILABLE))
                    video.continueAfterNarration();
            }
            public void onFinished(){if(closed||flow.step()!=4)return;narrator.stop();videosReady=true;videoError=false;maxLine=2;message="Demo finished · Review the instructions, then tap Done Assembling.";render();save();}
            public void onError(String error){narrator.stop();message=error;videoError=true;render();}
        });
        preview.showContent(video.view(),"Assembly demo · Tap to pause · Drag to scrub");
        if(resumeClip<clips.size())video.seek(resumeClip,resumePosition);
        if(!paused)video.play();
    }
    private void stopVideo(){if(video!=null){narrator.stop();resumeClip=video.clip();resumePosition=video.position();video.close();video=null;}}
    void onVoiceStatusChanged(){
        if(closed||paused||flow.step()!=4)return;
        AssemblyVoiceGuide.Status status=activity.voiceGuide().getStatus();
        if(status==AssemblyVoiceGuide.Status.AVAILABLE)narrator.onVoiceAvailable();
        else if(video!=null&&(status==AssemblyVoiceGuide.Status.MUTED||status==AssemblyVoiceGuide.Status.UNAVAILABLE))
            video.continueAfterNarration();
    }
    void onSpeechFinished(String key){
        if(!closed&&!paused&&video!=null&&narrator.onSpeechFinished(key))video.continueAfterNarration();
    }
    String checkpointState(){
        if(video!=null){resumeClip=video.clip();resumePosition=video.position();}
        return flow.snapshot()+"|"+maxLine+"|"+videosReady+"|"+resumeClip+"|"+resumePosition;
    }
    private void save(){
        String value=checkpointState();
        if(!value.equals(savedState)){
            if(!developer)PhaseProgressStore.saveGearMotorState(activity,NeobotSeedData.PROJECT_ID,value);
            savedState=value;checkpointChanged.run();
        }
    }
    void setSaving(boolean value){saving=value;render();}
    void pause(){paused=true;flow.pause();plan=null;narrator.stop();if(video!=null){resumeClip=video.clip();resumePosition=video.position();video.pause();}save();if(dialog!=null){dialog.dismiss();dialog=null;}}
    void resume(){
        paused=false;flow.resume(now());
        if(!flow.complete())message=flow.step()==1?"Show all four brackets together."
                :flow.step()==2?"Show both motors together."
                :flow.step()==5?"Progress saved · Continue with the "+viewName(flow.expectedHardwareView())+".":message;
        render();announce();
    }
    void devJump(int index){developer=true;flow.devJump(index,now());changed();}
    void devSkip(){developer=true;flow.devCompleteStep();render();}
    void devComplete(){developer=true;flow.devCompletePhase();changed();}
    void restart(){flow.restart(now());videosReady=false;maxLine=-1;resumeClip=0;resumePosition=0;changed();}
    @Override public void close(){if(closed)return;pause();closed=true;stopVideo();alignment.showGuide(null);}
}
