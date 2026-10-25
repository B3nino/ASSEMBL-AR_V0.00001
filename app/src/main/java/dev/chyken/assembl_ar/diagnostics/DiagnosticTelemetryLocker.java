package dev.chyken.assembl_ar.diagnostics;

import java.util.Objects;

/** Screen-owned immutable HUD snapshot. Logging and optical tracking do not use this lock. */
public final class DiagnosticTelemetryLocker {
    private String scope;
    private Object token;
    private DiagnosticScanTelemetry shown;
    private boolean frozen;

    /** A lifecycle token change preserves a completed scan; a rescan does not. */
    public synchronized boolean enter(String nextScope,Object nextToken,boolean scanComplete){
        boolean reset=!Objects.equals(scope,nextScope)||!scanComplete&&!Objects.equals(token,nextToken);
        if(reset){
            shown=null;frozen=false;
        }
        scope=nextScope;token=nextToken;
        return reset;
    }
    public synchronized DiagnosticScanTelemetry stream(Object frameToken,DiagnosticScanTelemetry value){
        if(frozen||value==null||!Objects.equals(token,frameToken))return shown;
        if(shown==null||value.totalAnalyzedFrames>=shown.totalAnalyzedFrames)shown=value;
        return shown;
    }
    /** Only the controller's completed scan may freeze its paired, verified result. */
    public synchronized boolean freeze(Object frameToken,DiagnosticScanTelemetry winning){
        if(frozen)return true;
        if(!Objects.equals(token,frameToken)||winning==null||!winning.workflowVerified)return false;
        shown=winning;frozen=true;return true;
    }
    public synchronized DiagnosticScanTelemetry snapshot(){return shown;}
    public synchronized boolean frozen(){return frozen;}
}
