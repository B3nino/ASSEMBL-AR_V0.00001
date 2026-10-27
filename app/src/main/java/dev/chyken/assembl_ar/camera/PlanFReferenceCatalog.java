package dev.chyken.assembl_ar.camera;

import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import java.util.*;

/** Read-only help photos, scoped exactly to the current camera target. No image/ORB work. */
public final class PlanFReferenceCatalog {
    private PlanFReferenceCatalog() { }

    public static List<ComponentReferenceSpec> select(List<ComponentReferenceSpec> all,
            RecognitionRequest request) {
        if (all == null || request == null || request.getExpectation() == null)
            return Collections.emptyList();
        List<ComponentReferenceSpec> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ComponentReferenceSpec spec : ActiveReferenceScope.select(all, request)) {
            String path = spec.getJpegAssetPath();
            if (path == null || !positive(spec, request) || !seen.add(path)) continue;
            candidates.add(spec);
        }
        // Prefer authored regions so installed-component locations can be highlighted honestly.
        candidates.sort(Comparator.comparing((ComponentReferenceSpec s) ->
                s.getFeatureRegionsNormalized().isEmpty()).thenComparing(ComponentReferenceSpec::getJpegAssetPath));
        List<ComponentReferenceSpec> result = new ArrayList<>();
        int count = Math.min(6, candidates.size());
        for (int i = 0; i < count; i++)
            result.add(candidates.get(count == 1 ? 0 : i * (candidates.size() - 1) / (count - 1)));
        return Collections.unmodifiableList(result);
    }

    private static boolean positive(ComponentReferenceSpec s, RecognitionRequest r) {
        String id = s.getReferenceId(), role;
        if (r.getPhase2Stage() > 0) {
            role = Phase2AssetCatalog.referenceRole(id);
            if (role == null) return false;
            if (r.getPhase2Stage() == 1) return role.equals("brackets");
            if (r.getPhase2Stage() == 2) return role.equals("motors");
            return role.equals(r.getPhase2VerificationView() == null ? "top" : r.getPhase2VerificationView());
        }
        if (r.getPhase3Stage() > 0) return (r.getPhase3Stage() == 1 ? "front" : "assembly").equals(Phase3AssetCatalog.referenceRole(id));
        if (r.getPhase5Stage() > 0) return (r.getPhase5Stage() == 1 ? "front" : "assembly").equals(Phase5AssetCatalog.referenceRole(id));
        if (r.getPhase7Stage() > 0) return new String[]{"", "caster", "trackers", "casterwork", "trackerwork"}[r.getPhase7Stage()].equals(Phase7AssetCatalog.referenceRole(id));
        if (r.getPhase8Stage() > 0) return (r.getPhase8Stage() == 1 ? "front" : "assembly").equals(Phase8AssetCatalog.referenceRole(id));
        if (r.getPhase9Stage() > 0) {
            role = Phase9AssetCatalog.referenceRole(id);
            return r.getPhase9Stage() == 1 ? "standoff".equals(role) : r.getPhase9Stage() == 2 ? "pair".equals(role) || "wheel".equals(role) : "assembly".equals(role);
        }
        if (r.getPhase10Stage() > 0) return new String[]{"", "cable", "extension", "protector", "protected", "stacked"}[r.getPhase10Stage()].equals(Phase10AssetCatalog.referenceRole(id));
        if (r.getPhase11Stage() > 0) return new String[]{"", "front", "battery", "assembly"}[r.getPhase11Stage()].equals(Phase11AssetCatalog.referenceRole(id));
        String path = s.getJpegAssetPath().toUpperCase(Locale.ROOT);
        return s.getComponentId().equals(r.getExpectation().getComponentId())
                && !s.isBottomFace() && !path.contains("INVALID") && !path.contains("REJECT");
    }

    public static String targetName(RecognitionRequest r, String componentName) {
        if (r.getPhase2Stage() == 5) return "Motor and bracket assembly · " + (r.getPhase2VerificationView() == null ? "top" : r.getPhase2VerificationView()) + " view";
        if (r.getPhase7Stage() >= 3) return r.getPhase7Stage() == 4 ? "Both line trackers and caster · bottom view" : "Caster wheel installation · bottom view";
        if (r.getPhase9Stage() >= 3) return "Both wheels and center standoff · top view";
        if (r.getPhase10Stage() == 4) return "OLED protector on extension board · top view";
        if (r.getPhase10Stage() == 5) return "Stacked extension board and chassis · top view";
        if (r.getPhase3Stage() > 1 || r.getPhase5Stage() > 1 || r.getPhase8Stage() == 2 || r.getPhase11Stage() == 3)
            return componentName + " installation · top view";
        return componentName + (r.getExpectedInstanceCount() > 1 ? " · " + r.getExpectedInstanceCount() + " required" : "");
    }

    public static String tip(RecognitionRequest r) {
        String common = "Tap the camera view to focus. Keep the target sharp and fully visible; tilt the tablet slightly to move glare away. ";
        if (r.getPhase7Stage() >= 3) return common + (r.getPhase7Stage() == 4
                ? "Show the bottom of the chassis. Keep both sensor heads and the caster visible together; bottom-view left and right are reversed."
                : "Show the bottom of the chassis with the caster and its mounting points visible.");
        if (r.getPhase8Stage() == 2) return common + "Show the sensor and nearby PCB from above. Both metal sensor eyes must face outward, away from the board. Move loose wires clear of the sensor body.";
        if (r.getPhase3Stage() > 1) return common + "Show the seated driver and nearby board markings. Match the heatsink and terminal orientation to this photo; move loose wires clear of the driver.";
        if (r.getPhase5Stage() > 1) return common + "Show the Nano, USB connector and nearby socket/board markings together. The USB end must match this installation photo.";
        if (r.getPhase2Stage() == 5) return common + "Match this step's camera angle. Keep both motor bodies and brackets visible for the top view; for a side view, include that motor and nearby mounting points.";
        if (r.getPhase9Stage() >= 3) return common + "Show the full chassis from above, including both wheels and the center standoff. Physical firmness still requires the checklist.";
        if (r.getPhase10Stage() >= 4 || r.getPhase11Stage() == 3) return common + "Show the installed part and surrounding board together from above. Compare mounting holes and headers with the photo; move glare off the acrylic or display.";
        return common + "Match the shown face and angle. Keep fingers clear of the component's body and connectors.";
    }
}
