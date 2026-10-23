package dev.chyken.assembl_ar.ui;
import java.util.*;import org.junit.Test;import static org.junit.Assert.*;
public final class LatestUiTaskTest {
    @Test public void prolongedBlockedUiRetainsOnlyNewestUpdate(){
        Queue<Runnable> messages=new ArrayDeque<>();LatestUiTask tasks=new LatestUiTask(messages::add);int[] shown={-1};
        for(int i=0;i<100000;i++){final int frame=i;tasks.submit(()->shown[0]=frame);}
        assertEquals(1,messages.size());messages.remove().run();assertEquals(99999,shown[0]);assertTrue(messages.isEmpty());
        tasks.submit(()->shown[0]=100000);messages.remove().run();assertEquals(100000,shown[0]);
    }
    @Test public void navigationDropsPendingGeometryAndConcurrentDeliveryIsScheduled(){
        Queue<Runnable> messages=new ArrayDeque<>();LatestUiTask tasks=new LatestUiTask(messages::add);int[] shown={0};
        tasks.submit(()->shown[0]++);tasks.clear();messages.remove().run();assertEquals(0,shown[0]);
        tasks.submit(()->{shown[0]=1;tasks.submit(()->shown[0]=2);});messages.remove().run();assertEquals(1,shown[0]);assertEquals(1,messages.size());messages.remove().run();assertEquals(2,shown[0]);
    }
}
