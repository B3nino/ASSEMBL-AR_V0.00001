package dev.chyken.assembl_ar;

import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.function.Consumer;

import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhaseCatalog;

/** Small, explicit developer controls for camera trials. */
final class DevStepSkipDialog {
    private DevStepSkipDialog() { }

    static void show(AssemblyActivity activity, @Nullable PhaseAssemblyController phases,
                     boolean diagnosticsVisible, Consumer<Boolean> setDiagnosticsVisible) {
        String[] actions = {
                "Force-complete current step / phase",
                "Jump to assembly phase (1–17)",
                "Jump to manual section (1–18)",
                diagnosticsVisible ? "Hide live diagnostics" : "Show live diagnostics"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Developer controls")
                .setItems(actions, (dialog, choice) -> {
                    if (choice == 0) {
                        if (phases != null) phases.devForceCompleteCurrentPhase();
                        else activity.devForceCompleteCurrentStep();
                    } else if (choice == 1) {
                        if (phases == null) unavailable(activity);
                        else choosePhase(activity, phases);
                    } else if (choice == 2) {
                        if (phases == null) unavailable(activity);
                        else chooseSection(activity, phases);
                    } else {
                        setDiagnosticsVisible.accept(!diagnosticsVisible);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void choosePhase(AssemblyActivity activity, PhaseAssemblyController phases) {
        List<AssemblyPhase> catalog = AssemblyPhaseCatalog.phases();
        String[] labels = new String[catalog.size()];
        for (int index = 0; index < labels.length; index++) {
            AssemblyPhase phase = catalog.get(index);
            labels[index] = "Phase " + phase.getNumber() + " · " + phase.getTitle();
        }
        new AlertDialog.Builder(activity)
                .setTitle("Jump to phase")
                .setItems(labels, (dialog, index) -> phases.devJumpToPhase(catalog.get(index)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void chooseSection(AssemblyActivity activity, PhaseAssemblyController phases) {
        String[] labels = new String[18];
        for (int section = 1; section <= labels.length; section++) {
            AssemblyPhase owner = null;
            for (AssemblyPhase candidate : AssemblyPhaseCatalog.phases()) {
                if (candidate.getSections().contains(section)) {
                    owner = candidate;
                    break;
                }
            }
            labels[section - 1] = "Section " + section + " → "
                    + (owner == null ? "Unavailable" : "Phase " + owner.getNumber()
                    + " · " + owner.getTitle());
        }
        new AlertDialog.Builder(activity)
                .setTitle("18 manual sections map to 17 phases")
                .setItems(labels, (dialog, index) -> phases.devJumpToSection(index + 1))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void unavailable(AssemblyActivity activity) {
        Toast.makeText(activity, "Switch to the phase workflow to jump phases",
                Toast.LENGTH_SHORT).show();
    }
}
