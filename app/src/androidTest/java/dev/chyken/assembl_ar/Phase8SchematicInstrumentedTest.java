package dev.chyken.assembl_ar;

import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import dev.chyken.assembl_ar.model.ComponentWiringColorConfig;
import dev.chyken.assembl_ar.ui.AssemblyDemoView;
import static org.junit.Assert.*;

/** Actual Android Canvas smoke test, runnable from Android Studio without touching saved progress. */
@RunWith(AndroidJUnit4.class)
public final class Phase8SchematicInstrumentedTest {
    @Test public void allThreeDiagramsRenderPackagedArtAtTabletAndCompactSizes(){
        AssemblyDemoView[] holder=new AssemblyDemoView[1];InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{holder[0]=new AssemblyDemoView(new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),R.style.Theme_ASSEMBLAR));holder[0].setComponentWireColors(ComponentWiringColorConfig.defaults(),1);});
        long end=SystemClock.elapsedRealtime()+10000;boolean[] ready={false};while(SystemClock.elapsedRealtime()<end){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ready[0]=holder[0].isComponentArtworkReady());if(ready[0])break;SystemClock.sleep(30);}assertTrue("All bundled component images must decode",ready[0]);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AssemblyDemoView v=holder[0];for(int section=1;section<=3;section++)for(int[] size:new int[][]{{1280,900},{600,480}}){v.setComponentWireColors(ComponentWiringColorConfig.defaults(),section);v.layout(0,0,size[0],size[1]);v.pause();Bitmap image=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);v.draw(new Canvas(image));int changed=0,bg=image.getPixel(10,10);for(int y=size[1]/4;y<size[1]*3/4;y+=5)for(int x=15;x<size[0]-15;x+=5)if(image.getPixel(x,y)!=bg)changed++;assertTrue("Diagram must include components and connections",changed>100);assertTrue(v.getContentDescription().toString().contains("Sensor wiring"));image.recycle();}v.pause();});
    }
}
