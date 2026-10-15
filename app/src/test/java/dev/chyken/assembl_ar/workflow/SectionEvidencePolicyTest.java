package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class SectionEvidencePolicyTest {
    @Test public void connectionSectionsUseActionChecksBeforeTheirPhysicalFinal() {
        List<AssemblyStep> sections = NeobotSeedData.getAssemblySteps();
        for (int number : Arrays.asList(4, 5, 7, 8, 15)) {
            AssemblyStep section = sections.get(number - 1);
            assertTrue(section.getTitle(), SectionEvidencePolicy.isPhysicalFinal(number));
            List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
            assertFalse(section.getTitle(), cards.isEmpty());
            for (int index = 0; index < cards.size() - 1; index++) {
                GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(section, cards.get(index));
                assertEquals(section.getTitle(), GuidedCheckpoint.Kind.PHYSICAL, checkpoint.getKind());
                assertFalse(section.getTitle(), checkpoint.requiresCamera());
                assertTrue(checkpoint.getPhysicalCheck().contains(
                        cards.get(index).getPrimaryInstruction().getText()));
            }
            assertNull(GuidedRecognitionCatalog.checkpointFor(section, cards.get(cards.size() - 1)));
        }
    }

    @Test public void visibleInstallationSectionsRetainTheirCameraPlacementGate() {
        for (int number : Arrays.asList(2, 3, 6, 9, 10, 11, 12, 13, 14, 16, 17, 18)) {
            assertFalse("Section " + number + " still requires visible installation evidence",
                    SectionEvidencePolicy.isPhysicalFinal(number));
        }
    }
}
