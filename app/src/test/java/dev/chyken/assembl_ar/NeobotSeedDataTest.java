package dev.chyken.assembl_ar;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.LearningModule;
import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.model.SeedIntegrityReport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the exact manual-derived seed against accidental omissions or wiring regressions. */
public final class NeobotSeedDataTest {
    @Test
    public void seedContainsTheCompleteManualScope() {
        SeedIntegrityReport report = NeobotSeedData.validate();
        assertTrue(report.getErrors().toString(), report.isValid());
        assertEquals(18, NeobotSeedData.getAssemblySteps().size());
        assertEquals(7, NeobotSeedData.getLearningModules().size());

        int learningSections = 0;
        for (LearningModule module : NeobotSeedData.getLearningModules()) {
            learningSections += module.getSteps().size();
        }
        assertEquals(32, learningSections);
    }

    @Test
    public void motorDriverPwmMapMatchesTheAssemblyManual() {
        AssemblyStep wiringStep = NeobotSeedData.getAssemblySteps().get(7);
        assertConnection(wiringStep, "A-1A", "D5 / PWM");
        assertConnection(wiringStep, "A-1B", "D9 / PWM");
        assertConnection(wiringStep, "B-1A", "D6 / PWM");
        assertConnection(wiringStep, "B-1B", "D10 / PWM");
    }

    @Test
    public void recognitionCatalogUsesUniqueExtractedRgbSources() throws Exception {
        Set<String> intentionallyUnbundled = new HashSet<>(Arrays.asList(
                NeobotSeedData.THREADLOCKER,
                NeobotSeedData.NAIL_POLISH,
                NeobotSeedData.DOUBLE_SIDED_TAPE,
                NeobotSeedData.BATTERY_HOLDER));
        Set<String> usedPaths = new HashSet<>();
        int componentsWithBundledReferences = 0;

        for (Component component : NeobotSeedData.getComponents()) {
            assertTrue(component.getId(), component.getReferenceImagePaths().size() <= 4);
            if (component.getReferenceImagePaths().isEmpty()) {
                assertTrue("Unexpected empty recognition catalog: " + component.getId(),
                        intentionallyUnbundled.contains(component.getId()));
            } else {
                componentsWithBundledReferences++;
            }

            for (String path : component.getReferenceImagePaths()) {
                assertFalse("Manual page render leaked into recognition: " + path,
                        path.startsWith("manuals/"));
                assertTrue(path, path.startsWith("references/assembly/")
                        || path.startsWith("references/learning/")
                        || path.startsWith("references/variants/"));
                assertTrue(path, path.endsWith(".png") || path.endsWith(".jpg"));
                assertTrue("A shared photo would make catalog order decide identity: " + path,
                        usedPaths.add(path));

                File asset = resolveAsset(path);
                assertTrue("Missing source asset: " + asset, asset.isFile());
                assertRgbImage(asset, path);
            }

            for (String path : component.getManualReference().getAssetPaths()) {
                assertTrue("Manual help asset changed: " + path,
                        path.startsWith("manuals/") && path.endsWith(".jpg"));
            }
        }

        assertEquals(29, componentsWithBundledReferences);
        assertEquals(39, usedPaths.size());
    }

    @Test
    public void jumperInventoryUsesTheDerivedBuildMinimumNotAPackClaim() {
        Component jumper = component(NeobotSeedData.JUMPER_WIRE);
        assertEquals("Jumper wire", jumper.getName());
        assertEquals(16, jumper.getKitQuantity());
        assertTrue(jumper.getDescription().contains("at least 16"));
        assertTrue(jumper.getDescription()
                .contains("manuals do not state the packaged set's total lead count"));
    }

    @Test
    public void polarityOverlaysOnlyCoverComponentsWithObservableRedPositiveEvidence() {
        Set<Integer> polaritySteps = new HashSet<>();
        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            if (step.getOverlayTypes().contains(OverlayType.POLARITY_ARROW)) {
                polaritySteps.add(step.getStepNumber());
            }
        }

        assertEquals(new HashSet<>(Arrays.asList(5, 7, 15)), polaritySteps);
        AssemblyStep oled = NeobotSeedData.getAssemblySteps().get(16);
        assertTrue(oled.getOverlayTypes().contains(OverlayType.ORIENTATION_MARKER));
        assertFalse(oled.getOverlayTypes().contains(OverlayType.POLARITY_ARROW));
    }

    private static File resolveAsset(String relativePath) {
        File[] roots = {
                new File("src/main/assets"),
                new File("app/src/main/assets"),
                new File("android-app/app/src/main/assets")
        };
        for (File root : roots) {
            File candidate = new File(root, relativePath);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return new File(roots[0], relativePath);
    }

    private static void assertRgbImage(File asset, String path) throws Exception {
        if (path.endsWith(".jpg")) {
            assertRgbJpeg(asset, path);
            return;
        }
        byte[] header = new byte[26];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(asset)) {
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
        }
        assertEquals("Truncated PNG: " + path, header.length, offset);
        assertEquals("Not a PNG: " + path, 0x89, header[0] & 0xff);
        assertEquals("Missing PNG IHDR: " + path, 'I', header[12]);
        assertEquals("Missing PNG IHDR: " + path, 'H', header[13]);
        assertEquals("Missing PNG IHDR: " + path, 'D', header[14]);
        assertEquals("Missing PNG IHDR: " + path, 'R', header[15]);
        int colorType = header[25] & 0xff;
        assertTrue("Grayscale mask selected instead of RGB source: " + path,
                colorType == 2 || colorType == 6);
    }

    private static void assertRgbJpeg(File asset, String path) throws Exception {
        byte[] bytes = new byte[(int) asset.length()];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(asset)) {
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
        }
        assertEquals("Truncated JPEG: " + path, bytes.length, offset);
        assertTrue("Not a JPEG: " + path, bytes.length >= 4
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8);

        int cursor = 2;
        while (cursor + 9 < bytes.length) {
            while (cursor < bytes.length && (bytes[cursor] & 0xff) != 0xff) {
                cursor++;
            }
            while (cursor < bytes.length && (bytes[cursor] & 0xff) == 0xff) {
                cursor++;
            }
            if (cursor >= bytes.length) {
                break;
            }
            int marker = bytes[cursor++] & 0xff;
            if (marker == 0xd9 || marker == 0xda) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                continue;
            }
            assertTrue("Invalid JPEG segment: " + path, cursor + 1 < bytes.length);
            int segmentLength = ((bytes[cursor] & 0xff) << 8)
                    | (bytes[cursor + 1] & 0xff);
            assertTrue("Invalid JPEG segment length: " + path,
                    segmentLength >= 2 && cursor + segmentLength <= bytes.length);
            if (isStartOfFrame(marker)) {
                assertTrue("Truncated JPEG frame: " + path, segmentLength >= 8);
                int components = bytes[cursor + 7] & 0xff;
                assertTrue("Grayscale mask selected instead of RGB source: " + path,
                        components >= 3);
                return;
            }
            cursor += segmentLength;
        }
        throw new AssertionError("JPEG frame metadata not found: " + path);
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xc0 && marker <= 0xcf
                && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
    }

    private static Component component(String componentId) {
        for (Component component : NeobotSeedData.getComponents()) {
            if (componentId.equals(component.getId())) {
                return component;
            }
        }
        throw new AssertionError("Missing component " + componentId);
    }

    private static void assertConnection(AssemblyStep step, String source, String target) {
        for (PinConnection connection : step.getPinConnections()) {
            if (source.equals(connection.getSourcePin())
                    && target.equals(connection.getTargetPin())) {
                return;
            }
        }
        throw new AssertionError("Missing manual pin map " + source + " -> " + target);
    }
}
