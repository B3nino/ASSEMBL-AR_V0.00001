package dev.chyken.assembl_ar.diagnostics;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.chyken.assembl_ar.camera.AssemblyProgressReferenceCatalog;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

/** Writes the production reference catalog (and staged Phase 4 specs) for offline replicas. */
@RunWith(AndroidJUnit4.class)
public final class ReferenceCatalogDumpInstrumentedTest {
    @Test
    public void dumpCatalog() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        JSONObject root = new JSONObject()
                .put("production", specs(VisionReferenceFactory.build(
                        AssemblArRepository.getInstance(context))))
                .put("stagedProgress", specs(AssemblyProgressReferenceCatalog.all()));
        File directory = context.getExternalFilesDir("reference-catalog");
        assertTrue(directory != null && (directory.isDirectory() || directory.mkdirs()));
        File output = new File(directory, "catalog.json");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
        }
        Log.i("ReferenceCatalogDump", "wrote " + output + " production="
                + root.getJSONArray("production").length());
    }

    private static JSONArray specs(List<ComponentReferenceSpec> specs) throws Exception {
        JSONArray result = new JSONArray();
        for (ComponentReferenceSpec spec : specs) {
            JSONArray regions = new JSONArray();
            for (List<VisionPoint> region : spec.getFeatureRegionsNormalized()) {
                regions.put(points(region));
            }
            result.put(new JSONObject()
                    .put("referenceId", spec.getReferenceId())
                    .put("componentId", spec.getComponentId())
                    .put("jpegAssetPath", spec.getJpegAssetPath())
                    .put("localImageFilePath", spec.getLocalImageFilePath())
                    .put("hasAdminDescriptorBlob", spec.getAdminDescriptorBlob() != null)
                    .put("physicalWidthMm", spec.getPhysicalWidthMm())
                    .put("physicalHeightMm", spec.getPhysicalHeightMm())
                    .put("shapeHint", String.valueOf(spec.getShapeHint()))
                    .put("planarCornersNormalized", points(spec.getPlanarCornersNormalized()))
                    .put("featureRegionsNormalized", regions)
                    .put("maximumImageDimension", spec.getMaximumImageDimension())
                    .put("descriptorRegionNormalization",
                            spec.isDescriptorRegionNormalizationEnabled())
                    .put("assemblyStepNumber", spec.getAssemblyStepNumber())
                    .put("installedBracketView", spec.isInstalledBracketView())
                    .put("polarityAnchorsVerified", spec.hasVerifiedPolarityAnchors()));
        }
        return result;
    }

    private static JSONArray points(List<VisionPoint> points) throws Exception {
        JSONArray result = new JSONArray();
        for (VisionPoint point : points) {
            result.put(new JSONArray().put(point.getX()).put(point.getY()));
        }
        return result;
    }
}
