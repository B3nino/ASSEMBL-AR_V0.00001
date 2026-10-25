package dev.chyken.assembl_ar.workflow.phase;

/** Shared action guards; the phase flow remains the owner of its persisted evidence. */
public final class AssemblyStepNavigationManager {
    private AssemblyStepNavigationManager() { }
    public static boolean canRescan(boolean cameraCheckpoint,boolean verified){return cameraCheckpoint&&verified;}
    public static boolean canSkipPreview(boolean preview,boolean paused,boolean saving){return preview&&!paused&&!saving;}
}
