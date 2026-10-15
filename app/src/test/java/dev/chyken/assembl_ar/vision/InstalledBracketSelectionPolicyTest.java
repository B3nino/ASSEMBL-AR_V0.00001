package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import org.opencv.core.Mat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Selection policy only: candidates represent observations already accepted by the matcher. */
public final class InstalledBracketSelectionPolicyTest {
    private static final List<VisionPoint> BOARD = Arrays.asList(
            new VisionPoint(120, 70), new VisionPoint(1040, 110),
            new VisionPoint(940, 950), new VisionPoint(160, 900));

    @Test
    public void everySlotPrefersAnAcceptedBracketThereOverAStrongerBracketElsewhere()
            throws Exception {
        for (int slot = 1; slot <= 4; slot++) {
            int otherSlot = slot == 4 ? 1 : slot + 1;
            List<VisionPoint> required = target(slot);
            List<VisionPoint> elsewhere = target(otherSlot);
            ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forMotorBracketSlot(slot);
            assertTrue(gate.evaluateSingle(BOARD, required).isAccepted());
            assertFalse(gate.evaluateSingle(BOARD, elsewhere).isAccepted());
            Object strongerWrongSlot = candidate("stronger_elsewhere", elsewhere, 10_000);
            Object weakerRequiredSlot = candidate("weaker_required", required, 5_000);
            assertSame(weakerRequiredSlot, select(
                    Arrays.asList(strongerWrongSlot, weakerRequiredSlot), request(slot)));
            assertSame(weakerRequiredSlot, select(
                    Arrays.asList(weakerRequiredSlot, strongerWrongSlot), request(slot)));
        }
    }

    @Test
    public void missingRequiredSlotPreservesObservedMismatchWithoutInventingADetection()
            throws Exception {
        for (int slot = 1; slot <= 4; slot++) {
            List<VisionPoint> elsewhere = target(slot == 4 ? 1 : slot + 1);
            Object observed = candidate("observed_elsewhere", elsewhere, 10_000);
            assertSame(observed, select(Collections.singletonList(observed), request(slot)));
            assertFalse(ChassisRelativePlacementGate.forMotorBracketSlot(slot)
                    .evaluateSingle(BOARD, elsewhere).isAccepted());
            assertNull(select(Collections.emptyList(), request(slot)));
        }
    }

    @Test
    public void noWorkplaneRetainsOrdinaryObservedEvidenceRanking() throws Exception {
        Object stronger = candidate("stronger_elsewhere", target(4), 10_000);
        Object weaker = candidate("weaker_required", target(1), 5_000);
        RecognitionRequest unanchored = baseRequest().withGuidedBracketSlot(1);
        assertSame(stronger, select(Arrays.asList(stronger, weaker), unanchored));
    }

    @Test
    public void workplaneCopyOwnsSnapshotAndRetainsRequestContext() {
        RecognitionRequest original = baseRequest().withGuidedBracketSlot(3);
        List<VisionPoint> mutable = new ArrayList<>(BOARD);
        RecognitionRequest copy = original.withGuidedBracketWorkplane(mutable);
        mutable.clear();
        assertTrue(original.getGuidedBracketWorkplane().isEmpty());
        assertEquals(BOARD, copy.getGuidedBracketWorkplane());
        assertEquals(3, copy.getGuidedBracketSlot());
        assertSame(original.getExpectation(), copy.getExpectation());
        assertSame(original.getCameraCalibration(), copy.getCameraCalibration());
        assertSame(original.getPolarityObservation(), copy.getPolarityObservation());
        assertEquals(original.getSpatialAnchorComponentId(), copy.getSpatialAnchorComponentId());
        assertEquals(original.getExpectedInstanceCount(), copy.getExpectedInstanceCount());
        assertEquals(original.getAssemblyStepNumber(), copy.getAssemblyStepNumber());
        assertEquals(original.getMinimumCornerAreaRatioOverride(), copy.getMinimumCornerAreaRatioOverride());
        assertEquals(original.isDescriptorOnlyIdentityAllowed(), copy.isDescriptorOnlyIdentityAllowed());
        try {
            copy.getGuidedBracketWorkplane().clear();
            fail("Workplane snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static RecognitionRequest baseRequest() {
        return new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component("motor_bracket"),
                CameraCalibration.pinhole(1200, 1200, 600, 500), null,
                "main_board", 1, 2, 0.003, true);
    }

    private static RecognitionRequest request(int slot) {
        return baseRequest().withGuidedBracketSlot(slot).withGuidedBracketWorkplane(BOARD);
    }

    private static List<VisionPoint> target(int slot) {
        return ChassisRelativePlacementGate.forMotorBracketSlot(slot)
                .projectTargets(BOARD).get(0).getCorners();
    }

    /** Reflection keeps internal candidate types private without adding a production test API. */
    private static Object candidate(String id, List<VisionPoint> corners, double rankingScore)
            throws Exception {
        ComponentReferenceSpec spec = ComponentReferenceSpec.builder(
                id, "motor_bracket", "Installed bracket", 22, 40)
                .jpegAssetPath("synthetic/" + id + ".png")
                .assemblyStepNumber(2).installedBracketView(true).build();
        ReferenceDatabase.PreparedReference reference = new ReferenceDatabase.PreparedReference(
                spec, 100, 100, Collections.emptyList());
        Class<?> candidateClass = Class.forName(ComponentRecognizer.class.getName() + "$SpatialCandidate");
        Constructor<?> constructor = candidateClass.getDeclaredConstructor(
                ReferenceDatabase.PreparedReference.class, Mat.class, List.class,
                int.class, int.class, double.class, double.class, double.class, List.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(reference, null, corners, 100, 50, 0.5, 0.9,
                rankingScore, Collections.emptyList(), Collections.emptyList());
    }

    private static Object select(List<Object> candidates, RecognitionRequest request) throws Exception {
        Method method = ComponentRecognizer.class.getDeclaredMethod(
                "selectPrimaryCandidate", List.class, RecognitionRequest.class);
        method.setAccessible(true);
        return method.invoke(null, candidates, request);
    }
}
