package dev.chyken.assembl_ar;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.MotorWireColorConfig;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.workflow.phase.*;

/** UI-thread wiring guide. Android speech synthesis stays on AssemblyVoiceGuide's worker. */
final class Phase4GuideController implements AutoCloseable {
    private static final String[] HUD={"",
        "Step 4.1: Select Motor Wire Colors.\n• Match the pictured inner and outer copper tabs on both motors.\n• Pick from the 10 color swatches to match your physical wires.",
        "Step 4.2: Connect Wires per Custom Schematic.\n• Follow the 2D schematic showing your selected wire colors.\n• Screw each wire lead tightly into its matching Motor Driver terminal.",
        "Step 4.3: Wire Stability Pull-Test.\n• Gently tug each wire lead near the terminal blocks to check if any wire slips out or is loose."};
    private static final String[] SPOKEN={"",
        "Step 4 point 1. Match the motor orientation in the picture, then select the wire colors for the inner and outer copper tabs of both motors.",
        "Step 4 point 2. Orient the driver with green screw blocks toward you and the six small header pins away. Match the pictured motor tabs, then follow your wire colors into the highlighted driver openings and tighten the matching screws.",
        "Step 4 point 3. Perform a gentle pull test on each screwed wire terminal to confirm they are firmly locked."};
    private static final String LOOSE="Please loosen the terminal screw, re-insert the wire lead fully, and tighten firmly with your screwdriver.";
    private final AssemblyActivity activity;
    private final CompactGuideCard card;
    private final ComponentPreviewWindow preview;
    private final Runnable finish,previousPhase,checkpointChanged;
    private final Handler main=new Handler(Looper.getMainLooper());
    final Phase4MotorWiringFlow flow;
    private final AssemblyDemoView schematic;
    private final FrameLayout previewContent;
    private WireColorPickerDialog picker;
    private AlertDialog pullDialog;
    private AlertDialog expandedDiagram;
    private MotorWireColorConfig pickerDraft;
    private Terminal pickerTerminal=Terminal.LEFT_POSITIVE;
    private boolean reopenPicker;
    private boolean developer,reviewing,paused,closed,saving,finishing,previewAttached;
    private String message="",savedState="",completionKey;
    private long speechSequence;

    Phase4GuideController(AssemblyActivity activity,boolean developer,Runnable finish,Runnable previousPhase,
                          String saved,Runnable checkpointChanged){
        this.activity=activity;this.developer=developer;this.finish=finish;this.previousPhase=previousPhase;this.checkpointChanged=checkpointChanged;
        card=activity.findViewById(R.id.phaseTwoGuide);preview=activity.findViewById(R.id.componentPreviewWindow);
        if((saved==null||saved.isEmpty())&&!developer)saved=PhaseProgressStore.wiringState(activity,NeobotSeedData.PROJECT_ID);
        String[] checkpoint=saved==null?new String[0]:saved.split("~",-1);
        flow=Phase4MotorWiringFlow.restore(checkpoint.length>0?checkpoint[0]:null);paused=!activity.isActivityResumed();
        if(checkpoint.length==3)try{pickerDraft=MotorWireColorConfig.decode(checkpoint[1]);pickerTerminal=Terminal.values()[Integer.parseInt(checkpoint[2])];reopenPicker=true;}catch(RuntimeException bad){pickerDraft=null;pickerTerminal=Terminal.LEFT_POSITIVE;}
        schematic=new AssemblyDemoView(activity);
        schematic.setOnMotorWireSelected(terminal->{
            String text=terminal.spoken()+": "+flow.colors().swatch(terminal).label+" wire to "+terminal.output+".";
            message=text;render();say(text,"connection:"+(++speechSequence));
        });
        previewContent=new FrameLayout(activity);previewContent.addView(schematic,new FrameLayout.LayoutParams(-1,-1));
        bindControls();
    }
    private int dp(int value){return UiUtils.dp(activity,value);}
    private void bindControls(){
        card.previous.setOnClickListener(v->{if(saving)return;if(flow.step()==1)previousPhase.run();else if(flow.previous())changed();});
        card.next.setOnClickListener(v->{if(saving)return;if(flow.step()==3){if(reviewing||flow.canFinish(developer))finish.run();}else if(flow.next())changed();});
        card.action.setOnClickListener(v->{if(saving)return;switch(flow.step()){
            case 1:openPicker();break;
            case 2:if(developer)flow.devSkip();else flow.confirmWired();message="Wiring marked done · Next unlocked";render();save();say("Wiring marked done. The next step is unlocked. Continue when you are ready for the pull test.","wired:"+(++speechSequence));break;
            case 3:pullTest();break;
        }});
        card.setOnDetailsChanged(text->say(text.isEmpty()?"Additional information hidden.":text,"details:"+(++speechSequence)));
    }
    void start(){changed();}
    void startReview(boolean atEnd){reviewing=true;developer=true;flow.devComplete();flow.devJump(atEnd?3:1);card.returnToCurrent.setOnClickListener(v->finish.run());changed();}
    private void say(String text,String key){if(!closed&&!paused&&activity.isActivityResumed())activity.voiceGuide().speak(text,"phase4:"+key);}
    private void changed(){
        if(closed)return;main.removeCallbacksAndMessages(null);finishing=false;completionKey=null;message="";
        dismissDialogs();activity.voiceGuide().stop();activity.resetAnalyzerTracking();activity.hideGuidance();
        ArHudView hud=activity.findViewById(R.id.arHudView);hud.reset();hud.setBoardCorners(null);hud.clearAssemblyTargets();
        render();save();say(flow.complete()?reviewing?"Reviewing completed motor wiring instructions. Your assembly progress is preserved.":flow.step()==3?"Phase 4 is complete. Choose Continue to Phase 5 when you are ready.":"This step is complete. Review the instructions, then choose Next.":SPOKEN[flow.step()],"step:"+flow.tag());
        if(!paused&&!reviewing&&reopenPicker)main.post(this::openPicker);
        else if(!paused&&!reviewing&&!flow.complete()){
            if(flow.step()==1)main.post(this::openPicker);else if(flow.step()==3)main.post(this::pullTest);
        }
    }
    private void openPicker(){
        if(closed||paused||saving||reviewing||picker!=null&&picker.isShowing())return;
        if(pullDialog!=null)pullDialog.dismiss();
        picker=new WireColorPickerDialog(activity,pickerDraft==null?flow.colors():pickerDraft,new WireColorPickerDialog.Listener(){
            @Override public void onApply(MotorWireColorConfig colors){if(closed||paused||saving)return;pickerDraft=null;reopenPicker=false;pickerTerminal=Terminal.LEFT_POSITIVE;flow.applyColors(colors);dismissDialogs();changed();}
            @Override public void onInstruction(String text){if(closed||paused)return;message=text;render();save();say(text,"picker:"+(++speechSequence));}
        });
        picker.setInitialTerminal(pickerTerminal);
        picker.setOnCancelListener(d->{if(picker!=d)return;picker=null;message="";pickerDraft=null;reopenPicker=false;render();save();say(SPOKEN[flow.step()],"picker-cancel:"+(++speechSequence));});picker.show();
    }
    private void expandDiagram(){
        if(closed||paused||expandedDiagram!=null&&expandedDiagram.isShowing())return;
        AssemblyDemoView full=new AssemblyDemoView(activity);full.setMotorWireColors(flow.colors());
        full.setOnMotorWireSelected(terminal->{full.setSelectedMotorWire(terminal);say(terminal.spoken()+", "+flow.colors().swatch(terminal).label+" wire to "+terminal.output+".","expanded:"+(++speechSequence));});
        FrameLayout body=new FrameLayout(activity);int height=Math.min(dp(460),Math.round(activity.getResources().getDisplayMetrics().heightPixels*.60f));body.addView(full,new FrameLayout.LayoutParams(-1,height));
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle("Motor wiring diagram").setView(body).setNegativeButton("Close",null);
        if(!reviewing&&!saving)builder.setPositiveButton("Edit colors",(d,w)->openPicker());
        expandedDiagram=builder.create();expandedDiagram.setOnShowListener(d->{expandedDiagram.getWindow().setLayout(Math.min(dp(1000),activity.getResources().getDisplayMetrics().widthPixels-dp(24)),-2);full.resume();say("Motor wiring diagram expanded. Tap a lead to hear its connection.","expand:"+(++speechSequence));});
        expandedDiagram.setOnDismissListener(d->full.pause());expandedDiagram.show();
    }
    private void pullTest(){
        if(closed||paused||saving||reviewing||flow.step()!=3||flow.complete()||pullDialog!=null&&pullDialog.isShowing())return;
        TextView prompt=new TextView(activity);prompt.setText("Are all four motor wires firmly screwed in?");prompt.setTextSize(17);prompt.setPadding(dp(24),dp(12),dp(24),dp(16));
        pullDialog=new AlertDialog.Builder(activity).setTitle("Wire pull test").setView(prompt)
                .setNeutralButton("Check later",null).setNegativeButton("Wire is Loose",null).setPositiveButton("All Wires Secure",null).create();
        pullDialog.setOnShowListener(d->{
            pullDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{flow.confirmPullTest(false);message=LOOSE;prompt.setText(LOOSE);render();save();say(LOOSE,"loose:"+(++speechSequence));});
            pullDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                flow.confirmPullTest(true);pullDialog.dismiss();message="All four wires secure";render();save();
                if(!flow.canFinish(developer)){say("Pull test complete. Apply your wire colors and confirm the wiring step before continuing.","remaining-checkpoints");return;}
                finishing=true;completionKey="complete:"+(++speechSequence);say("Phase 4 complete! Great job wiring the motors.",completionKey);
                AssemblyVoiceGuide.Status status=activity.voiceGuide().getStatus();
                if(status==AssemblyVoiceGuide.Status.MUTED||status==AssemblyVoiceGuide.Status.UNAVAILABLE)advance();else main.postDelayed(this::advance,16000);
            });
        });pullDialog.show();
    }
    private void advance(){if(!closed&&!paused&&!saving&&finishing&&flow.canFinish(developer)){finishing=false;main.removeCallbacksAndMessages(null);finish.run();}}
    void render(){
        if(closed)return;int step=flow.step();boolean unlocked=flow.complete()&&(step!=3||reviewing||flow.canFinish(developer));
        StringBuilder more=new StringBuilder("Orient the driver with green screw blocks toward you and six small header pins away. Match each motor to its picture. Inner tabs face the board center; outer tabs face the wheels. Insert each stripped lead into the highlighted opening, then tighten its matching screw.\n\nYour connections:\n");
        for(Terminal t:Terminal.values())more.append(t.label).append(" · ").append(flow.colors().swatch(t).label).append(" → ").append(t.output).append('\n');
        more.append("\nThe four large openings are for motor wires. The six small header pins are separate control and power connections. Copper motor tabs are unmarked: this illustration does not establish positive or negative polarity. Wire colors identify your own leads. Tap a connection to inspect it, or expand to see all four.");
        card.render(40+step,"Phase 4 · Step 4."+step+" of 3",HUD[step],more.toString(),message,unlocked,true,false);
        card.next.setText(step==3?reviewing?"Return to current phase":developer?"Next phase (DEV) →":"Continue to Phase 5 →":"Next Step →");card.next.setEnabled(unlocked&&!saving);
        card.action.setVisibility(!reviewing&&!flow.complete()?View.VISIBLE:View.GONE);card.action.setEnabled(!saving);
        card.action.setText(step==1?"Select wire colors":step==2?"Done Wiring":"Check wires");card.returnToCurrent.setVisibility(reviewing?View.VISIBLE:View.GONE);
        ((TargetAlignmentBoxView)activity.findViewById(R.id.targetAlignmentBox)).showGuide(null);activity.hideGuidance();
        ((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(false);
        schematic.setMotorWireColors(flow.colors());if(!previewAttached){preview.showContent(previewContent,"Motor wiring · Component view");previewAttached=true;}
        preview.setContentAction("Expand motor wiring diagram",this::expandDiagram);
        if(!paused)schematic.resume();else schematic.pause();
        preview.onHudState(flow.complete()?ArHudView.HudState.MATCHED:ArHudView.HudState.SEARCHING);
        activity.setBanner(flow.complete()?"Step complete · Next unlocked":message.isEmpty()?HUD[step].split("\n")[0]:message,false,flow.complete()?R.drawable.bg_success:R.drawable.bg_live_pill);
    }
    String checkpointState(){
        if(picker!=null&&picker.isShowing()){pickerDraft=picker.draftColors();pickerTerminal=picker.selectedTerminal();reopenPicker=true;}
        return flow.snapshot()+(pickerDraft==null?"":"~"+pickerDraft.encode()+"~"+pickerTerminal.ordinal());
    }
    private void save(){String state=checkpointState();if(!state.equals(savedState)){savedState=state;if(!developer&&!reviewing)PhaseProgressStore.saveWiringState(activity,NeobotSeedData.PROJECT_ID,state);checkpointChanged.run();}}
    void onSpeechFinished(String key){if(finishing&&("phase4:"+completionKey).equals(key))advance();}
    void onVoiceStatusChanged(){AssemblyVoiceGuide.Status status=activity.voiceGuide().getStatus();if(finishing&&(status==AssemblyVoiceGuide.Status.MUTED||status==AssemblyVoiceGuide.Status.UNAVAILABLE))advance();}
    void setSaving(boolean value){boolean failed=saving&&!value;saving=value;if(failed){message="Progress could not be saved. Choose Continue to retry.";say(message,"save-retry:"+(++speechSequence));}render();}
    private void dismissDialogs(){if(picker!=null){picker.dismiss();picker=null;}if(pullDialog!=null){pullDialog.dismiss();pullDialog=null;}if(expandedDiagram!=null){expandedDiagram.dismiss();expandedDiagram=null;}}
    void pause(){paused=true;main.removeCallbacksAndMessages(null);flow.interrupt();schematic.pause();activity.voiceGuide().stop();card.setOnDetailsChanged(null);save();dismissDialogs();}
    void resume(){paused=false;flow.interrupt();bindControls();changed();}
    void prepareForReview(){pause();previewAttached=false;}
    void devJump(int step){developer=true;flow.devJump(step);changed();}
    void devSkip(){developer=true;flow.devSkip();render();save();}
    void devComplete(){developer=true;flow.devComplete();render();save();}
    void restart(){dismissDialogs();pickerDraft=null;reopenPicker=false;flow.restart();changed();}
    @Override public void close(){if(closed)return;pause();closed=true;schematic.setOnMotorWireSelected(null);((ArHudView)activity.findViewById(R.id.arHudView)).setScanningEnabled(true);}
}
