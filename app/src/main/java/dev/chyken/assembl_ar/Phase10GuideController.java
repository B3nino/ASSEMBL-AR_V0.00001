package dev.chyken.assembl_ar;

import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.*;
import dev.chyken.assembl_ar.camera.Phase10AssetCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import dev.chyken.assembl_ar.workflow.phase.Phase10ExtensionBoardFlow.Screen;

/** Main-thread view coordination; vision, asset preparation and TTS retain their workers. */
final class Phase10GuideController implements AutoCloseable {
    private final AssemblyActivity activity;private final CompactGuideCard card;private final ComponentPreviewWindow preview;private final TargetAlignmentBoxView alignment;
    private final Runnable finish,previousPhase,checkpointChanged;private final Handler main=new Handler(Looper.getMainLooper());
    final Phase10ExtensionBoardFlow flow;private final DemoClipNarrator narrator;private Phase2VideoPlayer video;private AlertDialog dialog;
    private volatile Plan plan;private Screen attached;private final long createdAt=SystemClock.elapsedRealtimeNanos();
    private final boolean[] watched=new boolean[4];private final long[] positions=new long[4];
    private boolean developer,reviewing,reviewScan,paused,closed,saving,modal,videoError,finishing,warning;
    private String message="",savedState="",completionKey;private long sequence;private final Set<String> spoken=new HashSet<>();
    private static final class Plan{final int stage;final PhaseFlow.ScanTag tag;Plan(int stage,PhaseFlow.ScanTag tag){this.stage=stage;this.tag=tag;}}
    private static final String[] NARRATION={
        "Watch the video to plug one end of the data extension cable into the mainboard. Leave the other end free for later.",
        "Watch the demo to mount the OLED protector onto the extension board using two 20 millimeter screws and M3 nuts.",
        "Watch the video to align the extension board with the mainboard brackets and tighten the standoff screw saved from Phase 9. Keep wires clear of the supports.",
        "Watch the video to plug the remaining cable end into the top extension board in the orientation shown."};
    Phase10GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,String saved,Runnable changed){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;checkpointChanged=changed;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);alignment=activity.findViewById(R.id.targetAlignmentBox);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.extensionBoardState(activity,NeobotSeedData.PROJECT_ID);
        String[] p=saved==null?new String[0]:saved.split("\\|",-1);flow=Phase10ExtensionBoardFlow.restore(p.length>0?p[0]:null,createdAt);
        if(p.length==9)try{for(int i=0;i<4;i++){watched[i]=Boolean.parseBoolean(p[1+i*2]);positions[i]=Math.max(0,Long.parseLong(p[2+i*2]));}}catch(RuntimeException invalid){Arrays.fill(watched,false);Arrays.fill(positions,0);}
        paused=!activity.isActivityResumed();if(paused)flow.pause();
        narrator=new DemoClipNarrator(NARRATION,new DemoClipNarrator.Voice(){public void speak(String text,String key){say(text,key);}public void stop(){activity.voiceGuide().stop();}});bind();
    }
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    private void say(String text,String key){if(!closed&&!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase10:"+key);}
    private void status(String text){message=text;if(spoken.add(text))say(text,"status:"+(++sequence));}
    private String instruction(){switch(flow.screen()){
        case CABLE_SCAN:return "Step 10.1A: Scan Data Cable.\n• Place the Data Extension Cable flat in camera frame.";
        case EXTENSION_SCAN:return "Step 10.1B: Scan Extension Board.\n• Show the front face of the Extension Board.\n• Locate the standoff screw saved from Phase 9.";
        case STANDOFF_SCREW_PREP:return "Step 10.1B: Prepare the saved screw.\n• Bring out the top screw removed from the Phase 9 standoff.\n• Tap I Have This when ready.";
        case PROTECTOR_SCAN:return "Step 10.1C: Scan OLED Protector.\n• Show the front face of the OLED Protector flat inside the frame.";
        case PROTECTOR_HARDWARE_PREP:return "Step 10.1C: Prepare protector hardware.\n• Gather 2 × 20mm screws and 2 × M3 nuts.\n• Inspect the models and tap I Have These.";
        case CABLE_MAIN_DEMO:return "Step 10.2A: Connect the mainboard cable.\n• Plug the Data Extension Cable into the Mainboard header as shown.\n• Leave the other end free for later.";
        case CABLE_MAIN_CHECK:return "Step 10.2B: Check the mainboard connection.\n• Confirm the connector is fully and evenly seated.\n• Keep the cable free of twists.";
        case PROTECTOR_DEMO:return "Step 10.3A: Mount the OLED Protector.\n1. Lay the Extension Board flat.\n2. Align the protector and both mounting holes.\n3. Insert 2 × 20mm screws and lock with M3 nuts underneath.";
        case PROTECTOR_WORK_SCAN:return "Step 10.3B: Verify OLED Protector Assembly.\n• Point camera straight down at the Extension Board.\n• Keep the protector and board visible within the recommended frame.";
        case PROTECTOR_CHECK:return "Step 10.3C: Check protector stability.\n• Check both screws and nuts.\n• Gently test that the OLED protector does not jiggle.";
        case BOARD_DEMO:return "Step 10.4A: Mount the Extension Board.\n• Align the bracket supports and center standoff hole.\n• Route wiring between the boards without pinching.\n• Tighten the screw saved from Phase 9.";
        case BOARD_WORK_SCAN:return "Step 10.4B: Verify Stacked Assembly.\n• Point camera straight down at the stacked assembly.\n• Keep the full Extension Board and both chassis wheels in frame.";
        case BOARD_CHECK:return "Step 10.4C: Check the top board.\n• Confirm the Extension Board is firm, level and supported.\n• No wire should be trapped under a support.";
        case CABLE_TOP_DEMO:return "Step 10.5A: Connect the final cable end.\n• Plug the free end into the top Extension Board header in the orientation shown.";
        default:return "Step 10.5B: Final Cable Check.\n• Confirm the top board connector is fully seated.\n• Check that the cable is not twisted or pinched.";
    }}
    private String spokenInstruction(){switch(flow.screen()){
        case CABLE_SCAN:return "Step 10 point 1 A. Scan your data extension cable.";
        case EXTENSION_SCAN:return "Step 10 point 1 B. Scan the front face of your extension board, and locate the standoff screw saved from Phase 9.";
        case STANDOFF_SCREW_PREP:return "Prepare the saved standoff screw. Tap I Have This once it is ready.";
        case PROTECTOR_SCAN:return "Step 10 point 1 C. Scan the front face of your OLED protector.";
        case PROTECTOR_HARDWARE_PREP:return "Gather two 20 millimeter screws and two M3 nuts. Tap I Have These when ready.";
        case CABLE_MAIN_DEMO:case PROTECTOR_DEMO:case BOARD_DEMO:case CABLE_TOP_DEMO:return NARRATION[videoIndex()];
        case CABLE_MAIN_CHECK:return "Check that the data extension cable is firmly plugged into the mainboard.";
        case PROTECTOR_WORK_SCAN:return "Point your camera straight down to verify the OLED protector assembly.";
        case PROTECTOR_CHECK:return "Check that both 20 millimeter screws and M3 nuts are tight and the protector does not jiggle.";
        case BOARD_WORK_SCAN:return "Point your camera straight down to scan the installed extension board. Keep the complete chassis in frame.";
        case BOARD_CHECK:return "Check that the extension board is level and secure on its supports, with no pinched wires.";
        default:return "Check that the extension data cable is firmly plugged into the top board header.";
    }}
    private void bind(){
        card.previous.setOnClickListener(v->{if(saving)return;if(flow.screen()==Screen.CABLE_SCAN)previousPhase.run();else if(flow.previous(now())){if(flow.screen().video())positions[videoIndex()]=0;changed();}});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.screen()==Screen.CABLE_TOP_CHECK){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.next(now()))changed();});
        card.action.setOnClickListener(v->{if(saving)return;Screen s=flow.screen();if(s.preparation())hardware();else if(s.check())check();else if(s.video()){
            if(videoError){stopVideo();attached=null;videoError=false;render();}else if(watched[videoIndex()]||developer&&flow.complete()){flow.confirm();flow.next(now());changed();}
        }});
        card.rescan.setOnClickListener(v->rescan());
        card.setOnDetailsChanged(text->{if(video!=null){video.pause();narrator.stop();}say(text.isEmpty()?"Additional information hidden.":text,"details:"+(++sequence));});
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?15:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);finishing=false;message="";warning=false;spoken.clear();dismiss();stopVideo();attached=null;videoError=false;
        activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.clearAssemblyTargets();hud.setBoardCorners(null);
        render();save();if(!flow.screen().video())say(spokenInstruction(),"step:"+flow.tag());
        if(!paused&&(!reviewing||reviewScan)&&!flow.complete()&&(flow.screen().preparation()||flow.screen().check()))main.post(flow.screen().check()?this::check:this::hardware);
    }
    boolean scanning(){return plan!=null;}
    RecognitionRequest request(CameraCalibration calibration){Plan p=plan;if(p==null)return null;
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(Phase10AssetCatalog.target(p.stage)),calibration,null,null,1,10).withPhase10Stage(p.stage).withRequestTag(p.tag);}
    boolean current(RecognitionResult r){return !closed&&r.getFrameTimestampNanos()>=createdAt&&flow.tag().equals(r.getRequestTag());}
    void onResult(RecognitionResult r){
        if(paused||saving||modal||!current(r)||!flow.accepts(r.getRequestTag(),r.getFrameTimestampNanos()))return;
        Phase10Observation o=r.getPhase10Observation();if(o==null)return;flow.observe(o.accepted());warning=o.flipped();
        if(flow.complete())status("Scan successful · Next step unlocked");
        else if(o.accepted())status("Front face verified · Hold briefly to confirm");
        else if(warning)status(flow.screen()==Screen.PROTECTOR_SCAN?"OLED protector orientation invalid. Please flip it over to show its front face.":"Extension board orientation invalid. Please flip it over to show its front face.");
        else status(flow.screen().scanStage()<=3?"Show the complete front face inside the recommended frame":"Keep the complete assembly visible · Match the recommended scanning frame");
        ((ArHudView)activity.findViewById(R.id.arHudView)).update(flow.complete()?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.TRACKING,"Phase 10",activity.normalizeCorners(r.getProjectedCorners()));
        render();save();
    }
    private void hardware(){
        if(closed||paused||saving||modal||reviewing||!flow.screen().preparation())return;modal=true;render();boolean screw=flow.screen()==Screen.STANDOFF_SCREW_PREP;
        String text=screw?"Locate the top screw removed from the center standoff in Phase 9. Keep it safe until the extension board mounting demo.":"Prepare two 20mm screws and two M3 nuts for the OLED protector. Inspect the rotatable models before continuing.";
        dialog=new AlertDialog.Builder(activity).setTitle(screw?"Saved standoff screw":"OLED protector hardware").setView(PhysicalCheckUi.prompt(activity,text)).setNegativeButton("Inspect first",null).setPositiveButton(screw?"I Have This":"I Have These",(d,w)->{flow.confirm();status("Hardware ready · Next step unlocked");render();save();}).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;PhysicalCheckUi.style(dialog);say(text,"hardware:"+(++sequence));});dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused)render();});dialog.show();
    }
    private void check(){
        if(closed||paused||saving||modal||reviewing&&!reviewScan||!flow.screen().check()||flow.complete())return;modal=true;render();Screen s=flow.screen();
        String question=s==Screen.CABLE_MAIN_CHECK?"Is the data extension cable firmly plugged into the mainboard socket without pulling out easily?":s==Screen.PROTECTOR_CHECK?"Are both 20mm screws locked firmly with M3 nuts and the OLED protector does not jiggle?":s==Screen.BOARD_CHECK?"Is the top Extension Board firm, level and not wobbling on its standoff, with no pinched wires?":"Is the extension data cable firmly plugged into the top board header?";
        String correction=s==Screen.PROTECTOR_CHECK?"Tighten both 20 millimeter screws and M3 nuts until the protector is secure.":s==Screen.BOARD_CHECK?"Please tighten the center standoff screw with your screwdriver. Make sure all bracket supports are level and the wires are clear.":"Push the cable connector firmly and evenly into the header socket in the orientation shown.";
        TextView prompt=PhysicalCheckUi.prompt(activity,question);
        dialog=new AlertDialog.Builder(activity).setTitle(s==Screen.BOARD_CHECK?"Top board stability":s==Screen.PROTECTOR_CHECK?"Protector stability":"Cable connection check").setView(prompt).setNeutralButton("Check later",null).setNegativeButton(s==Screen.BOARD_CHECK?"Wobbling":"Loose",null).setPositiveButton(s==Screen.BOARD_CHECK?"Firm & Level":s==Screen.PROTECTOR_CHECK?"Firm & Secure":"Firmly Plugged",null).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;PhysicalCheckUi.style(dialog);say(question,"question:"+(++sequence));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{flow.answerSecure(false);prompt.setText(correction+"\n\n"+question);say(correction,"correction:"+(++sequence));save();});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{flow.answerSecure(true);dialog.dismiss();save();status("Connection secure · Next step unlocked");render();
                if(flow.canFinish(developer)){finishing=true;completionKey="complete:"+(++sequence);say("Phase 10 complete! Extension circuit board placed and wired successfully.",completionKey);AssemblyVoiceGuide.Status voice=activity.voiceGuide().getStatus();if(voice==AssemblyVoiceGuide.Status.MUTED||voice==AssemblyVoiceGuide.Status.UNAVAILABLE)advance();else main.postDelayed(this::advance,16000);}
            });
        });dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused)render();});dialog.show();
    }
    private void rescan(){if(closed||paused||saving||!flow.canRescan())return;dismiss();flow.pause();modal=true;render();dialog=RescanConfirmationDialog.show(activity,()->{reviewScan=reviewing;flow.rescan(now());flow.resume(now());changed();});dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused){flow.resume(now());render();}});}
    private int videoIndex(){return flow.screen().videoIndex();}
    private void startVideo(){
        final Screen screen=flow.screen();final int index=videoIndex();List<String> clips=Phase10AssetCatalog.videos(activity.getAssets(),index);
        if(clips.size()!=1){videoError=true;status("The required Phase 10 demo is missing. Check its video folder.");main.post(this::render);return;}
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
    private String demoAction(){return flow.screen()==Screen.CABLE_MAIN_DEMO||flow.screen()==Screen.CABLE_TOP_DEMO?"Check Connection":"Ready to Scan";}
    void render(){
        if(closed)return;Screen s=flow.screen();plan=!paused&&!saving&&!modal&&(!reviewing||reviewScan)&&flow.scanning()?new Plan(s.scanStage(),flow.tag()):null;
        boolean unlocked=flow.complete()&&(s!=Screen.CABLE_TOP_CHECK||reviewing||flow.canFinish(developer));
        String details="Keep power disconnected. Identify the component front faces before installation. The red edge stripe and connector shape help orient the data cable; never force a reversed plug. Use two 20mm screws and two M3 nuts for the protector. Reuse the standoff screw saved in Phase 9. Route wires between the boards without pinching. Photos verify visible placement; physical checks confirm tight fasteners and secure connectors.";
        card.render(1000+flow.step(),"Phase 10 · "+s.label()+" · "+flow.step()+"/15",instruction(),details,message,unlocked,true,flow.canRescan());
        card.next.setText(s==Screen.CABLE_TOP_CHECK?reviewing?"Return to current phase":"Continue to Phase 11 →":"Next Step →");card.next.setEnabled(unlocked&&!saving);card.next.setVisibility(s.video()&&!reviewing&&!(developer&&flow.complete())?View.GONE:View.VISIBLE);
        boolean actionable=s.preparation()||s.check()||s.video();card.action.setVisibility(actionable&&(!reviewing||reviewScan)&&(!flow.complete()||s.video())?View.VISIBLE:View.GONE);
        card.action.setText(s.video()?videoError?"Retry demo":demoAction():s.preparation()?s==Screen.STANDOFF_SCREW_PREP?"I Have This":"I Have These":"Complete physical check");card.action.setEnabled(!saving&&(!s.video()||watched[videoIndex()]||developer&&flow.complete()||videoError));card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        alignment.setInstruction(s.label());alignment.showGuide(plan==null?null:Phase10AssetCatalog.guide(activity.getAssets(),s.scanStage()));
        ((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(plan!=null);activity.hideGuidance();
        if(attached!=s){stopVideo();attached=s;if(s.video())startVideo();
            else if(s==Screen.STANDOFF_SCREW_PREP)preview.showModelChoices(new String[]{NeobotSeedData.MOUNTING_SCREW,NeobotSeedData.STANDOFF},new String[]{"Saved standoff screw","Center standoff · Already installed"},0,text->say(text,"model:"+(++sequence)));
            else if(s==Screen.PROTECTOR_HARDWARE_PREP)preview.showModelChoices(new String[]{dev.chyken.assembl_ar.render.ComponentModelCatalog.CASTER_FASTENERS,NeobotSeedData.OLED_PROTECTOR},new String[]{"2 × 20mm screws + 2 × M3 nuts","OLED protector"},0,text->say(text,"model:"+(++sequence)));
            else{String id=s==Screen.CABLE_SCAN||s==Screen.CABLE_MAIN_CHECK||s==Screen.CABLE_TOP_CHECK?NeobotSeedData.DATA_CABLE:s==Screen.PROTECTOR_SCAN||s==Screen.PROTECTOR_CHECK?NeobotSeedData.OLED_PROTECTOR:NeobotSeedData.EXTENSION_BOARD;preview.showComponent(id,AssemblyPhaseCatalog.componentName(id));}}
        preview.onHudState(flow.complete()?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.SEARCHING);
        activity.setBanner(flow.complete()?"Step complete · Next step unlocked":message.isEmpty()?s.label():message,plan!=null,warning?R.drawable.bg_camera_warning:flow.complete()?R.drawable.bg_success:R.drawable.bg_live_pill);

        preview.setSkipPreviewAction(AssemblyStepNavigationManager.canSkipPreview(flow.screen().video(),paused,saving)?this::skipPreview:null);
    }
    String checkpointState(){if(video!=null)positions[videoIndex()]=video.position();StringBuilder state=new StringBuilder(flow.snapshot());for(int i=0;i<4;i++)state.append('|').append(watched[i]).append('|').append(positions[i]);return state.toString();}
    private void save(){String state=checkpointState();if(!state.equals(savedState)){savedState=state;if(!developer&&!reviewing)PhaseProgressStore.saveExtensionBoardState(activity,NeobotSeedData.PROJECT_ID,state);checkpointChanged.run();}}
    private void advance(){if(!closed&&!paused&&!saving&&finishing&&flow.canFinish(developer)){finishing=false;main.removeCallbacksAndMessages(null);finish.run();}}
    void onSpeechFinished(String key){if(closed||paused)return;if(finishing&&("phase10:"+completionKey).equals(key)){advance();return;}if(video!=null&&narrator.onSpeechFinished(key.startsWith("phase10:")?key.substring(8):key))video.continueAfterNarration();}
    void onVoiceStatusChanged(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(finishing&&(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))advance();else if(video!=null){if(s==AssemblyVoiceGuide.Status.AVAILABLE)narrator.onVoiceAvailable();else if(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE)video.continueAfterNarration();}}
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
