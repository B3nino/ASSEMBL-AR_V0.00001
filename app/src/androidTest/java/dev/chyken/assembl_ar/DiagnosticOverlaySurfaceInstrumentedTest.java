package dev.chyken.assembl_ar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.ui.DevDiagnosticsOverlayView;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises a real popup, including its shadow elevation and content bounds.
 * The camera-free host does not change assembly progress or reference files. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticOverlaySurfaceInstrumentedTest {
    @Test public void diagnosticPopupUsesZeroElevationAndKeepsOneBoundedSurface() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent=new Intent().setClassName(context,"dev.chyken.assembl_ar.PartRecognitionTestActivity")
                .putExtra("test_mode",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        DevDiagnosticsOverlayView[] overlay={null};PopupWindow[] first={null};
        try(ActivityScenario<Activity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(activity->{
                View previous=activity.findViewById(R.id.devDiagnosticsOverlay);
                if(previous!=null)previous.setVisibility(View.GONE);
                DevDiagnosticsOverlayView readout=new DevDiagnosticsOverlayView(activity,null);
                readout.setMaxWidth((int)(260*activity.getResources().getDisplayMetrics().density));
                readout.setMaxLines(3);readout.setText("Assigned kp: 2400\nLive kp: 1000\nLowe pairs: 26/2400");
                FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
                params.leftMargin=24;params.topMargin=120;
                ((ViewGroup)activity.findViewById(android.R.id.content)).addView(readout,params);overlay[0]=readout;
            });
            awaitPopup(overlay[0]);
            scenario.onActivity(activity->{
                DevDiagnosticsOverlayView readout=overlay[0];PopupWindow popup=popup(readout);first[0]=popup;
                assertTrue(popup.isShowing());assertEquals(0f,popup.getElevation(),0f);
                assertFalse(popup.isTouchable());assertFalse(popup.isFocusable());
                assertNull(readout.getOutlineProvider());assertTrue(readout.getElevation()>900);
                assertBoundedSurface(popup,activity);
                for(int i=0;i<1000;i++)readout.setText("Latest diagnostic "+i+"\nLive kp: 1000\nLowe pairs: 26/2400");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                PopupWindow popup=popup(overlay[0]);assertSame(first[0],popup);
                assertTrue(((TextView)popup.getContentView()).getText().toString().startsWith("Latest diagnostic 999"));
                assertEquals(3,((TextView)popup.getContentView()).getMaxLines());assertBoundedSurface(popup,activity);
                ((ViewGroup)overlay[0].getParent()).removeView(overlay[0]);
                assertNull(popup(overlay[0]));assertFalse(first[0].isShowing());
            });
        }
    }
    private static void awaitPopup(DevDiagnosticsOverlayView overlay){
        for(int i=0;i<30;i++){
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();boolean[] ready={false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ready[0]=popup(overlay)!=null&&popup(overlay).isShowing());
            if(ready[0])return;android.os.SystemClock.sleep(100);
        }
        throw new AssertionError("Diagnostic popup did not appear");
    }
    private static void assertBoundedSurface(PopupWindow popup,Activity activity){
        View root=popup.getContentView().getRootView();
        assertTrue(root.getWidth()<=activity.getResources().getDisplayMetrics().widthPixels);
        assertTrue(root.getHeight()<=activity.getResources().getDisplayMetrics().heightPixels);
    }
    private static PopupWindow popup(DevDiagnosticsOverlayView overlay){
        try{Field field=DevDiagnosticsOverlayView.class.getDeclaredField("window");field.setAccessible(true);return (PopupWindow)field.get(overlay);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
}
