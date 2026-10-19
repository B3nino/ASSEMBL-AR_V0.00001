package dev.chyken.assembl_ar.ui;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DemoClipNarratorTest {
    private static final class Voice implements DemoClipNarrator.Voice {
        final List<String> text=new ArrayList<>(),keys=new ArrayList<>();
        int stops;
        public void speak(String line,String key){text.add(line);keys.add(key);}
        public void stop(){stops++;}
    }
    private final Voice voice=new Voice();
    private final DemoClipNarrator narrator=new DemoClipNarrator(new String[]{"Brackets","Motors inward","Screws"},voice);

    @Test public void narratesActualPlaybackAndClipTransitionsOnlyOnce(){
        narrator.onPlayback(0,false,false);assertTrue(voice.text.isEmpty());
        narrator.onPlayback(0,true,false);narrator.onPlayback(0,true,false);
        narrator.onPlayback(1,true,false);narrator.onPlayback(2,true,false);
        assertEquals(List.of("Brackets","Motors inward","Screws"),voice.text);
    }
    @Test public void pauseCancelsAndResumeRepeatsTheCurrentInstruction(){
        narrator.onPlayback(1,true,false);narrator.onPlayback(1,false,false);
        assertEquals(1,voice.stops);narrator.onPlayback(1,true,false);
        assertEquals(List.of("Motors inward","Motors inward"),voice.text);
        assertNotEquals(voice.keys.get(0),voice.keys.get(1));
    }
    @Test public void seekWithinSameClipAndReplayNarrateAgain(){
        narrator.onPlayback(2,true,false);narrator.onPlayback(2,true,true);
        narrator.onPlayback(0,true,true);
        assertEquals(List.of("Screws","Screws","Brackets"),voice.text);
    }
    @Test public void ScrubbingWhilePausedDoesNotSpeak(){
        narrator.onPlayback(0,true,false);narrator.onPlayback(0,false,false);
        narrator.onPlayback(1,false,true);assertEquals(1,voice.text.size());
        narrator.onPlayback(1,true,false);assertEquals("Motors inward",voice.text.get(1));
    }
    @Test public void voiceReadinessAndUnmuteOnlySpeakWhileVideoIsPlaying(){
        narrator.onVoiceAvailable();assertTrue(voice.text.isEmpty());
        narrator.onPlayback(1,true,false);narrator.onVoiceAvailable();
        assertEquals(2,voice.text.size());narrator.onPlayback(1,false,false);
        narrator.onVoiceAvailable();assertEquals(2,voice.text.size());
    }
    @Test public void leavingDemoCancelsAndInvalidClipsNeverSpeak(){
        narrator.onPlayback(0,true,false);narrator.stop();narrator.onVoiceAvailable();
        narrator.onPlayback(3,true,false);narrator.onPlayback(-1,true,false);
        assertEquals(List.of("Brackets"),voice.text);
        assertTrue(voice.stops>0);
    }
    @Test public void onlyTheCurrentUtteranceCanReleaseTheClipBoundary(){
        narrator.onPlayback(0,true,false);String oldKey=voice.keys.get(0);
        narrator.onPlayback(1,true,false);assertTrue(narrator.isPending());
        assertFalse(narrator.onSpeechFinished(oldKey));assertTrue(narrator.isPending());
        assertTrue(narrator.onSpeechFinished(voice.keys.get(1)));assertFalse(narrator.isPending());
        assertFalse(narrator.onSpeechFinished(voice.keys.get(1)));
    }
    @Test public void pauseAndLeavingTheStepClearPendingNarration(){
        narrator.onPlayback(0,true,false);String key=voice.keys.get(0);
        narrator.onPlayback(0,false,false);assertFalse(narrator.isPending());
        assertFalse(narrator.onSpeechFinished(key));
        narrator.onPlayback(2,true,false);narrator.stop();assertFalse(narrator.isPending());
    }
}
