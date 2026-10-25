package dev.chyken.assembl_ar.vision;

/** One Lowe/Hamming rule for production, diagnostics and recovery comparisons. */
public final class OrbMatchPolicy {
    public static final double LOWE_RATIO=.75;
    public static final int MAX_HAMMING=64;
    private OrbMatchPolicy() { }
    public static boolean accepts(double first,double second){
        return Double.isFinite(first)&&Double.isFinite(second)&&first>=0&&second>0
                &&first<=MAX_HAMMING&&first<LOWE_RATIO*second;
    }
}
