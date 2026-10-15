package dev.chyken.assembl_ar.vision;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import dev.chyken.assembl_ar.data.NeobotSeedData;

/** Assembly-only mesh evidence. Does not turn synthesized renders into photographic references. */
public final class ModelRecognitionCatalog {
    private static final Set<String> IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            NeobotSeedData.LINE_TRACKER, NeobotSeedData.DISTANCE_SENSOR, NeobotSeedData.STANDOFF,
            NeobotSeedData.OLED_PROTECTOR, NeobotSeedData.WHEEL)));
    private ModelRecognitionCatalog() { }
    public static boolean supports(String componentId) { return IDS.contains(componentId); }
    public static Set<String> supportedComponentIds() { return IDS; }
    public static String displayName(String id) {
        if (NeobotSeedData.LINE_TRACKER.equals(id)) return "Line tracker";
        if (NeobotSeedData.DISTANCE_SENSOR.equals(id)) return "Ultrasonic distance sensor";
        if (NeobotSeedData.STANDOFF.equals(id)) return "Center standoff";
        if (NeobotSeedData.OLED_PROTECTOR.equals(id)) return "OLED protector";
        if (NeobotSeedData.WHEEL.equals(id)) return "Drive wheel";
        throw new IllegalArgumentException("No mesh evidence for " + id);
    }
    public static double[] nominalFaceDimensions(String id) {
        if (NeobotSeedData.LINE_TRACKER.equals(id)) return new double[]{14, 42};
        if (NeobotSeedData.DISTANCE_SENSOR.equals(id)) return new double[]{45, 20};
        if (NeobotSeedData.STANDOFF.equals(id)) return new double[]{8, 8};
        if (NeobotSeedData.OLED_PROTECTOR.equals(id)) return new double[]{40, 32};
        if (NeobotSeedData.WHEEL.equals(id)) return new double[]{65, 65};
        throw new IllegalArgumentException("No mesh evidence for " + id);
    }
}
