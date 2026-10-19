package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.MainBoardDimensions;

/** Discovers raw front/bottom photos without a filename manifest. Runs on the vision loader. */
public final class RawBoardReferenceCatalog {
    private RawBoardReferenceCatalog() { }
    interface Listing { String[] list(String path) throws IOException; }

    public static List<ComponentReferenceSpec> scan(AssetManager assets) {
        return scan(assets::list);
    }

    static List<ComponentReferenceSpec> scan(Listing assets) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        try {
            String root = "references/Phase1";
            String[] folders = assets.list(root);
            if (folders == null) return result;
            Arrays.sort(folders);
            for (String folder : folders) {
                String face = folder.toLowerCase(Locale.ROOT);
                boolean bottom = face.equals("mainboardbottom") || face.equals("mainboardback");
                if (!bottom && !face.equals("mainboardfront")) continue;
                List<String> paths = new ArrayList<>();
                collect(assets, root + "/" + folder, paths, 0);
                Collections.sort(paths);
                for (int index = 0; index < paths.size(); index++) {
                    String path = paths.get(index);
                    result.add(ComponentReferenceSpec.builder("main_board_raw_v1_" + path,
                                    "main_board", "Main Board", MainBoardDimensions.WIDTH_MM,
                                    MainBoardDimensions.HEIGHT_MM)
                            .jpegAssetPath(path).maximumImageDimension(960)
                            .automaticBoardGeometry(bottom).assemblyStepNumber(1)
                            .fastAcquisitionView(index % Math.max(1, paths.size() / 4) == 0)
                            .planarInlierPose(true).polarityAnchorsVerified(false).build());
                }
            }
        } catch (IOException failure) {
            Log.e("RawBoardReferences", "Could not list Phase1 reference assets", failure);
        }
        return Collections.unmodifiableList(result);
    }

    private static void collect(Listing assets, String path, List<String> output, int depth)
            throws IOException {
        if (depth > 8) return;
        String[] children = assets.list(path);
        if (children == null) return;
        for (String child : children) {
            String next = path + "/" + child;
            String lower = child.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                output.add(next);
            } else {
                collect(assets, next, output, depth + 1);
            }
        }
    }
}
