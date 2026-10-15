package dev.chyken.assembl_ar.ui;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.render.HomeRobotSurfaceView;

/** Lifecycle-owned, standalone inspection of the component library's 3D models. */
public final class ComponentModelDialog extends DialogFragment {
    private static final String TAG = "component-model";
    private static final String COMPONENT = "component";
    private static final String TITLE = "title";
    private static final String BACK_FACE = "backFace";
    @Nullable private HomeRobotSurfaceView surface;
    private boolean backFace;

    public static void show(AppCompatActivity activity, String componentId, String title) {
        if (activity.getSupportFragmentManager().isStateSaved()
                || activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) {
            return;
        }
        ComponentModelDialog dialog = new ComponentModelDialog();
        Bundle arguments = new Bundle();
        arguments.putString(COMPONENT, componentId);
        arguments.putString(TITLE, title);
        dialog.setArguments(arguments);
        dialog.show(activity.getSupportFragmentManager(), TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle arguments = requireArguments();
        backFace = savedInstanceState != null && savedInstanceState.getBoolean(BACK_FACE);
        View content = getLayoutInflater().inflate(R.layout.dialog_component_model, null);
        FrameLayout viewport = content.findViewById(R.id.componentModelViewport);
        // Keep controls visible on compact landscape screens and cap unused tablet space.
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        viewport.getLayoutParams().height = Math.min(UiUtils.dp(requireContext(), 380),
                Math.max(UiUtils.dp(requireContext(), 120), (int) (screenHeight * 0.40f)));
        surface = HomeRobotSurfaceView.forComponent(requireContext(),
                arguments.getString(COMPONENT, ""));
        surface.setContentDescription(getString(R.string.model_accessibility,
                arguments.getString(TITLE, "")));
        viewport.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        surface.showBack(backFace);
        RadioGroup faces = content.findViewById(R.id.modelFaces);
        faces.check(backFace ? R.id.modelBackFace : R.id.modelFrontFace);
        faces.setOnCheckedChangeListener((group, checked) -> {
            backFace = checked == R.id.modelBackFace;
            if (surface != null) {
                surface.showBack(backFace);
            }
        });
        GradientDrawable background = new GradientDrawable();
        background.setColor(requireContext().getColor(R.color.white));
        background.setCornerRadius(UiUtils.dp(requireContext(), 28));
        return new MaterialAlertDialogBuilder(requireContext())
                .setBackground(background)
                .setTitle(arguments.getString(TITLE))
                .setView(content)
                .setPositiveButton(R.string.close_model, (dialog, which) -> dismiss())
                .setNeutralButton(R.string.reset_model_view, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            if (surface != null) {
                surface.resetView();
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            int width = getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(width - UiUtils.dp(requireContext(), 32),
                    UiUtils.dp(requireContext(), 640)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (surface != null) {
            surface.onResume();
        }
    }

    @Override
    public void onPause() {
        if (surface != null) {
            surface.onPause();
        }
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(BACK_FACE, backFace);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        surface = null;
        super.onDestroyView();
    }
}
