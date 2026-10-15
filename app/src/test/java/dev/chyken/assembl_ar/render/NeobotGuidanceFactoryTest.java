package dev.chyken.assembl_ar.render;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Guards the manuscript's complete 18-step spatial-authoring table. */
public final class NeobotGuidanceFactoryTest {
    private static final Set<Integer> WIRING_STEPS = new HashSet<>(
            Arrays.asList(5, 7, 8, 11, 15, 17));

    @Test
    public void everyAssemblyStepHasActionableAuthoredGuidance() {
        float[] pose = identityPose();
        int authoredWiringSteps = 0;

        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            String componentId = step.getRequiredComponents().isEmpty()
                    ? NeobotSeedData.MAIN_BOARD
                    : step.getRequiredComponents().get(0).getComponentId();
            ArGuidance guidance = NeobotGuidanceFactory.create(step, componentId, pose);

            assertEquals(componentId, guidance.getComponentId());
            assertArrayEquals(pose, guidance.getOpenGlModelViewMatrix(), 0f);
            // Render coordinates share the ruler-measured pose plane, not the printed mat box.
            assertEquals(100f, guidance.getReferencePlaneWidthUnits(), 0f);
            assertEquals(120f, guidance.getReferencePlaneHeightUnits(), 0f);
            if (step.getStepNumber() == 2 || step.getStepNumber() == 10
                    || step.getStepNumber() == 13) {
                assertEquals("Step " + step.getStepNumber() + " needs both targets",
                        2, guidance.getComponentTargets().size());
            }
            assertFalse("Step " + step.getStepNumber() + " lacks a target marker",
                    guidance.getTargetMarker() == ArGuidance.TargetMarker.NONE);

            if (WIRING_STEPS.contains(step.getStepNumber())) {
                authoredWiringSteps++;
                assertNotNull("Step " + step.getStepNumber() + " source pin",
                        guidance.getSourcePin());
                assertNotNull("Step " + step.getStepNumber() + " target pin",
                        guidance.getTargetPin());
                assertTrue("Step " + step.getStepNumber() + " needs routed waypoints",
                        guidance.getConnectionPath().size() >= 2);
                assertEquals("Step " + step.getStepNumber() + " rendered route count",
                        step.getPinConnections().size(), guidance.getWireRoutes().size());
            } else {
                assertTrue("Step " + step.getStepNumber() + " lacks placement/fastener target",
                        guidance.getComponentTarget() != null || guidance.getTargetPin() != null);
            }
        }

        assertEquals(18, NeobotSeedData.getAssemblySteps().size());
        assertEquals(6, authoredWiringSteps);

        ArGuidance motors = NeobotGuidanceFactory.create(
                NeobotSeedData.getAssemblySteps().get(1),
                NeobotSeedData.GEAR_MOTOR, pose);
        assertEquals(90f, motors.getComponentTargetRotationZ(0), 0f);
        assertEquals(-90f, motors.getComponentTargetRotationZ(1), 0f);
    }

    @Test
    public void everySeededPinMappingHasAnIndependentDetailedRoute() {
        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            if (step.getPinConnections().isEmpty()) {
                continue;
            }
            ArGuidance guidance = NeobotGuidanceFactory.create(
                    step, step.getRequiredComponents().get(0).getComponentId(), identityPose());
            List<ArGuidance.WireRoute> routes = guidance.getWireRoutes();

            assertEquals("Step " + step.getStepNumber() + " must render every pin mapping",
                    step.getPinConnections().size(), routes.size());
            ArGuidance.Point3 previousSource = null;
            for (int index = 0; index < routes.size(); index++) {
                PinConnection mapping = step.getPinConnections().get(index);
                ArGuidance.WireRoute route = routes.get(index);
                assertEquals(mapping.getOrder(), route.getOrder());
                assertEquals(mapping.getSourceComponentId(), route.getSourceComponentId());
                assertEquals(mapping.getSourcePin(), route.getSourcePin());
                assertEquals(mapping.getTargetComponentId(), route.getTargetComponentId());
                assertEquals(mapping.getTargetPin(), route.getTargetPin());
                assertEquals(mapping.getWireColor(), route.getWireColor());
                assertEquals(mapping.getSignal(), route.getSignal());
                assertEquals(mapping.getNotes(), route.getNotes());
                assertTrue("Step " + step.getStepNumber() + " route " + route.getOrder()
                                + " needs a complete polyline",
                        route.getPoints().size() >= 2);
                if (previousSource != null) {
                    assertFalse("Step " + step.getStepNumber()
                                    + " routes must not collapse onto one bundle",
                            samePoint(previousSource, route.getSourcePoint()));
                }
                previousSource = route.getSourcePoint();
            }
        }
    }

    @Test
    public void casterAndDriveWheelsUseDedicatedGhostStyles() {
        ArGuidance caster = NeobotGuidanceFactory.create(
                NeobotSeedData.getAssemblySteps().get(8),
                NeobotSeedData.CASTER_WHEEL, identityPose());
        ArGuidance wheels = NeobotGuidanceFactory.create(
                NeobotSeedData.getAssemblySteps().get(12),
                NeobotSeedData.WHEEL, identityPose());

        assertEquals(ArGuidance.GhostStyle.CASTER, caster.getGhostStyle());
        assertEquals(ArGuidance.GhostStyle.WHEEL, wheels.getGhostStyle());
        assertFalse(caster.getGhostStyle() == ArGuidance.GhostStyle.FASTENER);
        assertFalse(wheels.getGhostStyle() == ArGuidance.GhostStyle.FASTENER);
    }

    @Test(expected = IllegalArgumentException.class)
    public void factoryRejectsStepsOutsideTheNeobotSequence() {
        AssemblyStep first = NeobotSeedData.getAssemblySteps().get(0);
        AssemblyStep invalid = new AssemblyStep(
                first.getId(), first.getProjectId(), 19, first.getTitle(), first.getSummary(),
                first.getValidationType(), first.getValidationPrompt(), first.getManualReference(),
                first.getInstructions(), first.getRequiredComponents(), first.getPinConnections(),
                first.getOverlayTypes());
        NeobotGuidanceFactory.create(invalid, NeobotSeedData.MAIN_BOARD, identityPose());
    }

    @Test
    public void renderedPlacementCentersMatchTheEnforcedChassisTargets() {
        List<VisionPoint> unitAnchor = Arrays.asList(
                new VisionPoint(0.0, 0.0),
                new VisionPoint(1.0, 0.0),
                new VisionPoint(1.0, 1.0),
                new VisionPoint(0.0, 1.0));
        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            ArGuidance guidance = NeobotGuidanceFactory.create(
                    step, NeobotSeedData.MAIN_BOARD, identityPose());
            List<ChassisRelativePlacementGate.TargetProjection> enforced =
                    ChassisRelativePlacementGate.forStep(step).projectTargets(unitAnchor);

            assertEquals("Step " + step.getStepNumber() + " target count",
                    enforced.size(), guidance.getComponentTargets().size());
            for (int index = 0; index < enforced.size(); index++) {
                ArGuidance.Point3 rendered = guidance.getComponentTargets().get(index);
                assertEquals("Step " + step.getStepNumber() + " target X",
                        enforced.get(index).getCenter().getX(),
                        (rendered.x + 1.0) * 0.5, 0.0001);
                assertEquals("Step " + step.getStepNumber() + " target Y",
                        enforced.get(index).getCenter().getY(),
                        (1.0 - rendered.y) * 0.5, 0.0001);
            }
        }
    }

    private static float[] identityPose() {
        return new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, -3f, 1f
        };
    }

    private static boolean samePoint(ArGuidance.Point3 first, ArGuidance.Point3 second) {
        return Float.compare(first.x, second.x) == 0
                && Float.compare(first.y, second.y) == 0
                && Float.compare(first.z, second.z) == 0;
    }
}
