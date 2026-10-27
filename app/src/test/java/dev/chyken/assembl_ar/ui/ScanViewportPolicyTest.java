package dev.chyken.assembl_ar.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public class ScanViewportPolicyTest {
    @Test public void phonePortraitAndLandscapeUseTheSameCompactPolicy(){
        assertTrue(ScanViewportPolicy.compact(360,780,1));assertTrue(ScanViewportPolicy.compact(780,360,1));
        assertTrue(ScanViewportPolicy.compact(1080,2340,3));assertFalse(ScanViewportPolicy.compact(0,0,1));
    }
    @Test public void tabletBreakpointAndLargerWindowsKeepTheirExistingLayout(){
        assertFalse(ScanViewportPolicy.compact(600,960,1));assertFalse(ScanViewportPolicy.compact(960,600,1));
        assertFalse(ScanViewportPolicy.compact(2000,1248,1.5f));assertFalse(ScanViewportPolicy.compact(1280,800,1));
    }
    @Test public void landscapeRailLeavesAtLeastSixtyPercentOfWidthForTheCamera(){
        for(int width:new int[]{568,640,780,900})assertTrue(ScanViewportPolicy.phoneRailWidth(width,1)<=width*.4f+1);
    }
    @Test public void secondaryTesterToolsHaveABoundedDrawer(){
        for(int height:new int[]{320,568,780,900})assertTrue(ScanViewportPolicy.phoneDrawerHeight(height,1)<=height*.34f+1);
        assertEquals(260,ScanViewportPolicy.phoneDrawerHeight(1000,1));
    }
}
