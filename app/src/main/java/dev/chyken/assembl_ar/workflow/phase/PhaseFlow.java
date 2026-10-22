package dev.chyken.assembl_ar.workflow.phase;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Learner-visible state of one phase:
 * scan parts → demo → assemble ⇄ replay → Scan → verify → correction / physical check → complete.
 *
 * <p>Camera evidence is accepted only in {@link Mode#SCAN_PARTS} and {@link Mode#VERIFY}, and only
 * for requests tagged with the current phase, mode and generation. Every transition that starts,
 * ends or interrupts evidence collection advances the generation, so an in-flight frame from an
 * earlier inventory, an earlier Scan or a paused Activity can never satisfy the current check.
 * Replaying a demonstration never verifies or completes anything.</p>
 */
public final class PhaseFlow {
    public enum Mode { SCAN_PARTS, DEMO, ASSEMBLE, VERIFY, CORRECTION, PHYSICAL_CHECK, COMPLETE }

    /** Identifies the phase, mode and attempt a recognition request was made for. */
    public static final class ScanTag {
        private final String phaseId;
        private final Mode mode;
        private final int generation;

        public ScanTag(String phaseId, Mode mode, int generation) {
            this.phaseId = Objects.requireNonNull(phaseId, "phaseId");
            this.mode = Objects.requireNonNull(mode, "mode");
            this.generation = generation;
        }

        public String getPhaseId() { return phaseId; }
        public Mode getMode() { return mode; }
        public int getGeneration() { return generation; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ScanTag)) return false;
            ScanTag tag = (ScanTag) other;
            return generation == tag.generation && phaseId.equals(tag.phaseId) && mode == tag.mode;
        }

        @Override public int hashCode() { return Objects.hash(phaseId, mode, generation); }

        @Override public String toString() { return phaseId + "/" + mode + "#" + generation; }
    }

    private final AssemblyPhase phase;
    private Phase2GearMotorFlow gearMotorFlow;
    private Phase3MotorDriverFlow driverFlow;
    private Phase9StandoffWheelsFlow standoffWheelsFlow;
    public void attachStandoffWheelsFlow(Phase9StandoffWheelsFlow flow){if(!AssemblyPhaseCatalog.CENTER_STANDOFF.equals(phase.getId()))throw new IllegalStateException();standoffWheelsFlow=Objects.requireNonNull(flow);}
    private Phase8SensorWiringFlow sensorWiringFlow;
    public void attachSensorWiringFlow(Phase8SensorWiringFlow flow){if(!AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT.equals(phase.getId()))throw new IllegalStateException();sensorWiringFlow=Objects.requireNonNull(flow);}
    private Phase7CasterTrackerFlow casterTrackerFlow;
    public void attachCasterTrackerFlow(Phase7CasterTrackerFlow flow){if(!AssemblyPhaseCatalog.CASTER_FIT.equals(phase.getId()))throw new IllegalStateException();casterTrackerFlow=Objects.requireNonNull(flow);}
    private Phase6DriverWiringFlow driverWiringFlow;
    public void attachDriverWiringFlow(Phase6DriverWiringFlow flow){if(!AssemblyPhaseCatalog.DRIVER_WIRING.equals(phase.getId()))throw new IllegalStateException();driverWiringFlow=Objects.requireNonNull(flow);}
    private Phase5MicrocontrollerFlow microcontrollerFlow;
    public void attachMicrocontrollerFlow(Phase5MicrocontrollerFlow flow){if(!AssemblyPhaseCatalog.MICROCONTROLLER_FIT.equals(phase.getId()))throw new IllegalStateException();microcontrollerFlow=Objects.requireNonNull(flow);}
    private Phase4MotorWiringFlow wiringFlow;
    public void attachWiringFlow(Phase4MotorWiringFlow flow){if(!AssemblyPhaseCatalog.MOTOR_LEADS.equals(phase.getId()))throw new IllegalStateException();wiringFlow=Objects.requireNonNull(flow);}
    public void attachDriverFlow(Phase3MotorDriverFlow flow){if(!AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT.equals(phase.getId()))throw new IllegalStateException();driverFlow=Objects.requireNonNull(flow);}
    public void attachGearMotorFlow(Phase2GearMotorFlow flow){
        if(!AssemblyPhaseCatalog.MOTOR_ASSEMBLIES.equals(phase.getId()))throw new IllegalStateException();
        gearMotorFlow=Objects.requireNonNull(flow);
    }
    private Mode mode;
    private boolean previewing;
    private int generation;
    private long evidenceStartNanos = Long.MAX_VALUE;
    private long lastFrameNanos = Long.MIN_VALUE;
    private boolean inventoryComplete;
    @Nullable private String correctionTargetId;

    public PhaseFlow(AssemblyPhase phase, long nowNanos) {
        this(phase, nowNanos, false);
    }

    /**
     * @param inventoryAlreadyComplete the parts of this phase were identified before the screen
     *        was recreated; resume assembling (Replay and Scan available) instead of rescanning.
     *        Inventory never substitutes for Scan, so this cannot skip any assembly check.
     */
    public PhaseFlow(AssemblyPhase phase, long nowNanos, boolean inventoryAlreadyComplete) {
        this.phase = Objects.requireNonNull(phase, "phase");
        start(nowNanos, inventoryAlreadyComplete && !phase.completesOnInventory());
    }

    private void start(long nowNanos, boolean resumeAssembling) {
        previewing = false;
        correctionTargetId = null;
        inventoryComplete = resumeAssembling || phase.getCameraInventory().isEmpty();
        // A phase with nothing new to identify goes straight to its demonstration.
        enter(resumeAssembling ? Mode.ASSEMBLE : inventoryComplete ? Mode.DEMO : Mode.SCAN_PARTS,
                nowNanos);
    }

    public AssemblyPhase getPhase() { return phase; }
    public Mode getMode() { return standoffWheelsFlow!=null?standoffWheelsFlow.mode():sensorWiringFlow!=null?sensorWiringFlow.mode():casterTrackerFlow!=null?casterTrackerFlow.mode():driverWiringFlow!=null?driverWiringFlow.mode():microcontrollerFlow!=null?microcontrollerFlow.mode():wiringFlow!=null?wiringFlow.mode():driverFlow!=null?driverFlow.mode():gearMotorFlow==null?mode:gearMotorFlow.mode(); }
    public int getGeneration() { return standoffWheelsFlow!=null?standoffWheelsFlow.tag().getGeneration():sensorWiringFlow!=null?sensorWiringFlow.tag().getGeneration():casterTrackerFlow!=null?casterTrackerFlow.tag().getGeneration():driverWiringFlow!=null?driverWiringFlow.tag().getGeneration():microcontrollerFlow!=null?microcontrollerFlow.tag().getGeneration():wiringFlow!=null?wiringFlow.tag().getGeneration():driverFlow!=null?driverFlow.tag().getGeneration():gearMotorFlow==null?generation:gearMotorFlow.tag().getGeneration(); }
    /** A demo opened from part scanning; it returns to scanning without skipping inventory. */
    public boolean isPreviewing() { return previewing; }
    public boolean isInventoryComplete() { return standoffWheelsFlow!=null?standoffWheelsFlow.inventoryComplete():sensorWiringFlow!=null?sensorWiringFlow.step()>2:casterTrackerFlow!=null?casterTrackerFlow.step()>2:driverWiringFlow!=null?driverWiringFlow.step()>1:microcontrollerFlow!=null?microcontrollerFlow.step()>1:wiringFlow!=null||(driverFlow!=null?driverFlow.step()>1:gearMotorFlow==null?inventoryComplete:gearMotorFlow.partsComplete()); }
    @Nullable public String getCorrectionTargetId() { return correctionTargetId; }

    public ScanTag tag() { return standoffWheelsFlow!=null?standoffWheelsFlow.tag():sensorWiringFlow!=null?sensorWiringFlow.tag():casterTrackerFlow!=null?casterTrackerFlow.tag():driverWiringFlow!=null?driverWiringFlow.tag():microcontrollerFlow!=null?microcontrollerFlow.tag():wiringFlow!=null?wiringFlow.tag():driverFlow!=null?driverFlow.tag():gearMotorFlow==null?new ScanTag(phase.getId(), mode, generation):gearMotorFlow.tag(); }

    /** Camera analysis is needed only while identifying parts or verifying the finished group. */
    public boolean collectsEvidence() {
        if(standoffWheelsFlow!=null)return standoffWheelsFlow.scanning();
        if(sensorWiringFlow!=null)return sensorWiringFlow.scanning();
        if(casterTrackerFlow!=null)return casterTrackerFlow.scanning();
        if(driverWiringFlow!=null)return driverWiringFlow.scanning();
        if(microcontrollerFlow!=null)return microcontrollerFlow.scanning();
        if(wiringFlow!=null)return false;
        if(driverFlow!=null)return driverFlow.scanning();
        if(gearMotorFlow!=null)return gearMotorFlow.scanning();
        return mode == Mode.SCAN_PARTS && !previewing || mode == Mode.VERIFY;
    }

    /** Scan is offered while the learner watches, assembles, or has just seen a correction. */
    public boolean canScan() {
        if(standoffWheelsFlow!=null)return false;
        if(sensorWiringFlow!=null)return false;
        if(casterTrackerFlow!=null)return false;
        if(driverWiringFlow!=null)return false;
        if(microcontrollerFlow!=null)return false;
        if(wiringFlow!=null)return false;
        if(driverFlow!=null)return false;
        if(gearMotorFlow!=null)return false;
        return mode == Mode.DEMO && !previewing || mode == Mode.ASSEMBLE || mode == Mode.CORRECTION;
    }

    /**
     * Accepts a result only for this exact phase/mode/generation, captured after the evidence
     * window opened and newer than every frame already used. Duplicates and reordered frames fail.
     */
    public boolean accept(@Nullable ScanTag resultTag, long frameNanos) {
        if(standoffWheelsFlow!=null)return standoffWheelsFlow.accepts(resultTag,frameNanos);
        if(sensorWiringFlow!=null)return sensorWiringFlow.accepts(resultTag,frameNanos);
        if(casterTrackerFlow!=null)return casterTrackerFlow.accepts(resultTag,frameNanos);
        if(driverWiringFlow!=null)return driverWiringFlow.accepts(resultTag,frameNanos);
        if(microcontrollerFlow!=null)return microcontrollerFlow.accepts(resultTag,frameNanos);
        if(wiringFlow!=null)return false;
        if(driverFlow!=null)return driverFlow.accepts(resultTag,frameNanos);
        if(gearMotorFlow!=null)return gearMotorFlow.accepts(resultTag,frameNanos);
        if (!collectsEvidence() || !tag().equals(resultTag)
                || frameNanos < evidenceStartNanos || frameNanos <= lastFrameNanos) return false;
        lastFrameNanos = frameNanos;
        return true;
    }

    /**
     * Opens a fresh evidence window in the current mode, e.g. for the next inventory part or the
     * next verification group, so frames requested for the previous subject cannot be reused.
     */
    public void restartEvidence(long nowNanos) {
        if(standoffWheelsFlow!=null){standoffWheelsFlow.resume(nowNanos);return;}
        if(sensorWiringFlow!=null){sensorWiringFlow.resume(nowNanos);return;}
        if(casterTrackerFlow!=null){casterTrackerFlow.resume(nowNanos);return;}
        if(driverWiringFlow!=null){driverWiringFlow.resume(nowNanos);return;}
        if(microcontrollerFlow!=null){microcontrollerFlow.resume(nowNanos);return;}
        if(wiringFlow!=null){wiringFlow.interrupt();return;}
        if(driverFlow!=null){driverFlow.resume(nowNanos);return;}
        if(gearMotorFlow!=null){gearMotorFlow.restartEvidence(nowNanos);return;}
        if (collectsEvidence()) enter(mode, nowNanos);
    }

    /** True when a result was requested for this exact phase attempt, even outside evidence modes. */
    public boolean isCurrent(@Nullable ScanTag resultTag) {
        return tag().equals(resultTag);
    }

    /** @return true when inventory finished; completesOnInventory phases are then complete. */
    public boolean inventoryComplete(long nowNanos) {
        if (mode != Mode.SCAN_PARTS || previewing) return false;
        inventoryComplete = true;
        enter(phase.completesOnInventory() ? Mode.COMPLETE : Mode.DEMO, nowNanos);
        return true;
    }

    /** The full demonstration reached its final view. */
    public void demoFinished(long nowNanos) {
        if (mode != Mode.DEMO) return;
        if (previewing) {
            previewing = false;
            enter(Mode.SCAN_PARTS, nowNanos);
        } else {
            enter(Mode.ASSEMBLE, nowNanos);
        }
    }

    /**
     * Restarts the complete demonstration. From part scanning this is a preview that returns to
     * scanning. It never changes inventory, verification or completion.
     */
    public boolean replay(long nowNanos) {
        switch (mode) {
            case SCAN_PARTS:
                previewing = true;
                enter(Mode.DEMO, nowNanos);
                return true;
            case DEMO:
            case ASSEMBLE:
            case CORRECTION:
            case VERIFY:
                correctionTargetId = null;
                enter(Mode.DEMO, nowNanos);
                return true;
            default:
                return false;
        }
    }

    /**
     * The learner's explicit "check my finished phase" action. Phases without camera targets go
     * straight to their direct checklist; the camera cannot establish wiring or fastening.
     */
    public boolean scan(long nowNanos) {
        if (!canScan()) return false;
        correctionTargetId = null;
        enter(phase.hasVisualTargets() ? Mode.VERIFY : Mode.PHYSICAL_CHECK, nowNanos);
        return true;
    }

    public void verificationPassed(long nowNanos) {
        if (mode == Mode.VERIFY) enter(Mode.PHYSICAL_CHECK, nowNanos);
    }

    /** Cannot see clearly is not wrong: return to assembling with framing help. */
    public void verificationInconclusive(long nowNanos) {
        if (mode == Mode.VERIFY) enter(Mode.ASSEMBLE, nowNanos);
    }

    public void correctionNeeded(String targetId, long nowNanos) {
        if (mode != Mode.VERIFY) return;
        correctionTargetId = Objects.requireNonNull(targetId, "targetId");
        enter(Mode.CORRECTION, nowNanos);
    }

    public void physicalCheckCancelled(long nowNanos) {
        if (mode == Mode.PHYSICAL_CHECK) enter(Mode.ASSEMBLE, nowNanos);
    }

    /** Only a confirmed physical checklist after the required visual pass completes a phase. */
    public boolean physicalCheckConfirmed(long nowNanos) {
        if (mode != Mode.PHYSICAL_CHECK) return false;
        enter(Mode.COMPLETE, nowNanos);
        return true;
    }

    /** Progress could not be saved; offer the checklist again rather than losing the result. */
    public void completionSaveFailed(long nowNanos) {
        if (mode == Mode.COMPLETE && !phase.completesOnInventory()) enter(Mode.PHYSICAL_CHECK, nowNanos);
    }

    /** Activity pause/navigation: in-flight evidence is discarded and a Scan must be repeated. */
    public void interrupt(long nowNanos) {
        if(standoffWheelsFlow!=null){standoffWheelsFlow.pause();return;}
        if(sensorWiringFlow!=null){sensorWiringFlow.pause();return;}
        if(casterTrackerFlow!=null){casterTrackerFlow.pause();return;}
        if(driverWiringFlow!=null){driverWiringFlow.pause();return;}
        if(microcontrollerFlow!=null){microcontrollerFlow.pause();return;}
        if(wiringFlow!=null){wiringFlow.interrupt();return;}
        if(driverFlow!=null){driverFlow.pause();return;}
        if(gearMotorFlow!=null){gearMotorFlow.pause();return;}
        if (mode == Mode.VERIFY) {
            enter(Mode.ASSEMBLE, nowNanos);
        } else if (mode == Mode.SCAN_PARTS) {
            enter(Mode.SCAN_PARTS, Long.MAX_VALUE);
        }
    }

    /** Resuming part scanning accepts only frames captured after the resume. */
    public void resume(long nowNanos) {
        if(standoffWheelsFlow!=null){standoffWheelsFlow.resume(nowNanos);return;}
        if(sensorWiringFlow!=null){sensorWiringFlow.resume(nowNanos);return;}
        if(casterTrackerFlow!=null){casterTrackerFlow.resume(nowNanos);return;}
        if(driverWiringFlow!=null){driverWiringFlow.resume(nowNanos);return;}
        if(microcontrollerFlow!=null){microcontrollerFlow.resume(nowNanos);return;}
        if(wiringFlow!=null){wiringFlow.interrupt();return;}
        if(driverFlow!=null){driverFlow.resume(nowNanos);return;}
        if(gearMotorFlow!=null){gearMotorFlow.resume(nowNanos);return;}
        if (mode == Mode.SCAN_PARTS && !previewing) enter(Mode.SCAN_PARTS, nowNanos);
    }

    /** Restart this phase from its first mode, discarding every result of the current attempt. */
    public void restart(long nowNanos) {
        if(standoffWheelsFlow!=null){standoffWheelsFlow.restart(nowNanos);return;}
        if(sensorWiringFlow!=null){sensorWiringFlow.restart(nowNanos);return;}
        if(casterTrackerFlow!=null){casterTrackerFlow.restart(nowNanos);return;}
        if(driverWiringFlow!=null){driverWiringFlow.restart(nowNanos);return;}
        if(microcontrollerFlow!=null){microcontrollerFlow.restart(nowNanos);return;}
        if(wiringFlow!=null){wiringFlow.restart();return;}
        if(driverFlow!=null){driverFlow.restart(nowNanos);return;}
        if(gearMotorFlow!=null){gearMotorFlow.restart(nowNanos);return;}
        start(nowNanos, false);
    }

    /** Explicit developer action; produces no learner completion or camera evidence. */
    public void devStartDemo(long nowNanos) {
        inventoryComplete=true; previewing=false; correctionTargetId=null;
        enter(Mode.DEMO,nowNanos);
    }

    public void devComplete(long nowNanos) {
        previewing=false; correctionTargetId=null; enter(Mode.COMPLETE,nowNanos);
    }

    /** Restores a saved UI checkpoint; camera evidence always starts with a new generation/window. */
    public void restoreSession(Mode savedMode,boolean partsComplete,boolean preview,long nowNanos){
        if(gearMotorFlow!=null||driverFlow!=null||wiringFlow!=null||microcontrollerFlow!=null||driverWiringFlow!=null||casterTrackerFlow!=null||sensorWiringFlow!=null||standoffWheelsFlow!=null)throw new IllegalStateException("Specialized flow owns its checkpoint");
        inventoryComplete=partsComplete||phase.getCameraInventory().isEmpty();
        previewing=preview&&!inventoryComplete&&savedMode==Mode.DEMO;
        correctionTargetId=null;
        Mode restored=savedMode==Mode.CORRECTION?Mode.ASSEMBLE:savedMode;
        if(!inventoryComplete&&!previewing)restored=Mode.SCAN_PARTS;
        enter(restored,nowNanos);
    }

    private void enter(Mode next, long evidenceStart) {
        mode = next;
        generation++;
        evidenceStartNanos = evidenceStart;
        lastFrameNanos = Long.MIN_VALUE;
    }
}
