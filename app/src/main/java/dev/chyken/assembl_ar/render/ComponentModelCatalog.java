package dev.chyken.assembl_ar.render;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.chyken.assembl_ar.data.NeobotSeedData;

/** Packaged standalone models keyed by the same component IDs as the library. */
public final class ComponentModelCatalog {
    public static final String SHORT_FASTENERS="preview_short_fasteners",LONG_FASTENERS="preview_long_fasteners";
    public static final String CASTER_FASTENERS="preview_caster_fasteners",TRACKER_FASTENERS="preview_tracker_fasteners";
    private static final Map<String, String> ASSETS;

    static {
        Map<String, String> assets = new LinkedHashMap<>();
        assets.put(NeobotSeedData.MAIN_BOARD, "models/main_board.obj");
        register(assets, NeobotSeedData.MOTOR_BRACKET);
        assets.put(NeobotSeedData.GEAR_MOTOR, "models/motor_ghost.obj");
        register(assets, NeobotSeedData.MOTOR_DRIVER);
        register(assets, NeobotSeedData.MICROCONTROLLER);
        assets.put(NeobotSeedData.CASTER_WHEEL, "models/caster_ghost.obj");
        register(assets, NeobotSeedData.LINE_TRACKER);
        register(assets, NeobotSeedData.DISTANCE_SENSOR);
        register(assets, NeobotSeedData.LED);
        register(assets, NeobotSeedData.SPEAKER);
        register(assets, NeobotSeedData.STANDOFF);
        assets.put(NeobotSeedData.WHEEL, "models/wheel_ghost.obj");
        register(assets, NeobotSeedData.OLED_PROTECTOR);
        register(assets, NeobotSeedData.DATA_CABLE);
        assets.put(NeobotSeedData.EXTENSION_BOARD, "models/extension_board.obj");
        register(assets, NeobotSeedData.OLED_DISPLAY);
        register(assets, NeobotSeedData.BATTERY_PACK);
        register(assets, NeobotSeedData.JUMPER_WIRE);
        register(assets, NeobotSeedData.MOMENTARY_BUTTON);
        register(assets, NeobotSeedData.IR_RECEIVER);
        register(assets, NeobotSeedData.IR_TRANSMITTER);
        register(assets, NeobotSeedData.CR2025_BATTERY);
        register(assets, NeobotSeedData.SCREW_30MM);
        register(assets, NeobotSeedData.SCREW_SHORT);
        register(assets, NeobotSeedData.SCREW_20MM);
        register(assets, NeobotSeedData.MOUNTING_SCREW);
        register(assets, NeobotSeedData.NUT);
        register(assets, NeobotSeedData.PLASTIC_SPACER);
        register(assets, NeobotSeedData.THREADLOCKER);
        register(assets, NeobotSeedData.NAIL_POLISH);
        register(assets, NeobotSeedData.DOUBLE_SIDED_TAPE);
        register(assets, NeobotSeedData.BATTERY_HOLDER);
        register(assets, NeobotSeedData.PHILLIPS_SCREWDRIVER);
        ASSETS = Collections.unmodifiableMap(assets);
    }

    private ComponentModelCatalog() {}

    private static void register(Map<String, String> assets, String componentId) {
        assets.put(componentId, "models/" + componentId + ".obj");
    }

    public static boolean hasModel(String componentId) {
        return isFastenerGroup(componentId)||ASSETS.containsKey(componentId);
    }
    public static boolean isFastenerGroup(String id){return SHORT_FASTENERS.equals(id)||LONG_FASTENERS.equals(id)||CASTER_FASTENERS.equals(id)||TRACKER_FASTENERS.equals(id);}

    @Nullable public static String assetPath(String componentId) {
        if(SHORT_FASTENERS.equals(componentId)||TRACKER_FASTENERS.equals(componentId))return ASSETS.get(NeobotSeedData.SCREW_SHORT);
        if(LONG_FASTENERS.equals(componentId))return ASSETS.get(NeobotSeedData.SCREW_30MM);
        if(CASTER_FASTENERS.equals(componentId))return ASSETS.get(NeobotSeedData.SCREW_20MM);
        return ASSETS.get(componentId);
    }

    public static Map<String, String> assets() {
        return ASSETS;
    }
}
