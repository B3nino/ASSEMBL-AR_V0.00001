package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.res.Configuration;
import android.view.*;
import android.widget.LinearLayout;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Layout regression: diagnostics and long controls must never consume the camera viewport. */
@RunWith(AndroidJUnit4.class)
public final class PartTestLayoutInstrumentedTest {
    @Test public void cameraKeepsItsOwnPaneAtTabletAndCompactSizes(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            for(int[] size:new int[][]{{1024,600},{640,360},{400,640}}){
                Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
                Configuration c=new Configuration(target.getResources().getConfiguration());
                c.orientation=size[0]>size[1]?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
                c.screenWidthDp=size[0];c.screenHeightDp=size[1];c.fontScale=1.3f;
                Context themed=new ContextThemeWrapper(target.createConfigurationContext(c),R.style.Theme_ASSEMBLAR);
                float density=themed.getResources().getDisplayMetrics().density;
                View root=LayoutInflater.from(themed).inflate(R.layout.activity_part_recognition_test,null,false);
                int w=Math.round(size[0]*density),h=Math.round(size[1]*density);
                root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
                root.layout(0,0,w,h);
                View camera=root.findViewById(R.id.partTestCameraPane),panes=root.findViewById(R.id.partTestPanes),controls=root.findViewById(R.id.partTestControls);
                assertTrue("Camera height at "+size[0]+"×"+size[1],camera.getHeight()>=panes.getHeight()*.59);
                assertTrue("Controls retain their own scroll viewport",controls.getHeight()>0);
                assertEquals(camera.getHeight(),root.findViewById(R.id.partTestPreview).getHeight());
                if(c.orientation==Configuration.ORIENTATION_LANDSCAPE){
                    assertEquals(LinearLayout.HORIZONTAL,((LinearLayout)panes).getOrientation());
                    assertEquals(panes.getHeight(),camera.getHeight());
                    assertTrue(camera.getWidth()>=panes.getWidth()*.61);
                }
            }
        });
    }
}
