package dev.chyken.assembl_ar.camera;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;

/** Reviewed production registrations. Manual/library pictures are never automatic training data. */
public final class RecognitionReferencePolicy {
    private RecognitionReferencePolicy() { }

    public static List<String> bundledAssetsFor(String componentId) {
        switch (componentId) {
            case NeobotSeedData.MAIN_BOARD:
                return Arrays.asList("references/assembly/image-001.png",
                        "references/assembly/image-034.png",
                        "references/variants/main-board-white-top.jpg",
                        "references/variants/main-board-white-underside.jpg");
            case NeobotSeedData.MOTOR_DRIVER:
                // The installed, canonical terminal view is needed for authored wiring checks.
                return Collections.singletonList("references/assembly/image-013.png");
            case NeobotSeedData.MICROCONTROLLER:
                return Collections.singletonList("references/assembly/image-024.png");
            case NeobotSeedData.OLED_DISPLAY:
                return Collections.singletonList("references/learning/image-019.png");
            case NeobotSeedData.IR_RECEIVER:
                return Collections.singletonList("references/learning/image-044.png");
            case NeobotSeedData.IR_TRANSMITTER:
                return Collections.singletonList("references/learning/image-042.png");
            default:
                // Wrong-object, occluded, annotated, empty and nonplanar contextual templates
                // were removed after visual review. See docs/RECOGNITION_REFERENCE_AUDIT.md.
                return Collections.emptyList();
        }
    }

}
