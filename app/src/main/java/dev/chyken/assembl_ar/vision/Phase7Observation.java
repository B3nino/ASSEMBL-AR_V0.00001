package dev.chyken.assembl_ar.vision;

/** Evidence describes located components; hardware and firmness remain explicit user checks. */
public final class Phase7Observation {
    public final int count;
    public final boolean invalid;
    public final double score;
    public final int visibleTrackers,verifiedMounts;
    public final int imageConfirmedNuts;
    public final boolean trackerInstallation,boardContext;
    public Phase7Observation(int count,boolean invalid,double score){this(count,invalid,score,false,0,0,false);}
    private Phase7Observation(int count,boolean invalid,double score,boolean installation,int bodies,int mounts,boolean context){
        this(count,invalid,score,installation,bodies,mounts,context,0);
    }
    private Phase7Observation(int count,boolean invalid,double score,boolean installation,int bodies,int mounts,boolean context,int nuts){
        this.count=Math.max(0,Math.min(2,count));this.invalid=invalid;this.score=score;trackerInstallation=installation;imageConfirmedNuts=Math.max(0,Math.min(2,nuts));
        visibleTrackers=Math.max(0,Math.min(2,bodies));verifiedMounts=Math.max(0,Math.min(2,mounts));boardContext=context;
    }
    public static Phase7Observation installedTrackers(int bodies,int mounts,boolean context,double score){
        return installedTrackers(bodies,mounts,context,score,0);
    }
    public static Phase7Observation installedTrackers(int bodies,int mounts,boolean context,double score,int nuts){
        return new Phase7Observation(bodies==2&&mounts==2&&context?1:0,false,score,true,bodies,mounts,context,nuts);
    }
    public boolean accepted(int step){return step>=1&&step<=4&&!invalid&&count>=(step==2?2:1);}
}

