package dev.chyken.assembl_ar.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DiagnosticTelemetryLockerTest {
    private DiagnosticScanTelemetry frame(long n,boolean verified){
        DiagnosticScanTelemetry t=new DiagnosticScanTelemetry("Probe",null,-1,200,-1,-1,5,10,false,false,false,null,Double.NaN,null,null,Double.NaN,n);
        return verified?t.withVerification("Extension Board","Photo + geometry",.85):t;
    }
    @Test public void completedScanRetainsExactWinnerDespiteLaterFailuresAndSuccessfulTracking(){
        DiagnosticTelemetryLocker lock=new DiagnosticTelemetryLocker();lock.enter("P10/12",1,false);
        DiagnosticScanTelemetry winner=frame(2,true);lock.stream(1,winner);assertTrue(lock.freeze(1,winner));
        assertSame(winner,lock.stream(1,frame(300,false)));assertSame(winner,lock.stream(1,frame(400,true)));assertTrue(lock.frozen());
    }
    @Test public void holdFramesRemainLiveUntilTheControllerActuallyCompletesItsGate(){
        DiagnosticTelemetryLocker lock=new DiagnosticTelemetryLocker();lock.enter("P10/12",1,false);
        lock.stream(1,frame(1,true));assertFalse(lock.frozen());DiagnosticScanTelemetry next=frame(2,false);
        assertSame(next,lock.stream(1,next));assertFalse(lock.freeze(1,next));
    }
    @Test public void rescanAndNextClearTheLatchAndRejectQueuedOldFrames(){
        DiagnosticTelemetryLocker lock=new DiagnosticTelemetryLocker();lock.enter("P10/12",1,false);lock.freeze(1,frame(2,true));
        lock.enter("P10/12",2,false);assertFalse(lock.frozen());assertNull(lock.stream(1,frame(50,true)));assertFalse(lock.freeze(1,frame(50,true)));
        lock.freeze(2,frame(51,true));lock.enter("P10/13",3,true);assertFalse(lock.frozen());assertNull(lock.snapshot());
    }
    @Test public void pauseAndResumePreserveCompletedSnapshotAcrossNewCameraTokens(){
        DiagnosticTelemetryLocker lock=new DiagnosticTelemetryLocker();lock.enter("P10/12",1,false);DiagnosticScanTelemetry winner=frame(2,true);lock.freeze(1,winner);
        lock.enter("P10/12",3,true);assertTrue(lock.frozen());assertSame(winner,lock.snapshot());
    }
    @Test public void reversedFrameOrderCannotReplaceNewerStreamingSnapshot(){
        DiagnosticTelemetryLocker lock=new DiagnosticTelemetryLocker();lock.enter("scan",1,false);DiagnosticScanTelemetry newer=frame(20,true);
        lock.stream(1,newer);assertSame(newer,lock.stream(1,frame(10,false)));
    }
    @Test public void concurrentLateFramesCannotThawACompletedScan()throws Exception{
        DiagnosticTelemetryLocker lock=new DiagnosticTelemetryLocker();lock.enter("scan",1,false);DiagnosticScanTelemetry winner=frame(1,true);lock.freeze(1,winner);
        Thread worker=new Thread(()->{for(int i=2;i<2000;i++)lock.stream(1,frame(i,false));});worker.start();worker.join(5000);assertFalse(worker.isAlive());assertSame(winner,lock.snapshot());
    }
}
