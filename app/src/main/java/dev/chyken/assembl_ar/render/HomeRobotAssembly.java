package dev.chyken.assembl_ar.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

/**
 * Installed kit instances, in the board frame: +Y is the front/LED end, +Z is up.
 * Display units and placements are nominal, not recognition or fabrication calibration.
 * Reuses the library meshes; repeated hardware shares a single decoded asset.
 */
final class HomeRobotAssembly {
    static final float UPPER_DECK = 0.76f;
    static final String WIRING_ASSET = "models/home_robot_wiring.obj";
    static final List<Part> PARTS = createParts();

    private HomeRobotAssembly() {}

    static final class Part {
        final String componentId;
        final String assetPath;
        final float x, y, z, rotateX, rotateY, rotateZ, scale;

        Part(String id, float x, float y, float z, float rx, float ry, float rz, float scale) {
            componentId = id;
            assetPath = ComponentModelCatalog.assetPath(id);
            if (assetPath == null) throw new IllegalArgumentException("Missing assembled part " + id);
            this.x = x; this.y = y; this.z = z;
            rotateX = rx; rotateY = ry; rotateZ = rz; this.scale = scale;
        }
    }

    private static List<Part> createParts() {
        List<Part> parts = new ArrayList<>();
        add(parts, MAIN_BOARD, 0f, 0f, 0f, 0f, 0f, 0f, 1.25f);
        add(parts, EXTENSION_BOARD, 0f, 0f, UPPER_DECK, 0f, 0f, 0f, 1.25f);

        // Both motor cans point toward the front. Their dual shafts meet the rear wheels.
        // The four T brackets rise from the main PCB to support the extension PCB.
        for (float side : new float[]{-1f, 1f}) {
            add(parts, GEAR_MOTOR, side * 0.84f, -0.68f, 0.245f, 0f, 0f, 180f, 0.50f);
            add(parts, WHEEL, side * 1.48f, -0.68f, 0.245f, 0f, side * 90f, 0f, 0.65f);
            add(parts, SCREW_SHORT, side * 1.74f, -0.68f, 0.245f,
                    0f, side * 90f, 0f, 0.065f);
            for (float x : new float[]{0.62f, 1.06f}) {
                add(parts, MOTOR_BRACKET, side * x, -0.60f, 0.345f,
                        90f, 0f, 90f, 0.40f);
            }
            for (float y : new float[]{-0.66f, -0.39f}) {
                add(parts, SCREW_30MM, side * 0.84f, y, 0.22f,
                        0f, side * 90f, 0f, 0.275f);
                add(parts, NUT, side * 0.58f, y, 0.22f, 0f, 90f, 0f, 0.055f);
            }
        }

        add(parts, CASTER_WHEEL, 0f, 0.76f, -0.02f, 0f, 0f, 0f, 0.50f);
        for (float x : new float[]{-0.40f, 0.40f}) {
            add(parts, MOUNTING_SCREW, x, 0.76f, -0.05f, 0f, 0f, 0f, 0.10f);
        }
        // One center standoff, including the top screw already authored in its mesh.
        add(parts, STANDOFF, 0.015f, 0.025f, 0.335f, 0f, 0f, 0f, 0.435f);

        // Lower-deck electronics: Nano seats in the photographed socket rows.
        add(parts, MICROCONTROLLER, 0.037f, -0.59f, 0.155f, 0f, 0f, 0f, 0.405f);
        add(parts, MOTOR_DRIVER, 0.01f, 0.43f, 0.145f, 0f, 0f, 0f, 0.30f);
        for (float x : new float[]{-0.195f, 0.215f}) {
            add(parts, SCREW_SHORT, x, 0.45f, 0.055f, 0f, 0f, 0f, 0.07f);
        }
        add(parts, DISTANCE_SENSOR, 0.03f, 1.14f, 0.22f, -90f, 0f, 0f, 0.50f);
        for (float x : new float[]{-0.41444f, 0.47444f}) {
            add(parts, PLASTIC_SPACER, x, 0.96f, 0.05111f, -90f, 0f, 0f, 0.055f);
            add(parts, MOUNTING_SCREW, x, 0.98f, 0.05111f, -90f, 0f, 0f, 0.11f);
        }
        for (float x : new float[]{-0.78f, 0.78f}) {
            // Optical domes face the floor, with sensing ends toward the front edge.
            add(parts, LINE_TRACKER, x, 0.66f, -0.20f, 0f, 180f, 0f, 0.36f);
            add(parts, PLASTIC_SPACER, x, 0.747f, -0.085f, 0f, 0f, 0f, 0.07f);
            add(parts, SCREW_20MM, x, 0.747f, -0.10f, 0f, 0f, 0f, 0.19f);
        }

        // OLED and battery sit above the extension PCB; its five switches, the lower
        // LED and sounder are already built into the two populated board assets.
        add(parts, OLED_PROTECTOR, 0.01f, -0.49f, UPPER_DECK + 0.125f,
                0f, 0f, 0f, 0.34f);
        add(parts, OLED_DISPLAY, 0.01f, -0.49f, UPPER_DECK + 0.15f,
                0f, 0f, 0f, 0.26f);
        add(parts, BATTERY_PACK, 0.64f, -0.58f, UPPER_DECK + 0.19f,
                0f, 0f, 0f, 0.58f);
        return Collections.unmodifiableList(parts);
    }

    private static void add(List<Part> parts, String id, float x, float y, float z,
                            float rx, float ry, float rz, float scale) {
        parts.add(new Part(id, x, y, z, rx, ry, rz, scale));
    }
}
