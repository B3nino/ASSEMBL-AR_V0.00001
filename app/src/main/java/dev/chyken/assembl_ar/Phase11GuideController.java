package dev.chyken.assembl_ar;

import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.*;
import dev.chyken.assembl_ar.camera.Phase11AssetCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import dev.chyken.assembl_ar.workflow.phase.Phase11OledBatteryFlow.Screen;

/** Main-thread view coordination; vision, asset preparation and TTS retain their workers. */
final class Phase11GuideController implements AutoCloseable {
    private final AssemblyActivity activity;private final CompactGuideCard card;private final ComponentPreviewWindow preview;private final TargetAlignmentBoxView alignment;
    private final Runnable finish,previousPhase,checkpointChanged;private final Handler main=new Handler(Looper.getMainLooper());
    final Phase11OledBatteryFlow flow;private final DemoClipNarrator narrator;private Phase2VideoPlayer video;private AlertDialog dialog;
    private volatile Plan plan;private Screen attached;private final long createdAt=SystemClock.elapsedRealtimeNanos();
    private final boolean[] watched=new boolean[2];private final long[] positions=new long[2];
    private boolean developer,reviewing,reviewScan,paused,closed,saving,modal,videoError,warning;
    private String message="",savedState="";private long sequence;private final Set<String> spoken=new HashSet<>();
    private static final class Plan{final int stage;final PhaseFlow.ScanTag tag;Plan(int stage,PhaseFlow.ScanTag tag){this.stage=stage;this.tag=tag;}}
    private static final String[] NARRATION={
        "Watch the video to insert the OLED display into the extension board female header socket. Align all four pins before pressing gently.",
        "Watch the video to place the battery on the upper extension board beside the OLED, secure it with tape or a strap, and plug in the power connector. Keep the power switch off during installation."};
    Phase11GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,String saved,Runnable changed){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;checkpointChanged=changed;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);alignment=activity.findViewById(R.id.targetAlignmentBox);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.oledBatteryState(activity,NeobotSeedData.PROJECT_ID);
        String[] p=saved==null?new String[0]:saved.split("\\|",-1);flow=Phase11OledBatteryFlow.restore(p.length>0?p[0]:null,createdAt);
        if(p.length==5)try{for(int i=0;i<2;i++){watched[i]=Boolean.parseBoolean(p[1+i*2]);positions[i]=Math.max(0,Long.parseLong(p[2+i*2]));}}catch(RuntimeException invalid){Arrays.fill(watched,false);Arrays.fill(positions,0);}
        paused=!activity.isActivityResumed();if(paused)flow.pause();
        narrator=new DemoClipNarrator(NARRATION,new DemoClipNarrator.Voice(){public void speak(String text,String key){say(text,key);}public void stop(){activity.voiceGuide().stop();}});bind();
    }
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    private void say(String text,String key){if(!closed&&!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase11:"+key);}
    private void status(String text){message=text;if(spoken.add(text))say(text,"status:"+(++sequence));}
    private String instruction(){switch(flow.screen()){
        case OLED_SCAN:return "Step 11.1: Scan OLED Display.\n• Place the front of the 0.96 inch I2C OLED flat in frame.";
        case BATTERY_SCAN:return "Step 11.2A: Scan Battery Pack.\n• Place your compatible battery pack in frame.\n• Keep power switched OFF during installation.";
        case BATTERY_HARDWARE_PREP:return "Step 11.2B: Prepare Battery Securing Materials.\n• Have electrical tape or a securing strap ready.\n• Tap I Have These when ready.";
        case OLED_DEMO:return "Step 11.3A: Insert the OLED Display.\n• Align GND, VCC, SCL and SDA with the female socket.\n• Push down gently until all four pins are seated.";
        case OLED_WORK_SCAN:return "Step 11.3B: Verify OLED Placement.\n• Point the camera straight down at the Extension Board.\n• Keep the OLED and surrounding board visible.";
        case OLED_CHECK:return "Step 11.3C: Check OLED Seating.\n• Check all four pins are inside the socket.\n• The display should sit straight without loose tilt.";
        case BATTERY_DEMO:return "Step 11.4A: Place and Connect the Battery.\n1. Place it on the upper Extension Board beside the OLED as shown.\n2. Secure with tape or a strap, clear of moving parts.\n3. Insert the power plug with the switch OFF.";
        case BATTERY_CHECK:return "Step 11.4B: Check Battery and Power.\n• Confirm the pack cannot slide into nearby components.\n• Check the power connector is fully inserted.";
        default:return "Assembly complete.\n• Your required scans and physical checks are complete.\n• Save completion to open the power-switch guide and learning hub.";
    }}
    private String spokenInstruction(){switch(flow.screen()){
        case OLED_SCAN:return "Step 11 point 1. Scan the front face of your OLED display module.";
        case BATTERY_SCAN:return "Step 11 point 2. Scan your battery pack. Keep the power switch off during installation.";
        case BATTERY_HARDWARE_PREP:return "Have electrical tape or a securing strap ready, then tap I Have These.";
        case OLED_DEMO:case BATTERY_DEMO:return NARRATION[videoIndex()];
        case OLED_WORK_SCAN:return "Point your camera straight down to scan your OLED display and the surrounding extension board.";
        case OLED_CHECK:return "Check that the OLED display is firmly seated without loose tilt and all four pins are inside the socket.";
        case BATTERY_CHECK:return "Confirm that the battery is secured in place and its power plug is fully connected.";
        default:return "All assembly checks are complete. Saving your progress.";
    }}
    private void bind(){
        card.previous.setOnClickListener(v->{if(saving)return;if(flow.screen()==Screen.OLED_SCAN)previousPhase.run();else if(flow.previous(now())){if(flow.screen().video())positions[videoIndex()]=0;changed();}});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.screen()==Screen.CELEBRATION){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.next(now()))changed();});
        card.action.setOnClickListener(v->{if(saving)return;Screen s=flow.screen();if(s.preparation())hardware();else if(s.check())check();else if(s.video()){
            if(videoError){stopVideo();attached=null;videoError=false;render();}else if(watched[videoIndex()]||developer&&flow.complete()){flow.confirm();flow.next(now());changed();}
        }});
        card.rescan.setOnClickListener(v->rescan());
        card.setOnDetailsChanged(text->{if(video!=null){video.pause();narrator.stop();}say(text.isEmpty()?"Additional information hidden.":text,"details:"+(++sequence));});
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?9:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);message="";warning=false;spoken.clear();dismiss();stopVideo();attached=null;videoError=false;
        activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.clearAssemblyTargets();hud.setBoardCorners(null);
        render();save();if(!flow.screen().video())say(spokenInstruction(),"step:"+flow.tag());
        if(!paused&&(!reviewing||reviewScan)&&!flow.complete()&&(flow.screen().preparation()||flow.screen().check()))main.post(flow.screen().check()?this::check:this::hardware);
    }
    boolean scanning(){return plan!=null;}
    RecognitionRequest request(CameraCalibration calibration){Plan p=plan;if(p==null)return null;
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(Phase11AssetCatalog.target(p.stage)),calibration,null,null,1,11).withPhase11Stage(p.stage).withRequestTag(p.tag);}
    boolean current(RecognitionResult r){return !closed&&r.getFrameTimestampNanos()>=createdAt&&flow.tag().equals(r.getRequestTag());}
    void onResult(RecognitionResult r){
        if(paused||saving||modal||!current(r)||!flow.accepts(r.getRequestTag(),r.getFrameTimestampNanos()))return;
        Phase11Observation o=r.getPhase11Observation();if(o==null)return;flow.observe(o.accepted());warning=o.warning();
        if(flow.complete())status("Scan successful · Next step unlocked");
        else if(o.accepted())status("Component verified · Hold briefly to confirm");
        else if(warning)status(flow.screen()==Screen.OLED_SCAN?"The OLED is upside down. Please show its front display face.":"OLED placement is misaligned. Check the four pins and re-seat it straight.");
        else if(o.state==Phase11Observation.State.BOARD_LOCATED)status("Board located · Keep the OLED display visible and clear of glare");
        else status(flow.screen().scanStage()<=2?"Show the complete component inside the recommended frame":"Keep the OLED and surrounding Extension Board visible");
        ((ArHudView)activity.findViewById(R.id.arHudView)).update(flow.complete()?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.TRACKING,"Phase 11",activity.normalizeCorners(r.getProjectedCorners()));
        render();save();
    }
    private void hardware(){
        if(closed||paused||saving||modal||reviewing||!flow.screen().preparation())return;modal=true;render();
        String text="Prepare electrical tape or a securing strap to hold the battery on the upper Extension Board beside the OLED, as shown in the demo. Keep the pack clear of moving parts and keep the power switch OFF while connecting.";
        dialog=new AlertDialog.Builder(activity).setTitle("Battery securing materials").setView(PhysicalCheckUi.prompt(activity,text)).setNegativeButton("Inspect first",null).setPositiveButton("I Have These",(d,w)->{flow.confirm();status("Materials ready · Next step unlocked");render();save();}).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;PhysicalCheckUi.style(dialog);say(text,"hardware:"+(++sequence));});dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused)render();});dialog.show();
    }
    private void check(){
        if(closed||paused||saving||modal||reviewing&&!reviewScan||!flow.screen().check()||flow.complete())return;modal=true;render();boolean oled=flow.screen()==Screen.OLED_CHECK;
        String question=oled?"Is the OLED display firmly seated in the female header without loose tilt, with all four pins in the socket?":"Is the battery firmly secured on the upper Extension Board beside the OLED and the power plug securely connected?";
        String correction=oled?"Gently press down on the OLED header pins until fully seated. Check no pin is outside the socket.":"Secure the battery with tape or a strap and fully insert the power plug into the mainboard socket. Keep the switch OFF while adjusting.";
        TextView prompt=PhysicalCheckUi.prompt(activity,question);
        dialog=new AlertDialog.Builder(activity).setTitle(oled?"OLED seating check":"Battery and power check").setView(prompt).setNeutralButton("Check later",null).setNegativeButton(oled?"Loose":"Check Power",null).setPositiveButton(oled?"Firmly Seated":"Secured & Connected",null).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;PhysicalCheckUi.style(dialog);say(question,"question:"+(++sequence));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{flow.answerSecure(false);prompt.setText(correction+"\n\n"+question);say(correction,"correction:"+(++sequence));save();});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{flow.answerSecure(true);dialog.dismiss();save();status("Check complete · Next step unlocked");render();
                if(!oled&&flow.next(now())){changed();if(flow.canFinish(developer))finish.run();}
            });
        });dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused)render();});dialog.show();
    }
    private void rescan(){if(closed||paused||saving||!flow.canRescan())return;dismiss();flow.pause();modal=true;render();dialog=RescanConfirmationDialog.show(activity,()->{reviewScan=reviewing;flow.rescan(now());flow.resume(now());changed();});dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused){flow.resume(now());render();}});}
    private int videoIndex(){return flow.screen().videoIndex();}
    private void startVideo(){
        final Screen screen=flow.screen();final int index=videoIndex();List<String> clips=Phase11AssetCatalog.videos(activity.getAssets(),index);
        if(clips.size()!=1){videoError=true;status("The required Phase 11 demo is missing. Check its video folder.");main.post(this::render);return;}
        video=new Phase2VideoPlayer(activity,clips,new Phase2VideoPlayer.Listener(){
            public void onClip(int n){if(!closed&&flow.screen()==screen)save();}
            public void onPlayback(int n,boolean playing,boolean seek){if(!closed&&flow.screen()==screen)narrator.onPlayback(index,playing&&!paused,seek);}
            public void onClipEnded(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(video!=null&&(!narrator.isPending()||s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))video.continueAfterNarration();}
            public void onFinished(){if(closed||flow.screen()!=screen)return;narrator.stop();watched[index]=true;status("Demo complete · "+demoAction()+" is available");render();save();}
            public void onError(String text){narrator.stop();videoError=true;status(text);render();}
        },path->index);
        preview.showContent(video.view(),screen.label()+" · Tap to pause · Drag to scrub");video.seek(0,positions[index]);if(!paused)video.play();
    }
    private void stopVideo(){if(video!=null){int index=attached==null?-1:attached.videoIndex();if(index<0)throw new IllegalStateException("Video outside demo screen");positions[index]=video.position();narrator.stop();video.close();video=null;}}
    private String demoAction(){return flow.screen()==Screen.OLED_DEMO?"Ready to Scan":"Confirm Battery Seated";}
    void render(){
        if(closed)return;Screen s=flow.screen();plan=!paused&&!saving&&!modal&&(!reviewing||reviewScan)&&flow.scanning()?new Plan(s.scanStage(),flow.tag()):null;
        boolean unlocked=flow.complete()&&(s!=Screen.CELEBRATION||reviewing||flow.canFinish(developer));
        String details="The OLED uses four I2C connections: GND, VCC, SCL and SDA. Match their printed order to the socket and never force bent pins. Mount the battery on the upper Extension Board beside the OLED, following the supplied demo. Secure it so it cannot move into components or wheels. Keep power off while adjusting. A photo verifies visible placement; your physical checks confirm seating, fastening and the power connection.";
        card.render(1100+flow.step(),"Phase 11 · "+s.label()+" · "+flow.step()+"/9",instruction(),details,message,unlocked,true,flow.canRescan());
        card.next.setText(s==Screen.CELEBRATION?reviewing?"Return to current phase":developer?"Preview completion →":"Finish Assembly →":"Next Step →");card.next.setEnabled(unlocked&&!saving);card.next.setVisibility(s.video()&&!reviewing&&!(developer&&flow.complete())?View.GONE:View.VISIBLE);
        boolean actionable=s.preparation()||s.check()||s.video();card.action.setVisibility(actionable&&(!reviewing||reviewScan)&&(!flow.complete()||s.video())?View.VISIBLE:View.GONE);
        card.action.setText(s.video()?videoError?"Retry demo":demoAction():s.preparation()?"I Have These":"Complete physical check");card.action.setEnabled(!saving&&(!s.video()||watched[videoIndex()]||developer&&flow.complete()||videoError));card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        alignment.setInstruction(s.label());alignment.showGuide(plan==null?null:Phase11AssetCatalog.guide(activity.getAssets(),s.scanStage()));
        ((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(plan!=null);activity.hideGuidance();
        if(attached!=s){stopVideo();attached=s;if(s.video())startVideo();else{String id=s==Screen.BATTERY_SCAN||s==Screen.BATTERY_HARDWARE_PREP||s==Screen.BATTERY_CHECK||s==Screen.CELEBRATION?NeobotSeedData.BATTERY_PACK:NeobotSeedData.OLED_DISPLAY;preview.showComponent(id,AssemblyPhaseCatalog.componentName(id));}}
        preview.onHudState(flow.complete()?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.SEARCHING);
        activity.setBanner(saving?"Saving assembly completion…":flow.complete()?"Step complete · Next step unlocked":message.isEmpty()?s.label():message,plan!=null,warning?R.drawable.bg_camera_warning:flow.complete()?R.drawable.bg_success:R.drawable.bg_live_pill);

        preview.setSkipPreviewAction(AssemblyStepNavigationManager.canSkipPreview(flow.screen().video(),paused,saving)?this::skipPreview:null);
    }
    String checkpointState(){if(video!=null)positions[videoIndex()]=video.position();StringBuilder state=new StringBuilder(flow.snapshot());for(int i=0;i<2;i++)state.append('|').append(watched[i]).append('|').append(positions[i]);return state.toString();}
    private void save(){String state=checkpointState();if(!state.equals(savedState)){savedState=state;if(!developer&&!reviewing)PhaseProgressStore.saveOledBatteryState(activity,NeobotSeedData.PROJECT_ID,state);checkpointChanged.run();}}
    void onSpeechFinished(String key){if(closed||paused)return;if(video!=null&&narrator.onSpeechFinished(key.startsWith("phase11:")?key.substring(8):key))video.continueAfterNarration();}
    void onVoiceStatusChanged(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(video!=null){if(s==AssemblyVoiceGuide.Status.AVAILABLE)narrator.onVoiceAvailable();else if(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE)video.continueAfterNarration();}}
    void setSaving(boolean value){boolean failed=saving&&!value;saving=value;if(failed)status("Progress could not be saved. Choose Continue to retry.");render();}
    private void dismiss(){if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}modal=false;}
    void pause(){paused=true;main.removeCallbacksAndMessages(null);flow.pause();plan=null;save();dismiss();if(video!=null)video.pause();narrator.stop();activity.voiceGuide().stop();card.setOnDetailsChanged(null);}
    void resume(){paused=false;flow.resume(now());bind();changed();}
    void prepareForReview(){pause();stopVideo();attached=null;}
    void devJump(int step){developer=true;flow.devJump(step,now());changed();}
    void devSkip(){developer=true;flow.devSkip();render();save();}
    void devComplete(){developer=true;flow.devComplete();changed();}
    void restart(){Arrays.fill(watched,false);Arrays.fill(positions,0);flow.restart(now());changed();}
    @Override public void close(){if(closed)return;pause();closed=true;stopVideo();alignment.showGuide(null);((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(false);}

    private void skipPreview(){if(closed||!AssemblyStepNavigationManager.canSkipPreview(flow.screen().video(),paused,saving))return;watched[videoIndex()]=true;flow.confirm();if(flow.next(now()))changed();}
}
