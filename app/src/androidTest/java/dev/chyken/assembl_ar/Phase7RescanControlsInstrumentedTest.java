package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.ui.CompactGuideCard;
import dev.chyken.assembl_ar.vision.Phase7Observation;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.workflow.phase.Phase7CasterTrackerFlow;
import dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the actual Phase 7 controls using isolated flows; never resets learner progress. */
@RunWith(AndroidJUnit4.class)
public final class Phase7RescanControlsInstrumentedTest {
    @Test public void replayAndReviewBothOfferGuardedRescansWithoutChangingSavedProgress()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        String checkpoint=PhaseProgressStore.casterTrackerState(context,NeobotSeedData.PROJECT_ID);
        java.util.Map<String,?> session=new java.util.HashMap<>(context.getSharedPreferences("assembly_phase_progress",Context.MODE_PRIVATE).getAll());
        Intent intent=new Intent(context,AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_PROJECT_ID,NeobotSeedData.PROJECT_ID)
                .putExtra(AssemblyActivity.EXTRA_WORKFLOW_MODE,AssemblyActivity.WORKFLOW_CARDS)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE,"none");
        try(ActivityScenario<AssemblyActivity> scenario=ActivityScenario.launch(intent)) {
            // Wait for the screen's read-only project load before composing the test guide.
            boolean[] loaded={false};
            for(int i=0;i<100&&!loaded[0];i++) {
                scenario.onActivity(a->loaded[0]=field(a,"currentStep")!=null);
                if(!loaded[0])SystemClock.sleep(50);
            }
            assertTrue(loaded[0]);
            for(int step:new int[]{3,4}) {
                Phase7GuideController[] holder=new Phase7GuideController[1];
                String[] before=new String[1];Object[] video=new Object[1];
                try {
                    ui(scenario,activity->{
                        activity.setPhaseTwoGuideActive(true);
                        Phase7CasterTrackerFlow fixture=new Phase7CasterTrackerFlow(0);
                        fixture.devComplete();fixture.devJump(step,SystemClock.elapsedRealtimeNanos());
                        fixture.rescan(SystemClock.elapsedRealtimeNanos());fixture.previous(SystemClock.elapsedRealtimeNanos());
                        holder[0]=new Phase7GuideController(activity,true,()->fail("Review must not auto-advance"),()->{},fixture.snapshot(),()->{});
                        holder[0].start();assertTrue(holder[0].flow.viewingDemo());assertFalse(holder[0].flow.scanned());
                    });
                    ui(scenario,activity->{
                        assertTrue(card(activity).isShown());
                        assertEquals(View.VISIBLE,card(activity).rescan.getVisibility());
                        before[0]=holder[0].flow.snapshot();video[0]=field(holder[0],"video");card(activity).rescan.performClick();
                    });
                    ui(scenario,activity->{
                        assertTrue(dialog(holder[0]).isShowing());assertFalse(holder[0].scanning());
                        dialog(holder[0]).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                    });
                    ui(scenario,activity->{
                        assertEquals(before[0],holder[0].flow.snapshot());assertSame(video[0],field(holder[0],"video"));
                        card(activity).rescan.performClick();
                    });
                    ui(scenario,activity->dialog(holder[0]).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                    ui(scenario,activity->{
                        Phase7GuideController guide=holder[0];
                        assertEquals(step,guide.flow.step());assertTrue(guide.scanning());assertFalse(guide.flow.viewingDemo());assertNull(field(guide,"video"));
                        // A completed-phase look-back uses a separate, unsaved flow.
                        guide.startReview(true);
                        if(step==3){card(activity).previous.performClick();assertEquals(3,guide.flow.step());}
                        assertEquals(View.VISIBLE,card(activity).rescan.getVisibility());card(activity).rescan.performClick();
                    });
                    ui(scenario,activity->dialog(holder[0]).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                    ui(scenario,activity->{
                        Phase7GuideController guide=holder[0];
                        assertTrue(guide.scanning());assertEquals(step,guide.request(null).getPhase7Stage());
                        assertFalse(card(activity).next.isEnabled());assertEquals(View.VISIBLE,card(activity).returnToCurrent.getVisibility());
                        Object builder=call(RecognitionResult.class,"builder",new Class<?>[]{long.class},SystemClock.elapsedRealtimeNanos());
                        RecognitionResult result=(RecognitionResult)call(builder,"build",new Class<?>[0]);
                        result=(RecognitionResult)call(result,"withRequestTag",new Class<?>[]{Object.class},guide.flow.tag());
                        result=(RecognitionResult)call(result,"withPhase7Observation",new Class<?>[]{Phase7Observation.class},step==3?new Phase7Observation(1,false,50):Phase7Observation.installedTrackers(2,2,true,90));
                        guide.onResult(result);assertTrue(guide.flow.scanned());
                    });
                    ui(scenario,activity->{
                        AlertDialog check=dialog(holder[0]);assertTrue(check.isShowing());
                        check.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                        if(step==3)check.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                        assertTrue(holder[0].flow.complete());assertTrue(card(activity).next.isEnabled());
                        assertFalse((Boolean)field(holder[0],"finishing"));
                    });
                } finally {if(holder[0]!=null)ui(scenario,a->holder[0].close());}
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        assertEquals(checkpoint,PhaseProgressStore.casterTrackerState(context,NeobotSeedData.PROJECT_ID));
        assertEquals(session,context.getSharedPreferences("assembly_phase_progress",Context.MODE_PRIVATE).getAll());
    }
    private static Object field(Object object,String name){try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new AssertionError(e);}}
    private static AlertDialog dialog(Phase7GuideController guide){return (AlertDialog)field(guide,"dialog");}
    private static CompactGuideCard card(AssemblyActivity activity){return activity.findViewById(R.id.phaseTwoGuide);}
    private static void ui(ActivityScenario<AssemblyActivity> scenario,java.util.function.Consumer<AssemblyActivity> action){scenario.onActivity(action::accept);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private static Object call(Object object,String name,Class<?>[] types,Object... args){try{Class<?> type=object instanceof Class<?>?(Class<?>)object:object.getClass();java.lang.reflect.Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object instanceof Class<?>?null:object,args);}catch(Exception e){throw new AssertionError(e);}}
}
