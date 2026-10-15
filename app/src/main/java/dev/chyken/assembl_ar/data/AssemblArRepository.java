package dev.chyken.assembl_ar.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyProject;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.ComponentCategory;
import dev.chyken.assembl_ar.model.ComponentRegistration;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.LearningContentType;
import dev.chyken.assembl_ar.model.LearningModule;
import dev.chyken.assembl_ar.model.LearningStep;
import dev.chyken.assembl_ar.model.ManualReference;
import dev.chyken.assembl_ar.model.OrbDescriptorData;
import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.model.ProjectProgress;
import dev.chyken.assembl_ar.model.ReferenceImageMetadata;
import dev.chyken.assembl_ar.model.RequiredComponent;
import dev.chyken.assembl_ar.model.SeedIntegrityReport;
import dev.chyken.assembl_ar.model.SpatialMetadata;
import dev.chyken.assembl_ar.model.StepProgress;
import dev.chyken.assembl_ar.model.StepStatus;
import dev.chyken.assembl_ar.model.ValidationType;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Synchronous local repository for UI, instructional flow, recognition and authorized registration.
 * Call database methods from a worker thread when used with a frame-processing or UI pipeline.
 */
public final class AssemblArRepository implements Closeable {
    private static volatile AssemblArRepository instance;

    private final AssemblArDatabaseHelper helper;

    private AssemblArRepository(Context context) {
        helper = new AssemblArDatabaseHelper(context);
    }

    public static AssemblArRepository getInstance(Context context) {
        Objects.requireNonNull(context, "context");
        AssemblArRepository local = instance;
        if (local == null) {
            synchronized (AssemblArRepository.class) {
                local = instance;
                if (local == null) {
                    local = new AssemblArRepository(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    public List<AssemblyProject> getProjects() {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<AssemblyProject> projects = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.Projects.TABLE, null,
                null, null, null, null, AssemblArContract.Projects.TITLE + " COLLATE NOCASE")) {
            while (cursor.moveToNext()) {
                projects.add(readProject(cursor, getSteps(text(cursor, AssemblArContract.Projects.ID))));
            }
        }
        return immutable(projects);
    }

    /** Returns null when the stable project ID is unknown. */
    public AssemblyProject getProject(String projectId) {
        requireId(projectId, "projectId");
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(AssemblArContract.Projects.TABLE, null,
                AssemblArContract.Projects.ID + " = ?", new String[]{projectId},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? readProject(cursor, getSteps(projectId)) : null;
        }
    }

    public List<AssemblyStep> getSteps(String projectId) {
        requireId(projectId, "projectId");
        SQLiteDatabase db = helper.getReadableDatabase();
        List<AssemblyStep> steps = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.Steps.TABLE, null,
                AssemblArContract.Steps.PROJECT_ID + " = ?", new String[]{projectId},
                null, null, AssemblArContract.Steps.STEP_NUMBER + " ASC")) {
            while (cursor.moveToNext()) {
                steps.add(readStep(cursor));
            }
        }
        return immutable(steps);
    }

    /** Returns null when the stable step ID is unknown. */
    public AssemblyStep getStep(String stepId) {
        requireId(stepId, "stepId");
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(AssemblArContract.Steps.TABLE, null,
                AssemblArContract.Steps.ID + " = ?", new String[]{stepId},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? readStep(cursor) : null;
        }
    }

    public List<Component> getComponents() {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<Component> components = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.Components.TABLE, null,
                null, null, null, null, AssemblArContract.Components.NAME + " COLLATE NOCASE")) {
            while (cursor.moveToNext()) {
                components.add(readComponent(cursor));
            }
        }
        return immutable(components);
    }

    /** Returns null when the stable component ID is unknown. */
    public Component getComponent(String componentId) {
        requireId(componentId, "componentId");
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(AssemblArContract.Components.TABLE, null,
                AssemblArContract.Components.ID + " = ?", new String[]{componentId},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? readComponent(cursor) : null;
        }
    }

    public List<LearningModule> getLearningModules() {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<LearningModule> modules = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.LearningModules.TABLE, null,
                null, null, null, null, AssemblArContract.LearningModules.ORDER_INDEX + " ASC")) {
            while (cursor.moveToNext()) {
                modules.add(readLearningModule(cursor));
            }
        }
        return immutable(modules);
    }

    /** Returns null when the stable learning-module ID is unknown. */
    public LearningModule getLearningModule(String moduleId) {
        requireId(moduleId, "moduleId");
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(AssemblArContract.LearningModules.TABLE, null,
                AssemblArContract.LearningModules.ID + " = ?", new String[]{moduleId},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? readLearningModule(cursor) : null;
        }
    }

    private AssemblyProject readProject(Cursor cursor, List<AssemblyStep> steps) {
        return new AssemblyProject(
                text(cursor, AssemblArContract.Projects.ID),
                text(cursor, AssemblArContract.Projects.TITLE),
                text(cursor, AssemblArContract.Projects.KIT_NAME),
                text(cursor, AssemblArContract.Projects.DESCRIPTION),
                readManualReference(cursor,
                        AssemblArContract.Projects.MANUAL_DOCUMENT_ID,
                        AssemblArContract.Projects.MANUAL_DOCUMENT_TITLE,
                        AssemblArContract.Projects.PAGE_START,
                        AssemblArContract.Projects.PAGE_END,
                        AssemblArContract.Projects.ASSET_PATHS),
                steps);
    }

    private Component readComponent(Cursor cursor) {
        String componentId = text(cursor, AssemblArContract.Components.ID);
        return new Component(
                componentId,
                text(cursor, AssemblArContract.Components.NAME),
                ComponentCategory.valueOf(text(cursor, AssemblArContract.Components.CATEGORY)),
                integer(cursor, AssemblArContract.Components.KIT_QUANTITY),
                text(cursor, AssemblArContract.Components.DESCRIPTION),
                text(cursor, AssemblArContract.Components.PURPOSE),
                text(cursor, AssemblArContract.Components.ASSEMBLY_USAGE),
                text(cursor, AssemblArContract.Components.RECOGNITION_NOTES),
                readManualReference(cursor,
                        AssemblArContract.Components.MANUAL_DOCUMENT_ID,
                        AssemblArContract.Components.MANUAL_DOCUMENT_TITLE,
                        AssemblArContract.Components.PAGE_START,
                        AssemblArContract.Components.PAGE_END,
                        AssemblArContract.Components.ASSET_PATHS),
                bundledReferencePaths(componentId));
    }

    /**
     * Bundled recognition images are app-versioned assets, not mutable database content. The
     * component table's asset_paths column belongs to its manual reference and must never be
     * interpreted as the vision catalog, especially after an in-place app upgrade.
     */
    private static List<String> bundledReferencePaths(String componentId) {
        for (Component canonical : NeobotSeedData.getComponents()) {
            if (canonical.getId().equals(componentId)) {
                return canonical.getReferenceImagePaths();
            }
        }
        return Collections.emptyList();
    }

    private AssemblyStep readStep(Cursor cursor) {
        String stepId = text(cursor, AssemblArContract.Steps.ID);
        return new AssemblyStep(
                stepId,
                text(cursor, AssemblArContract.Steps.PROJECT_ID),
                integer(cursor, AssemblArContract.Steps.STEP_NUMBER),
                text(cursor, AssemblArContract.Steps.TITLE),
                text(cursor, AssemblArContract.Steps.SUMMARY),
                ValidationType.valueOf(text(cursor, AssemblArContract.Steps.VALIDATION_TYPE)),
                text(cursor, AssemblArContract.Steps.VALIDATION_PROMPT),
                readManualReference(cursor,
                        AssemblArContract.Steps.MANUAL_DOCUMENT_ID,
                        AssemblArContract.Steps.MANUAL_DOCUMENT_TITLE,
                        AssemblArContract.Steps.PAGE_START,
                        AssemblArContract.Steps.PAGE_END,
                        AssemblArContract.Steps.ASSET_PATHS),
                getInstructions(stepId), getRequirements(stepId), getPinConnections(stepId),
                getOverlayTypes(stepId));
    }

    private List<AssemblyInstruction> getInstructions(String stepId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<AssemblyInstruction> values = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.Instructions.TABLE, null,
                AssemblArContract.Instructions.STEP_ID + " = ?", new String[]{stepId},
                null, null, AssemblArContract.Instructions.ORDER_INDEX + " ASC")) {
            while (cursor.moveToNext()) {
                values.add(new AssemblyInstruction(
                        integer(cursor, AssemblArContract.Instructions.ORDER_INDEX),
                        InstructionType.valueOf(text(cursor, AssemblArContract.Instructions.TYPE)),
                        text(cursor, AssemblArContract.Instructions.TEXT),
                        integer(cursor, AssemblArContract.Instructions.MANUAL_PAGE),
                        text(cursor, AssemblArContract.Instructions.REFERENCE_IMAGE_PATH)));
            }
        }
        return immutable(values);
    }

    private List<RequiredComponent> getRequirements(String stepId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<RequiredComponent> values = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.Requirements.TABLE, null,
                AssemblArContract.Requirements.STEP_ID + " = ?", new String[]{stepId},
                null, null, AssemblArContract.Requirements.COMPONENT_ID + " ASC")) {
            while (cursor.moveToNext()) {
                values.add(new RequiredComponent(
                        text(cursor, AssemblArContract.Requirements.COMPONENT_ID),
                        integer(cursor, AssemblArContract.Requirements.QUANTITY),
                        text(cursor, AssemblArContract.Requirements.NOTES)));
            }
        }
        return immutable(values);
    }

    private List<PinConnection> getPinConnections(String stepId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<PinConnection> values = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.Connections.TABLE, null,
                AssemblArContract.Connections.STEP_ID + " = ?", new String[]{stepId},
                null, null, AssemblArContract.Connections.ORDER_INDEX + " ASC")) {
            while (cursor.moveToNext()) {
                values.add(new PinConnection(
                        integer(cursor, AssemblArContract.Connections.ORDER_INDEX),
                        text(cursor, AssemblArContract.Connections.SOURCE_COMPONENT_ID),
                        text(cursor, AssemblArContract.Connections.SOURCE_PIN),
                        text(cursor, AssemblArContract.Connections.TARGET_COMPONENT_ID),
                        text(cursor, AssemblArContract.Connections.TARGET_PIN),
                        text(cursor, AssemblArContract.Connections.WIRE_COLOR),
                        text(cursor, AssemblArContract.Connections.SIGNAL),
                        text(cursor, AssemblArContract.Connections.NOTES)));
            }
        }
        return immutable(values);
    }

    private List<OverlayType> getOverlayTypes(String stepId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<OverlayType> values = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.StepOverlays.TABLE, null,
                AssemblArContract.StepOverlays.STEP_ID + " = ?", new String[]{stepId},
                null, null, AssemblArContract.StepOverlays.ORDER_INDEX + " ASC")) {
            while (cursor.moveToNext()) {
                values.add(OverlayType.valueOf(text(cursor, AssemblArContract.StepOverlays.OVERLAY_TYPE)));
            }
        }
        return immutable(values);
    }

    private LearningModule readLearningModule(Cursor cursor) {
        String moduleId = text(cursor, AssemblArContract.LearningModules.ID);
        return new LearningModule(
                moduleId,
                integer(cursor, AssemblArContract.LearningModules.ORDER_INDEX),
                text(cursor, AssemblArContract.LearningModules.TITLE),
                text(cursor, AssemblArContract.LearningModules.OVERVIEW),
                text(cursor, AssemblArContract.LearningModules.PREREQUISITE_MODULE_ID),
                readManualReference(cursor,
                        AssemblArContract.LearningModules.MANUAL_DOCUMENT_ID,
                        AssemblArContract.LearningModules.MANUAL_DOCUMENT_TITLE,
                        AssemblArContract.LearningModules.PAGE_START,
                        AssemblArContract.LearningModules.PAGE_END,
                        AssemblArContract.LearningModules.ASSET_PATHS),
                getLearningSteps(moduleId));
    }

    private List<LearningStep> getLearningSteps(String moduleId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<LearningStep> values = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.LearningSteps.TABLE, null,
                AssemblArContract.LearningSteps.MODULE_ID + " = ?", new String[]{moduleId},
                null, null, AssemblArContract.LearningSteps.ORDER_INDEX + " ASC")) {
            while (cursor.moveToNext()) {
                values.add(new LearningStep(
                        text(cursor, AssemblArContract.LearningSteps.ID),
                        text(cursor, AssemblArContract.LearningSteps.MODULE_ID),
                        integer(cursor, AssemblArContract.LearningSteps.ORDER_INDEX),
                        text(cursor, AssemblArContract.LearningSteps.TITLE),
                        LearningContentType.valueOf(text(cursor, AssemblArContract.LearningSteps.CONTENT_TYPE)),
                        text(cursor, AssemblArContract.LearningSteps.CONTENT),
                        text(cursor, AssemblArContract.LearningSteps.CODE_SNIPPET),
                        text(cursor, AssemblArContract.LearningSteps.WARNING),
                        text(cursor, AssemblArContract.LearningSteps.DEPENDENCY),
                        readManualReference(cursor,
                                AssemblArContract.LearningSteps.MANUAL_DOCUMENT_ID,
                                AssemblArContract.LearningSteps.MANUAL_DOCUMENT_TITLE,
                                AssemblArContract.LearningSteps.PAGE_START,
                                AssemblArContract.LearningSteps.PAGE_END,
                                AssemblArContract.LearningSteps.ASSET_PATHS)));
            }
        }
        return immutable(values);
    }

    public ProjectProgress getProjectProgress(String projectId) {
        requireId(projectId, "projectId");
        if (getProjectRowCount(projectId) == 0L) {
            throw new IllegalArgumentException("Unknown project ID: " + projectId);
        }
        SQLiteDatabase db = helper.getReadableDatabase();
        List<StepProgress> steps = new ArrayList<>();
        int completed = 0;
        String currentStepId = "";
        String sql = "SELECT p.* FROM " + AssemblArContract.Progress.TABLE + " p JOIN "
                + AssemblArContract.Steps.TABLE + " s ON s." + AssemblArContract.Steps.ID + " = p."
                + AssemblArContract.Progress.STEP_ID + " WHERE p." + AssemblArContract.Progress.PROJECT_ID
                + " = ? ORDER BY s." + AssemblArContract.Steps.STEP_NUMBER + " ASC";
        try (Cursor cursor = db.rawQuery(sql, new String[]{projectId})) {
            while (cursor.moveToNext()) {
                StepProgress progress = readProgress(cursor);
                steps.add(progress);
                if (progress.getStatus() == StepStatus.COMPLETED) {
                    completed++;
                } else if (currentStepId.isEmpty()) {
                    currentStepId = progress.getStepId();
                }
            }
        }
        return new ProjectProgress(projectId, completed, steps.size(), currentStepId, steps);
    }

    /** Returns null when the project is complete. */
    public AssemblyStep getCurrentStep(String projectId) {
        String currentStepId = getProjectProgress(projectId).getCurrentStepId();
        return currentStepId.isEmpty() ? null : getStep(currentStepId);
    }

    /** Returns null when the stable step ID is unknown. */
    public StepProgress getStepProgress(String stepId) {
        requireId(stepId, "stepId");
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(AssemblArContract.Progress.TABLE, null,
                AssemblArContract.Progress.STEP_ID + " = ?", new String[]{stepId},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? readProgress(cursor) : null;
        }
    }

    /** Starts a step only after all preceding authored steps are complete. */
    public StepProgress startStep(String stepId) {
        AssemblyStep step = requireStep(stepId);
        SQLiteDatabase db = helper.getWritableDatabase();
        long incompletePredecessors = DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM " + AssemblArContract.Steps.TABLE + " s JOIN "
                        + AssemblArContract.Progress.TABLE + " p ON p." + AssemblArContract.Progress.STEP_ID
                        + " = s." + AssemblArContract.Steps.ID + " WHERE s."
                        + AssemblArContract.Steps.PROJECT_ID + " = ? AND s."
                        + AssemblArContract.Steps.STEP_NUMBER + " < ? AND p."
                        + AssemblArContract.Progress.STATUS + " <> ?",
                new String[]{step.getProjectId(), String.valueOf(step.getStepNumber()),
                        StepStatus.COMPLETED.name()});
        if (incompletePredecessors > 0) {
            throw new IllegalStateException("Complete all preceding assembly steps first");
        }
        return setStepProgress(stepId, StepStatus.IN_PROGRESS, false);
    }

    /**
     * Persists an explicit status. Completion always requires the user's physical validation confirmation.
     */
    public StepProgress setStepProgress(
            String stepId,
            StepStatus status,
            boolean validationConfirmed) {
        Objects.requireNonNull(status, "status");
        AssemblyStep step = requireStep(stepId);
        if (status == StepStatus.COMPLETED && !validationConfirmed) {
            throw new IllegalArgumentException("A completed step requires confirmed physical validation");
        }
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.Progress.STATUS, status.name());
        values.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, validationConfirmed ? 1 : 0);
        values.put(AssemblArContract.Progress.UPDATED_AT, System.currentTimeMillis());
        int rows = helper.getWritableDatabase().update(AssemblArContract.Progress.TABLE, values,
                AssemblArContract.Progress.PROJECT_ID + " = ? AND "
                        + AssemblArContract.Progress.STEP_ID + " = ?",
                new String[]{step.getProjectId(), stepId});
        if (rows != 1) {
            throw new IllegalStateException("Progress row missing for step " + stepId);
        }
        return getStepProgress(stepId);
    }

    /** Confirms the authored validation gate, completes the current step, and activates its successor atomically. */
    public ProjectProgress confirmAndCompleteStep(String stepId) {
        AssemblyStep step = requireStep(stepId);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long incompletePredecessors = DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM " + AssemblArContract.Steps.TABLE + " s JOIN "
                            + AssemblArContract.Progress.TABLE + " p ON p."
                            + AssemblArContract.Progress.STEP_ID + " = s." + AssemblArContract.Steps.ID
                            + " WHERE s." + AssemblArContract.Steps.PROJECT_ID + " = ? AND s."
                            + AssemblArContract.Steps.STEP_NUMBER + " < ? AND p."
                            + AssemblArContract.Progress.STATUS + " <> ?",
                    new String[]{step.getProjectId(), String.valueOf(step.getStepNumber()),
                            StepStatus.COMPLETED.name()});
            if (incompletePredecessors > 0) {
                throw new IllegalStateException("Cannot complete steps out of the authored sequence");
            }

            ContentValues completedValues = new ContentValues();
            completedValues.put(AssemblArContract.Progress.STATUS, StepStatus.COMPLETED.name());
            completedValues.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, 1);
            completedValues.put(AssemblArContract.Progress.UPDATED_AT, System.currentTimeMillis());
            db.update(AssemblArContract.Progress.TABLE, completedValues,
                    AssemblArContract.Progress.STEP_ID + " = ?", new String[]{stepId});

            String nextStepId = null;
            try (Cursor cursor = db.query(AssemblArContract.Steps.TABLE,
                    new String[]{AssemblArContract.Steps.ID},
                    AssemblArContract.Steps.PROJECT_ID + " = ? AND "
                            + AssemblArContract.Steps.STEP_NUMBER + " = ?",
                    new String[]{step.getProjectId(), String.valueOf(step.getStepNumber() + 1)},
                    null, null, null, "1")) {
                if (cursor.moveToFirst()) {
                    nextStepId = text(cursor, AssemblArContract.Steps.ID);
                }
            }
            if (nextStepId != null) {
                ContentValues nextValues = new ContentValues();
                nextValues.put(AssemblArContract.Progress.STATUS, StepStatus.IN_PROGRESS.name());
                nextValues.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, 0);
                nextValues.put(AssemblArContract.Progress.UPDATED_AT, System.currentTimeMillis());
                db.update(AssemblArContract.Progress.TABLE, nextValues,
                        AssemblArContract.Progress.STEP_ID + " = ? AND "
                                + AssemblArContract.Progress.STATUS + " <> ?",
                        new String[]{nextStepId, StepStatus.COMPLETED.name()});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return getProjectProgress(step.getProjectId());
    }

    /**
     * Completes consecutive manual sections finished by one grouped phase in a single transaction,
     * so a save failure can never leave only part of that phase recorded.
     */
    public ProjectProgress confirmAndCompleteSteps(List<String> stepIds) {
        if (stepIds == null || stepIds.isEmpty()) {
            throw new IllegalArgumentException("A grouped save needs at least one step");
        }
        String projectId = requireStep(stepIds.get(0)).getProjectId();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (String stepId : stepIds) {
                if (!projectId.equals(requireStep(stepId).getProjectId())) {
                    throw new IllegalArgumentException("A grouped save cannot span projects");
                }
                confirmAndCompleteStep(stepId);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return getProjectProgress(projectId);
    }

    /**
     * Redo: reopens {@code stepId} as the current step and returns every later step to not started,
     * clearing their confirmations. Earlier completed steps are unchanged.
     */
    public ProjectProgress reopenFromStep(String stepId) {
        AssemblyStep reopened = requireStep(stepId);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (AssemblyStep step : getSteps(reopened.getProjectId())) {
                if (step.getStepNumber() < reopened.getStepNumber()) continue;
                ContentValues values = new ContentValues();
                values.put(AssemblArContract.Progress.STATUS,
                        step.getStepNumber() == reopened.getStepNumber()
                                ? StepStatus.IN_PROGRESS.name() : StepStatus.NOT_STARTED.name());
                values.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, 0);
                values.put(AssemblArContract.Progress.UPDATED_AT, System.currentTimeMillis());
                db.update(AssemblArContract.Progress.TABLE, values,
                        AssemblArContract.Progress.STEP_ID + " = ?", new String[]{step.getId()});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return getProjectProgress(reopened.getProjectId());
    }

    public ProjectProgress resetProjectProgress(String projectId) {
        requireId(projectId, "projectId");
        List<AssemblyStep> steps = getSteps(projectId);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Unknown or empty project ID: " + projectId);
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (AssemblyStep step : steps) {
                ContentValues values = new ContentValues();
                values.put(AssemblArContract.Progress.STATUS,
                        step.getStepNumber() == 1
                                ? StepStatus.IN_PROGRESS.name()
                                : StepStatus.NOT_STARTED.name());
                values.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, 0);
                values.put(AssemblArContract.Progress.UPDATED_AT, System.currentTimeMillis());
                db.update(AssemblArContract.Progress.TABLE, values,
                        AssemblArContract.Progress.STEP_ID + " = ?", new String[]{step.getId()});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return getProjectProgress(projectId);
    }

    private StepProgress readProgress(Cursor cursor) {
        return new StepProgress(
                text(cursor, AssemblArContract.Progress.PROJECT_ID),
                text(cursor, AssemblArContract.Progress.STEP_ID),
                StepStatus.valueOf(text(cursor, AssemblArContract.Progress.STATUS)),
                integer(cursor, AssemblArContract.Progress.VALIDATION_CONFIRMED) != 0,
                longValue(cursor, AssemblArContract.Progress.UPDATED_AT));
    }

    public void saveComponentRegistration(ComponentRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        if (getComponent(registration.getComponentId()) == null) {
            throw new IllegalArgumentException("Unknown component ID: " + registration.getComponentId());
        }
        ComponentRegistration existing = getComponentRegistration(registration.getId());
        if (existing != null && !existing.getComponentId().equals(registration.getComponentId())) {
            throw new IllegalArgumentException("A registration cannot be reassigned to another component");
        }
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.Registrations.ID, registration.getId());
        values.put(AssemblArContract.Registrations.COMPONENT_ID, registration.getComponentId());
        values.put(AssemblArContract.Registrations.ADMINISTRATOR_ID, registration.getAdministratorId());
        values.put(AssemblArContract.Registrations.DISPLAY_NAME, registration.getDisplayName());
        values.put(AssemblArContract.Registrations.DESCRIPTION, registration.getDescription());
        values.put(AssemblArContract.Registrations.PURPOSE, registration.getPurpose());
        values.put(AssemblArContract.Registrations.ASSEMBLY_USAGE, registration.getAssemblyUsage());
        values.put(AssemblArContract.Registrations.MANUAL_REFERENCE, registration.getManualReference());
        values.put(AssemblArContract.Registrations.ACTIVE, registration.isActive() ? 1 : 0);
        values.put(AssemblArContract.Registrations.CREATED_AT, registration.getCreatedAtEpochMillis());
        values.put(AssemblArContract.Registrations.UPDATED_AT, registration.getUpdatedAtEpochMillis());
        upsert(helper.getWritableDatabase(), AssemblArContract.Registrations.TABLE,
                AssemblArContract.Registrations.ID, registration.getId(), values);
    }

    public List<ComponentRegistration> getComponentRegistrations(String componentId) {
        requireId(componentId, "componentId");
        SQLiteDatabase db = helper.getReadableDatabase();
        List<ComponentRegistration> values = new ArrayList<>();
        try (Cursor cursor = db.query(AssemblArContract.Registrations.TABLE, null,
                AssemblArContract.Registrations.COMPONENT_ID + " = ?", new String[]{componentId},
                null, null, AssemblArContract.Registrations.UPDATED_AT + " DESC")) {
            while (cursor.moveToNext()) {
                values.add(readRegistration(cursor));
            }
        }
        return immutable(values);
    }

    /** Returns null when the registration ID is unknown. */
    public ComponentRegistration getComponentRegistration(String registrationId) {
        requireId(registrationId, "registrationId");
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(AssemblArContract.Registrations.TABLE, null,
                AssemblArContract.Registrations.ID + " = ?", new String[]{registrationId},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? readRegistration(cursor) : null;
        }
    }

    public void setComponentRegistrationActive(String registrationId, boolean active) {
        if (getComponentRegistration(registrationId) == null) {
            throw new IllegalArgumentException("Unknown registration ID: " + registrationId);
        }
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.Registrations.ACTIVE, active ? 1 : 0);
        values.put(AssemblArContract.Registrations.UPDATED_AT, System.currentTimeMillis());
        helper.getWritableDatabase().update(AssemblArContract.Registrations.TABLE, values,
                AssemblArContract.Registrations.ID + " = ?", new String[]{registrationId});
    }

    public void saveReferenceImage(ReferenceImageMetadata image) {
        Objects.requireNonNull(image, "image");
        ComponentRegistration registration = getComponentRegistration(image.getRegistrationId());
        if (registration == null) {
            throw new IllegalArgumentException("Unknown registration ID: " + image.getRegistrationId());
        }
        if (!registration.getComponentId().equals(image.getComponentId())) {
            throw new IllegalArgumentException("Reference image component does not match its registration");
        }
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.ReferenceImages.ID, image.getId());
        values.put(AssemblArContract.ReferenceImages.REGISTRATION_ID, image.getRegistrationId());
        values.put(AssemblArContract.ReferenceImages.COMPONENT_ID, image.getComponentId());
        values.put(AssemblArContract.ReferenceImages.IMAGE_PATH, image.getImagePath());
        values.put(AssemblArContract.ReferenceImages.ANGLE_LABEL, image.getAngleLabel());
        values.put(AssemblArContract.ReferenceImages.AZIMUTH_DEGREES, image.getAzimuthDegrees());
        values.put(AssemblArContract.ReferenceImages.ELEVATION_DEGREES, image.getElevationDegrees());
        values.put(AssemblArContract.ReferenceImages.DISTANCE_MILLIMETERS, image.getDistanceMillimeters());
        values.put(AssemblArContract.ReferenceImages.LIGHTING_CONDITION, image.getLightingCondition());
        values.put(AssemblArContract.ReferenceImages.IS_SYNTHETIC, image.isSynthetic() ? 1 : 0);
        values.put(AssemblArContract.ReferenceImages.PIXEL_WIDTH, image.getPixelWidth());
        values.put(AssemblArContract.ReferenceImages.PIXEL_HEIGHT, image.getPixelHeight());
        values.put(AssemblArContract.ReferenceImages.SHA256, image.getSha256());
        values.put(AssemblArContract.ReferenceImages.CAPTURED_AT, image.getCapturedAtEpochMillis());
        upsert(helper.getWritableDatabase(), AssemblArContract.ReferenceImages.TABLE,
                AssemblArContract.ReferenceImages.ID, image.getId(), values);
    }

    public List<ReferenceImageMetadata> getReferenceImages(String componentId) {
        requireId(componentId, "componentId");
        return queryReferenceImages(
                "SELECT i.* FROM " + AssemblArContract.ReferenceImages.TABLE + " i WHERE i."
                        + AssemblArContract.ReferenceImages.COMPONENT_ID + " = ? ORDER BY i."
                        + AssemblArContract.ReferenceImages.CAPTURED_AT + " ASC, i."
                        + AssemblArContract.ReferenceImages.ID + " ASC",
                new String[]{componentId});
    }

    /** Returns only images belonging to active bundled/admin registrations for recognition matching. */
    public List<ReferenceImageMetadata> getActiveReferenceImages(String componentId) {
        requireId(componentId, "componentId");
        return queryReferenceImages(
                "SELECT i.* FROM " + AssemblArContract.ReferenceImages.TABLE + " i JOIN "
                        + AssemblArContract.Registrations.TABLE + " r ON r."
                        + AssemblArContract.Registrations.ID + " = i."
                        + AssemblArContract.ReferenceImages.REGISTRATION_ID + " WHERE i."
                        + AssemblArContract.ReferenceImages.COMPONENT_ID + " = ? AND r."
                        + AssemblArContract.Registrations.ACTIVE + " = 1 ORDER BY i."
                        + AssemblArContract.ReferenceImages.CAPTURED_AT + " ASC, i."
                        + AssemblArContract.ReferenceImages.ID + " ASC",
                new String[]{componentId});
    }

    /** Returns null when the reference-image ID is unknown. */
    public ReferenceImageMetadata getReferenceImage(String referenceImageId) {
        requireId(referenceImageId, "referenceImageId");
        List<ReferenceImageMetadata> values = queryReferenceImages(
                "SELECT * FROM " + AssemblArContract.ReferenceImages.TABLE + " WHERE "
                        + AssemblArContract.ReferenceImages.ID + " = ? LIMIT 1",
                new String[]{referenceImageId});
        return values.isEmpty() ? null : values.get(0);
    }

    private List<ReferenceImageMetadata> queryReferenceImages(String sql, String[] args) {
        List<ReferenceImageMetadata> values = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                values.add(readReferenceImage(cursor));
            }
        }
        return immutable(values);
    }

    public void saveOrbDescriptor(OrbDescriptorData descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (getReferenceImage(descriptor.getReferenceImageId()) == null) {
            throw new IllegalArgumentException(
                    "Unknown reference image ID: " + descriptor.getReferenceImageId());
        }
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.OrbDescriptors.ID, descriptor.getId());
        values.put(AssemblArContract.OrbDescriptors.REFERENCE_IMAGE_ID, descriptor.getReferenceImageId());
        values.put(AssemblArContract.OrbDescriptors.ALGORITHM_VERSION, descriptor.getAlgorithmVersion());
        values.put(AssemblArContract.OrbDescriptors.PARAMETERS_JSON, descriptor.getParametersJson());
        values.put(AssemblArContract.OrbDescriptors.DESCRIPTOR_ROWS, descriptor.getDescriptorRows());
        values.put(AssemblArContract.OrbDescriptors.DESCRIPTOR_COLUMNS, descriptor.getDescriptorColumns());
        values.put(AssemblArContract.OrbDescriptors.OPENCV_TYPE, descriptor.getOpenCvType());
        values.put(AssemblArContract.OrbDescriptors.FEATURE_COUNT, descriptor.getFeatureCount());
        values.put(AssemblArContract.OrbDescriptors.KEYPOINTS_BLOB, descriptor.getKeypointsBlob());
        values.put(AssemblArContract.OrbDescriptors.DESCRIPTORS_BLOB, descriptor.getDescriptorsBlob());
        upsert(helper.getWritableDatabase(), AssemblArContract.OrbDescriptors.TABLE,
                AssemblArContract.OrbDescriptors.REFERENCE_IMAGE_ID,
                descriptor.getReferenceImageId(), values);
    }

    /** Returns null until descriptors have been compiled for the reference image. */
    public OrbDescriptorData getOrbDescriptor(String referenceImageId) {
        requireId(referenceImageId, "referenceImageId");
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(AssemblArContract.OrbDescriptors.TABLE, null,
                AssemblArContract.OrbDescriptors.REFERENCE_IMAGE_ID + " = ?",
                new String[]{referenceImageId}, null, null, null, "1")) {
            return cursor.moveToFirst() ? readOrbDescriptor(cursor) : null;
        }
    }

    public List<OrbDescriptorData> getActiveOrbDescriptors(String componentId) {
        requireId(componentId, "componentId");
        List<OrbDescriptorData> values = new ArrayList<>();
        String sql = "SELECT d.* FROM " + AssemblArContract.OrbDescriptors.TABLE + " d JOIN "
                + AssemblArContract.ReferenceImages.TABLE + " i ON i."
                + AssemblArContract.ReferenceImages.ID + " = d."
                + AssemblArContract.OrbDescriptors.REFERENCE_IMAGE_ID + " JOIN "
                + AssemblArContract.Registrations.TABLE + " r ON r."
                + AssemblArContract.Registrations.ID + " = i."
                + AssemblArContract.ReferenceImages.REGISTRATION_ID + " WHERE i."
                + AssemblArContract.ReferenceImages.COMPONENT_ID + " = ? AND r."
                + AssemblArContract.Registrations.ACTIVE + " = 1";
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(sql, new String[]{componentId})) {
            while (cursor.moveToNext()) {
                values.add(readOrbDescriptor(cursor));
            }
        }
        return immutable(values);
    }

    public void saveSpatialMetadata(SpatialMetadata spatial) {
        Objects.requireNonNull(spatial, "spatial");
        if (getComponent(spatial.getComponentId()) == null) {
            throw new IllegalArgumentException("Unknown component ID: " + spatial.getComponentId());
        }
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.Spatial.COMPONENT_ID, spatial.getComponentId());
        values.put(AssemblArContract.Spatial.WIDTH_MILLIMETERS, spatial.getWidthMillimeters());
        values.put(AssemblArContract.Spatial.HEIGHT_MILLIMETERS, spatial.getHeightMillimeters());
        values.put(AssemblArContract.Spatial.DEPTH_MILLIMETERS, spatial.getDepthMillimeters());
        values.put(AssemblArContract.Spatial.COORDINATE_FRAME, spatial.getCoordinateFrame());
        values.put(AssemblArContract.Spatial.ANCHOR_POINTS_JSON, spatial.getAnchorPointsJson());
        values.put(AssemblArContract.Spatial.ORIENTATION_RULES_JSON, spatial.getOrientationRulesJson());
        values.put(AssemblArContract.Spatial.OBJECT_POINTS_BLOB, spatial.getObjectPointsBlob());
        values.put(AssemblArContract.Spatial.MODEL_ASSET_PATH, spatial.getModelAssetPath());
        values.put(AssemblArContract.Spatial.UPDATED_AT, spatial.getUpdatedAtEpochMillis());
        upsert(helper.getWritableDatabase(), AssemblArContract.Spatial.TABLE,
                AssemblArContract.Spatial.COMPONENT_ID, spatial.getComponentId(), values);
    }

    /** Returns null only if metadata has been removed or the component is unknown. */
    public SpatialMetadata getSpatialMetadata(String componentId) {
        requireId(componentId, "componentId");
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(AssemblArContract.Spatial.TABLE, null,
                AssemblArContract.Spatial.COMPONENT_ID + " = ?", new String[]{componentId},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? readSpatialMetadata(cursor) : null;
        }
    }

    public SeedIntegrityReport verifySeedIntegrity() {
        List<String> errors = new ArrayList<>(NeobotSeedData.validate().getErrors());
        SQLiteDatabase db = helper.getReadableDatabase();
        assertCount(errors, db, AssemblArContract.Projects.TABLE, 1);
        assertCount(errors, db, AssemblArContract.Steps.TABLE, 18);
        assertCount(errors, db, AssemblArContract.Components.TABLE,
                NeobotSeedData.getComponents().size());
        assertCount(errors, db, AssemblArContract.Progress.TABLE, 18);
        assertCount(errors, db, AssemblArContract.LearningModules.TABLE, 7);
        long exactSignalMaps = DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM " + AssemblArContract.Connections.TABLE + " WHERE "
                        + AssemblArContract.Connections.STEP_ID + " = ? AND (("
                        + AssemblArContract.Connections.SOURCE_PIN + " = 'A-1A' AND "
                        + AssemblArContract.Connections.TARGET_PIN + " = 'D5 / PWM') OR ("
                        + AssemblArContract.Connections.SOURCE_PIN + " = 'A-1B' AND "
                        + AssemblArContract.Connections.TARGET_PIN + " = 'D9 / PWM') OR ("
                        + AssemblArContract.Connections.SOURCE_PIN + " = 'B-1A' AND "
                        + AssemblArContract.Connections.TARGET_PIN + " = 'D6 / PWM') OR ("
                        + AssemblArContract.Connections.SOURCE_PIN + " = 'B-1B' AND "
                        + AssemblArContract.Connections.TARGET_PIN + " = 'D10 / PWM'))",
                new String[]{"neobot_step_08"});
        if (exactSignalMaps != 4L) {
            errors.add("Database is missing one or more exact motor-driver signal maps");
        }
        try (Cursor cursor = db.rawQuery("PRAGMA foreign_key_check", null)) {
            if (cursor.moveToFirst()) {
                errors.add("Database contains foreign-key violations");
            }
        }
        return new SeedIntegrityReport(errors);
    }

    private static void assertCount(
            List<String> errors,
            SQLiteDatabase db,
            String table,
            long expected) {
        long actual = DatabaseUtils.queryNumEntries(db, table);
        if (actual != expected) {
            errors.add(table + " expected " + expected + " rows, found " + actual);
        }
    }

    private ComponentRegistration readRegistration(Cursor cursor) {
        return new ComponentRegistration(
                text(cursor, AssemblArContract.Registrations.ID),
                text(cursor, AssemblArContract.Registrations.COMPONENT_ID),
                text(cursor, AssemblArContract.Registrations.ADMINISTRATOR_ID),
                text(cursor, AssemblArContract.Registrations.DISPLAY_NAME),
                text(cursor, AssemblArContract.Registrations.DESCRIPTION),
                text(cursor, AssemblArContract.Registrations.PURPOSE),
                text(cursor, AssemblArContract.Registrations.ASSEMBLY_USAGE),
                text(cursor, AssemblArContract.Registrations.MANUAL_REFERENCE),
                integer(cursor, AssemblArContract.Registrations.ACTIVE) != 0,
                longValue(cursor, AssemblArContract.Registrations.CREATED_AT),
                longValue(cursor, AssemblArContract.Registrations.UPDATED_AT));
    }

    private ReferenceImageMetadata readReferenceImage(Cursor cursor) {
        return new ReferenceImageMetadata(
                text(cursor, AssemblArContract.ReferenceImages.ID),
                text(cursor, AssemblArContract.ReferenceImages.REGISTRATION_ID),
                text(cursor, AssemblArContract.ReferenceImages.COMPONENT_ID),
                text(cursor, AssemblArContract.ReferenceImages.IMAGE_PATH),
                text(cursor, AssemblArContract.ReferenceImages.ANGLE_LABEL),
                doubleValue(cursor, AssemblArContract.ReferenceImages.AZIMUTH_DEGREES),
                doubleValue(cursor, AssemblArContract.ReferenceImages.ELEVATION_DEGREES),
                doubleValue(cursor, AssemblArContract.ReferenceImages.DISTANCE_MILLIMETERS),
                text(cursor, AssemblArContract.ReferenceImages.LIGHTING_CONDITION),
                integer(cursor, AssemblArContract.ReferenceImages.IS_SYNTHETIC) != 0,
                integer(cursor, AssemblArContract.ReferenceImages.PIXEL_WIDTH),
                integer(cursor, AssemblArContract.ReferenceImages.PIXEL_HEIGHT),
                text(cursor, AssemblArContract.ReferenceImages.SHA256),
                longValue(cursor, AssemblArContract.ReferenceImages.CAPTURED_AT));
    }

    private OrbDescriptorData readOrbDescriptor(Cursor cursor) {
        return new OrbDescriptorData(
                text(cursor, AssemblArContract.OrbDescriptors.ID),
                text(cursor, AssemblArContract.OrbDescriptors.REFERENCE_IMAGE_ID),
                text(cursor, AssemblArContract.OrbDescriptors.ALGORITHM_VERSION),
                text(cursor, AssemblArContract.OrbDescriptors.PARAMETERS_JSON),
                integer(cursor, AssemblArContract.OrbDescriptors.DESCRIPTOR_ROWS),
                integer(cursor, AssemblArContract.OrbDescriptors.DESCRIPTOR_COLUMNS),
                integer(cursor, AssemblArContract.OrbDescriptors.OPENCV_TYPE),
                integer(cursor, AssemblArContract.OrbDescriptors.FEATURE_COUNT),
                blob(cursor, AssemblArContract.OrbDescriptors.KEYPOINTS_BLOB),
                blob(cursor, AssemblArContract.OrbDescriptors.DESCRIPTORS_BLOB));
    }

    private SpatialMetadata readSpatialMetadata(Cursor cursor) {
        return new SpatialMetadata(
                text(cursor, AssemblArContract.Spatial.COMPONENT_ID),
                doubleValue(cursor, AssemblArContract.Spatial.WIDTH_MILLIMETERS),
                doubleValue(cursor, AssemblArContract.Spatial.HEIGHT_MILLIMETERS),
                doubleValue(cursor, AssemblArContract.Spatial.DEPTH_MILLIMETERS),
                text(cursor, AssemblArContract.Spatial.COORDINATE_FRAME),
                text(cursor, AssemblArContract.Spatial.ANCHOR_POINTS_JSON),
                text(cursor, AssemblArContract.Spatial.ORIENTATION_RULES_JSON),
                blob(cursor, AssemblArContract.Spatial.OBJECT_POINTS_BLOB),
                text(cursor, AssemblArContract.Spatial.MODEL_ASSET_PATH),
                longValue(cursor, AssemblArContract.Spatial.UPDATED_AT));
    }

    private static ManualReference readManualReference(
            Cursor cursor,
            String documentIdColumn,
            String documentTitleColumn,
            String pageStartColumn,
            String pageEndColumn,
            String assetPathsColumn) {
        return new ManualReference(
                text(cursor, documentIdColumn),
                text(cursor, documentTitleColumn),
                integer(cursor, pageStartColumn),
                integer(cursor, pageEndColumn),
                AssemblArDatabaseHelper.splitPaths(text(cursor, assetPathsColumn)));
    }

    private AssemblyStep requireStep(String stepId) {
        AssemblyStep step = getStep(stepId);
        if (step == null) {
            throw new IllegalArgumentException("Unknown step ID: " + stepId);
        }
        return step;
    }

    private long getProjectRowCount(String projectId) {
        return DatabaseUtils.queryNumEntries(helper.getReadableDatabase(),
                AssemblArContract.Projects.TABLE,
                AssemblArContract.Projects.ID + " = ?", new String[]{projectId});
    }

    private static void upsert(
            SQLiteDatabase db,
            String table,
            String idColumn,
            String id,
            ContentValues values) {
        int rows = db.update(table, values, idColumn + " = ?", new String[]{id});
        if (rows == 0) {
            db.insertOrThrow(table, null, values);
        }
    }

    private static void requireId(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    private static String text(Cursor cursor, String column) {
        return cursor.getString(cursor.getColumnIndexOrThrow(column));
    }

    private static int integer(Cursor cursor, String column) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(column));
    }

    private static long longValue(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private static double doubleValue(Cursor cursor, String column) {
        return cursor.getDouble(cursor.getColumnIndexOrThrow(column));
    }

    private static byte[] blob(Cursor cursor, String column) {
        byte[] value = cursor.getBlob(cursor.getColumnIndexOrThrow(column));
        return value == null ? new byte[0] : value;
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(values);
    }

    @Override
    public void close() {
        helper.close();
    }
}
