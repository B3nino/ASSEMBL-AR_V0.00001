package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.junit.Test;
import org.opencv.core.Rect;
import static org.junit.Assert.*;

public class TrainedHintBoundsTest {
    @Test public void emptyAndOffscreenProposalsHaveNoSearchWindows(){
        assertTrue(new TrainedModelAssist.Hints(640,400,1,1,Collections.emptyList()).windows(640,400).isEmpty());
        assertTrue(new TrainedModelAssist.Hints(640,400,1,1,Collections.singletonList(new Rect(-200,-200,10,10))).windows(640,400).isEmpty());
    }
    @Test public void proposalOwnershipAndInverseScalingDoNotDependOnMutableCallerRects(){
        Rect source=new Rect(1400,500,240,180);
        TrainedModelAssist.Hints hints=new TrainedModelAssist.Hints(1920,1080,1,5,Collections.singletonList(source));
        source.x=0;source.width=1920;
        Rect result=hints.windows(640,360).get(0);
        assertTrue(result.x>400);assertTrue(result.width<150);
        result.x=0;
        assertTrue(hints.windows(640,360).get(0).x>400);
    }
    @Test public void paddingIsClippedToTheCurrentFeatureImage(){
        TrainedModelAssist.Hints hints=new TrainedModelAssist.Hints(640,400,1,5,Collections.singletonList(new Rect(0,0,100,100)));
        Rect result=hints.windows(320,200).get(0);
        assertEquals(0,result.x);assertEquals(0,result.y);assertTrue(result.width<=320);assertTrue(result.height<=200);
    }
}
