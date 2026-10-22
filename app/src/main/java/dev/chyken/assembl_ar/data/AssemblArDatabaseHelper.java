package dev.chyken.assembl_ar.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyProject;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.ComponentCategory;
import dev.chyken.assembl_ar.model.LearningModule;
import dev.chyken.assembl_ar.model.LearningStep;
import dev.chyken.assembl_ar.model.ManualReference;
import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.model.RequiredComponent;
import dev.chyken.assembl_ar.model.StepStatus;

import java.util.List;
import java.util.Locale;

final class AssemblArDatabaseHelper extends SQLiteOpenHelper {
    private static final String PATH_SEPARATOR = "\u001F";

    AssemblArDatabaseHelper(Context context) {
        super(context.getApplicationContext(), AssemblArContract.DATABASE_NAME, null,
                AssemblArContract.DATABASE_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        super.onConfigure(database);
        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        createSchema(database);
        seedCanonicalContent(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            refreshCanonicalAssemblyContent(database);
            oldVersion = 2;
        }
        if ((oldVersion == 1 || oldVersion == 2) && newVersion >= 3) {
            // Version 3 assigned reviewed gray-background photographs to the early guided cards.
            // Reusing the canonical refresh preserves progress and administrator registrations.
            refreshCanonicalAssemblyContent(database);
            oldVersion = 3;
        }
        if (oldVersion == 3 && newVersion >= 4) {
            // Version 4 stores the instructional photographs as WebP instead of PNG. Refreshing
            // canonical rows replaces persisted asset extensions while preserving user state.
            refreshCanonicalAssemblyContent(database);
            oldVersion = 4;
        }
        if (oldVersion == 4 && newVersion >= 5) {
            // Version 5 keeps workspace advice as context instead of a compulsory guided check.
            // Stable section IDs preserve progress; the main-board action keeps its stable ID.
            refreshCanonicalAssemblyContent(database);
            oldVersion = 5;
        }
        if(oldVersion==5&&newVersion>=6){
            // Phase 9 now removes and saves the standoff top screw. Refresh canonical text
            // for existing installs; stable section IDs retain progress and custom references.
            refreshCanonicalAssemblyContent(database);
            oldVersion=6;
        }
        if(oldVersion==6&&newVersion>=7){
            // Phase 10 specifies 20mm protector screws and the combined extension-board flow.
            // Canonical refresh retains progress, administrator references and stable section IDs.
            refreshCanonicalAssemblyContent(database);oldVersion=7;
        }
        if (oldVersion != newVersion) {
            throw new IllegalStateException(
                    "No database migration from version " + oldVersion + " to " + newVersion);
        }
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + AssemblArContract.Projects.TABLE + " ("
                + AssemblArContract.Projects.ID + " TEXT PRIMARY KEY, "
                + AssemblArContract.Projects.TITLE + " TEXT NOT NULL, "
                + AssemblArContract.Projects.KIT_NAME + " TEXT NOT NULL, "
                + AssemblArContract.Projects.DESCRIPTION + " TEXT NOT NULL, "
                + AssemblArContract.Projects.MANUAL_DOCUMENT_ID + " TEXT NOT NULL, "
                + AssemblArContract.Projects.MANUAL_DOCUMENT_TITLE + " TEXT NOT NULL, "
                + AssemblArContract.Projects.PAGE_START + " INTEGER NOT NULL, "
                + AssemblArContract.Projects.PAGE_END + " INTEGER NOT NULL, "
                + AssemblArContract.Projects.ASSET_PATHS + " TEXT NOT NULL)");

        db.execSQL("CREATE TABLE " + AssemblArContract.Components.TABLE + " ("
                + AssemblArContract.Components.ID + " TEXT PRIMARY KEY, "
                + AssemblArContract.Components.NAME + " TEXT NOT NULL, "
                + AssemblArContract.Components.CATEGORY + " TEXT NOT NULL, "
                + AssemblArContract.Components.KIT_QUANTITY + " INTEGER NOT NULL CHECK("
                + AssemblArContract.Components.KIT_QUANTITY + " >= 0), "
                + AssemblArContract.Components.DESCRIPTION + " TEXT NOT NULL, "
                + AssemblArContract.Components.PURPOSE + " TEXT NOT NULL, "
                + AssemblArContract.Components.ASSEMBLY_USAGE + " TEXT NOT NULL, "
                + AssemblArContract.Components.RECOGNITION_NOTES + " TEXT NOT NULL, "
                + AssemblArContract.Components.MANUAL_DOCUMENT_ID + " TEXT NOT NULL, "
                + AssemblArContract.Components.MANUAL_DOCUMENT_TITLE + " TEXT NOT NULL, "
                + AssemblArContract.Components.PAGE_START + " INTEGER NOT NULL, "
                + AssemblArContract.Components.PAGE_END + " INTEGER NOT NULL, "
                + AssemblArContract.Components.ASSET_PATHS + " TEXT NOT NULL)");

        db.execSQL("CREATE TABLE " + AssemblArContract.Steps.TABLE + " ("
                + AssemblArContract.Steps.ID + " TEXT PRIMARY KEY, "
                + AssemblArContract.Steps.PROJECT_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Projects.TABLE + "(" + AssemblArContract.Projects.ID + ") ON DELETE CASCADE, "
                + AssemblArContract.Steps.STEP_NUMBER + " INTEGER NOT NULL, "
                + AssemblArContract.Steps.TITLE + " TEXT NOT NULL, "
                + AssemblArContract.Steps.SUMMARY + " TEXT NOT NULL, "
                + AssemblArContract.Steps.VALIDATION_TYPE + " TEXT NOT NULL, "
                + AssemblArContract.Steps.VALIDATION_PROMPT + " TEXT NOT NULL, "
                + AssemblArContract.Steps.MANUAL_DOCUMENT_ID + " TEXT NOT NULL, "
                + AssemblArContract.Steps.MANUAL_DOCUMENT_TITLE + " TEXT NOT NULL, "
                + AssemblArContract.Steps.PAGE_START + " INTEGER NOT NULL, "
                + AssemblArContract.Steps.PAGE_END + " INTEGER NOT NULL, "
                + AssemblArContract.Steps.ASSET_PATHS + " TEXT NOT NULL, "
                + "UNIQUE(" + AssemblArContract.Steps.PROJECT_ID + ", "
                + AssemblArContract.Steps.STEP_NUMBER + "))");

        db.execSQL("CREATE TABLE " + AssemblArContract.Instructions.TABLE + " ("
                + AssemblArContract.Instructions.STEP_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Steps.TABLE + "(" + AssemblArContract.Steps.ID + ") ON DELETE CASCADE, "
                + AssemblArContract.Instructions.ORDER_INDEX + " INTEGER NOT NULL, "
                + AssemblArContract.Instructions.TYPE + " TEXT NOT NULL, "
                + AssemblArContract.Instructions.TEXT + " TEXT NOT NULL, "
                + AssemblArContract.Instructions.MANUAL_PAGE + " INTEGER NOT NULL, "
                + AssemblArContract.Instructions.REFERENCE_IMAGE_PATH + " TEXT NOT NULL, "
                + "PRIMARY KEY(" + AssemblArContract.Instructions.STEP_ID + ", "
                + AssemblArContract.Instructions.ORDER_INDEX + "))");

        db.execSQL("CREATE TABLE " + AssemblArContract.Requirements.TABLE + " ("
                + AssemblArContract.Requirements.STEP_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Steps.TABLE + "(" + AssemblArContract.Steps.ID + ") ON DELETE CASCADE, "
                + AssemblArContract.Requirements.COMPONENT_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Components.TABLE + "(" + AssemblArContract.Components.ID + "), "
                + AssemblArContract.Requirements.QUANTITY + " INTEGER NOT NULL, "
                + AssemblArContract.Requirements.NOTES + " TEXT NOT NULL, "
                + "PRIMARY KEY(" + AssemblArContract.Requirements.STEP_ID + ", "
                + AssemblArContract.Requirements.COMPONENT_ID + "))");

        db.execSQL("CREATE TABLE " + AssemblArContract.Connections.TABLE + " ("
                + AssemblArContract.Connections.STEP_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Steps.TABLE + "(" + AssemblArContract.Steps.ID + ") ON DELETE CASCADE, "
                + AssemblArContract.Connections.ORDER_INDEX + " INTEGER NOT NULL, "
                + AssemblArContract.Connections.SOURCE_COMPONENT_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Components.TABLE + "(" + AssemblArContract.Components.ID + "), "
                + AssemblArContract.Connections.SOURCE_PIN + " TEXT NOT NULL, "
                + AssemblArContract.Connections.TARGET_COMPONENT_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Components.TABLE + "(" + AssemblArContract.Components.ID + "), "
                + AssemblArContract.Connections.TARGET_PIN + " TEXT NOT NULL, "
                + AssemblArContract.Connections.WIRE_COLOR + " TEXT NOT NULL, "
                + AssemblArContract.Connections.SIGNAL + " TEXT NOT NULL, "
                + AssemblArContract.Connections.NOTES + " TEXT NOT NULL, "
                + "PRIMARY KEY(" + AssemblArContract.Connections.STEP_ID + ", "
                + AssemblArContract.Connections.ORDER_INDEX + "))");

        db.execSQL("CREATE TABLE " + AssemblArContract.StepOverlays.TABLE + " ("
                + AssemblArContract.StepOverlays.STEP_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Steps.TABLE + "(" + AssemblArContract.Steps.ID + ") ON DELETE CASCADE, "
                + AssemblArContract.StepOverlays.ORDER_INDEX + " INTEGER NOT NULL, "
                + AssemblArContract.StepOverlays.OVERLAY_TYPE + " TEXT NOT NULL, "
                + "PRIMARY KEY(" + AssemblArContract.StepOverlays.STEP_ID + ", "
                + AssemblArContract.StepOverlays.ORDER_INDEX + "))");

        db.execSQL("CREATE TABLE " + AssemblArContract.Progress.TABLE + " ("
                + AssemblArContract.Progress.PROJECT_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Projects.TABLE + "(" + AssemblArContract.Projects.ID + ") ON DELETE CASCADE, "
                + AssemblArContract.Progress.STEP_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Steps.TABLE + "(" + AssemblArContract.Steps.ID + ") ON DELETE CASCADE, "
                + AssemblArContract.Progress.STATUS + " TEXT NOT NULL, "
                + AssemblArContract.Progress.VALIDATION_CONFIRMED + " INTEGER NOT NULL DEFAULT 0, "
                + AssemblArContract.Progress.UPDATED_AT + " INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(" + AssemblArContract.Progress.PROJECT_ID + ", "
                + AssemblArContract.Progress.STEP_ID + "))");

        createLearningSchema(db);
        createRegistrationSchema(db);

        db.execSQL("CREATE INDEX index_steps_project ON " + AssemblArContract.Steps.TABLE + "("
                + AssemblArContract.Steps.PROJECT_ID + ", " + AssemblArContract.Steps.STEP_NUMBER + ")");
        db.execSQL("CREATE INDEX index_connections_step ON " + AssemblArContract.Connections.TABLE + "("
                + AssemblArContract.Connections.STEP_ID + ", " + AssemblArContract.Connections.ORDER_INDEX + ")");
        db.execSQL("CREATE INDEX index_reference_component ON " + AssemblArContract.ReferenceImages.TABLE + "("
                + AssemblArContract.ReferenceImages.COMPONENT_ID + ")");
    }

    private static void createLearningSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + AssemblArContract.LearningModules.TABLE + " ("
                + AssemblArContract.LearningModules.ID + " TEXT PRIMARY KEY, "
                + AssemblArContract.LearningModules.ORDER_INDEX + " INTEGER NOT NULL UNIQUE, "
                + AssemblArContract.LearningModules.TITLE + " TEXT NOT NULL, "
                + AssemblArContract.LearningModules.OVERVIEW + " TEXT NOT NULL, "
                + AssemblArContract.LearningModules.PREREQUISITE_MODULE_ID + " TEXT NOT NULL, "
                + AssemblArContract.LearningModules.MANUAL_DOCUMENT_ID + " TEXT NOT NULL, "
                + AssemblArContract.LearningModules.MANUAL_DOCUMENT_TITLE + " TEXT NOT NULL, "
                + AssemblArContract.LearningModules.PAGE_START + " INTEGER NOT NULL, "
                + AssemblArContract.LearningModules.PAGE_END + " INTEGER NOT NULL, "
                + AssemblArContract.LearningModules.ASSET_PATHS + " TEXT NOT NULL)");

        db.execSQL("CREATE TABLE " + AssemblArContract.LearningSteps.TABLE + " ("
                + AssemblArContract.LearningSteps.ID + " TEXT PRIMARY KEY, "
                + AssemblArContract.LearningSteps.MODULE_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.LearningModules.TABLE + "(" + AssemblArContract.LearningModules.ID
                + ") ON DELETE CASCADE, "
                + AssemblArContract.LearningSteps.ORDER_INDEX + " INTEGER NOT NULL, "
                + AssemblArContract.LearningSteps.TITLE + " TEXT NOT NULL, "
                + AssemblArContract.LearningSteps.CONTENT_TYPE + " TEXT NOT NULL, "
                + AssemblArContract.LearningSteps.CONTENT + " TEXT NOT NULL, "
                + AssemblArContract.LearningSteps.CODE_SNIPPET + " TEXT NOT NULL, "
                + AssemblArContract.LearningSteps.WARNING + " TEXT NOT NULL, "
                + AssemblArContract.LearningSteps.DEPENDENCY + " TEXT NOT NULL, "
                + AssemblArContract.LearningSteps.MANUAL_DOCUMENT_ID + " TEXT NOT NULL, "
                + AssemblArContract.LearningSteps.MANUAL_DOCUMENT_TITLE + " TEXT NOT NULL, "
                + AssemblArContract.LearningSteps.PAGE_START + " INTEGER NOT NULL, "
                + AssemblArContract.LearningSteps.PAGE_END + " INTEGER NOT NULL, "
                + AssemblArContract.LearningSteps.ASSET_PATHS + " TEXT NOT NULL, "
                + "UNIQUE(" + AssemblArContract.LearningSteps.MODULE_ID + ", "
                + AssemblArContract.LearningSteps.ORDER_INDEX + "))");
    }

    private static void createRegistrationSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + AssemblArContract.Registrations.TABLE + " ("
                + AssemblArContract.Registrations.ID + " TEXT PRIMARY KEY, "
                + AssemblArContract.Registrations.COMPONENT_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Components.TABLE + "(" + AssemblArContract.Components.ID + "), "
                + AssemblArContract.Registrations.ADMINISTRATOR_ID + " TEXT NOT NULL, "
                + AssemblArContract.Registrations.DISPLAY_NAME + " TEXT NOT NULL, "
                + AssemblArContract.Registrations.DESCRIPTION + " TEXT NOT NULL, "
                + AssemblArContract.Registrations.PURPOSE + " TEXT NOT NULL, "
                + AssemblArContract.Registrations.ASSEMBLY_USAGE + " TEXT NOT NULL, "
                + AssemblArContract.Registrations.MANUAL_REFERENCE + " TEXT NOT NULL, "
                + AssemblArContract.Registrations.ACTIVE + " INTEGER NOT NULL DEFAULT 1, "
                + AssemblArContract.Registrations.CREATED_AT + " INTEGER NOT NULL, "
                + AssemblArContract.Registrations.UPDATED_AT + " INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + AssemblArContract.ReferenceImages.TABLE + " ("
                + AssemblArContract.ReferenceImages.ID + " TEXT PRIMARY KEY, "
                + AssemblArContract.ReferenceImages.REGISTRATION_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Registrations.TABLE + "(" + AssemblArContract.Registrations.ID
                + ") ON DELETE CASCADE, "
                + AssemblArContract.ReferenceImages.COMPONENT_ID + " TEXT NOT NULL REFERENCES "
                + AssemblArContract.Components.TABLE + "(" + AssemblArContract.Components.ID + "), "
                + AssemblArContract.ReferenceImages.IMAGE_PATH + " TEXT NOT NULL, "
                + AssemblArContract.ReferenceImages.ANGLE_LABEL + " TEXT NOT NULL, "
                + AssemblArContract.ReferenceImages.AZIMUTH_DEGREES + " REAL NOT NULL, "
                + AssemblArContract.ReferenceImages.ELEVATION_DEGREES + " REAL NOT NULL, "
                + AssemblArContract.ReferenceImages.DISTANCE_MILLIMETERS + " REAL NOT NULL, "
                + AssemblArContract.ReferenceImages.LIGHTING_CONDITION + " TEXT NOT NULL, "
                + AssemblArContract.ReferenceImages.IS_SYNTHETIC + " INTEGER NOT NULL, "
                + AssemblArContract.ReferenceImages.PIXEL_WIDTH + " INTEGER NOT NULL, "
                + AssemblArContract.ReferenceImages.PIXEL_HEIGHT + " INTEGER NOT NULL, "
                + AssemblArContract.ReferenceImages.SHA256 + " TEXT NOT NULL, "
                + AssemblArContract.ReferenceImages.CAPTURED_AT + " INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + AssemblArContract.OrbDescriptors.TABLE + " ("
                + AssemblArContract.OrbDescriptors.ID + " TEXT PRIMARY KEY, "
                + AssemblArContract.OrbDescriptors.REFERENCE_IMAGE_ID + " TEXT NOT NULL UNIQUE REFERENCES "
                + AssemblArContract.ReferenceImages.TABLE + "(" + AssemblArContract.ReferenceImages.ID
                + ") ON DELETE CASCADE, "
                + AssemblArContract.OrbDescriptors.ALGORITHM_VERSION + " TEXT NOT NULL, "
                + AssemblArContract.OrbDescriptors.PARAMETERS_JSON + " TEXT NOT NULL, "
                + AssemblArContract.OrbDescriptors.DESCRIPTOR_ROWS + " INTEGER NOT NULL, "
                + AssemblArContract.OrbDescriptors.DESCRIPTOR_COLUMNS + " INTEGER NOT NULL, "
                + AssemblArContract.OrbDescriptors.OPENCV_TYPE + " INTEGER NOT NULL, "
                + AssemblArContract.OrbDescriptors.FEATURE_COUNT + " INTEGER NOT NULL, "
                + AssemblArContract.OrbDescriptors.KEYPOINTS_BLOB + " BLOB NOT NULL, "
                + AssemblArContract.OrbDescriptors.DESCRIPTORS_BLOB + " BLOB NOT NULL)");

        db.execSQL("CREATE TABLE " + AssemblArContract.Spatial.TABLE + " ("
                + AssemblArContract.Spatial.COMPONENT_ID + " TEXT PRIMARY KEY REFERENCES "
                + AssemblArContract.Components.TABLE + "(" + AssemblArContract.Components.ID + ") ON DELETE CASCADE, "
                + AssemblArContract.Spatial.WIDTH_MILLIMETERS + " REAL NOT NULL, "
                + AssemblArContract.Spatial.HEIGHT_MILLIMETERS + " REAL NOT NULL, "
                + AssemblArContract.Spatial.DEPTH_MILLIMETERS + " REAL NOT NULL, "
                + AssemblArContract.Spatial.COORDINATE_FRAME + " TEXT NOT NULL, "
                + AssemblArContract.Spatial.ANCHOR_POINTS_JSON + " TEXT NOT NULL, "
                + AssemblArContract.Spatial.ORIENTATION_RULES_JSON + " TEXT NOT NULL, "
                + AssemblArContract.Spatial.OBJECT_POINTS_BLOB + " BLOB NOT NULL, "
                + AssemblArContract.Spatial.MODEL_ASSET_PATH + " TEXT NOT NULL, "
                + AssemblArContract.Spatial.UPDATED_AT + " INTEGER NOT NULL)");
    }

    private static void seedCanonicalContent(SQLiteDatabase db) {
        AssemblyProject project = NeobotSeedData.getProject();
        ContentValues projectValues = new ContentValues();
        projectValues.put(AssemblArContract.Projects.ID, project.getId());
        projectValues.put(AssemblArContract.Projects.TITLE, project.getTitle());
        projectValues.put(AssemblArContract.Projects.KIT_NAME, project.getKitName());
        projectValues.put(AssemblArContract.Projects.DESCRIPTION, project.getDescription());
        putManualReference(projectValues, project.getManualReference(),
                AssemblArContract.Projects.MANUAL_DOCUMENT_ID,
                AssemblArContract.Projects.MANUAL_DOCUMENT_TITLE,
                AssemblArContract.Projects.PAGE_START,
                AssemblArContract.Projects.PAGE_END,
                AssemblArContract.Projects.ASSET_PATHS);
        db.insertOrThrow(AssemblArContract.Projects.TABLE, null, projectValues);

        for (Component component : NeobotSeedData.getComponents()) {
            insertComponent(db, component);
        }
        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            insertStep(db, step);
        }
        for (LearningModule module : NeobotSeedData.getLearningModules()) {
            insertLearningModule(db, module);
        }
        seedBundledRecognitionMetadata(db);
    }

    /**
     * Replaces the bundled manual-derived content while preserving progress and administrator data.
     * Stable project, component and section IDs let existing foreign keys remain valid.
     */
    private static void refreshCanonicalAssemblyContent(SQLiteDatabase db) {
        AssemblyProject project = NeobotSeedData.getProject();
        ContentValues projectValues = projectValues(project);
        int updatedProjects = db.update(
                AssemblArContract.Projects.TABLE,
                projectValues,
                AssemblArContract.Projects.ID + " = ?",
                new String[]{project.getId()});
        if (updatedProjects == 0) {
            db.insertOrThrow(AssemblArContract.Projects.TABLE, null, projectValues);
        }

        for (Component component : NeobotSeedData.getComponents()) {
            ContentValues values = componentValues(component);
            int updatedComponents = db.update(
                    AssemblArContract.Components.TABLE,
                    values,
                    AssemblArContract.Components.ID + " = ?",
                    new String[]{component.getId()});
            if (updatedComponents == 0) {
                db.insertOrThrow(AssemblArContract.Components.TABLE, null, values);
            }
        }

        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            ContentValues values = stepValues(step);
            int updatedSteps = db.update(
                    AssemblArContract.Steps.TABLE,
                    values,
                    AssemblArContract.Steps.ID + " = ?",
                    new String[]{step.getId()});
            if (updatedSteps == 0) {
                insertStep(db, step);
                continue;
            }

            String selection = AssemblArContract.Instructions.STEP_ID + " = ?";
            String[] selectionArgs = new String[]{step.getId()};
            db.delete(AssemblArContract.Instructions.TABLE, selection, selectionArgs);
            db.delete(AssemblArContract.Requirements.TABLE, selection, selectionArgs);
            db.delete(AssemblArContract.Connections.TABLE, selection, selectionArgs);
            db.delete(AssemblArContract.StepOverlays.TABLE, selection, selectionArgs);
            insertStepContent(db, step);
        }
    }

    private static ContentValues projectValues(AssemblyProject project) {
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.Projects.ID, project.getId());
        values.put(AssemblArContract.Projects.TITLE, project.getTitle());
        values.put(AssemblArContract.Projects.KIT_NAME, project.getKitName());
        values.put(AssemblArContract.Projects.DESCRIPTION, project.getDescription());
        putManualReference(values, project.getManualReference(),
                AssemblArContract.Projects.MANUAL_DOCUMENT_ID,
                AssemblArContract.Projects.MANUAL_DOCUMENT_TITLE,
                AssemblArContract.Projects.PAGE_START,
                AssemblArContract.Projects.PAGE_END,
                AssemblArContract.Projects.ASSET_PATHS);
        return values;
    }

    private static void insertComponent(SQLiteDatabase db, Component component) {
        db.insertOrThrow(AssemblArContract.Components.TABLE, null, componentValues(component));
    }

    private static ContentValues componentValues(Component component) {
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.Components.ID, component.getId());
        values.put(AssemblArContract.Components.NAME, component.getName());
        values.put(AssemblArContract.Components.CATEGORY, component.getCategory().name());
        values.put(AssemblArContract.Components.KIT_QUANTITY, component.getKitQuantity());
        values.put(AssemblArContract.Components.DESCRIPTION, component.getDescription());
        values.put(AssemblArContract.Components.PURPOSE, component.getPurpose());
        values.put(AssemblArContract.Components.ASSEMBLY_USAGE, component.getAssemblyUsage());
        values.put(AssemblArContract.Components.RECOGNITION_NOTES, component.getRecognitionNotes());
        putManualReference(values, component.getManualReference(),
                AssemblArContract.Components.MANUAL_DOCUMENT_ID,
                AssemblArContract.Components.MANUAL_DOCUMENT_TITLE,
                AssemblArContract.Components.PAGE_START,
                AssemblArContract.Components.PAGE_END,
                AssemblArContract.Components.ASSET_PATHS);
        return values;
    }

    private static void insertStep(SQLiteDatabase db, AssemblyStep step) {
        db.insertOrThrow(AssemblArContract.Steps.TABLE, null, stepValues(step));
        insertStepContent(db, step);

        ContentValues progress = new ContentValues();
        progress.put(AssemblArContract.Progress.PROJECT_ID, step.getProjectId());
        progress.put(AssemblArContract.Progress.STEP_ID, step.getId());
        progress.put(AssemblArContract.Progress.STATUS,
                step.getStepNumber() == 1 ? StepStatus.IN_PROGRESS.name() : StepStatus.NOT_STARTED.name());
        progress.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, 0);
        progress.put(AssemblArContract.Progress.UPDATED_AT, 0L);
        db.insertOrThrow(AssemblArContract.Progress.TABLE, null, progress);
    }

    private static ContentValues stepValues(AssemblyStep step) {
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.Steps.ID, step.getId());
        values.put(AssemblArContract.Steps.PROJECT_ID, step.getProjectId());
        values.put(AssemblArContract.Steps.STEP_NUMBER, step.getStepNumber());
        values.put(AssemblArContract.Steps.TITLE, step.getTitle());
        values.put(AssemblArContract.Steps.SUMMARY, step.getSummary());
        values.put(AssemblArContract.Steps.VALIDATION_TYPE, step.getValidationType().name());
        values.put(AssemblArContract.Steps.VALIDATION_PROMPT, step.getValidationPrompt());
        putManualReference(values, step.getManualReference(),
                AssemblArContract.Steps.MANUAL_DOCUMENT_ID,
                AssemblArContract.Steps.MANUAL_DOCUMENT_TITLE,
                AssemblArContract.Steps.PAGE_START,
                AssemblArContract.Steps.PAGE_END,
                AssemblArContract.Steps.ASSET_PATHS);
        return values;
    }

    private static void insertStepContent(SQLiteDatabase db, AssemblyStep step) {
        for (AssemblyInstruction instruction : step.getInstructions()) {
            ContentValues values = new ContentValues();
            values.put(AssemblArContract.Instructions.STEP_ID, step.getId());
            values.put(AssemblArContract.Instructions.ORDER_INDEX, instruction.getOrder());
            values.put(AssemblArContract.Instructions.TYPE, instruction.getType().name());
            values.put(AssemblArContract.Instructions.TEXT, instruction.getText());
            values.put(AssemblArContract.Instructions.MANUAL_PAGE, instruction.getManualPage());
            values.put(AssemblArContract.Instructions.REFERENCE_IMAGE_PATH,
                    instruction.getReferenceImagePath());
            db.insertOrThrow(AssemblArContract.Instructions.TABLE, null, values);
        }
        for (RequiredComponent requirement : step.getRequiredComponents()) {
            ContentValues values = new ContentValues();
            values.put(AssemblArContract.Requirements.STEP_ID, step.getId());
            values.put(AssemblArContract.Requirements.COMPONENT_ID, requirement.getComponentId());
            values.put(AssemblArContract.Requirements.QUANTITY, requirement.getQuantity());
            values.put(AssemblArContract.Requirements.NOTES, requirement.getNotes());
            db.insertOrThrow(AssemblArContract.Requirements.TABLE, null, values);
        }
        for (PinConnection connection : step.getPinConnections()) {
            ContentValues values = new ContentValues();
            values.put(AssemblArContract.Connections.STEP_ID, step.getId());
            values.put(AssemblArContract.Connections.ORDER_INDEX, connection.getOrder());
            values.put(AssemblArContract.Connections.SOURCE_COMPONENT_ID, connection.getSourceComponentId());
            values.put(AssemblArContract.Connections.SOURCE_PIN, connection.getSourcePin());
            values.put(AssemblArContract.Connections.TARGET_COMPONENT_ID, connection.getTargetComponentId());
            values.put(AssemblArContract.Connections.TARGET_PIN, connection.getTargetPin());
            values.put(AssemblArContract.Connections.WIRE_COLOR, connection.getWireColor());
            values.put(AssemblArContract.Connections.SIGNAL, connection.getSignal());
            values.put(AssemblArContract.Connections.NOTES, connection.getNotes());
            db.insertOrThrow(AssemblArContract.Connections.TABLE, null, values);
        }
        int overlayOrder = 1;
        for (OverlayType overlayType : step.getOverlayTypes()) {
            ContentValues values = new ContentValues();
            values.put(AssemblArContract.StepOverlays.STEP_ID, step.getId());
            values.put(AssemblArContract.StepOverlays.ORDER_INDEX, overlayOrder++);
            values.put(AssemblArContract.StepOverlays.OVERLAY_TYPE, overlayType.name());
            db.insertOrThrow(AssemblArContract.StepOverlays.TABLE, null, values);
        }
    }

    private static void insertLearningModule(SQLiteDatabase db, LearningModule module) {
        ContentValues values = new ContentValues();
        values.put(AssemblArContract.LearningModules.ID, module.getId());
        values.put(AssemblArContract.LearningModules.ORDER_INDEX, module.getOrder());
        values.put(AssemblArContract.LearningModules.TITLE, module.getTitle());
        values.put(AssemblArContract.LearningModules.OVERVIEW, module.getOverview());
        values.put(AssemblArContract.LearningModules.PREREQUISITE_MODULE_ID,
                module.getPrerequisiteModuleId());
        putManualReference(values, module.getManualReference(),
                AssemblArContract.LearningModules.MANUAL_DOCUMENT_ID,
                AssemblArContract.LearningModules.MANUAL_DOCUMENT_TITLE,
                AssemblArContract.LearningModules.PAGE_START,
                AssemblArContract.LearningModules.PAGE_END,
                AssemblArContract.LearningModules.ASSET_PATHS);
        db.insertOrThrow(AssemblArContract.LearningModules.TABLE, null, values);

        for (LearningStep step : module.getSteps()) {
            ContentValues stepValues = new ContentValues();
            stepValues.put(AssemblArContract.LearningSteps.ID, step.getId());
            stepValues.put(AssemblArContract.LearningSteps.MODULE_ID, step.getModuleId());
            stepValues.put(AssemblArContract.LearningSteps.ORDER_INDEX, step.getOrder());
            stepValues.put(AssemblArContract.LearningSteps.TITLE, step.getTitle());
            stepValues.put(AssemblArContract.LearningSteps.CONTENT_TYPE, step.getContentType().name());
            stepValues.put(AssemblArContract.LearningSteps.CONTENT, step.getContent());
            stepValues.put(AssemblArContract.LearningSteps.CODE_SNIPPET, step.getCodeSnippet());
            stepValues.put(AssemblArContract.LearningSteps.WARNING, step.getWarning());
            stepValues.put(AssemblArContract.LearningSteps.DEPENDENCY, step.getDependency());
            putManualReference(stepValues, step.getManualReference(),
                    AssemblArContract.LearningSteps.MANUAL_DOCUMENT_ID,
                    AssemblArContract.LearningSteps.MANUAL_DOCUMENT_TITLE,
                    AssemblArContract.LearningSteps.PAGE_START,
                    AssemblArContract.LearningSteps.PAGE_END,
                    AssemblArContract.LearningSteps.ASSET_PATHS);
            db.insertOrThrow(AssemblArContract.LearningSteps.TABLE, null, stepValues);
        }
    }

    private static void seedBundledRecognitionMetadata(SQLiteDatabase db) {
        for (Component component : NeobotSeedData.getComponents()) {
            if (component.getCategory() == ComponentCategory.TOOL
                    || component.getCategory() == ComponentCategory.CONSUMABLE
                    || component.getCategory() == ComponentCategory.OPTIONAL) {
                continue;
            }
            String registrationId = "bundled_" + component.getId();
            ContentValues registration = new ContentValues();
            registration.put(AssemblArContract.Registrations.ID, registrationId);
            registration.put(AssemblArContract.Registrations.COMPONENT_ID, component.getId());
            registration.put(AssemblArContract.Registrations.ADMINISTRATOR_ID, "bundled_seed");
            registration.put(AssemblArContract.Registrations.DISPLAY_NAME, component.getName());
            registration.put(AssemblArContract.Registrations.DESCRIPTION, component.getDescription());
            registration.put(AssemblArContract.Registrations.PURPOSE, component.getPurpose());
            registration.put(AssemblArContract.Registrations.ASSEMBLY_USAGE, component.getAssemblyUsage());
            registration.put(AssemblArContract.Registrations.MANUAL_REFERENCE,
                    component.getManualReference().getDocumentTitle() + ", pages "
                            + component.getManualReference().getPageStart() + "-"
                            + component.getManualReference().getPageEnd());
            registration.put(AssemblArContract.Registrations.ACTIVE, 1);
            registration.put(AssemblArContract.Registrations.CREATED_AT, 0L);
            registration.put(AssemblArContract.Registrations.UPDATED_AT, 0L);
            db.insertOrThrow(AssemblArContract.Registrations.TABLE, null, registration);

            int imageIndex = 1;
            for (String path : component.getReferenceImagePaths()) {
                ContentValues image = new ContentValues();
                image.put(AssemblArContract.ReferenceImages.ID,
                        registrationId + "_reference_"
                                + String.format(Locale.US, "%02d", imageIndex++));
                image.put(AssemblArContract.ReferenceImages.REGISTRATION_ID, registrationId);
                image.put(AssemblArContract.ReferenceImages.COMPONENT_ID, component.getId());
                image.put(AssemblArContract.ReferenceImages.IMAGE_PATH, path);
                image.put(AssemblArContract.ReferenceImages.ANGLE_LABEL, "manual_reference");
                image.put(AssemblArContract.ReferenceImages.AZIMUTH_DEGREES, 0.0d);
                image.put(AssemblArContract.ReferenceImages.ELEVATION_DEGREES, 0.0d);
                image.put(AssemblArContract.ReferenceImages.DISTANCE_MILLIMETERS, 0.0d);
                image.put(AssemblArContract.ReferenceImages.LIGHTING_CONDITION,
                        "authored manual reference; capture geometry not supplied");
                image.put(AssemblArContract.ReferenceImages.IS_SYNTHETIC, 0);
                image.put(AssemblArContract.ReferenceImages.PIXEL_WIDTH, 0);
                image.put(AssemblArContract.ReferenceImages.PIXEL_HEIGHT, 0);
                image.put(AssemblArContract.ReferenceImages.SHA256, "");
                image.put(AssemblArContract.ReferenceImages.CAPTURED_AT, 0L);
                db.insertOrThrow(AssemblArContract.ReferenceImages.TABLE, null, image);
            }

            ContentValues spatial = new ContentValues();
            spatial.put(AssemblArContract.Spatial.COMPONENT_ID, component.getId());
            spatial.put(AssemblArContract.Spatial.WIDTH_MILLIMETERS, 0.0d);
            spatial.put(AssemblArContract.Spatial.HEIGHT_MILLIMETERS, 0.0d);
            spatial.put(AssemblArContract.Spatial.DEPTH_MILLIMETERS, 0.0d);
            spatial.put(AssemblArContract.Spatial.COORDINATE_FRAME, "normalized_component_frame");
            spatial.put(AssemblArContract.Spatial.ANCHOR_POINTS_JSON,
                    "{\"units\":\"normalized\",\"points\":[[0,0,0],[1,0,0],[1,1,0],[0,1,0]],"
                            + "\"physicalDimensionsPendingCapture\":true}");
            spatial.put(AssemblArContract.Spatial.ORIENTATION_RULES_JSON,
                    "{\"manualDerivedRule\":\"" + jsonEscape(component.getRecognitionNotes()) + "\"}");
            spatial.put(AssemblArContract.Spatial.OBJECT_POINTS_BLOB, new byte[0]);
            spatial.put(AssemblArContract.Spatial.MODEL_ASSET_PATH, "");
            spatial.put(AssemblArContract.Spatial.UPDATED_AT, 0L);
            db.insertOrThrow(AssemblArContract.Spatial.TABLE, null, spatial);
        }
    }

    private static void putManualReference(
            ContentValues values,
            ManualReference reference,
            String documentIdColumn,
            String documentTitleColumn,
            String pageStartColumn,
            String pageEndColumn,
            String assetPathsColumn) {
        values.put(documentIdColumn, reference.getDocumentId());
        values.put(documentTitleColumn, reference.getDocumentTitle());
        values.put(pageStartColumn, reference.getPageStart());
        values.put(pageEndColumn, reference.getPageEnd());
        values.put(assetPathsColumn, joinPaths(reference.getAssetPaths()));
    }

    static String joinPaths(List<String> paths) {
        StringBuilder builder = new StringBuilder();
        for (String path : paths) {
            if (builder.length() > 0) {
                builder.append(PATH_SEPARATOR);
            }
            builder.append(path);
        }
        return builder.toString();
    }

    static List<String> splitPaths(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return java.util.Collections.unmodifiableList(
                java.util.Arrays.asList(encoded.split(PATH_SEPARATOR, -1)));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
