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
                "Complete current phase · stay here",
                "Jump to assembly phase (1–"+AssemblyPhaseCatalog.phases().size()+")",
                "Jump to manual section (1–18)",
                "Choose phase and sub-step",
                "Skip current sub-step · stay here",
                diagnosticsVisible ? "Hide live diagnostics" : "Show live diagnostics"
        };
        if(phases!=null&&phases.isDeveloperSession()){
            actions=java.util.Arrays.copyOf(actions,actions.length+1);
            actions[actions.length-1]="Return to saved assembly progress";
        }
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
                    } else if (choice == 3) {
                        if(phases==null) unavailable(activity);
                        else chooseSubStepPhase(activity,phases);
                    } else if (choice == 4) {
                        if(phases==null) activity.devForceCompleteCurrentStep();
                        else phases.devSkipCurrentStep();
                    } else if(choice==5) {
                        setDiagnosticsVisible.accept(!diagnosticsVisible);
                    } else if(phases!=null){
                        phases.returnToSavedAssembly();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void chooseSubStepPhase(AssemblyActivity activity, PhaseAssemblyController phases) {
        List<AssemblyPhase> catalog=AssemblyPhaseCatalog.phases();
        String[] names=new String[catalog.size()];
        for(int i=0;i<names.length;i++) names[i]="Phase "+catalog.get(i).getNumber()+" · "+catalog.get(i).getTitle();
        new AlertDialog.Builder(activity).setTitle("Select phase for sub-steps")
                .setItems(names,(dialog,index)-> {
                    AssemblyPhase selected=catalog.get(index);
                    if(dev.chyken.assembl_ar.workflow.phase.PhaseGuideMetadata.overhauled(selected)){
                        String[] labels=dev.chyken.assembl_ar.workflow.phase.PhaseGuideMetadata.subSteps(selected).toArray(new String[0]);
                        new AlertDialog.Builder(activity).setTitle("Phase "+selected.getNumber()+" sub-steps")
                                .setItems(labels,(child,step)->phases.devJumpToChapter(selected,step))
                                .setNegativeButton(android.R.string.cancel,null).show();return;
                    }
                    List<dev.chyken.assembl_ar.workflow.phase.PhaseDemoScript.Chapter> chapters=
                            dev.chyken.assembl_ar.workflow.phase.PhaseDemoScript.forPhase(selected).getChapters();
                    String[] labels=new String[chapters.size()];
                    for(int i=0;i<labels.length;i++) labels[i]=(i+1)+" · "+chapters.get(i).getTitle();
                    new AlertDialog.Builder(activity).setTitle("Phase "+selected.getNumber()+" sub-steps")
                            .setItems(labels,(child,chapter)->phases.devJumpToChapter(selected,chapter))
                            .setNegativeButton(android.R.string.cancel,null).show();
                }).setNegativeButton(android.R.string.cancel,null).show();
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
                .setTitle("Jump to a manual section")
                .setItems(labels, (dialog, index) -> phases.devJumpToSection(index + 1))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void unavailable(AssemblyActivity activity) {
        Toast.makeText(activity, "Switch to the phase workflow to jump phases",
                Toast.LENGTH_SHORT).show();
    }
}
