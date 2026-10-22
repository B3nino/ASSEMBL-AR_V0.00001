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
    /** Version 3 combines driver power/control; manual section progress remains authoritative. */
    public static final int SCHEMA_VERSION = 3;
    private static final String PREFERENCES = "assembly_phase_progress";
    private static final String KEY_SCHEMA = "schema_version";
    private static final String KEY_WORKFLOW = "workflow_mode";
    private static final String SEPARATOR = "\u001F";
    private static final String PHASE = SEPARATOR + "phase" + SEPARATOR;
    private static final String INVENTORY = SEPARATOR + "inventory" + SEPARATOR;
    private static final String SESSION=SEPARATOR+"session";

    public enum WorkflowMode { PHASES, STEP_CARDS }

    private PhaseProgressStore() { }

    public static PhaseProgress.Records records(Context context, String projectId) {
        SharedPreferences preferences = preferences(context);
        return new PhaseProgress.Records() {
            @Override public boolean isPhaseComplete(String phaseId) {
                return preferences.getBoolean(key(projectId, phaseId), false)
                        &&(!AssemblyPhaseCatalog.CENTER_STANDOFF.equals(phaseId)||preferences.getBoolean(key(projectId,AssemblyPhaseCatalog.WHEEL_PAIR),false));
            }

            @Override public boolean isLegacyCheckpointComplete(AssemblyStep section, GuidedStep card) {
                return GuidedStepProgressStore.isCheckpointComplete(context, section.getProjectId(),
                        section.getId(), card);
            }
        };
    }

    /** Synchronous so a failed write can be reported instead of silently losing the phase. */
    public static boolean markComplete(Context context, String projectId, String phaseId) {
        SharedPreferences.Editor edit=preferences(context).edit().putBoolean(key(projectId,phaseId),true);
        if(AssemblyPhaseCatalog.CENTER_STANDOFF.equals(phaseId))edit.putBoolean(key(projectId,AssemblyPhaseCatalog.WHEEL_PAIR),true);
        return edit.commit();
    }

    public static boolean clear(Context context, String projectId, Iterable<String> phaseIds) {
        SharedPreferences.Editor editor = preferences(context).edit();
        editor.remove(requireText(projectId)+SESSION);
        for (String phaseId : phaseIds) {
            editor.remove(key(projectId, phaseId));
            editor.remove(inventoryKey(projectId, phaseId));
            if(AssemblyPhaseCatalog.CENTER_STANDOFF.equals(phaseId)){editor.remove(inventoryKey(projectId,phaseId)+":standoff_wheels_v1");editor.remove(key(projectId,AssemblyPhaseCatalog.WHEEL_PAIR));editor.remove(inventoryKey(projectId,AssemblyPhaseCatalog.WHEEL_PAIR));}
            if(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES.equals(phaseId))editor.remove(inventoryKey(projectId,phaseId)+":gear_v1");
            if(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT.equals(phaseId))editor.remove(inventoryKey(projectId,phaseId)+":driver_v1");
            if(AssemblyPhaseCatalog.MICROCONTROLLER_FIT.equals(phaseId))editor.remove(inventoryKey(projectId,phaseId)+":nano_v1");
            if(AssemblyPhaseCatalog.DRIVER_WIRING.equals(phaseId)){editor.remove(inventoryKey(projectId,phaseId)+":six_wire_v1");editor.remove(key(projectId,AssemblyPhaseCatalog.DRIVER_CONTROL));}
            if(AssemblyPhaseCatalog.CASTER_FIT.equals(phaseId)){editor.remove(inventoryKey(projectId,phaseId)+":caster_trackers_v1");editor.remove(key(projectId,AssemblyPhaseCatalog.LINE_TRACKERS));}
            if(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT.equals(phaseId)){editor.remove(inventoryKey(projectId,phaseId)+":sensor_wiring_v1");editor.remove(key(projectId,AssemblyPhaseCatalog.SENSOR_WIRING));}
            if(AssemblyPhaseCatalog.MOTOR_LEADS.equals(phaseId))editor.remove(inventoryKey(projectId,phaseId)+":wiring_v1");
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
    public static String gearMotorState(Context context,String projectId){
        return preferences(context).getString(inventoryKey(projectId,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES)+":gear_v1",null);
    }
    public static String standoffWheelsState(Context context,String projectId){SharedPreferences p=preferences(context);String state=p.getString(inventoryKey(projectId,AssemblyPhaseCatalog.CENTER_STANDOFF)+":standoff_wheels_v1",null);return state!=null?state:p.getBoolean(key(projectId,AssemblyPhaseCatalog.CENTER_STANDOFF),false)?(p.getBoolean(inventoryKey(projectId,AssemblyPhaseCatalog.WHEEL_PAIR),false)?"legacyWheels":"legacyStandoff"):null;}
    public static void saveStandoffWheelsState(Context context,String projectId,String state){preferences(context).edit().putString(inventoryKey(projectId,AssemblyPhaseCatalog.CENTER_STANDOFF)+":standoff_wheels_v1",state).apply();}
    public static String sensorWiringState(Context context,String projectId){SharedPreferences p=preferences(context);String state=p.getString(inventoryKey(projectId,AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT)+":sensor_wiring_v1",null);return state!=null?state:p.getBoolean(key(projectId,AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT),false)?"legacyMount":null;}
    public static void saveSensorWiringState(Context context,String projectId,String state){preferences(context).edit().putString(inventoryKey(projectId,AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT)+":sensor_wiring_v1",state).apply();}
    public static String casterTrackerState(Context context,String projectId){return preferences(context).getString(inventoryKey(projectId,AssemblyPhaseCatalog.CASTER_FIT)+":caster_trackers_v1",null);}
    public static void saveCasterTrackerState(Context context,String projectId,String state){preferences(context).edit().putString(inventoryKey(projectId,AssemblyPhaseCatalog.CASTER_FIT)+":caster_trackers_v1",state).apply();}
    public static String driverWiringState(Context context,String projectId){return preferences(context).getString(inventoryKey(projectId,AssemblyPhaseCatalog.DRIVER_WIRING)+":six_wire_v1",null);}
    public static void saveDriverWiringState(Context context,String projectId,String state){preferences(context).edit().putString(inventoryKey(projectId,AssemblyPhaseCatalog.DRIVER_WIRING)+":six_wire_v1",state).apply();}
    public static String microcontrollerState(Context context,String projectId){return preferences(context).getString(inventoryKey(projectId,AssemblyPhaseCatalog.MICROCONTROLLER_FIT)+":nano_v1",null);}
    public static void saveMicrocontrollerState(Context context,String projectId,String state){preferences(context).edit().putString(inventoryKey(projectId,AssemblyPhaseCatalog.MICROCONTROLLER_FIT)+":nano_v1",state).apply();}
    public static String driverState(Context context,String projectId){return preferences(context).getString(inventoryKey(projectId,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT)+":driver_v1",null);}
    public static String wiringState(Context context,String projectId){return preferences(context).getString(inventoryKey(projectId,AssemblyPhaseCatalog.MOTOR_LEADS)+":wiring_v1",null);}
    public static void saveWiringState(Context context,String projectId,String state){preferences(context).edit().putString(inventoryKey(projectId,AssemblyPhaseCatalog.MOTOR_LEADS)+":wiring_v1",state).apply();}
    public static void saveDriverState(Context context,String projectId,String state){preferences(context).edit().putString(inventoryKey(projectId,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT)+":driver_v1",state).apply();}
    public static void saveGearMotorState(Context context,String projectId,String state){
        preferences(context).edit().putString(inventoryKey(projectId,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES)+":gear_v1",state).apply();
    }
    public static PhaseSessionCheckpoint session(Context context,String projectId){
        return PhaseSessionCheckpoint.decode(preferences(context).getString(requireText(projectId)+SESSION,null));
    }
    public static void saveSession(Context context,String projectId,PhaseSessionCheckpoint state){
        preferences(context).edit().putString(requireText(projectId)+SESSION,state.encode()).apply();
    }
    public static void clearSession(Context context,String projectId){
        preferences(context).edit().remove(requireText(projectId)+SESSION).apply();
    }

    public static void clearProject(Context context, String projectId) {
        String prefix = requireText(projectId) + PHASE;
        String inventoryPrefix = requireText(projectId) + INVENTORY;
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(requireText(projectId)+SESSION);
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
            SharedPreferences.Editor migration=preferences.edit();
            // Revision 1's combined power/control phase certified both new phases. Keep every
            // project record and inventory result when separating the two wiring sections.
            if(preferences.getInt(KEY_SCHEMA,0)<2)for(Map.Entry<String,?> entry:preferences.getAll().entrySet()) {
                if(entry.getKey().endsWith(SEPARATOR+AssemblyPhaseCatalog.DRIVER_WIRING)
                        && Boolean.TRUE.equals(entry.getValue())) {
                    String prefix=entry.getKey().substring(0,entry.getKey().length()-AssemblyPhaseCatalog.DRIVER_WIRING.length());
                    migration.putBoolean(prefix+AssemblyPhaseCatalog.DRIVER_CONTROL,true);
                }
            }
            // A version-2 power-only certificate must not mark the new six-wire phase complete.
            for(Map.Entry<String,?> entry:preferences.getAll().entrySet()){
                if(entry.getKey().endsWith(SEPARATOR+AssemblyPhaseCatalog.DRIVER_WIRING)&&Boolean.TRUE.equals(entry.getValue())){
                    String prefix=entry.getKey().substring(0,entry.getKey().length()-AssemblyPhaseCatalog.DRIVER_WIRING.length());
                    boolean controls=preferences.getInt(KEY_SCHEMA,0)<2||preferences.getBoolean(prefix+AssemblyPhaseCatalog.DRIVER_CONTROL,false);
                    migration.putBoolean(entry.getKey(),controls);
                }
            }
            migration.putInt(KEY_SCHEMA, SCHEMA_VERSION).apply();
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
