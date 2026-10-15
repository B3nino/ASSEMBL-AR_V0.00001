package dev.chyken.assembl_ar.render;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static dev.chyken.assembl_ar.data.NeobotSeedData.*;
import static org.junit.Assert.*;

/** Verifies the installed kit inventory and real asset geometry used by the home view. */
public final class HomeRobotAssemblyTest {
    private static final File ASSETS = new File("src/main/assets");
    private static final double CAMERA_TARGET_HEIGHT = 0.35;

    @Test public void installedAssemblyContainsTheManualsMainPartsAndRepeatedHardware() {
        Map<String, Integer> counts = new HashMap<>();
        for (HomeRobotAssembly.Part part : HomeRobotAssembly.PARTS) {
            counts.merge(part.componentId, 1, Integer::sum);
        }
        for (String id : new String[]{MAIN_BOARD, EXTENSION_BOARD, MOTOR_DRIVER,
                MICROCONTROLLER, CASTER_WHEEL, DISTANCE_SENSOR, STANDOFF,
                OLED_DISPLAY, OLED_PROTECTOR, BATTERY_PACK}) {
            assertEquals(id, Integer.valueOf(1), counts.get(id));
        }
        for (String id : new String[]{GEAR_MOTOR, WHEEL, LINE_TRACKER, SCREW_20MM}) {
            assertEquals(id, Integer.valueOf(2), counts.get(id));
        }
        for (String id : new String[]{MOTOR_BRACKET, SCREW_30MM, SCREW_SHORT,
                MOUNTING_SCREW, NUT, PLASTIC_SPACER}) {
            assertEquals(id, Integer.valueOf(4), counts.get(id));
        }
    }

    @Test public void realInstalledMeshesAndWiringHaveMaterialsAndFitTheDefaultOrbit() {
        Map<String, ObjModel> meshes = new HashMap<>();
        double enclosingRadius = 0;
        for (HomeRobotAssembly.Part part : HomeRobotAssembly.PARTS) {
            assertTrue(part.componentId + " scale", Float.isFinite(part.scale) && part.scale > 0f);
            ObjModel mesh = meshes.computeIfAbsent(part.assetPath, HomeRobotAssemblyTest::loadMesh);
            FloatBuffer vertices = mesh.vertices();
            for (int offset = 0; offset < vertices.limit(); offset += 3) {
                double[] point = {
                        vertices.get(offset) * part.scale,
                        vertices.get(offset + 1) * part.scale,
                        vertices.get(offset + 2) * part.scale};
                // Renderer applies local X, then Y, then Z rotation before placement.
                rotate(point, 1, 2, part.rotateX);
                rotate(point, 2, 0, part.rotateY);
                rotate(point, 0, 1, part.rotateZ);
                point[0] += part.x;
                point[1] += part.y;
                point[2] += part.z;
                enclosingRadius = Math.max(enclosingRadius, radiusFromCameraTarget(point));
            }
        }
        ObjModel wiring = loadMesh(HomeRobotAssembly.WIRING_ASSET);
        FloatBuffer vertices = wiring.vertices();
        for (int offset = 0; offset < vertices.limit(); offset += 3) {
            enclosingRadius = Math.max(enclosingRadius, radiusFromCameraTarget(new double[]{
                    vertices.get(offset), vertices.get(offset + 1), vertices.get(offset + 2)}));
        }
        assertTrue("Assembly should have the full robot's scale", enclosingRadius > 1.5);
        for (float aspect : new float[]{1f, 0.5f, 1.6f}) {
            double verticalHalf = Math.toRadians(
                    HomeRobotRenderer.calculateVerticalFieldOfViewDegrees(aspect) * 0.5);
            double horizontalHalf = Math.atan(aspect * Math.tan(verticalHalf));
            double frustumRadius = OrbitController.DEFAULT_DISTANCE
                    * Math.sin(Math.min(verticalHalf, horizontalHalf));
            // A sphere centered on the orbit target fits at every yaw and pitch, including
            // underside views. This catches oversized or misplaced parts and narrow crops.
            assertTrue("Robot radius " + enclosingRadius + " exceeds camera fit "
                    + frustumRadius + " at aspect " + aspect, enclosingRadius < frustumRadius);
        }
    }

    private static ObjModel loadMesh(String path) {
        ObjModel mesh = ObjModel.load(asset -> new FileInputStream(new File(ASSETS, asset)), path);
        assertNotNull("Cannot load " + path, mesh);
        assertTrue("Missing geometry in " + path, mesh.vertexCount() >= 12);
        assertTrue("Invalid bounds in " + path,
                Float.isFinite(mesh.boundingRadius()) && mesh.boundingRadius() > 0f);
        boolean authoredMaterial = false;
        for (ObjModel.Batch batch : mesh.batches()) {
            authoredMaterial |= !batch.material.name.isEmpty();
            if (batch.material.texturePath != null) {
                assertTrue("Missing texture in " + path,
                        new File(ASSETS, batch.material.texturePath).isFile());
            }
        }
        assertTrue("Missing authored materials in " + path, authoredMaterial);
        return mesh;
    }

    private static void rotate(double[] point, int first, int second, float degrees) {
        double radians = Math.toRadians(degrees);
        double a = point[first], b = point[second];
        point[first] = a * Math.cos(radians) - b * Math.sin(radians);
        point[second] = a * Math.sin(radians) + b * Math.cos(radians);
    }

    private static double radiusFromCameraTarget(double[] point) {
        // The root rotation maps board Z to world Y and preserves distance.
        double height = point[2] - CAMERA_TARGET_HEIGHT;
        return Math.sqrt(point[0] * point[0] + point[1] * point[1] + height * height);
    }
}
