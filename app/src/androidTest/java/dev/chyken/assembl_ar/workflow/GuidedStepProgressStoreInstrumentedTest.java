package dev.chyken.assembl_ar.workflow;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Action checkpoints must survive a restart without allowing old cursors to bypass new gates. */
@RunWith(AndroidJUnit4.class)
public final class GuidedStepProgressStoreInstrumentedTest {
    private static final String PROJECT_ID = NeobotSeedData.PROJECT_ID;
    private static final String OTHER_PROJECT_ID = PROJECT_ID + "-other";
    private Context context;
    private AssemblyStep section;
    private List<GuidedStep> cards;
    private List<Integer> checkpointIndices;

    @Before
    public void setUp() {
        context = new TestPreferencesContext(ApplicationProvider.getApplicationContext());
        clearTestProjects();
        AssemblyStep source = NeobotSeedData.getAssemblySteps().get(1);
        section = copySection(source, PROJECT_ID, source.getId(), source.getInstructions());
        cards = GuidedAssemblySequence.forManualStep(section);
        checkpointIndices = new ArrayList<>();
        for (int index = 0; index < cards.size(); index++) {
            if (GuidedRecognitionCatalog.checkpointFor(section, cards.get(index)) != null) {
                checkpointIndices.add(index);
            }
        }
        assertTrue("The motor section must contain separate action checkpoints",
                checkpointIndices.size() >= 2);
    }

    @After
    public void tearDown() {
        clearTestProjects();
    }

    @Test
    public void completedCheckpointAndCursorRestoreWithRecreatedContextAndCards() throws Exception {
        int first = checkpointIndices.get(0);
        int second = checkpointIndices.get(1);
        GuidedStepProgressStore.markCheckpointComplete(
                context, PROJECT_ID, section.getId(), cards.get(first));
        GuidedStepProgressStore.write(context, PROJECT_ID, section.getId(), cards.get(second));

        // Flush the asynchronous writes before reopening through a fresh package context.
        assertTrue(context.getSharedPreferences("guided_assembly_steps", Context.MODE_PRIVATE)
                .edit().commit());
        Context reopenedContext = new TestPreferencesContext(
                context.createPackageContext(context.getPackageName(), 0));
        List<GuidedStep> restoredCards = GuidedAssemblySequence.forManualStep(section);

        assertTrue(GuidedStepProgressStore.isCheckpointComplete(
                reopenedContext, PROJECT_ID, section.getId(), restoredCards.get(first)));
        assertFalse(GuidedStepProgressStore.isCheckpointComplete(
                reopenedContext, PROJECT_ID, section.getId(), restoredCards.get(second)));
        assertEquals(second, GuidedStepProgressStore.readForCheckpoints(
                reopenedContext, section, restoredCards));
    }

    @Test
    public void retiredSurfaceCursorRestoresTheBoardCardAndPreservesItsRecognitionCheckpoint() {
        AssemblyStep preparation = NeobotSeedData.getAssemblySteps().get(0);
        List<GuidedStep> preparationCards = GuidedAssemblySequence.forManualStep(preparation);
        assertEquals(1, preparationCards.size());
        GuidedStep board = preparationCards.get(0);
        GuidedStepProgressStore.markCheckpointComplete(
                context, PROJECT_ID, preparation.getId(), board);
        String retiredCursorKey = PROJECT_ID + "\u001F" + preparation.getId();
        assertTrue(context.getSharedPreferences("guided_assembly_steps", Context.MODE_PRIVATE)
                .edit().putString(retiredCursorKey,
                        "2|CHECK|Check that the work surface is flat, clean and non-metal.")
                .commit());

        assertEquals(0, GuidedStepProgressStore.readForCheckpoints(
                context, preparation, preparationCards));
        assertTrue(GuidedStepProgressStore.isCheckpointComplete(
                context, PROJECT_ID, preparation.getId(), board));
        assertTrue(GuidedStepProgressStore.areCheckpointsComplete(
                context, preparation, preparationCards));
        assertFalse(context.getSharedPreferences("guided_assembly_steps", Context.MODE_PRIVATE)
                .contains(retiredCursorKey));
    }

    @Test
    public void legacyCursorRewindsToEachUnfinishedCheckpointWithoutChangingRawCursorRead() {
        int savedIndex = cards.size() - 1;
        GuidedStepProgressStore.write(context, PROJECT_ID, section.getId(), cards.get(savedIndex));

        assertEquals(savedIndex, GuidedStepProgressStore.read(
                context, PROJECT_ID, section.getId(), cards));
        for (int index : checkpointIndices) {
            assertEquals(index, GuidedStepProgressStore.readForCheckpoints(context, section, cards));
            GuidedStepProgressStore.markCheckpointComplete(
                    context, PROJECT_ID, section.getId(), cards.get(index));
        }
        assertEquals(savedIndex, GuidedStepProgressStore.readForCheckpoints(context, section, cards));
    }

    @Test
    public void oldLoosePartCompletionsCannotSatisfyCurrentActionCheckpoints() {
        int savedIndex = cards.size() - 1;
        GuidedStepProgressStore.write(context, PROJECT_ID, section.getId(), cards.get(savedIndex));
        SharedPreferences.Editor legacy = context.getSharedPreferences(
                "guided_assembly_steps", Context.MODE_PRIVATE).edit();
        for (int index : checkpointIndices) {
            legacy.putBoolean(legacyCheckpointKey(section.getId(), cards.get(index)), true);
        }
        assertTrue(legacy.commit());

        // Matching stable card IDs must not reuse the earlier, weaker scan contract.
        assertEquals(savedIndex, GuidedStepProgressStore.read(
                context, PROJECT_ID, section.getId(), cards));
        for (int index : checkpointIndices) {
            assertFalse(GuidedStepProgressStore.isCheckpointComplete(
                    context, PROJECT_ID, section.getId(), cards.get(index)));
            assertEquals(index, GuidedStepProgressStore.readForCheckpoints(context, section, cards));
            GuidedStepProgressStore.markCheckpointComplete(
                    context, PROJECT_ID, section.getId(), cards.get(index));
            assertTrue(GuidedStepProgressStore.isCheckpointComplete(
                    context, PROJECT_ID, section.getId(), cards.get(index)));
        }
        assertEquals(savedIndex, GuidedStepProgressStore.readForCheckpoints(context, section, cards));
    }

    @Test
    public void resumeCannotSkipAnUnfinishedPhysicalActionWithoutACameraTarget() {
        int physicalIndex = -1;
        for (int index : checkpointIndices) {
            GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(
                    section, cards.get(index));
            if (checkpoint.getKind() == GuidedCheckpoint.Kind.PHYSICAL) {
                physicalIndex = index;
                break;
            }
            GuidedStepProgressStore.markCheckpointComplete(
                    context, PROJECT_ID, section.getId(), cards.get(index));
        }
        assertTrue("The motor section must include an explicit physical action", physicalIndex >= 0);
        GuidedStepProgressStore.write(
                context, PROJECT_ID, section.getId(), cards.get(cards.size() - 1));

        assertEquals(physicalIndex, GuidedStepProgressStore.readForCheckpoints(context, section, cards));
    }

    @Test
    public void goingBackToReviewKeepsCompletedCheckpointsAndRestoresTheReviewCursor() {
        for (int index : checkpointIndices) {
            GuidedStepProgressStore.markCheckpointComplete(
                    context, PROJECT_ID, section.getId(), cards.get(index));
        }
        GuidedStepProgressStore.write(
                context, PROJECT_ID, section.getId(), cards.get(cards.size() - 1));
        GuidedStepProgressStore.write(context, PROJECT_ID, section.getId(), cards.get(0));

        assertEquals(0, GuidedStepProgressStore.readForCheckpoints(context, section, cards));
        for (int index : checkpointIndices) {
            assertTrue(GuidedStepProgressStore.isCheckpointComplete(
                    context, PROJECT_ID, section.getId(), cards.get(index)));
        }

        GuidedStepProgressStore.write(
                context, PROJECT_ID, section.getId(), cards.get(cards.size() - 1));
        assertEquals(cards.size() - 1,
                GuidedStepProgressStore.readForCheckpoints(context, section, cards));
    }

    @Test
    public void checkpointCompletionUsesStableCardIdentityAfterAnEarlierCardIsInserted() {
        GuidedStep checkpoint = cards.get(checkpointIndices.get(0));
        GuidedStepProgressStore.markCheckpointComplete(
                context, PROJECT_ID, section.getId(), checkpoint);

        List<AssemblyInstruction> editedInstructions = new ArrayList<>(section.getInstructions());
        editedInstructions.add(0, new AssemblyInstruction(1, InstructionType.ACTION,
                "Prepare a clear workspace before beginning.",
                checkpoint.getPrimaryInstruction().getManualPage(),
                checkpoint.getPrimaryInstruction().getReferenceImagePath()));
        AssemblyStep editedSection = copySection(
                section, PROJECT_ID, section.getId(), editedInstructions);
        List<GuidedStep> editedCards = GuidedAssemblySequence.forManualStep(editedSection);
        GuidedStep shiftedCheckpoint = editedCards.get(checkpointIndices.get(0) + 1);

        assertEquals(checkpoint.getStableId(), shiftedCheckpoint.getStableId());
        assertTrue(GuidedStepProgressStore.isCheckpointComplete(
                context, PROJECT_ID, section.getId(), shiftedCheckpoint));
        assertFalse(GuidedStepProgressStore.isCheckpointComplete(
                context, PROJECT_ID, section.getId(), editedCards.get(0)));
    }

    @Test
    public void redoClearsLaterChecksAtomicallyAndPreservesEarlierAndOtherSectionChecks() throws Exception {
        String otherSection = section.getId() + "-other";
        for (int index : checkpointIndices) {
            GuidedStepProgressStore.markCheckpointComplete(context, PROJECT_ID, section.getId(), cards.get(index));
            GuidedStepProgressStore.markCheckpointComplete(context, PROJECT_ID, otherSection, cards.get(index));
        }
        int redoIndex = checkpointIndices.get(2);
        assertTrue(GuidedStepProgressStore.areCheckpointsComplete(context, section, cards));
        GuidedStepProgressStore.clearCheckpointsFrom(context, section, cards, redoIndex);
        assertEquals(2, GuidedStepProgressStore.completedCheckpointCount(context, section, cards));
        assertFalse(GuidedStepProgressStore.areCheckpointsComplete(context, section, cards));
        assertTrue(context.getSharedPreferences("guided_assembly_steps", Context.MODE_PRIVATE).edit().commit());
        Context reopened = new TestPreferencesContext(context.createPackageContext(context.getPackageName(), 0));
        assertEquals(redoIndex, GuidedStepProgressStore.readForCheckpoints(reopened, section, cards));
        for (int index : checkpointIndices) {
            assertEquals(index < redoIndex, GuidedStepProgressStore.isCheckpointComplete(
                    reopened, PROJECT_ID, section.getId(), cards.get(index)));
            assertTrue(GuidedStepProgressStore.isCheckpointComplete(
                    reopened, PROJECT_ID, otherSection, cards.get(index)));
        }
        GuidedStepProgressStore.clearStep(context, PROJECT_ID, otherSection);
    }

    @Test
    public void reviewingEarlierCardsDoesNotChangeConfirmedCountOrFinalPrerequisites() {
        for (int index : checkpointIndices) {
            GuidedStepProgressStore.markCheckpointComplete(context, PROJECT_ID, section.getId(), cards.get(index));
        }
        GuidedStepProgressStore.write(context, PROJECT_ID, section.getId(), cards.get(0));
        assertEquals(checkpointIndices.size(),
                GuidedStepProgressStore.completedCheckpointCount(context, section, cards));
        assertTrue(GuidedStepProgressStore.areCheckpointsComplete(context, section, cards));
        GuidedStepProgressStore.clearCheckpointsFrom(context, section, cards, 0);
        assertEquals(0, GuidedStepProgressStore.completedCheckpointCount(context, section, cards));
        assertFalse(GuidedStepProgressStore.areCheckpointsComplete(context, section, cards));
    }

    @Test
    public void cursorAndCompletionAreIsolatedByProjectAndSection() {
        GuidedStep checkpoint = cards.get(checkpointIndices.get(0));
        String otherSectionId = section.getId() + "-other";
        GuidedStepProgressStore.markCheckpointComplete(
                context, PROJECT_ID, section.getId(), checkpoint);
        GuidedStepProgressStore.write(
                context, PROJECT_ID, section.getId(), cards.get(cards.size() - 1));

        assertFalse(GuidedStepProgressStore.isCheckpointComplete(
                context, OTHER_PROJECT_ID, section.getId(), checkpoint));
        assertFalse(GuidedStepProgressStore.isCheckpointComplete(
                context, PROJECT_ID, otherSectionId, checkpoint));
        assertEquals(0, GuidedStepProgressStore.read(
                context, OTHER_PROJECT_ID, section.getId(), cards));
        assertEquals(0, GuidedStepProgressStore.read(
                context, PROJECT_ID, otherSectionId, cards));
    }

    @Test
    public void clearingSectionRemovesItsCursorAndCheckpointsButPreservesOtherSections() {
        GuidedStep checkpoint = cards.get(checkpointIndices.get(0));
        GuidedStep lastCard = cards.get(cards.size() - 1);
        String otherSectionId = section.getId() + "-other";
        for (String sectionId : new String[] {section.getId(), otherSectionId}) {
            GuidedStepProgressStore.markCheckpointComplete(
                    context, PROJECT_ID, sectionId, checkpoint);
            GuidedStepProgressStore.write(context, PROJECT_ID, sectionId, lastCard);
            context.getSharedPreferences("guided_assembly_steps", Context.MODE_PRIVATE).edit()
                    .putBoolean(legacyCheckpointKey(sectionId, checkpoint), true).apply();
        }

        GuidedStepProgressStore.clearStep(context, PROJECT_ID, section.getId());

        assertEquals(0, GuidedStepProgressStore.read(context, PROJECT_ID, section.getId(), cards));
        assertFalse(GuidedStepProgressStore.isCheckpointComplete(
                context, PROJECT_ID, section.getId(), checkpoint));
        assertEquals(cards.size() - 1,
                GuidedStepProgressStore.read(context, PROJECT_ID, otherSectionId, cards));
        assertTrue(GuidedStepProgressStore.isCheckpointComplete(
                context, PROJECT_ID, otherSectionId, checkpoint));
        SharedPreferences preferences = context.getSharedPreferences(
                "guided_assembly_steps", Context.MODE_PRIVATE);
        assertFalse(preferences.contains(legacyCheckpointKey(section.getId(), checkpoint)));
        assertTrue(preferences.contains(legacyCheckpointKey(otherSectionId, checkpoint)));
    }

    @Test
    public void clearingProjectRemovesAllSectionProgressButPreservesOtherProjects() {
        GuidedStep checkpoint = cards.get(checkpointIndices.get(0));
        GuidedStep lastCard = cards.get(cards.size() - 1);
        String otherSectionId = section.getId() + "-other";
        for (String projectId : new String[] {PROJECT_ID, OTHER_PROJECT_ID}) {
            for (String sectionId : new String[] {section.getId(), otherSectionId}) {
                GuidedStepProgressStore.markCheckpointComplete(
                        context, projectId, sectionId, checkpoint);
                GuidedStepProgressStore.write(context, projectId, sectionId, lastCard);
            }
        }

        GuidedStepProgressStore.clearProject(context, PROJECT_ID);

        for (String sectionId : new String[] {section.getId(), otherSectionId}) {
            assertEquals(0, GuidedStepProgressStore.read(context, PROJECT_ID, sectionId, cards));
            assertFalse(GuidedStepProgressStore.isCheckpointComplete(
                    context, PROJECT_ID, sectionId, checkpoint));
            assertEquals(cards.size() - 1,
                    GuidedStepProgressStore.read(context, OTHER_PROJECT_ID, sectionId, cards));
            assertTrue(GuidedStepProgressStore.isCheckpointComplete(
                    context, OTHER_PROJECT_ID, sectionId, checkpoint));
        }
    }

    private void clearTestProjects() {
        GuidedStepProgressStore.clearProject(context, PROJECT_ID);
        GuidedStepProgressStore.clearProject(context, OTHER_PROJECT_ID);
    }

    private static String legacyCheckpointKey(String sectionId, GuidedStep card) {
        return PROJECT_ID + "\u001F" + sectionId + "\u001Fcheckpoint\u001F" + card.getStableId();
    }

    private static AssemblyStep copySection(
            AssemblyStep source, String projectId, String sectionId,
            List<AssemblyInstruction> instructions) {
        return new AssemblyStep(sectionId, projectId, source.getStepNumber(), source.getTitle(),
                source.getSummary(), source.getValidationType(), source.getValidationPrompt(),
                source.getManualReference(), instructions, source.getRequiredComponents(),
                source.getPinConnections(), source.getOverlayTypes());
    }

    /** Keep project-reset tests from touching the learner's saved production progress. */
    private static final class TestPreferencesContext extends ContextWrapper {
        TestPreferencesContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences("checkpoint_store_test_" + name, mode);
        }
    }
}
