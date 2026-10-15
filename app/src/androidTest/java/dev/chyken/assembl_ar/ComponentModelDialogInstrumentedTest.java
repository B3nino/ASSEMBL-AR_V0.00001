package dev.chyken.assembl_ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.render.HomeRobotSurfaceView;
import dev.chyken.assembl_ar.ui.ComponentModelDialog;

@RunWith(AndroidJUnit4.class)
public final class ComponentModelDialogInstrumentedTest {
    @Test
    public void everyComponentCardOpensItsOwnStandalonePreview() {
        try (ActivityScenario<ComponentLibraryActivity> scenario =
                     ActivityScenario.launch(ComponentLibraryActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout container = activity.findViewById(R.id.componentsContainer);
                assertEquals(NeobotSeedData.getComponents().size(), container.getChildCount());
                for (int index = 0; index < container.getChildCount(); index++) {
                    Component component = NeobotSeedData.getComponents().get(index);
                    View button = container.getChildAt(index).findViewById(R.id.componentModelButton);
                    assertEquals(component.getId(), View.VISIBLE, button.getVisibility());
                    assertEquals(activity.getString(R.string.view_named_component_model,
                            component.getName()), button.getContentDescription());
                    button.performClick();
                    activity.getSupportFragmentManager().executePendingTransactions();
                    ComponentModelDialog fragment = (ComponentModelDialog) activity
                            .getSupportFragmentManager().findFragmentByTag("component-model");
                    assertNotNull(component.getId(), fragment);
                    assertEquals(component.getId(), fragment.requireArguments().getString("component"));
                    AlertDialog dialog = (AlertDialog) fragment.requireDialog();
                    RadioGroup faces = dialog.findViewById(R.id.modelFaces);
                    assertNotNull(faces);
                    faces.check(R.id.modelBackFace);
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
                    assertTrue(dialog.isShowing());
                    assertEquals(R.id.modelBackFace, faces.getCheckedRadioButtonId());
                    fragment.dismissNow();
                }
            });
        }
    }

    @Test
    public void boardPreviewRetainsFaceOnRecreationAndResetDoesNotDismiss() {
        AtomicReference<AlertDialog> closedDialog = new AtomicReference<>();
        try (ActivityScenario<ComponentLibraryActivity> scenario =
                     ActivityScenario.launch(ComponentLibraryActivity.class)) {
            scenario.onActivity(activity -> {
                View modelButton = activity.findViewById(R.id.componentModelButton);
                assertEquals(View.VISIBLE, modelButton.getVisibility());
                modelButton.performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                ComponentModelDialog fragment = (ComponentModelDialog) activity
                        .getSupportFragmentManager().findFragmentByTag("component-model");
                assertNotNull(fragment);
                AlertDialog dialog = (AlertDialog) fragment.requireDialog();
                FrameLayout viewport = dialog.findViewById(R.id.componentModelViewport);
                assertNotNull(viewport);
                assertTrue(viewport.getChildAt(0) instanceof HomeRobotSurfaceView);
                RadioGroup faces = dialog.findViewById(R.id.modelFaces);
                assertNotNull(faces);
                faces.check(R.id.modelBackFace);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
                assertTrue(dialog.isShowing());
                assertEquals(R.id.modelBackFace, faces.getCheckedRadioButtonId());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                ComponentModelDialog fragment = (ComponentModelDialog) activity
                        .getSupportFragmentManager().findFragmentByTag("component-model");
                assertNotNull(fragment);
                AlertDialog dialog = (AlertDialog) fragment.requireDialog();
                RadioGroup faces = dialog.findViewById(R.id.modelFaces);
                assertNotNull(faces);
                assertEquals(R.id.modelBackFace, faces.getCheckedRadioButtonId());
                closedDialog.set(dialog);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            // AlertDialog dispatches button listeners through its Handler, not synchronously
            // from performClick. Let that user-visible dismissal finish before checking it.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                activity.getSupportFragmentManager().executePendingTransactions();
                assertFalse(closedDialog.get().isShowing());
                ComponentModelDialog.show(activity, NeobotSeedData.EXTENSION_BOARD,
                        "Extension circuit board");
                activity.getSupportFragmentManager().executePendingTransactions();
                ComponentModelDialog extension = (ComponentModelDialog) activity
                        .getSupportFragmentManager().findFragmentByTag("component-model");
                assertNotNull(extension);
                assertTrue(extension.requireDialog().isShowing());
            });
        }
    }
}
