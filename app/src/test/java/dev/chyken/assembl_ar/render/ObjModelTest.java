package dev.chyken.assembl_ar.render;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class ObjModelTest {
    private static final String QUAD = "v -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\n";

    @Test public void negativeIndicesTriangulateAndFlipTextureVExactlyOnce() {
        ObjModel model = load(QUAD + "vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n"
                + "f -4/-4 -3/-3 -2/-2 -1/-1\n");
        assertNotNull(model);
        assertEquals(6, model.vertexCount());
        assertEquals(1, model.batches().size());
        assertTrue(model.batches().get(0).hasTextureCoordinates);
        assertEquals(0f, model.textureCoordinates().get(0), 0f);
        assertEquals(1f, model.textureCoordinates().get(1), 0f);
        assertEquals(0f, model.textureCoordinates().get(5), 0f);
        assertEquals(1f, model.normals().get(2), 0f);
        assertEquals(1f, model.halfExtent(0), 0f);
    }

    @Test public void materialsResolveRelativePhotoPathsAndSeparateUntexturedFaces() {
        Map<String, String> files = new HashMap<>();
        files.put("models/board.obj", "mtllib board.mtl\n" + QUAD
                + "vt 0 0\nvt 1 0\nvt 1 1\nusemtl front\nf 1/1 2/2 3/3\nf 1 3 4\n"
                + "usemtl rim\nf 1//1 2//1 3//1\n");
        files.put("models/board.mtl", "newmtl front\nKd 1 0.8 0.7\nd 0.5\n"
                + "map_Kd ../references/physical/board.jpg\nnewmtl rim\nKd 0.2 0.3 0.4\n");
        ObjModel model = ObjModel.load(source(files), "models/board.obj");
        assertNotNull(model);
        assertEquals(3, model.batches().size());
        assertEquals("references/physical/board.jpg", model.batches().get(0).material.texturePath);
        assertEquals(0.5f, model.batches().get(0).material.color[3], 0f);
        assertFalse(model.batches().get(1).hasTextureCoordinates);
        assertEquals("rim", model.batches().get(2).material.name);
        assertEquals(6, model.batches().get(2).firstVertex);
    }

    @Test public void missingMaterialsDoNotDiscardLegacyGeometry() {
        ObjModel model = load("mtllib missing.mtl\n" + QUAD + "usemtl missing\nf 1 2 3 4 # quad\n");
        assertNotNull(model);
        assertEquals(6, model.vertexCount());
        assertNull(model.batches().get(0).material.texturePath);
        assertArrayEquals(new float[]{1, 1, 1, 1}, model.batches().get(0).material.color, 0f);
    }

    @Test public void malformedIndicesAndNonFiniteCoordinatesRejectMesh() {
        assertNull(load(QUAD + "f 0 2 3\n"));
        assertNull(load(QUAD + "f 1 2 5\n"));
        assertNull(load(QUAD + "f 1/1 2/1 3/1\n"));
        assertNull(load("v NaN 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"));
        assertNull(load("# no faces\n"));
    }

    @Test public void assetPathsStayWithinPackagedAssets() {
        assertEquals("references/front.jpg", ObjModel.relativePath("models/board.mtl", "../references/front.jpg"));
        assertNull(ObjModel.relativePath("models/board.mtl", "../../outside.jpg"));
        assertNull(ObjModel.relativePath("models/board.mtl", "C:/private.jpg"));
        assertNull(ObjModel.relativePath("models/board.mtl", "/private.jpg"));
        assertNull(ObjModel.relativePath("models/board.mtl", "\\private.jpg"));
    }

    @Test public void textureSamplingRespectsDeviceLimitIncludingOddPhotoSizes() {
        assertEquals(2, ObjMaterialRenderer.sampleSize(4032, 3024, 2048));
        assertEquals(4, ObjMaterialRenderer.sampleSize(4097, 3024, 2048));
        assertEquals(1, ObjMaterialRenderer.sampleSize(512, 600, 2048));
        assertEquals(8, ObjMaterialRenderer.sampleSize(4032, 3024, 512));
    }

    @Test public void previewBoundsCenterOffsetMeshesWithoutChangingArExtents() {
        ObjModel model = load("v 10 20 30\nv 14 20 30\nv 10 26 38\nf 1 2 3\n");
        assertNotNull(model);
        assertEquals(12f, model.center(0), 0f);
        assertEquals(23f, model.center(1), 0f);
        assertEquals(34f, model.center(2), 0f);
        assertEquals((float) Math.sqrt(29), model.boundingRadius(), 0.00001f);
        assertEquals(14f, model.halfExtent(0), 0f);
        assertEquals(26f, model.halfExtent(1), 0f);
        assertEquals(38f, model.halfExtent(2), 0f);
    }

    @Test public void previewFitsTinyAndLargeModelsWithTheSameProportions() {
        ObjModel tiny = load("v -0.01 -0.02 0\nv 0.01 -0.02 0\nv 0 0.02 0\nf 1 2 3\n");
        ObjModel large = load("v -100 -200 0\nv 100 -200 0\nv 0 200 0\nf 1 2 3\n");
        assertNotNull(tiny);
        assertNotNull(large);
        float tinyScale = HomeRobotRenderer.previewScale(tiny);
        float largeScale = HomeRobotRenderer.previewScale(large);
        assertEquals(tiny.halfExtent(0) * tinyScale, large.halfExtent(0) * largeScale, 0.00001f);
        assertEquals(tiny.halfExtent(1) * tinyScale, large.halfExtent(1) * largeScale, 0.00001f);
        assertEquals(1.75f, tiny.boundingRadius() * tinyScale, 0.00001f);
    }

    private static ObjModel load(String obj) {
        Map<String, String> files = new HashMap<>();
        files.put("models/board.obj", obj);
        return ObjModel.load(source(files), "models/board.obj");
    }

    private static ObjModel.AssetSource source(Map<String, String> files) {
        return path -> {
            String value = files.get(path);
            if (value == null) throw new IOException("Missing test asset");
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        };
    }
}
