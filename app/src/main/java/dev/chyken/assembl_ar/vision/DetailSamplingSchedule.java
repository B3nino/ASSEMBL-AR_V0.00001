package dev.chyken.assembl_ar.vision;

/** Reuse a proven sampling scale for the next fresh stability frame. It never
 * retains recognition evidence; two misses release the scale for reacquisition. */
final class DetailSamplingSchedule {
    private int attempt,last,locked=-1,misses;private boolean recovery;
    int next(){attempt++;last=locked>=0?locked:(attempt&1)==1?0:1+((attempt/2-1)%4);return last;}
    void observe(boolean complete){if(complete){locked=last;misses=0;}else if(locked>=0&&++misses>=2){locked=-1;misses=0;}}
    void observe(boolean complete,boolean usedRecovery){if(complete)recovery=usedRecovery;observe(complete);}
    boolean recoveryFor(boolean requested){return locked>=0?recovery:requested;}
    void reset(){attempt=0;last=0;locked=-1;misses=0;}
}
