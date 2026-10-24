package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.diagnostics.LightingEstimator;

/** Shared assembly/individual-camera input selection. It changes preprocessing,
 * never recognition thresholds, lighting trial labels, or workflow evidence. */
public final class AdaptiveIlluminationPolicy {
    private boolean initialized,preferred,alternate,used;
    private double exposure;
    private int changeSamples;
    public void reset(){initialized=false;alternate=false;changeSamples=0;}
    public boolean choose(byte[] gray,int width,int height){
        String category=LightingEstimator.estimate(gray,width,height);double mean=0;
        if(gray!=null&&gray.length>=width*height&&width*height>0){for(int i=0;i<width*height;i++)mean+=gray[i]&255;mean/=width*height;}
        boolean changed=initialized&&Math.abs(mean-exposure)>45;
        changeSamples=changed?changeSamples+1:0;
        if(!initialized||changeSamples>=2){initialized=true;exposure=mean;preferred=category.equals("Weak")||category.equals("Uneven");alternate=false;changeSamples=0;}
        else if(!changed)exposure=.9*exposure+.1*mean;
        used=preferred^alternate;return used;
    }
    public void observe(boolean spatiallyRecognized){if(spatiallyRecognized){preferred=used;alternate=false;}else alternate=!alternate;}
}
