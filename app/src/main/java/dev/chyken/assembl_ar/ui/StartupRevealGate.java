package dev.chyken.assembl_ar.ui;

/** Rendering readiness and the brief brand introduction must both finish before revealing home. */
public final class StartupRevealGate {
    private boolean intro,frame,revealed,references;
    public StartupRevealGate(){this(false);}
    public StartupRevealGate(boolean requireReferences){references=!requireReferences;}
    public void introFinished(){intro=true;}
    public void frameReady(){frame=true;}
    public void referencesReady(){references=true;}
    public boolean takeReveal(){if(revealed||!intro||!frame||!references)return false;revealed=true;return true;}
}

