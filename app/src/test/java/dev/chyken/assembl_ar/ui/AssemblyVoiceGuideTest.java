package dev.chyken.assembl_ar.ui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Engine-independent coverage of pending-card, mute and lifecycle cancellation races. */
public final class AssemblyVoiceGuideTest {
    @Test public void instructionTextStartsOnlyForTheCurrentSpokenUtterance() {
        FakeEngine engine=new FakeEngine();
        List<String> displayed=new ArrayList<>();
        AssemblyVoiceGuide guide=new AssemblyVoiceGuide(engine,new AssemblyVoiceGuide.Callback() {
            @Override public void onStatusChanged(AssemblyVoiceGuide.Status status) { }
            @Override public void onSpeechStarted(String key,String text) { displayed.add(key+":"+text); }
        });
        engine.initialize(true);
        guide.speak("Bring out the Main Board.","bring");
        assertTrue("Queued speech has not started yet",displayed.isEmpty());
        guide.speak("Place the board front-side up.","place");
        engine.listener.onStart(engine.ids.get(0));
        assertTrue("A flushed utterance cannot replace the current instruction",displayed.isEmpty());
        engine.listener.onStart(engine.ids.get(1));
        assertEquals(Collections.singletonList("place:Place the board front-side up."),displayed);
        guide.stop();
        engine.listener.onStart(engine.ids.get(1));
        assertEquals("A cancelled utterance cannot restore old text",1,displayed.size());
    }

    @Test public void initializationKeepsOnlyTheLatestCardAndDoesNotClaimPlayback() {
        FakeEngine engine = new FakeEngine();
        List<AssemblyVoiceGuide.Status> states = new ArrayList<>();
        AssemblyVoiceGuide guide = new AssemblyVoiceGuide(engine, states::add);
        guide.speak("Find the board.", "first");
        guide.speak("Insert the bracket.", "second");
        assertTrue(engine.spoken.isEmpty());
        assertEquals(Collections.singletonList(AssemblyVoiceGuide.Status.INITIALIZING), states);
        engine.initialize(true);
        assertEquals(Collections.singletonList("Insert the bracket."), engine.spoken);
        assertEquals(AssemblyVoiceGuide.Status.AVAILABLE, guide.getStatus());
    }

    @Test public void cameraUpdatesDoNotRepeatButReplayAndNewCardsDo() {
        FakeEngine engine = new FakeEngine();
        AssemblyVoiceGuide guide = guide(engine);
        engine.initialize(true);
        for (int i = 0; i < 20; i++) guide.speak("Fit the bracket.", "slot-one");
        assertEquals(1, engine.spoken.size());
        guide.replay();
        guide.speak("Fit the bracket.", "slot-two");
        assertEquals(3, engine.spoken.size());
        assertEquals(3, engine.ids.stream().distinct().count());
    }

    @Test public void pauseBeforeInitializationPreventsDelayedOldCardAndReplay() {
        FakeEngine engine = new FakeEngine();
        AssemblyVoiceGuide guide = guide(engine);
        guide.speak("Old card.", "old");
        guide.stop();
        engine.initialize(true);
        guide.replay();
        assertTrue(engine.spoken.isEmpty());
        guide.speak("Resumed card.", "new");
        assertEquals(Collections.singletonList("Resumed card."), engine.spoken);
    }

    @Test public void muteCancelsPendingAndUnmuteRequiresAnExplicitCurrentInstruction() {
        FakeEngine engine = new FakeEngine();
        AssemblyVoiceGuide guide = guide(engine);
        guide.speak("Pending card.", "pending");
        guide.setMuted(true);
        engine.initialize(true);
        guide.speak("Current card.", "current");
        guide.replay();
        assertEquals(AssemblyVoiceGuide.Status.MUTED, guide.getStatus());
        assertTrue(guide.isMuted());
        assertTrue(engine.spoken.isEmpty());
        guide.setMuted(false);
        assertEquals(AssemblyVoiceGuide.Status.AVAILABLE, guide.getStatus());
        assertTrue(engine.spoken.isEmpty());
        guide.speak("Current card.", "current");
        assertEquals(Collections.singletonList("Current card."), engine.spoken);
        guide.setMuted(true);
        assertTrue(engine.stopCount >= 2);
    }

    @Test public void missingEngineAndRejectedUtterancesReportUnavailable() {
        FakeEngine absent = new FakeEngine();
        AssemblyVoiceGuide noVoice = guide(absent);
        noVoice.speak("Instruction.", "card");
        absent.initialize(false);
        noVoice.replay();
        assertEquals(AssemblyVoiceGuide.Status.UNAVAILABLE, noVoice.getStatus());
        assertTrue(absent.spoken.isEmpty());

        FakeEngine failing = new FakeEngine();
        AssemblyVoiceGuide rejected = guide(failing);
        failing.initialize(true);
        failing.accept = false;
        rejected.speak("Instruction.", "card");
        assertEquals(AssemblyVoiceGuide.Status.UNAVAILABLE, rejected.getStatus());
    }

    @Test public void flushedOldUtteranceErrorsCannotDisableTheCurrentCard() {
        FakeEngine engine = new FakeEngine();
        AssemblyVoiceGuide guide = guide(engine);
        engine.initialize(true);
        guide.speak("Old instruction.", "old");
        String old = engine.ids.get(0);
        guide.speak("Current instruction.", "current");
        engine.listener.onError(old);
        assertEquals(AssemblyVoiceGuide.Status.AVAILABLE, guide.getStatus());
        engine.listener.onError(engine.ids.get(1));
        assertEquals(AssemblyVoiceGuide.Status.UNAVAILABLE, guide.getStatus());
    }

    @Test public void onlyTheCurrentUtteranceReportsCompletionForChapterTiming() {
        FakeEngine engine = new FakeEngine();
        List<String> finished = new ArrayList<>();
        AssemblyVoiceGuide guide = new AssemblyVoiceGuide(engine, new AssemblyVoiceGuide.Callback() {
            @Override public void onStatusChanged(AssemblyVoiceGuide.Status status) { }
            @Override public void onSpeechFinished(String key) { finished.add(key); }
        });
        engine.initialize(true);
        guide.speak("Chapter one.", "phase:1");
        String first = engine.ids.get(0);
        guide.speak("Chapter two.", "phase:2");
        engine.listener.onDone(first);
        assertTrue("A flushed chapter cannot advance the demo", finished.isEmpty());
        engine.listener.onDone(engine.ids.get(1));
        assertEquals(Collections.singletonList("phase:2"), finished);
        engine.listener.onDone(engine.ids.get(1));
        assertEquals("Completion is reported once", 1, finished.size());
        guide.speak("Chapter three.", "phase:3");
        guide.stop();
        engine.listener.onDone(engine.ids.get(2));
        assertEquals("Cancelled speech is not a finished chapter", 1, finished.size());
    }

    @Test public void destroyShutsDownOnceAndIgnoresLateInitializationAndSpeech() {
        FakeEngine engine = new FakeEngine();
        List<AssemblyVoiceGuide.Status> states = new ArrayList<>();
        AssemblyVoiceGuide guide = new AssemblyVoiceGuide(engine, states::add);
        guide.speak("Pending.", "card");
        guide.close();
        guide.close();
        engine.initialize(true);
        guide.speak("After destroy.", "late");
        guide.replay();
        assertEquals(1, engine.shutdownCount);
        assertTrue(engine.spoken.isEmpty());
        assertEquals(Collections.singletonList(AssemblyVoiceGuide.Status.INITIALIZING), states);
        assertEquals(AssemblyVoiceGuide.Status.UNAVAILABLE, guide.getStatus());
    }

    @Test public void onlyInstalledEnglishVoicesWithoutNetworkRequirementsAreEligible() {
        assertTrue(AssemblyVoiceGuide.isInstalledOfflineEnglish(Locale.US, false, Collections.emptySet()));
        assertTrue(AssemblyVoiceGuide.isInstalledOfflineEnglish(Locale.UK, false, null));
        assertFalse(AssemblyVoiceGuide.isInstalledOfflineEnglish(Locale.US, true, Collections.emptySet()));
        assertFalse(AssemblyVoiceGuide.isInstalledOfflineEnglish(Locale.FRENCH, false, Collections.emptySet()));
        assertFalse(AssemblyVoiceGuide.isInstalledOfflineEnglish(null, false, Collections.emptySet()));
        assertFalse(AssemblyVoiceGuide.isInstalledOfflineEnglish(Locale.US, false,
                Collections.singleton("notInstalled")));
    }

    private static AssemblyVoiceGuide guide(FakeEngine engine) {
        return new AssemblyVoiceGuide(engine, ignored -> { });
    }

    private static final class FakeEngine implements AssemblyVoiceGuide.Engine {
        Listener listener;
        final List<String> spoken = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        boolean accept = true;
        int stopCount;
        int shutdownCount;
        @Override public void start(Listener listener) { this.listener = listener; }
        void initialize(boolean ready) { listener.onInitialized(ready); }
        @Override public boolean speak(String text, String utteranceId) {
            spoken.add(text);
            ids.add(utteranceId);
            return accept;
        }
        @Override public void stop() { stopCount++; }
        @Override public void shutdown() { shutdownCount++; }
    }
}
