package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReferenceCacheFileNameTest {
    @Test public void longSourcePathsLeaveSpaceForSidecarsAndAtomicWrites() {
        String original="p7scene_trackerwork_references_Phase7_STEP7_4DUALTRACKER_SCANS_VALIDS_"
                +String.join("",java.util.Collections.nCopies(16,"lighting-angle-"))+".jpg_prep2_sampling5.asor";
        assertTrue(original.length()>255);
        String name=ReferenceCacheFileName.bounded(original);
        assertTrue(name.endsWith(".asor"));
        assertTrue((name+".photo-geometry1.tmp").length()<255);
        assertEquals(name,ReferenceCacheFileName.bounded(original));
        assertNotEquals(name,ReferenceCacheFileName.bounded(original+"different-mask"));
    }
    @Test public void existingShortNamesRemainReusable() {
        assertEquals("mainboard_prep2_sampling5.asor",ReferenceCacheFileName.bounded("mainboard_prep2_sampling5.asor"));
    }
    @Test public void digestIncludesTheWholeNameNotJustItsTruncatedPrefix() {
        String prefix=String.join("",java.util.Collections.nCopies(200,"x"));
        assertNotEquals(ReferenceCacheFileName.bounded(prefix+"_roi1.asor"),ReferenceCacheFileName.bounded(prefix+"_roi2.asor"));
    }
}
