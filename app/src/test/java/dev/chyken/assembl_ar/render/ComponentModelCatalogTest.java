package dev.chyken.assembl_ar.render;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;

import static org.junit.Assert.*;

/** Checks the actual Android assets and preview framing, not just filename conventions. */
public final class ComponentModelCatalogTest {
    @Test public void combinedFastenerPreviewsReuseRealScrewsWithoutAddingLibraryComponents(){
        assertTrue(ComponentModelCatalog.hasModel(ComponentModelCatalog.SHORT_FASTENERS));
        assertTrue(ComponentModelCatalog.hasModel(ComponentModelCatalog.LONG_FASTENERS));
        assertEquals(ComponentModelCatalog.assetPath(NeobotSeedData.SCREW_SHORT),ComponentModelCatalog.assetPath(ComponentModelCatalog.SHORT_FASTENERS));
        assertEquals(ComponentModelCatalog.assetPath(NeobotSeedData.SCREW_30MM),ComponentModelCatalog.assetPath(ComponentModelCatalog.LONG_FASTENERS));
        assertEquals("models/hex_nut.obj",ComponentModelCatalog.assetPath(NeobotSeedData.NUT));
        assertFalse(ComponentModelCatalog.assets().containsKey(ComponentModelCatalog.SHORT_FASTENERS));
    }
    @Test public void everyLibraryComponentHasItsOwnModelAndNoUnknownIdsAreAccepted() {
        Set<String> componentIds = new HashSet<>();
        Set<String> modelPaths = new HashSet<>();
        for (Component component : NeobotSeedData.getComponents()) {
            componentIds.add(component.getId());
            assertTrue(component.getId(), ComponentModelCatalog.hasModel(component.getId()));
            assertTrue("Shared model: " + component.getId(),
                    modelPaths.add(ComponentModelCatalog.assetPath(component.getId())));
        }
        assertEquals(33, componentIds.size());
        assertEquals(componentIds, ComponentModelCatalog.assets().keySet());
        assertFalse(ComponentModelCatalog.hasModel("unregistered_component"));
        assertNull(ComponentModelCatalog.assetPath("unregistered_component"));
    }

    @Test public void everyPackagedMeshLoadsWithMaterialsAndFitsInsideTheInitialCamera() throws Exception {
        File assets = new File("src/main/assets");
        for (String path : ComponentModelCatalog.assets().values()) {
            ObjModel mesh = ObjModel.load(asset -> new FileInputStream(new File(assets, asset)), path);
            assertNotNull("Cannot load " + path, mesh);
            assertTrue(path, mesh.vertexCount() >= 12);
            assertTrue(path, mesh.boundingRadius() > 0f);
            float scale = HomeRobotRenderer.previewScale(mesh);
            assertTrue(path, Float.isFinite(scale) && scale > 0f);
            FloatBuffer vertices = mesh.vertices();
            for (int offset = 0; offset < vertices.limit(); offset += 3) {
                double squaredRadius = 0;
                for (int axis = 0; axis < 3; axis++) {
                    double fitted = (vertices.get(offset + axis) - mesh.center(axis)) * scale;
                    squaredRadius += fitted * fitted;
                }
                assertTrue("Preview clips " + path, squaredRadius <= 1.751 * 1.751);
            }
            boolean hasAuthoredMaterial = false;
            for (ObjModel.Batch batch : mesh.batches()) {
                hasAuthoredMaterial |= !batch.material.name.isEmpty();
                if (batch.material.texturePath != null) {
                    assertTrue("Missing texture for " + path,
                            new File(assets, batch.material.texturePath).isFile());
                }
            }
            assertTrue("Missing materials for " + path, hasAuthoredMaterial);
        }
        // A radius of 1.75 fits at the default distance even in the narrower frustum axis.
        assertTrue(1.70 < 5.3 * Math.sin(Math.toRadians(19)));
    }
}
