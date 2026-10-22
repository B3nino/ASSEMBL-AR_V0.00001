package dev.chyken.assembl_ar.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.chyken.assembl_ar.model.StepStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards progress-preserving canonical-content refreshes across deployed database versions. */
@RunWith(AndroidJUnit4.class)
public final class AssemblArDatabaseMigrationInstrumentedTest {
    @Test public void versionFiveRefreshesTopScrewInstructionAndKeepsVerifiedProgress(){
        Context context=ApplicationProvider.getApplicationContext();AssemblArDatabaseHelper helper=new AssemblArDatabaseHelper(context);SQLiteDatabase db=SQLiteDatabase.create(null);
        try{
            helper.onConfigure(db);helper.onCreate(db);
            db.execSQL("UPDATE "+AssemblArContract.Instructions.TABLE+" SET "+AssemblArContract.Instructions.TEXT+" = ? WHERE "+AssemblArContract.Instructions.STEP_ID+" = ? AND "+AssemblArContract.Instructions.ORDER_INDEX+" = 6",new Object[]{"Leave the upper screw on the standoff for the extension board.","neobot_step_12"});
            db.execSQL("UPDATE "+AssemblArContract.Progress.TABLE+" SET "+AssemblArContract.Progress.STATUS+" = ?, "+AssemblArContract.Progress.VALIDATION_CONFIRMED+" = 1 WHERE "+AssemblArContract.Progress.STEP_ID+" = ?",new Object[]{StepStatus.COMPLETED.name(),"neobot_step_12"});
            insertCustomRegistration(db,"phase9_migration_reference");helper.onUpgrade(db,5,AssemblArContract.DATABASE_VERSION);
            assertEquals(1,DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM "+AssemblArContract.Instructions.TABLE+" WHERE "+AssemblArContract.Instructions.STEP_ID+" = ? AND "+AssemblArContract.Instructions.TEXT+" LIKE ?",new String[]{"neobot_step_12","Remove the upper screw%"}));
            assertEquals(1,DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM "+AssemblArContract.Progress.TABLE+" WHERE "+AssemblArContract.Progress.STEP_ID+" = ? AND "+AssemblArContract.Progress.STATUS+" = ? AND "+AssemblArContract.Progress.VALIDATION_CONFIRMED+" = 1",new String[]{"neobot_step_12",StepStatus.COMPLETED.name()}));
            assertEquals(1,DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM "+AssemblArContract.Registrations.TABLE+" WHERE "+AssemblArContract.Registrations.ID+" = ?",new String[]{"phase9_migration_reference"}));
        }finally{db.close();helper.close();}
    }
    @Test
    public void upgradeFromVersionOneToCurrentRefreshesCardsWithoutDiscardingUserState() {
        Context context = ApplicationProvider.getApplicationContext();
        AssemblArDatabaseHelper helper = new AssemblArDatabaseHelper(context);
        SQLiteDatabase database = SQLiteDatabase.create(null);
        try {
            helper.onConfigure(database);
            helper.onCreate(database);

            database.execSQL(
                    "DELETE FROM " + AssemblArContract.Instructions.TABLE + " WHERE rowid IN ("
                            + "SELECT rowid FROM " + AssemblArContract.Instructions.TABLE + " WHERE "
                            + AssemblArContract.Instructions.STEP_ID + " = ? AND "
                            + AssemblArContract.Instructions.TYPE + " IN ('ACTION', 'CHECK') "
                            + "ORDER BY " + AssemblArContract.Instructions.ORDER_INDEX + " LIMIT 2)",
                    new Object[]{"neobot_step_02"});
            assertEquals(126L, guidedCardCount(database));

            ContentValues progress = new ContentValues();
            progress.put(AssemblArContract.Progress.STATUS, StepStatus.COMPLETED.name());
            progress.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, 1);
            progress.put(AssemblArContract.Progress.UPDATED_AT, 4242L);
            assertEquals(1, database.update(
                    AssemblArContract.Progress.TABLE,
                    progress,
                    AssemblArContract.Progress.PROJECT_ID + " = ? AND "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{NeobotSeedData.PROJECT_ID, "neobot_step_02"}));

            ContentValues customRegistration = new ContentValues();
            customRegistration.put(AssemblArContract.Registrations.ID, "migration_test_registration");
            customRegistration.put(AssemblArContract.Registrations.COMPONENT_ID, NeobotSeedData.MAIN_BOARD);
            customRegistration.put(AssemblArContract.Registrations.ADMINISTRATOR_ID, "test_admin");
            customRegistration.put(AssemblArContract.Registrations.DISPLAY_NAME, "User board reference");
            customRegistration.put(AssemblArContract.Registrations.DESCRIPTION, "Preserve during migration");
            customRegistration.put(AssemblArContract.Registrations.PURPOSE, "Migration test");
            customRegistration.put(AssemblArContract.Registrations.ASSEMBLY_USAGE, "Test only");
            customRegistration.put(AssemblArContract.Registrations.MANUAL_REFERENCE, "User supplied");
            customRegistration.put(AssemblArContract.Registrations.ACTIVE, 1);
            customRegistration.put(AssemblArContract.Registrations.CREATED_AT, 4000L);
            customRegistration.put(AssemblArContract.Registrations.UPDATED_AT, 4242L);
            database.insertOrThrow(
                    AssemblArContract.Registrations.TABLE, null, customRegistration);

            helper.onUpgrade(database, 1, AssemblArContract.DATABASE_VERSION);

            assertEquals(128L, guidedCardCount(database));
            assertEquals(25L, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM " + AssemblArContract.Instructions.TABLE + " WHERE "
                            + AssemblArContract.Instructions.STEP_ID + " = ? AND "
                            + AssemblArContract.Instructions.TYPE + " IN ('ACTION', 'CHECK')",
                    new String[]{"neobot_step_02"}));
            assertEquals(0L, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM " + AssemblArContract.Connections.TABLE + " WHERE "
                            + AssemblArContract.Connections.STEP_ID + " = ?",
                    new String[]{"neobot_step_18"}));
            assertEquals(1L, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM " + AssemblArContract.Registrations.TABLE + " WHERE "
                            + AssemblArContract.Registrations.ID + " = ?",
                    new String[]{"migration_test_registration"}));

            try (Cursor cursor = database.query(
                    AssemblArContract.Progress.TABLE,
                    null,
                    AssemblArContract.Progress.PROJECT_ID + " = ? AND "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{NeobotSeedData.PROJECT_ID, "neobot_step_02"},
                    null,
                    null,
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(StepStatus.COMPLETED.name(), cursor.getString(
                        cursor.getColumnIndexOrThrow(AssemblArContract.Progress.STATUS)));
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(
                        AssemblArContract.Progress.VALIDATION_CONFIRMED)));
                assertEquals(4242L, cursor.getLong(cursor.getColumnIndexOrThrow(
                        AssemblArContract.Progress.UPDATED_AT)));
            }

            try (Cursor cursor = database.rawQuery("PRAGMA foreign_key_check", null)) {
                assertFalse(cursor.moveToFirst());
            }
        } finally {
            database.close();
            helper.close();
        }
    }

    @Test
    public void upgradeFromVersionTwoPublishesInstructionalImagesAndPreservesUserState() {
        Context context = ApplicationProvider.getApplicationContext();
        AssemblArDatabaseHelper helper = new AssemblArDatabaseHelper(context);
        SQLiteDatabase database = SQLiteDatabase.create(null);
        try {
            helper.onConfigure(database);
            helper.onCreate(database);

            // Simulate the deployed v2 rows, where every early guided card still showed a manual
            // page thumbnail instead of the reviewed instructional photograph.
            ContentValues staleReference = new ContentValues();
            staleReference.put(AssemblArContract.Instructions.REFERENCE_IMAGE_PATH,
                    "manuals/assembly/page-02.jpg");
            assertTrue(database.update(
                    AssemblArContract.Instructions.TABLE,
                    staleReference,
                    AssemblArContract.Instructions.REFERENCE_IMAGE_PATH + " LIKE ?",
                    new String[]{"references/instructional/%"}) > 0);
            assertEquals(0L, instructionalPathCount(database));

            ContentValues progress = new ContentValues();
            progress.put(AssemblArContract.Progress.STATUS, StepStatus.COMPLETED.name());
            progress.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, 1);
            progress.put(AssemblArContract.Progress.UPDATED_AT, 7331L);
            assertEquals(1, database.update(
                    AssemblArContract.Progress.TABLE,
                    progress,
                    AssemblArContract.Progress.PROJECT_ID + " = ? AND "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{NeobotSeedData.PROJECT_ID, "neobot_step_03"}));
            insertCustomRegistration(database, "migration_v2_registration");

            helper.onUpgrade(database, 2, AssemblArContract.DATABASE_VERSION);

            assertEquals(10L, instructionalPathCount(database));
            assertEquals("references/instructional/gray/s02-00-main-board-gray.webp",
                    instructionPath(database, "neobot_step_01", 1));
            assertEquals("references/instructional/gray/motor-bracket-half-gray.webp",
                    instructionPath(database, "neobot_step_02", 1));
            assertEquals("references/instructional/gray/s02-01-brackets-mounted-gray.webp",
                    instructionPath(database, "neobot_step_02", 4));
            assertEquals("references/instructional/gray/gear-motor-gray.webp",
                    instructionPath(database, "neobot_step_02", 5));
            assertEquals("references/instructional/gray/s02-03-first-motor-one-screw-gray.webp",
                    instructionPath(database, "neobot_step_02", 8));
            assertEquals("references/instructional/gray/s02-04-first-motor-two-screws-gray.webp",
                    instructionPath(database, "neobot_step_02", 10));
            assertEquals("references/instructional/gray/s02-05-both-motors-gray.webp",
                    instructionPath(database, "neobot_step_02", 27));
            assertEquals("references/instructional/gray/motor-driver-gray.webp",
                    instructionPath(database, "neobot_step_03", 1));
            assertEquals("references/instructional/gray/s03-00-motor-driver-installed-gray.webp",
                    instructionPath(database, "neobot_step_03", 2));

            assertEquals(1L, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM " + AssemblArContract.Registrations.TABLE + " WHERE "
                            + AssemblArContract.Registrations.ID + " = ?",
                    new String[]{"migration_v2_registration"}));
            try (Cursor cursor = database.query(
                    AssemblArContract.Progress.TABLE,
                    null,
                    AssemblArContract.Progress.PROJECT_ID + " = ? AND "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{NeobotSeedData.PROJECT_ID, "neobot_step_03"},
                    null,
                    null,
                    null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(StepStatus.COMPLETED.name(), cursor.getString(
                        cursor.getColumnIndexOrThrow(AssemblArContract.Progress.STATUS)));
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(
                        AssemblArContract.Progress.VALIDATION_CONFIRMED)));
                assertEquals(7331L, cursor.getLong(cursor.getColumnIndexOrThrow(
                        AssemblArContract.Progress.UPDATED_AT)));
            }

            try (Cursor cursor = database.rawQuery("PRAGMA foreign_key_check", null)) {
                assertFalse(cursor.moveToFirst());
            }
        } finally {
            database.close();
            helper.close();
        }
    }

    @Test
    public void upgradeFromVersionThreePublishesWebpPathsAndPreservesUserState() {
        Context context = ApplicationProvider.getApplicationContext();
        AssemblArDatabaseHelper helper = new AssemblArDatabaseHelper(context);
        SQLiteDatabase database = SQLiteDatabase.create(null);
        try {
            helper.onConfigure(database);
            helper.onCreate(database);

            database.execSQL(
                    "UPDATE " + AssemblArContract.Instructions.TABLE + " SET "
                            + AssemblArContract.Instructions.REFERENCE_IMAGE_PATH
                            + " = REPLACE(" + AssemblArContract.Instructions.REFERENCE_IMAGE_PATH
                            + ", '.webp', '.png') WHERE "
                            + AssemblArContract.Instructions.REFERENCE_IMAGE_PATH + " LIKE ?",
                    new Object[]{"references/instructional/%"});
            assertEquals("references/instructional/gray/s02-00-main-board-gray.png",
                    instructionPath(database, "neobot_step_01", 1));

            ContentValues progress = new ContentValues();
            progress.put(AssemblArContract.Progress.STATUS, StepStatus.COMPLETED.name());
            progress.put(AssemblArContract.Progress.VALIDATION_CONFIRMED, 1);
            progress.put(AssemblArContract.Progress.UPDATED_AT, 8442L);
            assertEquals(1, database.update(
                    AssemblArContract.Progress.TABLE,
                    progress,
                    AssemblArContract.Progress.PROJECT_ID + " = ? AND "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{NeobotSeedData.PROJECT_ID, "neobot_step_01"}));
            insertCustomRegistration(database, "migration_v3_registration");

            helper.onUpgrade(database, 3, AssemblArContract.DATABASE_VERSION);

            assertEquals(10L, instructionalPathCount(database));
            assertEquals("references/instructional/gray/s02-00-main-board-gray.webp",
                    instructionPath(database, "neobot_step_01", 1));
            assertEquals(0L, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM " + AssemblArContract.Instructions.TABLE + " WHERE "
                            + AssemblArContract.Instructions.REFERENCE_IMAGE_PATH + " LIKE ?",
                    new String[]{"references/instructional/%.png"}));
            assertEquals(1L, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM " + AssemblArContract.Registrations.TABLE + " WHERE "
                            + AssemblArContract.Registrations.ID + " = ?",
                    new String[]{"migration_v3_registration"}));
            assertEquals(8442L, DatabaseUtils.longForQuery(database,
                    "SELECT " + AssemblArContract.Progress.UPDATED_AT + " FROM "
                            + AssemblArContract.Progress.TABLE + " WHERE "
                            + AssemblArContract.Progress.PROJECT_ID + " = ? AND "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{NeobotSeedData.PROJECT_ID, "neobot_step_01"}));

            try (Cursor cursor = database.rawQuery("PRAGMA foreign_key_check", null)) {
                assertFalse(cursor.moveToFirst());
            }
        } finally {
            database.close();
            helper.close();
        }
    }

    @Test
    public void upgradeFromVersionFourRemovesWorkspaceGateAndKeepsSavedAssemblyState() {
        Context context = ApplicationProvider.getApplicationContext();
        AssemblArDatabaseHelper helper = new AssemblArDatabaseHelper(context);
        SQLiteDatabase database = SQLiteDatabase.create(null);
        try {
            helper.onConfigure(database);
            helper.onCreate(database);
            database.execSQL("UPDATE " + AssemblArContract.Instructions.TABLE + " SET "
                            + AssemblArContract.Instructions.TYPE + " = 'CHECK', "
                            + AssemblArContract.Instructions.TEXT + " = ? WHERE "
                            + AssemblArContract.Instructions.STEP_ID + " = ? AND "
                            + AssemblArContract.Instructions.ORDER_INDEX + " = 2",
                    new Object[]{"Check that the work surface is flat, clean and non-metal.",
                            "neobot_step_01"});
            assertEquals(129L, guidedCardCount(database));
            database.execSQL("UPDATE " + AssemblArContract.Progress.TABLE + " SET "
                            + AssemblArContract.Progress.STATUS + " = ?, "
                            + AssemblArContract.Progress.VALIDATION_CONFIRMED + " = 1, "
                            + AssemblArContract.Progress.UPDATED_AT + " = 9553 WHERE "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new Object[]{StepStatus.COMPLETED.name(), "neobot_step_01"});
            database.execSQL("UPDATE " + AssemblArContract.Progress.TABLE + " SET "
                            + AssemblArContract.Progress.STATUS + " = ? WHERE "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new Object[]{StepStatus.IN_PROGRESS.name(), "neobot_step_02"});
            insertCustomRegistration(database, "migration_v4_registration");

            helper.onUpgrade(database, 4, AssemblArContract.DATABASE_VERSION);

            assertEquals(128L, guidedCardCount(database));
            assertEquals("NOTE", DatabaseUtils.stringForQuery(database,
                    "SELECT " + AssemblArContract.Instructions.TYPE + " FROM "
                            + AssemblArContract.Instructions.TABLE + " WHERE "
                            + AssemblArContract.Instructions.STEP_ID + " = ? AND "
                            + AssemblArContract.Instructions.ORDER_INDEX + " = 2",
                    new String[]{"neobot_step_01"}));
            assertEquals(StepStatus.COMPLETED.name(), DatabaseUtils.stringForQuery(database,
                    "SELECT " + AssemblArContract.Progress.STATUS + " FROM "
                            + AssemblArContract.Progress.TABLE + " WHERE "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{"neobot_step_01"}));
            assertEquals(1L, DatabaseUtils.longForQuery(database,
                    "SELECT " + AssemblArContract.Progress.VALIDATION_CONFIRMED + " FROM "
                            + AssemblArContract.Progress.TABLE + " WHERE "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{"neobot_step_01"}));
            assertEquals(9553L, DatabaseUtils.longForQuery(database,
                    "SELECT " + AssemblArContract.Progress.UPDATED_AT + " FROM "
                            + AssemblArContract.Progress.TABLE + " WHERE "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{"neobot_step_01"}));
            assertEquals(StepStatus.IN_PROGRESS.name(), DatabaseUtils.stringForQuery(database,
                    "SELECT " + AssemblArContract.Progress.STATUS + " FROM "
                            + AssemblArContract.Progress.TABLE + " WHERE "
                            + AssemblArContract.Progress.STEP_ID + " = ?",
                    new String[]{"neobot_step_02"}));
            assertEquals(1L, DatabaseUtils.longForQuery(database,
                    "SELECT COUNT(*) FROM " + AssemblArContract.Registrations.TABLE + " WHERE "
                            + AssemblArContract.Registrations.ID + " = ?",
                    new String[]{"migration_v4_registration"}));
            try (Cursor cursor = database.rawQuery("PRAGMA foreign_key_check", null)) {
                assertFalse(cursor.moveToFirst());
            }
        } finally {
            database.close();
            helper.close();
        }
    }

    private static long guidedCardCount(SQLiteDatabase database) {
        return DatabaseUtils.longForQuery(database,
                "SELECT COUNT(*) FROM " + AssemblArContract.Instructions.TABLE + " WHERE "
                        + AssemblArContract.Instructions.TYPE + " IN ('ACTION', 'CHECK')",
                null);
    }

    private static long instructionalPathCount(SQLiteDatabase database) {
        return DatabaseUtils.longForQuery(database,
                "SELECT COUNT(DISTINCT "
                        + AssemblArContract.Instructions.REFERENCE_IMAGE_PATH + ") FROM "
                        + AssemblArContract.Instructions.TABLE + " WHERE "
                        + AssemblArContract.Instructions.REFERENCE_IMAGE_PATH + " LIKE ?",
                new String[]{"references/instructional/%"});
    }

    private static String instructionPath(
            SQLiteDatabase database, String stepId, int order) {
        return DatabaseUtils.stringForQuery(database,
                "SELECT " + AssemblArContract.Instructions.REFERENCE_IMAGE_PATH + " FROM "
                        + AssemblArContract.Instructions.TABLE + " WHERE "
                        + AssemblArContract.Instructions.STEP_ID + " = ? AND "
                        + AssemblArContract.Instructions.ORDER_INDEX + " = ?",
                new String[]{stepId, Integer.toString(order)});
    }

    private static void insertCustomRegistration(SQLiteDatabase database, String id) {
        ContentValues registration = new ContentValues();
        registration.put(AssemblArContract.Registrations.ID, id);
        registration.put(AssemblArContract.Registrations.COMPONENT_ID, NeobotSeedData.MAIN_BOARD);
        registration.put(AssemblArContract.Registrations.ADMINISTRATOR_ID, "test_admin");
        registration.put(AssemblArContract.Registrations.DISPLAY_NAME, "User board reference");
        registration.put(AssemblArContract.Registrations.DESCRIPTION, "Preserve during migration");
        registration.put(AssemblArContract.Registrations.PURPOSE, "Migration test");
        registration.put(AssemblArContract.Registrations.ASSEMBLY_USAGE, "Test only");
        registration.put(AssemblArContract.Registrations.MANUAL_REFERENCE, "User supplied");
        registration.put(AssemblArContract.Registrations.ACTIVE, 1);
        registration.put(AssemblArContract.Registrations.CREATED_AT, 7000L);
        registration.put(AssemblArContract.Registrations.UPDATED_AT, 7331L);
        database.insertOrThrow(AssemblArContract.Registrations.TABLE, null, registration);
    }
}
