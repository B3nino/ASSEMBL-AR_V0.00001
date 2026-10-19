package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.annotation.MainThread;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Lifecycle-owned, offline instruction speech. Call public methods on the main thread.
 * Availability describes the engine, not whether the device's volume makes speech audible.
 * The activity owns the mute preference and calls stop on pause or before changing cards.
 */
@MainThread
public final class AssemblyVoiceGuide implements AutoCloseable {
    public enum Status { INITIALIZING, AVAILABLE, UNAVAILABLE, MUTED }

    public interface Callback {
        void onStatusChanged(Status status);

        /** The utterance requested with {@code key} finished speaking (not cancelled or failed). */
        default void onSpeechFinished(String key) { }
        /** Called on the main thread when the engine actually begins the current utterance. */
        default void onSpeechStarted(String key, String text) { }
    }

    private final Engine engine;
    private Callback callback;
    private Status status;
    private boolean initialized;
    private boolean available;
    private boolean muted;
    private boolean closed;
    private boolean pending;
    private String currentText;
    private String currentKey;
    private String activeUtterance;
    private long utteranceSequence;

    public AssemblyVoiceGuide(Context context, Callback callback) {
        this(new AndroidEngine(Objects.requireNonNull(context, "context").getApplicationContext()), callback);
    }

    // A narrow engine seam keeps lifecycle races testable without a platform voice installation.
    AssemblyVoiceGuide(Engine engine, Callback callback) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.callback = Objects.requireNonNull(callback, "callback");
        publishStatus();
        try {
            engine.start(new Engine.Listener() {
                @Override public void onStart(String utteranceId) {
                    if(closed || utteranceId==null || !utteranceId.equals(activeUtterance)) return;
                    if(callback!=null && currentKey!=null) callback.onSpeechStarted(currentKey,currentText);
                }
                @Override public void onInitialized(boolean ready) {
                    if (closed) return;
                    initialized = true;
                    available = ready;
                    publishStatus();
                    if (pending && available && !muted) playCurrent();
                }

                @Override public void onError(String utteranceId) {
                    if (!closed && utteranceId != null && utteranceId.equals(activeUtterance)) {
                        failSpeech();
                    }
                }

                @Override public void onDone(String utteranceId) {
                    if (closed || utteranceId == null || !utteranceId.equals(activeUtterance)) return;
                    activeUtterance = null;
                    String finishedKey = currentKey;
                    if (callback != null && finishedKey != null) callback.onSpeechFinished(finishedKey);
                }
            });
        } catch (RuntimeException failure) {
            initialized = true;
            available = false;
            publishStatus();
        }
    }

    /** Requests the latest instruction; repeated camera/UI updates with the same key do not speak again. */
    public void speak(String text, String key) {
        if (closed) return;
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) { stop(); return; }
        String safeKey = key == null ? cleaned : key;
        if (safeKey.equals(currentKey) && cleaned.equals(currentText)) return;
        currentText = cleaned;
        currentKey = safeKey;
        pending = !muted;
        if (available && !muted) playCurrent();
    }

    /** Explicitly repeats the current instruction. Muting and lifecycle cancellation still win. */
    public void replay() {
        if (closed || muted || currentText == null) return;
        pending = true;
        if (available) playCurrent();
    }

    /** Cancels playback and forgets pending/current text so late initialization cannot read an old card. */
    public void stop() {
        if (closed) return;
        pending = false;
        currentText = null;
        currentKey = null;
        activeUtterance = null;
        stopEngine();
    }

    /** Unmuting does not automatically resurrect cancelled speech; call replay or speak explicitly. */
    public void setMuted(boolean muted) {
        if (closed || this.muted == muted) return;
        this.muted = muted;
        pending = false;
        activeUtterance = null;
        if (muted) stopEngine();
        else currentKey = null;
        publishStatus();
    }

    public boolean isMuted() { return muted; }
    public Status getStatus() { return status; }

    private void playCurrent() {
        if (closed || muted || !available || currentText == null) return;
        pending = false;
        activeUtterance = "assembly-" + (++utteranceSequence);
        try {
            if (!engine.speak(currentText, activeUtterance)) failSpeech();
        } catch (RuntimeException failure) {
            failSpeech();
        }
    }

    private void failSpeech() {
        available = false;
        pending = false;
        activeUtterance = null;
        stopEngine();
        publishStatus();
    }

    private void stopEngine() {
        try { engine.stop(); } catch (RuntimeException ignored) { /* Service may already be gone. */ }
    }

    private void publishStatus() {
        Status next = closed ? Status.UNAVAILABLE : muted ? Status.MUTED
                : !initialized ? Status.INITIALIZING : available ? Status.AVAILABLE : Status.UNAVAILABLE;
        if (next != status) {
            status = next;
            if (callback != null) callback.onStatusChanged(next);
        }
    }

    @Override public void close() {
        if (closed) return;
        stop();
        closed = true;
        available = false;
        callback = null;
        publishStatus();
        try { engine.shutdown(); } catch (RuntimeException ignored) { /* Already disconnected. */ }
    }

    interface Engine {
        interface Listener {
            default void onStart(String utteranceId) { }
            void onInitialized(boolean available);
            void onError(String utteranceId);
            void onDone(String utteranceId);
        }
        void start(Listener listener);
        boolean speak(String text, String utteranceId);
        void stop();
        void shutdown();
    }

    static boolean isInstalledOfflineEnglish(Locale locale, boolean networkRequired, Set<String> features) {
        return locale != null && "en".equals(locale.getLanguage()) && !networkRequired
                && (features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
    }

    private static final class AndroidEngine implements Engine {
        private final Context context;
        private final Handler main = new Handler(Looper.getMainLooper());
        private TextToSpeech tts;
        private Listener listener;
        private boolean closed;
        private boolean initializationFinished;
        private final Runnable initializationTimeout = () -> finishInitialization(TextToSpeech.ERROR);

        AndroidEngine(Context context) { this.context = context; }

        @Override public void start(Listener listener) {
            this.listener = listener;
            // Always post: even synchronous initialization failure must see the assigned tts field.
            tts = new TextToSpeech(context, result -> main.post(() -> finishInitialization(result)));
            main.postDelayed(initializationTimeout, 10_000L);
        }

        private void finishInitialization(int result) {
            if (closed || initializationFinished) return;
            initializationFinished = true;
            main.removeCallbacks(initializationTimeout);
            boolean ready = false;
            try {
                if (result == TextToSpeech.SUCCESS && selectOfflineVoice()) {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    ready = tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) {
                            main.post(() -> { if(!closed) listener.onStart(id); });
                        }
                        @Override public void onDone(String id) { reportDone(id); }
                        @Override public void onError(String id) { reportError(id); }
                        @Override public void onError(String id, int errorCode) { reportError(id); }
                    }) == TextToSpeech.SUCCESS;
                }
            } catch (RuntimeException failure) { ready = false; }
            listener.onInitialized(ready);
        }

        private boolean selectOfflineVoice() {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return false;
            List<Voice> candidates = new ArrayList<>();
            for (Voice voice : voices) {
                if (voice != null && isInstalledOfflineEnglish(voice.getLocale(),
                        voice.isNetworkConnectionRequired(), voice.getFeatures())) candidates.add(voice);
            }
            String preferredCountry = Locale.getDefault().getCountry();
            candidates.sort(Comparator.comparingInt((Voice voice) ->
                            voice.getLocale().getCountry().equals(preferredCountry) ? 0
                                    : "US".equals(voice.getLocale().getCountry()) ? 1 : 2)
                    .thenComparing(Comparator.comparingInt(Voice::getQuality).reversed())
                    .thenComparing(Voice::getName));
            for (Voice voice : candidates) {
                if (tts.setVoice(voice) == TextToSpeech.SUCCESS) return true;
            }
            return false;
        }

        private void reportError(String id) {
            main.post(() -> { if (!closed) listener.onError(id); });
        }

        private void reportDone(String id) {
            main.post(() -> { if (!closed) listener.onDone(id); });
        }

        @Override public boolean speak(String text, String utteranceId) {
            return !closed && tts != null && text.length() <= TextToSpeech.getMaxSpeechInputLength()
                    && tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS;
        }

        @Override public void stop() { if (!closed && tts != null) tts.stop(); }

        @Override public void shutdown() {
            closed = true;
            main.removeCallbacksAndMessages(null);
            if (tts != null) tts.shutdown();
            tts = null;
            listener = null;
        }
    }
}
