package dev.chyken.assembl_ar.workflow.phase;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manifest for recorded phase videos. A clip is {@code phase_<phaseId>.mp4} with its own narration
 * track, and its chapter start times follow {@link PhaseDemoScript}'s chapters exactly, so Replay
 * can restart it and a correction can play only the chapters that fix one target.
 *
 * <p>A clip is either bundled in {@code res/raw} and registered below, or side-loaded for trials
 * into {@link #SIDELOAD_DIRECTORY} together with {@code phase_<phaseId>.json}
 * ({@code {"chapterStartMillis": [0, ...], "durationMillis": n, "provenance": "..."}}).
 * Without a clip the app plays the narrated schematic chapters.</p>
 *
 * <p>No clip is authored yet: filming must use the physical kit (see docs/PHASE_MEDIA_SPEC.md).</p>
 */
public final class PhaseMediaCatalog {
    public static final class Clip {
        private final String phaseId;
        private final List<Long> chapterStartMillis;
        private final long durationMillis;
        private final String provenance;

        Clip(String phaseId, List<Long> chapterStartMillis, long durationMillis, String provenance) {
            this.phaseId = Objects.requireNonNull(phaseId, "phaseId");
            this.chapterStartMillis = Collections.unmodifiableList(new ArrayList<>(chapterStartMillis));
            if (this.chapterStartMillis.isEmpty() || this.chapterStartMillis.get(0) != 0L) {
                throw new IllegalArgumentException("Chapter one must start at zero");
            }
            for (int index = 1; index < this.chapterStartMillis.size(); index++) {
                if (this.chapterStartMillis.get(index) <= this.chapterStartMillis.get(index - 1)) {
                    throw new IllegalArgumentException("Chapter starts must increase");
                }
            }
            if (durationMillis <= this.chapterStartMillis.get(this.chapterStartMillis.size() - 1)) {
                throw new IllegalArgumentException("The final chapter needs a positive duration");
            }
            this.durationMillis = durationMillis;
            this.provenance = Objects.requireNonNull(provenance, "provenance");
        }

        public String getPhaseId() { return phaseId; }
        public String getRawResourceName() { return rawResourceName(phaseId); }
        public List<Long> getChapterStartMillis() { return chapterStartMillis; }
        public long getDurationMillis() { return durationMillis; }
        /** Who filmed/narrated the clip and against which kit and manual revision. */
        public String getProvenance() { return provenance; }

        public long chapterEndMillis(int chapterIndex) {
            return chapterIndex + 1 < chapterStartMillis.size()
                    ? chapterStartMillis.get(chapterIndex + 1) : durationMillis;
        }
    }

    /**
     * App-specific folder for side-loaded trial clips, e.g.
     * {@code adb push phase_motor_assemblies.mp4 /sdcard/Android/data/<package>/files/phase_media/}.
     */
    public static final String SIDELOAD_DIRECTORY = "phase_media";

    private static final Map<String, Clip> CLIPS = clips();

    private PhaseMediaCatalog() { }

    public static String rawResourceName(String phaseId) {
        return "phase_" + phaseId;
    }

    /**
     * Describes a side-loaded clip after checking that it has one start time per demo chapter.
     *
     * @throws IllegalArgumentException for an unknown phase or chapter times that do not fit
     */
    public static Clip describe(String phaseId, List<Long> chapterStartMillis, long durationMillis,
                                String provenance) {
        AssemblyPhase phase = AssemblyPhaseCatalog.byId(phaseId);
        if (phase == null) throw new IllegalArgumentException("Unknown phase " + phaseId);
        int chapters = PhaseDemoScript.forPhase(phase).getChapters().size();
        if (chapterStartMillis == null || chapterStartMillis.size() != chapters) {
            throw new IllegalArgumentException(phaseId + " needs " + chapters + " chapter start times");
        }
        return new Clip(phaseId, chapterStartMillis, durationMillis,
                provenance == null || provenance.trim().isEmpty() ? "side-loaded" : provenance);
    }

    /** The recorded clip for a phase, or null while the schematic chapters are the demo. */
    @Nullable
    public static Clip clipFor(String phaseId) { return CLIPS.get(phaseId); }

    public static Map<String, Clip> all() { return Collections.unmodifiableMap(CLIPS); }

    private static Map<String, Clip> clips() {
        Map<String, Clip> clips = new HashMap<>();
        // Add one entry per delivered clip, for example:
        // clips.put(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES, new Clip(
        //         AssemblyPhaseCatalog.MOTOR_ASSEMBLIES, Arrays.asList(0L, 6_000L, ...),
        //         212_000L, "Filmed 2026-10-xx on kit #1, manual rev. 2026, narrator ..."));
        return clips;
    }
}
