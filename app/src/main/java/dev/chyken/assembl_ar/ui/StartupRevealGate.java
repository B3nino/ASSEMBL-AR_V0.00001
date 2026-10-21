package dev.chyken.assembl_ar.ui;

/** Rendering readiness and the brief brand introduction must both finish before revealing home. */
public final class StartupRevealGate {
    private boolean intro,frame,revealed;
    public void introFinished(){intro=true;}
    public void frameReady(){frame=true;}
    public boolean takeReveal(){if(revealed||!intro||!frame)return false;revealed=true;return true;}
}

