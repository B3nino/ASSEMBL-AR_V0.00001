package dev.chyken.assembl_ar;

import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.*;
import dev.chyken.assembl_ar.camera.Phase8AssetCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.*;
import dev.chyken.assembl_ar.model.ComponentWiringColorConfig.Wire;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import dev.chyken.assembl_ar.workflow.phase.Phase8SensorWiringFlow.Screen;

/** Main-thread view coordinator. Recognition, image decoding and speech retain their own workers. */
final class Phase8GuideController implements AutoCloseable {
    boolean schematicFocused(){return !closed&&!paused&&flow.screen().wiring();}
    private final AssemblyActivity activity;private final CompactGuideCard card;private final ComponentPreviewWindow preview;private final TargetAlignmentBoxView alignment;private final WiringWorkspace workspace;
    private final Runnable finish,previousPhase,checkpointChanged;private final Handler main=new Handler(Looper.getMainLooper());
    final Phase8SensorWiringFlow flow;private final DemoClipNarrator narrator;private final List<String> mountClips,routingClips;
    private Phase2VideoPlayer video;private AlertDialog dialog;private ComponentWireColorPickerDialog picker;private ComponentWiringColorConfig draft;private Wire selected=Wire.TRIGGER;
    private final long createdAt=SystemClock.elapsedRealtimeNanos();private final long[] positions=new long[2];private final boolean[] watched=new boolean[2];
    private volatile Plan plan;private Screen attached;private boolean developer,reviewing,reviewScan,paused,closed,saving,modal,videoError,finishing;private long sequence;private String message="",savedState="",completionKey;private boolean warning;
    private final Set<String> spoken=new HashSet<>();
    private static final class Plan{final int stage;final PhaseFlow.ScanTag tag;Plan(int stage,PhaseFlow.ScanTag tag){this.stage=stage;this.tag=tag;}}
    Phase8GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,String saved,Runnable changed){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;checkpointChanged=changed;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);alignment=activity.findViewById(R.id.targetAlignmentBox);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.sensorWiringState(activity,NeobotSeedData.PROJECT_ID);
        String[] p=saved==null?new String[0]:saved.split("\\|",-1);flow=Phase8SensorWiringFlow.restore(p.length>0?p[0]:null,createdAt);
        if(p.length>=5)try{watched[0]=Boolean.parseBoolean(p[1]);positions[0]=Math.max(0,Long.parseLong(p[2]));watched[1]=Boolean.parseBoolean(p[3]);positions[1]=Math.max(0,Long.parseLong(p[4]));if(p.length==7){draft=ComponentWiringColorConfig.decode(p[5]);selected=Wire.values()[Integer.parseInt(p[6])];}}catch(RuntimeException bad){draft=null;selected=Wire.TRIGGER;}
        mountClips=Phase8AssetCatalog.videos(activity.getAssets(),false);routingClips=Phase8AssetCatalog.videos(activity.getAssets(),true);
        paused=!activity.isActivityResumed();if(paused)flow.pause();workspace=new WiringWorkspace(activity,text->say(text,"diagram:"+(++sequence)));
        narrator=new DemoClipNarrator(new String[]{spokenInstruction(Screen.MOUNT_DEMO),spokenInstruction(Screen.ROUTING_DEMO)},new DemoClipNarrator.Voice(){public void speak(String text,String key){say(text,key);}public void stop(){activity.voiceGuide().stop();}});bind();
    }
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    private void say(String text,String key){if(!closed&&!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase8:"+key);}
    private void status(String text){message=text;if(spoken.add(text))say(text,"status:"+(++sequence));}
    private void bind(){
        card.previous.setOnClickListener(v->{if(saving)return;if(flow.step()==1)previousPhase.run();else if(flow.previous(now()))changed();});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.screen()==Screen.TRACKER_WIRING){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.next(now()))changed();});
        card.action.setOnClickListener(v->{if(saving)return;Screen s=flow.screen();if(s.prep())prepare();else if(s.picker())colors();else if(s.wiring()){if(reviewing||flow.complete()){finishing=false;completionKey=null;main.removeCallbacksAndMessages(null);activity.voiceGuide().stop();workspace.expand();}else check();}else if(s.video()){
            if(videoError){stopVideo();attached=null;videoError=false;render();}else if(watched[videoIndex()]){flow.confirm();flow.next(now());changed();}
        }});
        card.rescan.setOnClickListener(v->rescan());card.setOnDetailsChanged(text->{if(video!=null){video.pause();narrator.stop();}say(text.isEmpty()?"Additional information hidden.":text,"details:"+(++sequence));});
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?14:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);finishing=false;completionKey=null;message="";warning=false;spoken.clear();dismiss();stopVideo();attached=null;videoError=false;
        activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.clearAssemblyTargets();hud.setBoardCorners(null);
        render();save();if(!flow.screen().video())say(spokenInstruction(flow.screen()),"step:"+flow.tag());
        if(!paused&&!reviewing&&!flow.complete()){if(flow.screen().prep())main.post(this::prepare);else if(flow.screen().picker())main.post(this::colors);}
    }
    private String instruction(){switch(flow.screen()){
        case SENSOR_SCAN:return "Step 8.1: Scan the Distance Sensor.\n• Place the module flat with both sensor eyes visible.";
        case HARDWARE_PREP:return "Step 8.1: Prepare jumper wires.\n• Gather 10 female-to-female jumper wires.\n• Sensor power and ground already connect through the PCB.";
        case MOUNT_DEMO:return "Step 8.2A: Watch the installation demo.\n• Both sensor eyes must face OUTWARD, away from the board.\n• Choose Ready to Scan after watching.";
        case MOUNT_SCAN:return "Step 8.2B: Verify Distance Sensor installation.\n• Point the camera straight down.\n• Match the recommended scanning frame.";
        case DISTANCE_PREP:return "Step 8.3: Prepare 2 female-to-female jumper wires.\n• These are for Trigger and Echo only.\n• VCC and GND need no additional jumpers.";
        case DISTANCE_COLORS:return "Step 8.3: Choose signal wire colors.\n• Trigger → D2\n• Echo → D4";
        case DISTANCE_WIRING:return "Step 8.3: Wire Distance Sensor signals.\n• Trigger → Nano D2\n• Echo → Nano D4\n• Check both jumper housings are firmly seated.";
        case INDICATOR_PREP:return "Step 8.4: Prepare 2 female-to-female jumper wires.\n• One LED lead and one speaker lead.";
        case INDICATOR_COLORS:return "Step 8.4: Choose LED and Speaker colors.\n• LED → A1\n• Speaker (SPKR) → D3";
        case INDICATOR_WIRING:return "Step 8.4: Wire LED & Speaker.\n• Rail socket 12 (LED) → Nano A1\n• Rail socket 11 (SPKR) → Nano D3";
        case ROUTING_DEMO:return "Step 8.5A: Watch the wire routing demo.\n• Feed 6 jumper wires through the PCB pass-through holes, from the Nano above to the trackers underneath.";
        case TRACKER_PREP:return "Step 8.5: Prepare 6 female-to-female jumper wires.\n• Each tracker needs V+, G and S.\n• Viewing from underneath mirrors physical left and right.";
        case TRACKER_COLORS:return "Step 8.5: Choose 6 tracker wire colors.\n• Physical Left: V+, G and S → A2\n• Physical Right: V+, G and S → A3";
        default:return "Step 8.5: Wire Dual Line Trackers.\n• Left: V+ → 5V rail, G → GND rail, S → A2\n• Right: V+ → 5V rail, G → GND rail, S → A3\n• Check all 10 installed jumpers; keep 2 spares.";
    }}
    private static String spokenInstruction(Screen s){switch(s){
        case SENSOR_SCAN:return "Step 8 point 1. Scan your ultrasonic distance sensor with both sensor eyes visible.";
        case HARDWARE_PREP:return "Prepare ten female to female jumper wires. Ten are used and two are spares. The distance sensor power and ground connect through the board, so no extra wires are needed for those pins.";
        case MOUNT_DEMO:return "Watch the video to mount the distance sensor. Make sure the sensor eyes face outward away from the circuit board. Tap Ready to Scan when you are ready.";
        case MOUNT_SCAN:return "Step 8 point 2. Point your camera straight down to verify distance sensor orientation.";
        case DISTANCE_PREP:return "Prepare two female to female jumper wires for Trigger and Echo. No additional jumper wires are needed for sensor power and ground. Wire colors are optional personal visual guidance and do not affect the circuit.";
        case DISTANCE_COLORS:return "Step 8 point 3. Select wire colors for Trigger connected to digital pin 2, and Echo connected to digital pin 4.";
        case DISTANCE_WIRING:return "Connect Trigger to digital pin 2 and Echo to digital pin 4, then check both headers are fully seated.";
        case INDICATOR_PREP:return "Prepare two female to female jumper wires for the LED and speaker. Colors are optional visual guidance and do not change electrical behavior.";
        case INDICATOR_COLORS:return "Step 8 point 4. Select wire colors for the LED connected to analog pin A1, and the speaker connected to digital pin 3.";
        case INDICATOR_WIRING:return "Connect the LED signal from rail socket twelve to A1, and speaker from socket eleven to digital pin 3. Their grounds already connect through the board.";
        case ROUTING_DEMO:return "Watch the preview demo to see which PCB holes to feed your six jumper wires through between the Arduino and line trackers.";
        case TRACKER_PREP:return "Prepare six female to female jumper wires. Left and right refer to the robot viewed from above. Looking underneath mirrors the sides. Colors are personal visual guidance only.";
        case TRACKER_COLORS:return "Step 8 point 5. Assign wire colors for both trackers. Physical left connects to A2, physical right connects to A3.";
        default:return "Connect both tracker power pins to the five volt rail and both ground pins to the ground rail. Connect physical left signal to A2 and physical right signal to A3. Perform a gentle pull test on all ten installed jumper wires.";
    }}
    boolean scanning(){return plan!=null;}
    RecognitionRequest request(CameraCalibration calibration){Plan p=plan;return p==null?null:new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.DISTANCE_SENSOR),calibration,null,null,1,8).withPhase8Stage(p.stage).withRequestTag(p.tag);}
    boolean current(RecognitionResult r){return !closed&&r.getFrameTimestampNanos()>=createdAt&&flow.tag().equals(r.getRequestTag());}
    void onResult(RecognitionResult r){
        if(paused||modal||saving||!current(r)||!flow.accepts(r.getRequestTag(),r.getFrameTimestampNanos()))return;
        Phase8Observation o=r.getPhase8Observation();int stage=flow.screen()==Screen.SENSOR_SCAN?1:2;boolean accepted=o!=null&&o.accepted(stage);flow.observe(accepted);
        warning=o!=null&&(o.state==Phase8Observation.State.FLIPPED||o.state==Phase8Observation.State.INVALID||o.state==Phase8Observation.State.INWARD);
        if(flow.complete())status("Scan successful · Next step unlocked");else if(warning)status(stage==1?"Distance sensor orientation invalid. Place flat with sensor eyes visible.":o.state==Phase8Observation.State.INWARD?"Distance sensor is facing inward! Re-seat it so both sensor eyes face outward, away from the board.":"Distance sensor is missing, misplaced or facing inward. Re-seat it with both eyes facing outward and rescan.");else if(!accepted)return;
        ((ArHudView)activity.findViewById(R.id.arHudView)).update(flow.complete()?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.TRACKING,"Distance sensor",activity.normalizeCorners(r.getProjectedCorners()));activity.hideGuidance();render();save();
    }
    private void prepare(){
        if(closed||paused||saving||modal||!flow.screen().prep())return;modal=true;render();Screen s=flow.screen();int count=s==Screen.HARDWARE_PREP?12:s.section()==3?6:2;
        String extra=s==Screen.HARDWARE_PREP?"Use 10 wires for this phase and keep 2 spares. Sensor VCC and GND connect through the PCB; no extra jumpers are needed.":s.section()==3?"Use physical left and right as viewed from above. From the bottom, screen-left is physical right (A3), and screen-right is physical left (A2). Route through the holes shown in the demo.":s.section()==1?"Trigger → D2. Echo → D4. Sensor VCC and GND are already connected through the PCB.":"LED → A1. Speaker → D3. Both grounds already connect through the PCB.";
        dialog=JumperWirePreparationDialog.show(activity,count,false,extra,s==Screen.HARDWARE_PREP?"I Have These":"Select Colors / Next",()->{flow.confirm();flow.next(now());changed();});
        dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused){flow.resume(now());render();}});
        say("Prepare "+count+" female to female jumper wires. "+extra+" "+JumperWirePreparationDialog.DISCLAIMER,"prep:"+(++sequence));
    }
    private void colors(){
        if(closed||paused||saving||modal||reviewing||!flow.screen().picker())return;modal=true;render();
        if(draft!=null&&selected.section!=flow.screen().section())draft=null;
        picker=new ComponentWireColorPickerDialog(activity,draft==null?flow.colors():draft,flow.screen().section(),selected,new ComponentWireColorPickerDialog.Listener(){
            public void onApply(ComponentWiringColorConfig value){draft=null;flow.applyColors(value,now());changed();}
            public void onInstruction(String text){say(text,"picker:"+(++sequence));save();}
        });picker.setOnDismissListener(d->{if(picker!=d)return;modal=false;picker=null;if(!closed&&!paused){render();save();}});picker.show();
    }
    private String checkQuestion(){return flow.screen()==Screen.DISTANCE_WIRING?"Are Trigger (D2) and Echo (D4) jumper headers fully seated without slipping?":flow.screen()==Screen.INDICATOR_WIRING?"Are LED and Speaker jumper wires firmly connected to pins A1 and D3?":"Are all 10 installed jumper wires firmly seated and the tracker wires routed through the correct PCB holes? Sensor VCC and GND already connect through the PCB; the other 2 wires are spares.";}
    private void check(){
        if(closed||paused||saving||modal||reviewing||!flow.screen().wiring())return;modal=true;render();TextView prompt=PhysicalCheckUi.prompt(activity,checkQuestion());
        dialog=new AlertDialog.Builder(activity).setTitle(flow.screen()==Screen.TRACKER_WIRING?"Final wire stability check":"Connection check").setView(prompt).setNeutralButton("Check later",null).setNegativeButton(flow.screen()==Screen.TRACKER_WIRING?"Check Wires":"Loose Wire",null).setPositiveButton(flow.screen()==Screen.TRACKER_WIRING?"All Secure":"Firm & Secure",null).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;PhysicalCheckUi.style(dialog);say(checkQuestion(),"check:"+(++sequence));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{String correction="Please check all female jumper headers and push each housing firmly onto the correct pin. Re-seat loose wires.";prompt.setText(correction+"\n\n"+checkQuestion());say(correction,"loose:"+(++sequence));});dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{flow.confirm();dialog.dismiss();modal=false;status("Connections secure · Next step unlocked");render();save();if(flow.canFinish(developer)){finishing=true;completionKey="complete:"+(++sequence);say("Phase 8 complete! All sensors and component wires verified successfully.",completionKey);AssemblyVoiceGuide.Status voice=activity.voiceGuide().getStatus();if(voice==AssemblyVoiceGuide.Status.MUTED||voice==AssemblyVoiceGuide.Status.UNAVAILABLE)advance();else main.postDelayed(this::advance,16000);}});});
        dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused)render();});dialog.show();
    }
    private void rescan(){if(closed||paused||saving||!flow.canRescan())return;dismiss();flow.pause();modal=true;render();dialog=RescanConfirmationDialog.show(activity,()->{reviewScan=reviewing;flow.rescan(now());flow.resume(now());changed();});dialog.setOnDismissListener(d->{if(dialog!=d)return;modal=false;dialog=null;if(!closed&&!paused){flow.resume(now());render();}});}
    private int videoIndex(){return flow.screen()==Screen.ROUTING_DEMO?1:0;}
    private void startVideo(){
        final Screen screen=flow.screen();final int index=videoIndex();List<String> clips=index==0?mountClips:routingClips;if(clips.isEmpty()){videoError=true;status("The required Phase 8 demo is missing.");main.post(this::render);return;}
        video=new Phase2VideoPlayer(activity,clips,new Phase2VideoPlayer.Listener(){
            public void onClip(int n){if(!closed&&flow.screen()==screen)save();}
            public void onPlayback(int n,boolean playing,boolean seek){if(!closed&&flow.screen()==screen)narrator.onPlayback(index,playing&&!paused,seek);}
            public void onClipEnded(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(video!=null&&(!narrator.isPending()||s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))video.continueAfterNarration();}
            public void onFinished(){if(closed||flow.screen()!=screen)return;narrator.stop();watched[index]=true;status(index==0?"Demo complete · Choose Ready to Scan":"Demo complete · Continue to prepare tracker wires");render();save();}
            public void onError(String text){narrator.stop();videoError=true;status(text);render();}
        },path->index);
        preview.showContent(video.view(),index==0?"Sensor installation · Tap to pause · Drag to scrub":"Tracker wire routing · Tap to pause · Drag to scrub");video.seek(0,positions[index]);if(!paused)video.play();
    }
    private void stopVideo(){if(video!=null){int index=attached==Screen.ROUTING_DEMO?1:0;positions[index]=video.position();narrator.stop();video.close();video=null;}}
    void render(){
        if(closed)return;Screen s=flow.screen();plan=!paused&&!saving&&!modal&&(!reviewing||reviewScan)&&flow.scanning()?new Plan(s==Screen.SENSOR_SCAN?1:2,flow.tag()):null;
        boolean unlocked=flow.complete()&&(s!=Screen.TRACKER_WIRING||reviewing||flow.canFinish(developer));
        String details="Keep battery power disconnected while making connections. Sensor VCC/GND and LED/speaker grounds are already linked through the PCB. No additional jumpers are needed for those connections.\n\n"+JumperWirePreparationDialog.DISCLAIMER+"\n\nPhysical left/right are defined from above with the robot facing forward. When viewed underneath, screen-left is physical right (A3) and screen-right is physical left (A2).\n\nThe camera checks visible sensor installation only. The physical checklist checks wire seating and routing.";
        card.render(800+flow.step(),"Phase 8 · "+s.label()+" · "+flow.step()+"/14",instruction(),details,message,unlocked,true,flow.canRescan());
        card.next.setText(s==Screen.TRACKER_WIRING?reviewing?"Return to current phase":"Continue to Phase 9 →":"Next Step →");card.next.setEnabled(unlocked&&!saving);card.next.setVisibility(s.video()&&!flow.complete()?View.GONE:View.VISIBLE);
        boolean actionable=s.prep()||s.picker()||s.video()||s.wiring();card.action.setVisibility(s.wiring()||actionable&&!reviewing&&(!flow.complete()||s.picker()||s.video())?View.VISIBLE:View.GONE);
        card.action.setText(s.wiring()&&(reviewing||flow.complete())?"Review wiring diagram":s.prep()?s==Screen.HARDWARE_PREP?"I Have These":"Prepare wires":s.picker()?"Select wire colors":s.video()?videoError?"Retry demo":s==Screen.MOUNT_DEMO?"Ready to Scan":"Continue to wire preparation":"Check connections");card.action.setEnabled(!saving&&(!s.video()||watched[videoIndex()]||videoError));card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        alignment.setInstruction(s==Screen.SENSOR_SCAN?"Distance Sensor · Front face":"Distance Sensor · Installed top view");alignment.showGuide(plan==null?null:Phase8AssetCatalog.guide(activity.getAssets(),s==Screen.MOUNT_SCAN));
        ((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(plan!=null);activity.hideGuidance();
        if(s.wiring()){workspace.setComponentColors(flow.colors(),s.section());workspace.show();if(paused)workspace.pause();else workspace.resume();attached=s;}
        else{workspace.hide();if(attached!=s){stopVideo();attached=s;if(s.video())startVideo();else if(s.picker()){TextView hint=PhysicalCheckUi.prompt(activity,"Choose your physical wire colors.\n\nColors are visual guidance only.");preview.showContent(hint,"Wire color assignments");}else preview.showComponent(NeobotSeedData.DISTANCE_SENSOR,"Ultrasonic distance sensor");}}
        preview.onHudState(flow.complete()?ArHudView.HudState.MATCHED:warning?ArHudView.HudState.WRONG_ORIENTATION:ArHudView.HudState.SEARCHING);
        activity.setBanner(flow.complete()?"Step complete · Next step unlocked":message.isEmpty()?s.label():message,plan!=null,warning?R.drawable.bg_camera_warning:flow.complete()?R.drawable.bg_success:R.drawable.bg_live_pill);

        preview.setSkipPreviewAction(AssemblyStepNavigationManager.canSkipPreview(flow.screen().video(),paused,saving)?this::skipPreview:null);
    }
    String checkpointState(){if(video!=null)positions[videoIndex()]=video.position();if(picker!=null&&picker.isShowing()){draft=picker.draftColors();selected=picker.selectedWire();}return flow.snapshot()+"|"+watched[0]+"|"+positions[0]+"|"+watched[1]+"|"+positions[1]+(draft==null?"":"|"+draft.encode()+"|"+selected.ordinal());}
    private void save(){String state=checkpointState();if(!state.equals(savedState)){savedState=state;if(!developer&&!reviewing)PhaseProgressStore.saveSensorWiringState(activity,NeobotSeedData.PROJECT_ID,state);checkpointChanged.run();}}
    private void advance(){if(!closed&&!paused&&!saving&&finishing&&flow.canFinish(developer)){finishing=false;main.removeCallbacksAndMessages(null);finish.run();}}
    void onSpeechFinished(String key){if(closed||paused)return;if(finishing&&("phase8:"+completionKey).equals(key)){advance();return;}if(video!=null&&narrator.onSpeechFinished(key.startsWith("phase8:")?key.substring(7):key))video.continueAfterNarration();}
    void onVoiceStatusChanged(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(finishing&&(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))advance();else if(video!=null){if(s==AssemblyVoiceGuide.Status.AVAILABLE)narrator.onVoiceAvailable();else if(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE)video.continueAfterNarration();}}
    void setSaving(boolean value){boolean failed=saving&&!value;saving=value;if(failed)status("Progress could not be saved. Choose Continue to retry.");render();}
    private void dismiss(){if(picker!=null){picker.setOnDismissListener(null);picker.dismiss();picker=null;}if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}modal=false;}
    void pause(){paused=true;main.removeCallbacksAndMessages(null);flow.pause();plan=null;save();dismiss();if(video!=null)video.pause();narrator.stop();workspace.pause();workspace.hide();activity.voiceGuide().stop();card.setOnDetailsChanged(null);}
    void resume(){paused=false;flow.resume(now());bind();changed();}
    void prepareForReview(){pause();stopVideo();attached=null;}
    void devJump(int step){developer=true;flow.devJump(step,now());changed();}
    void devSkip(){developer=true;flow.devSkip();render();save();}
    void devComplete(){developer=true;flow.devComplete();changed();}
    void restart(){draft=null;Arrays.fill(watched,false);Arrays.fill(positions,0);flow.restart(now());changed();}
    @Override public void close(){if(closed)return;pause();closed=true;stopVideo();workspace.close();alignment.showGuide(null);((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(false);}

    private void skipPreview(){if(closed||!AssemblyStepNavigationManager.canSkipPreview(flow.screen().video(),paused,saving))return;watched[videoIndex()]=true;flow.confirm();if(flow.next(now()))changed();}
}
