package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.diagnostics.LightingEstimator;

/** Shared assembly/individual-camera input selection. It changes preprocessing,
 * never recognition thresholds, lighting trial labels, or workflow evidence. */
public final class AdaptiveIlluminationPolicy {
    private boolean initialized,preferred,alternate,used,structuralPreferred;
    private double exposure;
    private int changeSamples;
    public void reset(){initialized=false;alternate=false;changeSamples=0;structuralPreferred=false;}
    /** Structural evidence is eligible on the first frame, never behind a miss counter. */
    public boolean edgeRecovery(){return true;}
    public boolean structuralFirst(){return structuralPreferred||preferred;}
    public boolean choose(byte[] gray,int width,int height){
        String category=LightingEstimator.estimate(gray,width,height);double mean=0;
        if(gray!=null&&gray.length>=width*height&&width*height>0){for(int i=0;i<width*height;i++)mean+=gray[i]&255;mean/=width*height;}
        boolean changed=initialized&&Math.abs(mean-exposure)>45;
        changeSamples=changed?changeSamples+1:0;
        if(!initialized||changeSamples>=2){initialized=true;exposure=mean;preferred=category.equals("Weak")||category.equals("Uneven");alternate=false;changeSamples=0;}
        else if(!changed)exposure=.9*exposure+.1*mean;
        used=preferred^alternate;return used;
    }
    public void observe(boolean spatiallyRecognized){observe(spatiallyRecognized,false);}
    /** Keep the winning input for consecutive-frame verification; a miss reopens recovery. */
    public void observe(boolean spatiallyRecognized,boolean structuralUsed){if(spatiallyRecognized){preferred=used;alternate=false;structuralPreferred=structuralUsed;}else{alternate=!alternate;structuralPreferred=false;}}
}
