package dev.chyken.assembl_ar.camera;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

/** Original camera captures and documented resized views; generated artwork is not evidence. */
public final class OriginalBoardReferenceCatalog {
    private OriginalBoardReferenceCatalog() { }

    public static List<ComponentReferenceSpec> forComponent(Component component) {
        if (!NeobotSeedData.MAIN_BOARD.equals(component.getId())) return Collections.emptyList();
        ComponentReferenceSpec bare = base(component, "bare-20260906_115902")
                .planarReferenceCornersNormalized(new VisionPoint(.111, .159),
                        new VisionPoint(.900, .159), new VisionPoint(.910, .879),
                        new VisionPoint(.105, .879))
                .featureRegionsNormalized(Arrays.asList(
                        rect(.165, .210, .300, .260), rect(.710, .210, .825, .252),
                        rect(.411, .322, .599, .495), rect(.390, .568, .607, .607),
                        rect(.130, .743, .287, .822), rect(.719, .692, .864, .815)))
                .build();
        ComponentReferenceSpec installed = base(component, "motors-20260906_124302")
                .assemblyStepNumber(2)
                .planarReferenceCornersNormalized(new VisionPoint(.194, .200),
                        new VisionPoint(.866, .211), new VisionPoint(.867, .830),
                        new VisionPoint(.164, .819))
                .featureRegionsNormalized(Arrays.asList(
                        rect(.345, .252, .430, .289), rect(.639, .278, .718, .321),
                        rect(.447, .340, .608, .491), rect(.397, .548, .626, .588),
                        rect(.388, .600, .430, .739), rect(.612, .596, .656, .740),
                        rect(.338, .754, .395, .804), rect(.610, .744, .667, .792)))
                .build();
        ComponentReferenceSpec oblique = base(component, "motors-oblique-124312")
                .assemblyStepNumber(2)
                .planarReferenceCornersNormalized(new VisionPoint(.238, .257),
                        new VisionPoint(.755, .259), new VisionPoint(.923, .698),
                        new VisionPoint(.085, .698))
                .featureRegionsNormalized(Arrays.asList(
                        rect(.451, .333, .545, .366), rect(.430, .373, .528, .420),
                        rect(.453, .457, .531, .480), rect(.374, .519, .414, .654),
                        rect(.596, .530, .629, .647), rect(.471, .664, .546, .689),
                        rect(.152, .608, .221, .640)))
                .build();
        ComponentReferenceSpec left = base(component, "motors-left-124307").assemblyStepNumber(2)
                .planarReferenceCornersNormalized(new VisionPoint(.276,.312),new VisionPoint(.753,.180),
                        new VisionPoint(.800,.879),new VisionPoint(.244,.813))
                .featureRegionsNormalized(Arrays.asList(
                        rect(.432,.382,.513,.431),rect(.430,.452,.523,.512),
                        rect(.591,.241,.675,.280),rect(.299,.325,.354,.366),
                        rect(.697,.843,.764,.861),rect(.294,.794,.313,.814),
                        Arrays.asList(new VisionPoint(.445,.609),new VisionPoint(.469,.615),
                                new VisionPoint(.501,.804),new VisionPoint(.471,.800))))
                .build();
        ComponentReferenceSpec right = base(component, "motors-right-124316").assemblyStepNumber(2)
                .planarReferenceCornersNormalized(new VisionPoint(.287,.296),new VisionPoint(.756,.387),
                        new VisionPoint(.857,.897),new VisionPoint(.246,.914))
                .featureRegionsNormalized(Arrays.asList(
                        rect(.294,.303,.337,.325),rect(.340,.348,.404,.380),
                        rect(.516,.450,.606,.580),rect(.583,.700,.610,.865),
                        rect(.581,.874,.627,.890),rect(.679,.828,.717,.849),
                        rect(.674,.409,.724,.438)))
                .build();
        return Arrays.asList(bare, installed, oblique, left, right);
    }

    private static ComponentReferenceSpec.Builder base(Component component, String name) {
        return ComponentReferenceSpec.builder("main_board_original_" + name,
                        component.getId(), component.getName(), 100.0, 120.0)
                .jpegAssetPath("references/assembly-captures/main-board-" + name + ".jpg")
                .maximumImageDimension(1280).polarityAnchorsVerified(false);
    }

    private static List<VisionPoint> rect(double l, double t, double r, double b) {
        return Arrays.asList(new VisionPoint(l,t), new VisionPoint(r,t),
                new VisionPoint(r,b), new VisionPoint(l,b));
    }
}
