package dev.chyken.assembl_ar.workflow.phase;

/** Display/session position, separate from verified section completion in SQLite. */
public final class PhaseSessionCheckpoint {
    public final String phaseId,gearState;
    public final boolean developer,inventoryComplete,previewing;
    public final PhaseFlow.Mode mode;
    public final int chapter,inventoryIndex;

    public PhaseSessionCheckpoint(String phaseId,boolean developer,PhaseFlow.Mode mode,int chapter,
                                  int inventoryIndex,boolean inventoryComplete,boolean previewing,String gearState){
        if(AssemblyPhaseCatalog.byId(phaseId)==null||mode==null||chapter<0||chapter>1000||inventoryIndex<0||inventoryIndex>100)
            throw new IllegalArgumentException("Invalid assembly session");
        if(gearState!=null&&gearState.indexOf(';')>=0)throw new IllegalArgumentException("Invalid gear state");
        if((AssemblyPhaseCatalog.RIBBON_CABLE.equals(phaseId)||AssemblyPhaseCatalog.UPPER_BOARD.equals(phaseId))&&(gearState==null||gearState.isEmpty()))gearState="legacy:"+(AssemblyPhaseCatalog.UPPER_BOARD.equals(phaseId)?16:15);
        if(AssemblyPhaseCatalog.SENSOR_WIRING.equals(phaseId)&&(gearState==null||gearState.isEmpty()))gearState="legacyMount";
        if(AssemblyPhaseCatalog.WHEEL_PAIR.equals(phaseId)&&(gearState==null||gearState.isEmpty()))gearState=inventoryComplete?"legacyWheels":"legacyStandoff";
        if(AssemblyPhaseCatalog.BATTERY_FIT.equals(phaseId)&&(gearState==null||gearState.isEmpty()))gearState="legacyBattery";
        this.phaseId=AssemblyPhaseCatalog.byId(phaseId).getId();this.developer=developer;this.mode=mode;this.chapter=chapter;
        this.inventoryIndex=inventoryIndex;this.inventoryComplete=inventoryComplete;this.previewing=previewing;
        this.gearState=gearState==null?"":gearState;
    }
    public String encode(){
        return "1;"+phaseId+";"+developer+";"+mode+";"+chapter+";"+inventoryIndex+";"+inventoryComplete+";"+previewing+";"+gearState;
    }
    public static PhaseSessionCheckpoint decode(String saved){
        if(saved==null)return null;
        try {
            String[] p=saved.split(";",9);
            if(p.length!=9||!p[0].equals("1")||!booleanValue(p[2])||!booleanValue(p[6])||!booleanValue(p[7]))return null;
            return new PhaseSessionCheckpoint(p[1],Boolean.parseBoolean(p[2]),PhaseFlow.Mode.valueOf(p[3]),
                    Integer.parseInt(p[4]),Integer.parseInt(p[5]),Boolean.parseBoolean(p[6]),Boolean.parseBoolean(p[7]),p[8]);
        }catch(IllegalArgumentException invalid){return null;}
    }
    private static boolean booleanValue(String value){return value.equals("true")||value.equals("false");}
    /** A normal session cannot skip the first unfinished phase. DEV sessions restore their own position. */
    public boolean canRestore(AssemblyPhase firstUnfinished){
        return firstUnfinished!=null&&(developer||phaseId.equals(firstUnfinished.getId()));
    }
}
