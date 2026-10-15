package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.model.ManualReference;

public final class ManualReferenceDialog {
    private ManualReferenceDialog() {
    }

    public static void show(Activity activity, ManualReference reference) {
        show(activity, reference, reference.getPageStart());
    }

    /** Opens the complete reference range while focusing the page for the current guided card. */
    public static void show(Activity activity, ManualReference reference, int initialPage) {
        int clampedPage = Math.max(reference.getPageStart(),
                Math.min(initialPage, reference.getPageEnd()));
        show(activity, reference.getDocumentTitle(), reference.getPageStart(),
                reference.getAssetPaths(), clampedPage - reference.getPageStart());
    }

    public static void show(Activity activity, String title, int firstPage, List<String> assetPaths) {
        show(activity, title, firstPage, assetPaths, 0);
    }

    private static void show(
            Activity activity,
            String title,
            int firstPage,
            List<String> assetPaths,
            int initialIndex) {
        if (assetPaths == null || assetPaths.isEmpty()) {
            return;
        }
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_manual_reference);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }

        TextView titleView = dialog.findViewById(R.id.referenceTitle);
        TextView counter = dialog.findViewById(R.id.referencePageCounter);
        ZoomImageView image = dialog.findViewById(R.id.referenceImage);
        ImageButton previous = dialog.findViewById(R.id.previousPageButton);
        ImageButton next = dialog.findViewById(R.id.nextPageButton);
        View close = dialog.findViewById(R.id.closeReferenceButton);
        titleView.setText(title);

        List<String> pages = Collections.unmodifiableList(new ArrayList<>(assetPaths));
        int[] position = {Math.max(0, Math.min(initialIndex, pages.size() - 1))};
        Runnable render = () -> {
            int index = position[0];
            AssetImageLoader.load(image, pages.get(index));
            image.resetZoom();
            counter.setText(activity.getString(R.string.page_counter,
                    firstPage + index, firstPage + pages.size() - 1));
            previous.setEnabled(index > 0);
            previous.setAlpha(index > 0 ? 1f : 0.35f);
            next.setEnabled(index < pages.size() - 1);
            next.setAlpha(index < pages.size() - 1 ? 1f : 0.35f);
        };
        previous.setOnClickListener(view -> {
            if (position[0] > 0) {
                position[0]--;
                render.run();
            }
        });
        next.setOnClickListener(view -> {
            if (position[0] < pages.size() - 1) {
                position[0]++;
                render.run();
            }
        });
        close.setOnClickListener(view -> dialog.dismiss());
        render.run();
        dialog.show();
    }
}
