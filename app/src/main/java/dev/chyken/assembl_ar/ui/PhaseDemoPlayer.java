package dev.chyken.assembl_ar.ui;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.VideoView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import dev.chyken.assembl_ar.workflow.phase.PhaseDemoScript;
import dev.chyken.assembl_ar.workflow.phase.PhaseDemoScript.Chapter;
import dev.chyken.assembl_ar.workflow.phase.PhaseMediaCatalog;

/**
 * Plays a phase's complete demonstration as consecutive chapters and keeps the last chapter on
 * screen. A chapter advances only after both its schematic animation and its narration finished,
 * so speech is never cut off by the next diagram. Without an available voice, a chapter stays up
 * for a reading-time estimate instead. When a recorded clip exists for the phase, the clip (with
 * its own narration) plays instead and chapter captions follow its authored start times.
 *
 * <p>Playback is instruction only: it never produces recognition evidence or completion.</p>
 */
@MainThread
public final class PhaseDemoPlayer {
    public interface Listener {
        void onChapterStarted(Chapter chapter, int position, int count);
        void onFinished();
    }

    private static final long PAUSE_BETWEEN_CHAPTERS_MILLIS = 700L;
    private static final long CLIP_POLL_MILLIS = 250L;

    private final AssemblyDemoView demoView;
    private final AssemblyVoiceGuide voice;
    @Nullable private final VideoView videoView;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable animationDone = () -> { animationFinished = true; maybeAdvance(); };
    private final Runnable silentDone = () -> { speechFinished = true; maybeAdvance(); };
    private final Runnable advance = this::advance;
    private final Runnable pollClip = this::pollClip;

    private List<Chapter> chapters = Collections.emptyList();
    @Nullable private String leadIn;
    private String keyPrefix = "";
    private int position = -1;
    private int playCount;
    private boolean playing;
    private boolean animationFinished;
    private boolean speechFinished;
    @Nullable private String expectedSpeechKey;
    @Nullable private PhaseMediaCatalog.Clip clip;
    @Nullable private Uri clipUri;
    @Nullable private MediaPlayer clipPlayer;
    private long clipEndMillis;

    public PhaseDemoPlayer(AssemblyDemoView demoView, AssemblyVoiceGuide voice,
                           @Nullable VideoView videoView, Listener listener) {
        this.demoView = Objects.requireNonNull(demoView, "demoView");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.videoView = videoView;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Plays chapters from the first, prefixing the first narration with {@code leadIn} (the phase
     * introduction or a correction explanation). {@code clip}/{@code clipUri} select a recorded
     * video; each chapter's index in the full script selects its time range within that clip.
     */
    public void play(List<Chapter> chapters, @Nullable String leadIn, String keyPrefix,
                     @Nullable PhaseMediaCatalog.Clip clip, @Nullable Uri clipUri) {
        stopTimers();
        this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
        this.leadIn = leadIn;
        this.keyPrefix = keyPrefix;
        this.clip = clip != null && clipUri != null && videoView != null ? clip : null;
        this.clipUri = this.clip == null ? null : clipUri;
        playCount++;
        playing = !this.chapters.isEmpty();
        position = -1;
        if (!playing) {
            listener.onFinished();
            return;
        }
        if (this.clip != null) startClip();
        else startChapter(0);
    }

    /** Learner navigation to another chapter; playback continues from there. */
    public void jumpTo(int target) {
        if (chapters.isEmpty() || target < 0 || target >= chapters.size()) return;
        stopTimers();
        playing = true;
        playCount++;
        if (clip != null) {
            seekClipTo(target);
        } else {
            startChapter(target);
        }
    }

    /** Lifecycle pause: silence and freeze, keeping the current chapter on screen. */
    public void pause() {
        stopTimers();
        voice.stop();
        demoView.pause();
        if (clipPlayer != null && videoView != null) videoView.pause();
    }

    /** Restarts the current chapter after a pause, if the demo was still playing. */
    public void resume() {
        if (!playing || position < 0) {
            demoView.resume();
            return;
        }
        playCount++;
        if (clip != null && videoView != null) {
            if (clipPlayer != null) {
                videoView.start();
                handler.post(pollClip);
            } else {
                startClip();
            }
        } else {
            startChapter(position);
        }
    }

    /** Stops playback but leaves the current view (normally the final chapter) on screen. */
    public void stop() {
        playing = false;
        stopTimers();
        voice.stop();
        if (videoView != null && clipPlayer != null) videoView.pause();
    }

    /** Releases the clip view; call when leaving the phase or the Activity. */
    public void release() {
        stop();
        if (videoView != null) {
            videoView.stopPlayback();
            videoView.setVisibility(View.GONE);
        }
        clipPlayer = null;
        clip = null;
        demoView.setVisibility(View.VISIBLE);
    }

    public boolean isPlaying() { return playing; }
    public int getPosition() { return position; }
    public int getCount() { return chapters.size(); }

    /** Forwarded from the voice guide callback. */
    public void onSpeechFinished(String key) {
        if (!playing || clip != null || !key.equals(expectedSpeechKey)) return;
        speechFinished = true;
        maybeAdvance();
    }

    /** The learner muted/unmuted, or the voice became unavailable during a chapter. */
    public void onVoiceModeChanged() {
        if (clip != null) {
            applyClipVolume();
            return;
        }
        if (!playing || position < 0 || speechFinished) return;
        if (!voiceUsable()) {
            expectedSpeechKey = null;
            handler.removeCallbacks(silentDone);
            handler.postDelayed(silentDone, chapters.get(position).getSilentDurationMillis());
        }
    }

    private void startChapter(int index) {
        stopTimers();
        position = index;
        Chapter chapter = chapters.get(index);
        demoView.setVisibility(View.VISIBLE);
        if (videoView != null) videoView.setVisibility(View.GONE);
        demoView.setDemo(chapter.getDemo());
        demoView.replay();
        animationFinished = false;
        speechFinished = false;
        String narration = index == 0 && leadIn != null && !leadIn.isEmpty()
                ? leadIn + " " + chapter.getNarration() : chapter.getNarration();
        listener.onChapterStarted(chapter, index, chapters.size());
        handler.postDelayed(animationDone, PhaseDemoScript.ANIMATION_MILLIS);
        if (voiceUsable()) {
            expectedSpeechKey = keyPrefix + ":" + playCount + ":" + index;
            voice.speak(narration, expectedSpeechKey);
            // Safety net: an engine that never reports completion cannot stall the demo.
            handler.postDelayed(silentDone, 3 * PhaseDemoScript.silentDurationMillis(narration) + 5_000L);
        } else {
            expectedSpeechKey = null;
            voice.stop();
            handler.postDelayed(silentDone, PhaseDemoScript.silentDurationMillis(narration));
        }
    }

    private void maybeAdvance() {
        if (!playing || !animationFinished || !speechFinished) return;
        handler.removeCallbacks(silentDone);
        handler.postDelayed(advance, PAUSE_BETWEEN_CHAPTERS_MILLIS);
    }

    private void advance() {
        if (!playing) return;
        if (position + 1 < chapters.size()) {
            startChapter(position + 1);
        } else {
            playing = false;
            listener.onFinished();
        }
    }

    private boolean voiceUsable() {
        return !voice.isMuted() && voice.getStatus() == AssemblyVoiceGuide.Status.AVAILABLE;
    }

    private void startClip() {
        if (videoView == null || clip == null || clipUri == null) return;
        voice.stop();
        demoView.pause();
        demoView.setVisibility(View.GONE);
        videoView.setVisibility(View.VISIBLE);
        int first = chapters.get(0).getIndex();
        int last = chapters.get(chapters.size() - 1).getIndex();
        clipEndMillis = clip.chapterEndMillis(last);
        videoView.setOnPreparedListener(player -> {
            clipPlayer = player;
            applyClipVolume();
            player.seekTo((int) clip.getChapterStartMillis().get(first).longValue());
            videoView.start();
            handler.post(pollClip);
        });
        videoView.setOnCompletionListener(player -> finishClip());
        videoView.setOnErrorListener((player, what, extra) -> {
            // A damaged clip falls back to the narrated schematic chapters.
            clip = null;
            clipPlayer = null;
            videoView.setVisibility(View.GONE);
            startChapter(Math.max(0, position));
            return true;
        });
        videoView.setVideoURI(clipUri);
    }

    private void seekClipTo(int target) {
        if (videoView == null || clip == null || clipPlayer == null) return;
        videoView.seekTo((int) clip.getChapterStartMillis().get(chapters.get(target).getIndex()).longValue());
        videoView.start();
        handler.post(pollClip);
    }

    private void pollClip() {
        if (!playing || clip == null || videoView == null) return;
        long now = videoView.getCurrentPosition();
        if (now >= clipEndMillis) {
            finishClip();
            return;
        }
        int current = -1;
        for (int index = 0; index < chapters.size(); index++) {
            if (now >= clip.getChapterStartMillis().get(chapters.get(index).getIndex())) current = index;
        }
        if (current >= 0 && current != position) {
            position = current;
            listener.onChapterStarted(chapters.get(current), current, chapters.size());
        }
        handler.postDelayed(pollClip, CLIP_POLL_MILLIS);
    }

    private void finishClip() {
        if (!playing) return;
        playing = false;
        handler.removeCallbacks(pollClip);
        if (videoView != null) videoView.pause();
        listener.onFinished();
    }

    private void applyClipVolume() {
        if (clipPlayer == null) return;
        float volume = voice.isMuted() ? 0f : 1f;
        try {
            clipPlayer.setVolume(volume, volume);
        } catch (IllegalStateException released) {
            clipPlayer = null;
        }
    }

    private void stopTimers() {
        handler.removeCallbacks(animationDone);
        handler.removeCallbacks(silentDone);
        handler.removeCallbacks(advance);
        handler.removeCallbacks(pollClip);
    }
}
