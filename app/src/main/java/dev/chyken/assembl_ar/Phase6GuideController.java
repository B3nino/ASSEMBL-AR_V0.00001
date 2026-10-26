package dev.chyken.assembl_ar;

import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.DriverWireColorConfig;
import dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;

/** UI coordinator: camera analysis, artwork decoding and speech remain on their existing workers. */
final class Phase6GuideController implements AutoCloseable {
    boolean schematicFocused(){return !closed&&!paused&&flow.step()>=2;}
    private static final String[] HUD={"",
        "Step 6.1: Select 6x Jumper Wire Colors.\n• Pick custom colors for VCC, GND, A-1A, A-1B, B-1A and B-1B.\n• Match the colors to your physical jumper wires.",
        "Step 6.2: Connect Power Wires.",
        "Step 6.3: Connect Signal Control Wires.\n• A-1A → D5  |  A-1B → D9\n• B-1A → D6  |  B-1B → D10",
        "Step 6.4: Verify Signal Wire Connections.\n• Lightly touch each female jumper housing to ensure full seat.\n• Confirm no cross-bridged or loose jumper pins."};
    private static final String[] SPOKEN={"",
        "Step 6 point 1. Select the colors for your six jumper wires from the ten swatches on screen.",
        "Step 6 point 2. Connect the power and ground jumper wires between the motor driver and Arduino Nano.",
        "Step 6 point 3. Connect signal lines A one A to digital pin 5, A one B to pin 9, B one A to pin 6, and B one B to pin 10 using your chosen wire colors.",
        "Step 6 point 4. Perform a final physical check on all six jumper wire connections."};
    private static final String INFO="Control pinout\n\nMotor Driver A-1A → Nano D5, and A-1B → D9: left motor PWM control.\nMotor Driver B-1A → Nano D6, and B-1B → D10: right motor PWM control.\nPower: VCC → VIN. Ground: GND → GND.\n\nSome driver boards print IA/IB instead of 1A/1B. These name the same inputs. Match the USB end and printed pin labels to the picture. Keep battery power disconnected while wiring.";
    private static final String RESEAT="Please re-check pin numbers on the Arduino Nano header and re-seat loose wires.";
    private final AssemblyActivity activity;
    private final CompactGuideCard card;
    private final ComponentPreviewWindow preview;
    private final WiringWorkspace workspace;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable finish,previousPhase,checkpointChanged;
    final Phase6DriverWiringFlow flow;
    private boolean preparationAccepted,preparing;
    private DriverWireColorPickerDialog picker;
    private DriverWireColorConfig draft;
    private Wire selected=Wire.VCC;
    private AlertDialog dialog;
    private final long createdAt=SystemClock.elapsedRealtimeNanos();
    private boolean developer,reviewing,paused,closed,saving,finishing,reopenPicker;
    private String message="",savedState="",completionKey;
    private long sequence;

    Phase6GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,String saved,Runnable changed){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;this.checkpointChanged=changed;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.driverWiringState(activity,NeobotSeedData.PROJECT_ID);
        if(saved!=null&&saved.contains("^")){int divider=saved.lastIndexOf('^');preparationAccepted=saved.substring(divider+1).equals("true");saved=saved.substring(0,divider);}
        String[] fields=saved==null?new String[0]:saved.split("~",-1);
        flow=Phase6DriverWiringFlow.restore(fields.length>0?fields[0]:null,createdAt);
        if(fields.length==3)try{draft=DriverWireColorConfig.decode(fields[1]);selected=Wire.values()[Integer.parseInt(fields[2])];reopenPicker=true;}catch(RuntimeException bad){draft=null;selected=Wire.VCC;}
        if(flow.step()>1)preparationAccepted=true;
        paused=!activity.isActivityResumed();if(paused)flow.pause();
        workspace=new WiringWorkspace(activity,text->say(text,"diagram:"+(++sequence)));
        bind();
    }
    private long now(){return SystemClock.elapsedRealtimeNanos();}
    private void say(String text,String key){if(!closed&&!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase6:"+key);}
    private void status(String text){if(!message.equals(text)){message=text;say(text,"status:"+(++sequence));}}
    private String instruction(){
        if(flow.step()!=2)return HUD[flow.step()];
        return HUD[2]+"\n• Motor Driver VCC → Nano VIN ("+flow.colors().swatch(Wire.VCC).label+").\n• Motor Driver GND → Nano GND ("+flow.colors().swatch(Wire.GND).label+").";
    }
    private void bind(){
        card.previous.setOnClickListener(v->{if(saving)return;if(flow.step()==1)previousPhase.run();else if(flow.previous(now()))changed();});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.step()==4){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.next(now()))changed();});
        card.action.setOnClickListener(v->{if(saving)return;if(flow.step()>=2&&(reviewing||flow.complete())){finishing=false;completionKey=null;main.removeCallbacksAndMessages(null);activity.voiceGuide().stop();workspace.expand();return;}switch(flow.step()){
            case 1:colors();break;case 2:check();break;
            case 3:flow.confirmSignals();status("Signal connections placed · Next unlocked");render();save();break;case 4:check();break;
        }});
        card.rescan.setOnClickListener(null);
        card.setOnDetailsChanged(text->say(text.isEmpty()?"Additional information hidden.":text,"details:"+(++sequence)));
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?4:1,now());card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);finishing=false;completionKey=null;message="";dismiss();
        activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.clearAssemblyTargets();hud.setBoardCorners(null);
        render();save();
        say(flow.complete()?"This step is complete. Review the information, then choose Next.":SPOKEN[flow.step()]+(flow.step()==2?" Use "+flow.colors().swatch(Wire.VCC).label+" for VCC to VIN, and "+flow.colors().swatch(Wire.GND).label+" for ground.":""),"step:"+flow.tag());
        if(!paused&&!reviewing){if(reopenPicker||flow.step()==1&&!flow.complete())main.post(this::colors);else if(flow.step()==4&&!flow.complete())main.post(this::check);}
    }
    boolean scanning(){return false;}
    RecognitionRequest request(CameraCalibration calibration){return null;}
    boolean current(RecognitionResult result){return false;}
    void onResult(RecognitionResult result){/* Phase 5 owns Nano recognition. */}
    private void colors(){
        if(closed||paused||saving||reviewing||picker!=null&&picker.isShowing())return;
        if(!preparationAccepted){
            if(preparing)return;preparing=true;
            dialog=JumperWirePreparationDialog.show(activity,6,false,"Two leads for VCC/VIN and ground, plus four PWM control leads. Keep battery power disconnected.","Select Colors / Next",()->{preparationAccepted=true;save();main.post(this::colors);});
            dialog.setOnDismissListener(d->preparing=false);say("Prepare six female to female jumper wires. "+JumperWirePreparationDialog.DISCLAIMER,"wire-preparation");return;
        }
        picker=new DriverWireColorPickerDialog(activity,draft==null?flow.colors():draft,new DriverWireColorPickerDialog.Listener(){
            public void onApply(DriverWireColorConfig value){if(closed||paused||saving)return;draft=null;reopenPicker=false;flow.applyColors(value,now());changed();}
            public void onInstruction(String text){if(!closed&&!paused){say(text,"picker:"+(++sequence));save();}}
        });
        picker.setInitialWire(selected);
        picker.setOnCancelListener(d->{if(picker!=d)return;picker=null;draft=null;reopenPicker=false;render();save();say(SPOKEN[flow.step()],"picker-cancel:"+(++sequence));});
        picker.show();
    }
    private void check(){
        if(closed||paused||saving||reviewing||flow.complete()||dialog!=null&&dialog.isShowing()||(flow.step()!=2&&flow.step()!=4))return;
        TextView prompt=PhysicalCheckUi.prompt(activity,question());
        dialog=new dev.chyken.assembl_ar.ui.PhysicalChecklistBottomSheetDialog.Builder(activity).setTitle(flow.step()==2?"Power connection check":"Final connection check").setView(prompt).setNeutralButton("Check later",null).setNegativeButton("Loose",null).setPositiveButton("Firm & Secure",null).create();
        dialog.setOnShowListener(d->{if(dialog!=d||closed||paused)return;
            PhysicalCheckUi.style(dialog);updateCheck(prompt);say(question(),"check:"+(++sequence));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{
                if(flow.step()==2)flow.confirmPower(false);else if(!flow.seated())flow.confirmSeated(false);else flow.confirmCorrect(false);
                String correction=flow.step()==2?"Push the VCC and ground female housings fully onto the correct pins. Keep battery power disconnected.":RESEAT;
                status(correction);prompt.setText(correction+"\n\n"+question());updateLabels();render();save();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(flow.step()==2){flow.confirmPower(true);dialog.dismiss();status("Power connections secure · Next unlocked");render();save();return;}
                if(!flow.seated()){flow.confirmSeated(true);updateCheck(prompt);say(question(),"check:"+(++sequence));render();save();return;}
                flow.confirmCorrect(true);dialog.dismiss();render();save();
                if(!flow.canFinish(developer)){status("Physical checks complete. Finish the earlier checkpoints before continuing.");return;}
                finishing=true;completionKey="complete:"+(++sequence);say("Phase 6 complete! Microcontroller to motor driver wiring verified.",completionKey);
                AssemblyVoiceGuide.Status voice=activity.voiceGuide().getStatus();
                if(voice==AssemblyVoiceGuide.Status.MUTED||voice==AssemblyVoiceGuide.Status.UNAVAILABLE)advance();else main.postDelayed(this::advance,16000);
            });
        });dialog.show();
    }
    private String question(){return flow.step()==2?"Are VCC and GND wires firmly attached to the female pin headers?":!flow.seated()?"Are all six jumper wire housings fully pushed down onto the metal pins?":"Are pins D5, D6, D9 and D10 correctly wired without adjacent cross-connections?";}
    private void updateLabels(){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(flow.step()==2?"Firm & Secure":flow.seated()?"Correct":"All Seated");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(flow.step()==4&&flow.seated()?"Wrong Pin":"Loose");}
    private void updateCheck(TextView text){text.setText(question());updateLabels();}
    private void advance(){if(!closed&&!paused&&!saving&&finishing&&flow.canFinish(developer)){finishing=false;main.removeCallbacksAndMessages(null);finish.run();}}
    void render(){
        if(closed)return;int step=flow.step();
        boolean unlocked=flow.complete()&&(step!=4||reviewing||flow.canFinish(developer));
        StringBuilder details=new StringBuilder(INFO).append("\n\nYour wire colors:\n");for(Wire wire:Wire.values())details.append(wire.label()).append(" · ").append(flow.colors().swatch(wire).label).append('\n');
        card.render(60+step,"Phase 6 · Step 6."+step+" of 4",instruction(),details.toString(),message,unlocked,true,false);
        card.next.setText(step==4?reviewing?"Return to current phase":"Continue to Phase 7 →":"Next Step →");card.next.setEnabled(unlocked&&!saving);
        card.action.setVisibility(step>=2||!reviewing&&(step==1||!flow.complete())?View.VISIBLE:View.GONE);card.action.setEnabled(!saving);
        card.action.setText(step>=2&&(reviewing||flow.complete())?"Review wiring diagram":step==1?"Select wire colors":step==2?"Check power wires":step==3?"Connections placed":"Check all six wires");card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        ((TargetAlignmentBoxView)activity.findViewById(R.id.targetAlignmentBox)).showGuide(null);activity.hideGuidance();((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(false);
        if(step>=2){workspace.setDriverColors(flow.colors(),step==2?1:step==3?2:0);workspace.show();if(!paused)workspace.resume();else workspace.pause();}
        else{workspace.hide();preview.showComponent(NeobotSeedData.MICROCONTROLLER,"Arduino Nano");}
        preview.onHudState(flow.complete()?ArHudView.HudState.MATCHED:ArHudView.HudState.SEARCHING);
        activity.setBanner(flow.complete()?"Step complete · Next unlocked":message.isEmpty()?instruction().split("\n")[0]:message,false,flow.complete()?R.drawable.bg_success:R.drawable.bg_live_pill);
    }
    String checkpointState(){if(picker!=null&&picker.isShowing()){draft=picker.draftColors();selected=picker.selectedWire();reopenPicker=true;}return flow.snapshot()+(draft==null?"":"~"+draft.encode()+"~"+selected.ordinal())+"^"+preparationAccepted;}
    private void save(){String state=checkpointState();if(!state.equals(savedState)){savedState=state;if(!developer&&!reviewing)PhaseProgressStore.saveDriverWiringState(activity,NeobotSeedData.PROJECT_ID,state);checkpointChanged.run();}}
    void onSpeechFinished(String key){if(finishing&&("phase6:"+completionKey).equals(key))advance();}
    void onVoiceStatusChanged(){AssemblyVoiceGuide.Status s=activity.voiceGuide().getStatus();if(finishing&&(s==AssemblyVoiceGuide.Status.MUTED||s==AssemblyVoiceGuide.Status.UNAVAILABLE))advance();}
    void setSaving(boolean value){boolean failed=saving&&!value;saving=value;if(failed)status("Progress could not be saved. Choose Continue to retry.");render();}
    private void dismiss(){preparing=false;if(picker!=null){picker.dismiss();picker=null;}if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}}
    void pause(){paused=true;main.removeCallbacksAndMessages(null);flow.pause();workspace.pause();workspace.hide();activity.voiceGuide().stop();card.setOnDetailsChanged(null);save();dismiss();}
    void resume(){paused=false;flow.resume(now());bind();changed();}
    void prepareForReview(){pause();}
    void devJump(int step){developer=true;flow.devJump(step,now());changed();}
    void devSkip(){developer=true;flow.devSkip();render();save();}
    void devComplete(){developer=true;flow.devComplete();render();save();}
    void restart(){draft=null;reopenPicker=false;preparationAccepted=false;flow.restart(now());changed();}
    @Override public void close(){if(closed)return;pause();closed=true;workspace.close();}
}

