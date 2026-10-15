package dev.chyken.assembl_ar.workflow.phase;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.Objects;

import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;

/**
 * Device-local phase records beside the SQLite manual-section progress. The database remains the
 * source of truth for completed sections; this store only needs to remember a finished phase
 * that does not finish its manual section (mounting the distance sensor before its wiring).
 */
public final class PhaseProgressStore {
    /** Version 1: the 17-phase catalog of revision 1. */
    public static final int SCHEMA_VERSION = 1;
    private static final String PREFERENCES = "assembly_phase_progress";
    private static final String KEY_SCHEMA = "schema_version";
    private static final String KEY_WORKFLOW = "workflow_mode";
    private static final String SEPARATOR = "\u001F";
    private static final String PHASE = SEPARATOR + "phase" + SEPARATOR;
    private static final String INVENTORY = SEPARATOR + "inventory" + SEPARATOR;

    public enum WorkflowMode { PHASES, STEP_CARDS }

    private PhaseProgressStore() { }

    public static PhaseProgress.Records records(Context context, String projectId) {
        SharedPreferences preferences = preferences(context);
        return new PhaseProgress.Records() {
            @Override public boolean isPhaseComplete(String phaseId) {
                return preferences.getBoolean(key(projectId, phaseId), false);
            }

            @Override public boolean isLegacyCheckpointComplete(AssemblyStep section, GuidedStep card) {
                return GuidedStepProgressStore.isCheckpointComplete(context, section.getProjectId(),
                        section.getId(), card);
            }
        };
    }

    /** Synchronous so a failed write can be reported instead of silently losing the phase. */
    public static boolean markComplete(Context context, String projectId, String phaseId) {
        return preferences(context).edit().putBoolean(key(projectId, phaseId), true).commit();
    }

    public static boolean clear(Context context, String projectId, Iterable<String> phaseIds) {
        SharedPreferences.Editor editor = preferences(context).edit();
        for (String phaseId : phaseIds) {
            editor.remove(key(projectId, phaseId));
            editor.remove(inventoryKey(projectId, phaseId));
        }
        return editor.commit();
    }

    /**
     * Remembers that this phase's parts were identified, so a recreated screen resumes assembly
     * rather than asking for the parts again. It is not completion evidence of any kind.
     */
    public static void setInventoryComplete(Context context, String projectId, String phaseId,
                                            boolean complete) {
        SharedPreferences.Editor editor = preferences(context).edit();
        if (complete) editor.putBoolean(inventoryKey(projectId, phaseId), true);
        else editor.remove(inventoryKey(projectId, phaseId));
        editor.apply();
    }

    public static boolean isInventoryComplete(Context context, String projectId, String phaseId) {
        return preferences(context).getBoolean(inventoryKey(projectId, phaseId), false);
    }

    public static void clearProject(Context context, String projectId) {
        String prefix = requireText(projectId) + PHASE;
        String inventoryPrefix = requireText(projectId) + INVENTORY;
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix) || entry.getKey().startsWith(inventoryPrefix)) {
                editor.remove(entry.getKey());
            }
        }
        editor.apply();
    }

    /** The grouped phase guide is the default; step-by-step cards remain available. */
    public static WorkflowMode workflowMode(Context context) {
        String stored = preferences(context).getString(KEY_WORKFLOW, WorkflowMode.PHASES.name());
        try {
            return WorkflowMode.valueOf(stored);
        } catch (IllegalArgumentException unknown) {
            return WorkflowMode.PHASES;
        }
    }

    public static void setWorkflowMode(Context context, WorkflowMode mode) {
        preferences(context).edit().putString(KEY_WORKFLOW, Objects.requireNonNull(mode).name()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        SharedPreferences preferences = Objects.requireNonNull(context, "context")
                .getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (preferences.getInt(KEY_SCHEMA, 0) != SCHEMA_VERSION) {
            // Future schema changes migrate here; version 1 starts from an empty record set.
            preferences.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).apply();
        }
        return preferences;
    }

    private static String key(String projectId, String phaseId) {
        return requireText(projectId) + PHASE + requireText(phaseId);
    }

    private static String inventoryKey(String projectId, String phaseId) {
        return requireText(projectId) + INVENTORY + requireText(phaseId);
    }

    private static String requireText(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Identifier cannot be blank");
        }
        return value.trim();
    }
}
