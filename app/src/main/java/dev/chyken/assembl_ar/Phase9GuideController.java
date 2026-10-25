package dev.chyken.assembl_ar;

import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.*;
import dev.chyken.assembl_ar.camera.Phase9AssetCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import dev.chyken.assembl_ar.workflow.phase.Phase9StandoffWheelsFlow.Screen;

/** Main-thread view coordination; vision, asset preparation and TTS retain their workers. */
final class Phase9GuideController implements AutoCloseable {
    private final AssemblyActivity activity;private final CompactGuideCard card;private final ComponentPreviewWindow preview;private final TargetAlignmentBoxView alignment;
    private final Runnable finish,previousPhase,checkpointChanged;private final Handler main=new Handler(Looper.getMainLooper());
    final Phase9StandoffWheelsFlow flow;private final DemoClipNarrator narrator;private Phase2VideoPlayer video;private AlertDialog dialog;
    private volatile Plan plan;private Screen attached;private final long createdAt=SystemClock.elapsedRealtimeNanos();
    private final boolean[] watched=new boolean[2];private final long[] positions=new long[2];
    private boolean developer,reviewing,reviewScan,paused,closed,saving,modal,videoError,finishing,warning;
    private String message="",savedState="",completionKey;private long sequence;private final Set<String> spoken=new HashSet<>();
    private static final class Plan{final int stage;final PhaseFlow.ScanTag tag;Plan(int stage,PhaseFlow.ScanTag tag){this.stage=stage;this.tag=tag;}}
    private static final String[] NARRATION={
        "Watch the video to install the standoff in the center hole and lock it underneath with an M3 nut. Remove the top screw and set it aside for the extension board.",
        "Watch the demo to align the flat edge of each motor shaft with the wheel hub, then push both wheels firmly onto their axles."};
    Phase9GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,String saved,Runnable changed){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;checkpointChanged=changed;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);alignment=activity.findViewById(R.id.targetAlignmentBox);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.standoffWheelsState(activity,NeobotSeedData.PROJECT_ID);
        String[] p=saved==null?new String[0]:saved.split("\\|",-1);flow=Phase9StandoffWheelsFlow.restore(p.length>0?p[0]:null,createdAt);
        if(p.length==5)try{watched[0]=Boolean.parseBoolean(p[1]);positions[0]=Math.max(0,Long.parseLong(p[2]));watched[1]=Boolean.parseBoolean(p[3]);positions[1]=Math.max(0,Long.parseLong(p[4]));}catch(RuntimeException invalid){Arrays.fill(watched,false);Arrays.fill(positions,0);}
        paused=!activity.isActivityResumed();if(paused)flow.pause();
        narrator=new DemoClipNarrator(NARRATION,new DemoClipNarrator.Voice(){public void speak(String text,String key){say(text,key);}public void stop(){activity.voiceGuide().stop();}});bind();
    }
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    private void say(String text,String key){if(!closed&&!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase9:"+key);}
    private void status(String text){message=text;if(spoken.add(text))say(text,"status:"+(++sequence));}
    private String instruction(){switch(flow.screen()){
        case HARDWARE_PREP:return "Step 9.1: Gather hardware.\n• Prepare 1 metal standoff, 1 M3 nut and a screwdriver.\n• Inspect the rotatable models, then tap I Have These.\n• No standalone standoff scan is needed.";
        case WHEELS_SCAN:return "Step 9.2a: Scan Robot Wheels.\n• Place BOTH wheels flat in frame.\n• Either front or back hub face is accepted.";
        case WHEELS_READY:return "Step 9.2b: Confirm your wheels.\n• Keep both robot wheels ready.\n• Tap I Have These to continue.";
        case STANDOFF_DEMO:return "Step 9.3A: Install the center standoff.\n1. Insert it into the center PCB hole.\n2. Lock underneath with an M3 nut.\n3. REMOVE the top screw and set it aside for the extension board.";
        case STANDOFF_WORK_SCAN:return "Step 9.3B: Verify Standoff Placement.\n• Point the camera straight down.\n• Keep the full main board and center standoff visible.\n• Wheels are checked after their own demo.";
        case STANDOFF_CHECK:return "Step 9.3C: Check the standoff.\n• Confirm the lower M3 nut is tight.\n• Remove and save the top screw for the extension board.";
        case WHEELS_DEMO:return "Step 9.4A: Install both wheels.\n• Align each TT motor D-shaft flat edge with its wheel hub.\n• Press both hubs firmly onto the axles.";
        case WHEELS_WORK_SCAN:return "Step 9.4B: Verify the complete chassis.\n• Point the camera straight down.\n• BOTH wheels and the center standoff must be visible.\n• Match the recommended scanning frame.";
        default:return "Step 9.4C: Dual-Wheel Stability Check.\n• Gently turn and tug both wheels.\n• Confirm both hubs are fully seated, do not slip and do not rub the board.";
    }}
    private String spokenInstruction(){switch(flow.screen()){
        case HARDWARE_PREP:return "Gather one metal standoff, one M3 nut and your screwdriver. Inspect the models and tap I Have These. The installed standoff is checked after the demo.";
        case WHEELS_SCAN:return "Step 9 point 2. Scan both robot wheels together. Either front or back hub faces are accepted.";
        case WHEELS_READY:return "Both wheels are identified. Confirm that you have both wheels ready.";
        case STANDOFF_DEMO:return NARRATION[0];case WHEELS_DEMO:return NARRATION[1];
        case STANDOFF_WORK_SCAN:return "Point your camera straight down to verify the center standoff installation. Wheels are checked later.";
        case STANDOFF_CHECK:return "Confirm the lower nut is locked tight, then remove the top screw and save it for the extension board.";
        case WHEELS_WORK_SCAN:return "Point your camera straight down to scan both installed wheels and the center standoff.";
        default:return "Check that both wheels are fully pushed onto their motor shafts without slipping or rubbing the board.";
    }}
    private void bind(){
        card.previous.setOnClickListener(v->{if(saving)return;if(flow.screen()==Screen.HARDWARE_PREP)previousPhase.run();else if(flow.previous(now())){if(flow.screen().video())positions[videoIndex()]=0;changed();}});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.screen()==Screen.WHEELS_CHECK){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.next(now()))changed();});
        card.action.setOnClickListener(v->{if(saving)return;Screen s=flow.screen();if(s.preparation())hardware();else if(s.check())check();else if(s.video()){
            if(videoError){stopVideo();attached=null;videoError=false;render();}else if(watched[videoIndex()]){flow.confirm();flow.next(now());changed();}
        }});
        card.rescan.setOnClickListener(v->rescan());
        card.setOnDetailsChanged(text->{if(video!=null){video.pause();narrator.stop();}say(text.isEmpty()?"Additional information hidden.":text,"details:"+(++sequence));});
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?Screen.values().length:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);finishing=false;message="";warning=false;spoken.clear();dismiss();stopVideo();attached=null;videoError=false;
        activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.clearAssemblyTargets();hud.setBoardCorners(null);
        render();save();if(!flow.screen().video())say(spokenInstruction(),"step:"+flow.tag());
        if(!paused&&!reviewing&&!flow.complete()&&(flow.screen().preparation()||flow.screen().check()))main.post(flow.screen().check()?this::check:this::hardware);
    }
    boolean scanning(){return plan!=null;}
    RecognitionRequest request(CameraCalibration calibration){Plan p=plan;if(p==null)return null;String target=p.stage==1?NeobotSeedData.STANDOFF:p.stage==2?NeobotSeedData.WHEEL:NeobotSeedData.MAIN_BOARD;
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(target),calibration,null,null,p.stage==2?2:1,9).withPhase9Stage(p.stage).withRequestTag(p.tag);}
    boolean current(RecognitionResult r){return !closed&&r.getFrameTimestampNanos()>=createdAt&&flow.tag().equals(r.getRequestTag());}
    void onResult(RecognitionResult r){
        if(paused||saving||modal||!current(r)||!flow.accepts(r.getRequestTag(),r.getFrameTimestampNanos()))return;
        Phase9Observation o=r.getPhase9Observation();if(o==null)return;int stage=flow.screen().scanStage();flow.observe(o.accepted(stage));warning=o.invalid;
        if(flow.complete())status("Scan successful · Next step unlocked");
        else if(o.accepted(stage))status("Components verified · Hold briefly to confirm");
        else if(stage==2)status(o.looseWheels+"/2 wheels found · Keep both wheels in frame");
        else if(stage==4&&o.boardContext){
            message="Left wheel: "+(o.leftWheel?"verified":"not yet verified")+" · Right wheel: "+(o.rightWheel?"verified":"not yet verified")+" · Standoff: "+(o.standoff?"verified":"not yet verified");
            if(warning&&spoken.add("assembly-warning"))say("Assembly verification failed. Ensure both left and right wheels are firmly attached to the motor shafts.","wheel-warning");
        }else if(stage==3&&o.boardContext)status("Board found · Keep the center standoff visible");
        else return;
        ((ArHudView)activity.findViewById(R.id.arHudView)).update(flow.complete()?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.TRACKING,"Phase 9",activity.normalizeCorners(r.getProjectedCorners()));
        render();save();
    }
    private void hardware(){
        if(closed||paused||saving||modal||reviewing||!flow.screen().preparation())return;modal=true;render();boolean wheels=flow.screen()==Screen.WHEELS_READY;
        String text=wheels?"Are both robot wheels ready for installation? Either hub face may have been used for identification.":"Prepare one M3 nut and a Phillips screwdriver. Keep the standoff's top screw safe; it will be removed after installation and saved for the extension board.";
        dialog=new AlertDialog.Builder(activity).setTitle(wheels?"Two robot wheels":"Standoff hardware").setView(PhysicalCheckUi.prompt(activity,text)).setNegativeButton("Inspect first",null).setPositiveButton("I Have These",(d,w)->{flow.confirm();status("Hardware ready · Next step unlocked");render();save();}).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;PhysicalCheckUi.style(dialog);say(text,"hardware:"+(++sequence));});dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused)render();});dialog.show();
    }
    private void check(){
        if(closed||paused||saving||modal||reviewing||!flow.screen().check()||flow.complete())return;modal=true;render();boolean wheels=flow.screen()==Screen.WHEELS_CHECK,nut=!wheels&&!flow.nutLocked();
        String question=wheels?"Are both wheels pushed fully onto the motor shafts without slipping?":nut?"Is the standoff locked tightly underneath with an M3 nut?":"Have you REMOVED the top screw from the standoff and set it aside?";
        String correction=wheels?"Press firmly on the center wheel hub until it seats completely on the axle shaft.":nut?"Tighten the lower M3 nut until the center standoff is secure without wobbling.":"Please remove the top screw from the standoff and set it aside. You will need it to fasten the extension board later.";
        TextView prompt=PhysicalCheckUi.prompt(activity,question);
        dialog=new AlertDialog.Builder(activity).setTitle(wheels?"Wheel stability":"Standoff check").setView(prompt).setNeutralButton("Check later",null)
            .setNegativeButton(wheels?"Wheel Loose":nut?"Loose":"Still Attached",null).setPositiveButton(wheels?"Both Wheels Locked":nut?"Locked Tight":"Top Screw Removed",null).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;PhysicalCheckUi.style(dialog);say(question,"question:"+(++sequence));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{if(wheels)flow.answerWheels(false);else if(nut)flow.answerNut(false);else flow.answerTopScrew(false);prompt.setText(correction+"\n\n"+question);say(correction,"correction:"+(++sequence));save();});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(wheels)flow.answerWheels(true);else if(nut)flow.answerNut(true);else flow.answerTopScrew(true);dialog.dismiss();modal=false;save();render();
                if(!wheels&&!flow.complete())main.post(this::check);
                else if(flow.complete()){status(wheels?"Standoff and wheels verified":"Standoff secure · Top screw saved · Next unlocked");render();if(flow.canFinish(developer)){finishing=true;completionKey="complete:"+(++sequence);say("Phase 9 complete! Standoff and wheels installed successfully.",completionKey);AssemblyVoiceGuide.Status voice=activity.voiceGuide().getStatus();if(voice==AssemblyVoiceGuide.Status.MUTED||voice==AssemblyVoiceGuide.Status.UNAVAILABLE)advance();else main.postDelayed(this::advance,16000);}}
            });
        });dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused)render();});dialog.show();
    }
    private void rescan(){if(closed||paused||saving||!flow.canRescan())return;dismiss();flow.pause();modal=true;render();dialog=RescanConfirmationDialog.show(activity,()->{reviewScan=reviewing;flow.rescan(now());flow.resume(now());changed();});dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused){flow.resume(now());render();}});}
    private int videoIndex(){return flow.screen()==Screen.WHEELS_DEMO?1:0;}
    private void startVideo(){
        final Screen screen=flow.screen();final int index=videoIndex();List<String> clips=Phase9AssetCatalog.videos(activity.getAssets(),index==1);
        if(clips.size()!=1){videoError=true;status("The required Phase 9 demo is missing. Check its video folder.");main.post(this::render);return;}
        video=new Phase2VideoPlayer(activity,clips,new Phase2VideoPlayer.Listener(){
            public void onClip(int n){if(!closed&&flow.screen()==screen)save();}
            public void onPlayback(int n,boolean playing,boolean seek){if(!closed&&flow.screen()==screen)narrator.onPlayback(index,playing&&!paused,seek);}
            public void onClipEnded(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(video!=null&&(!narrator.isPending()||s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))video.continueAfterNarration();}
            public void onFinished(){if(closed||flow.screen()!=screen)return;narrator.stop();watched[index]=true;status("Demo complete · Ready to Scan is available");render();save();}
            public void onError(String text){narrator.stop();videoError=true;status(text);render();}
        },path->index);
        preview.showContent(video.view(),index==0?"Standoff installation · Tap to pause · Drag to scrub":"Wheel installation · Tap to pause · Drag to scrub");video.seek(0,positions[index]);if(!paused)video.play();
    }
    private void stopVideo(){if(video!=null){int index=attached==Screen.WHEELS_DEMO?1:0;positions[index]=video.position();narrator.stop();video.close();video=null;}}
    void render(){
        if(closed)return;Screen s=flow.screen();plan=!paused&&!saving&&!modal&&(!reviewing||reviewScan)&&flow.scanning()?new Plan(s.scanStage(),flow.tag()):null;
        boolean unlocked=flow.complete()&&(s!=Screen.WHEELS_CHECK||reviewing||flow.canFinish(developer));
        String details="The center standoff will support the extension board later. Tighten its lower nut, remove the top screw and store it safely.\n\nWheel D-shaped hubs must match the flat motor shafts. The camera checks visible part identity and alignment. Your mandatory stability answers confirm axle grip and lower-nut tightness. Keep power disconnected during assembly.";
        card.render(900+flow.step(),"Phase 9 · "+s.label()+" · "+flow.step()+"/"+Screen.values().length,instruction(),details,message,unlocked,true,flow.canRescan());
        card.next.setText(s==Screen.WHEELS_CHECK?reviewing?"Return to current phase":"Continue to Phase 10 →":"Next Step →");card.next.setEnabled(unlocked&&!saving);card.next.setVisibility(s.video()&&!reviewing?View.GONE:View.VISIBLE);
        boolean actionable=s.preparation()||s.check()||s.video();card.action.setVisibility(actionable&&!reviewing&&(!flow.complete()||s.video())?View.VISIBLE:View.GONE);
        card.action.setText(s.video()?videoError?"Retry demo":"Ready to Scan":s.preparation()?"I Have These":"Complete physical check");card.action.setEnabled(!saving&&(!s.video()||watched[videoIndex()]||videoError));card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        alignment.setInstruction(s==Screen.WHEELS_SCAN?"Both wheels · Either hub face":s==Screen.STANDOFF_WORK_SCAN?"Center standoff · Before wheels":"Complete chassis · Both wheels");alignment.showGuide(plan==null?null:Phase9AssetCatalog.guide(activity.getAssets(),s.scanStage()));
        ((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(plan!=null);activity.hideGuidance();
        if(attached!=s){stopVideo();attached=s;if(s.video())startVideo();else if(s==Screen.HARDWARE_PREP)preview.showModelChoices(new String[]{NeobotSeedData.NUT,NeobotSeedData.PHILLIPS_SCREWDRIVER,NeobotSeedData.STANDOFF},new String[]{"1 × M3 nut","Phillips screwdriver","Metal standoff"},0,text->say(text,"model:"+(++sequence)));else preview.showComponent(s.ordinal()<=Screen.STANDOFF_CHECK.ordinal()&&s!=Screen.WHEELS_SCAN&&s!=Screen.WHEELS_READY?NeobotSeedData.STANDOFF:NeobotSeedData.WHEEL,s.ordinal()<=Screen.STANDOFF_CHECK.ordinal()&&s!=Screen.WHEELS_SCAN&&s!=Screen.WHEELS_READY?"Metal standoff":"Robot wheel");}
        preview.onHudState(flow.complete()?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.SEARCHING);
        activity.setBanner(flow.complete()?"Step complete · Next step unlocked":message.isEmpty()?s.label():message,plan!=null,warning?R.drawable.bg_camera_warning:flow.complete()?R.drawable.bg_success:R.drawable.bg_live_pill);

        preview.setSkipPreviewAction(AssemblyStepNavigationManager.canSkipPreview(flow.screen().video(),paused,saving)?this::skipPreview:null);
    }
    String checkpointState(){if(video!=null)positions[videoIndex()]=video.position();return flow.snapshot()+"|"+watched[0]+"|"+positions[0]+"|"+watched[1]+"|"+positions[1];}
    private void save(){String state=checkpointState();if(!state.equals(savedState)){savedState=state;if(!developer&&!reviewing)PhaseProgressStore.saveStandoffWheelsState(activity,NeobotSeedData.PROJECT_ID,state);checkpointChanged.run();}}
    private void advance(){if(!closed&&!paused&&!saving&&finishing&&flow.canFinish(developer)){finishing=false;main.removeCallbacksAndMessages(null);finish.run();}}
    void onSpeechFinished(String key){if(closed||paused)return;if(finishing&&("phase9:"+completionKey).equals(key)){advance();return;}if(video!=null&&narrator.onSpeechFinished(key.startsWith("phase9:")?key.substring(7):key))video.continueAfterNarration();}
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
