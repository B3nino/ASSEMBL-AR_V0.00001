package dev.chyken.assembl_ar.camera;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

/** Historical rejected screenshot registration; retained only for catalog exclusion tests. */
public final class AssemblyStateReferenceCatalog {
    private AssemblyStateReferenceCatalog() { }

    public static List<ComponentReferenceSpec> forComponent(Component component) {
        if (!NeobotSeedData.MAIN_BOARD.equals(component.getId())) return Collections.emptyList();
        // The old mask could not isolate all composited AR pixels. Production now uses raw
        // camera captures through OriginalBoardReferenceCatalog, never this screenshot.
        ComponentReferenceSpec reference = ComponentReferenceSpec.builder(
                "main_board_assembly_step_2_white", NeobotSeedData.MAIN_BOARD,
                component.getName(), 100.0, 120.0)
                .jpegAssetPath("references/assembly-state/main-board-white-motors.jpg")
                .maximumImageDimension(1280)
                .assemblyStepNumber(2)
                .polarityAnchorsVerified(false)
                .planarReferenceCornersNormalized(
                        new VisionPoint(.182576, .199667), new VisionPoint(.822057, .183827),
                        new VisionPoint(.869323, .540642), new VisionPoint(.229842, .556899))
                .featureRegionsNormalized(Arrays.asList(
                        polygon(.344477,.209987, .645043,.202534, .650715,.245363, .350148,.252839),
                        polygon(.394208,.294735, .630819,.288831, .641690,.370927, .405079,.376867),
                        polygon(.415258,.405275, .651863,.399323, .667935,.520689, .431329,.526694)))
                .build();
        return Collections.singletonList(reference);
    }

    private static List<VisionPoint> polygon(double... values) {
        return Arrays.asList(new VisionPoint(values[0], values[1]),
                new VisionPoint(values[2], values[3]), new VisionPoint(values[4], values[5]),
                new VisionPoint(values[6], values[7]));
    }
}
