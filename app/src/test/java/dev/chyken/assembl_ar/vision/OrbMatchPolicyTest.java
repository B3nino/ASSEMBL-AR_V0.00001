package dev.chyken.assembl_ar.vision;
import org.junit.Test;import static org.junit.Assert.*;
public final class OrbMatchPolicyTest {
 @Test(expected=IllegalArgumentException.class) public void configurationCannotSilentlyOverrideTheGlobalRule(){VisionConfig.builder().matching(.8f,64,12);}
 @Test public void strictRatioAndInclusiveHammingLimit(){assertTrue(OrbMatchPolicy.accepts(64,100));assertFalse(OrbMatchPolicy.accepts(65,100));assertFalse(OrbMatchPolicy.accepts(60,80));assertTrue(OrbMatchPolicy.accepts(59,80));}
 @Test public void ambiguousAndMissingNeighborsNeverPass(){assertFalse(OrbMatchPolicy.accepts(0,0));assertFalse(OrbMatchPolicy.accepts(40,40));assertFalse(OrbMatchPolicy.accepts(-1,20));assertFalse(OrbMatchPolicy.accepts(20,Double.NaN));}
}
