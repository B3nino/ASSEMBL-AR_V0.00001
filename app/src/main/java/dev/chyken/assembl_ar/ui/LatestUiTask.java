package dev.chyken.assembl_ar.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A stalled UI retains one latest visual update, not a queue of old camera frames. */
public final class LatestUiTask {
    private final Executor ui;
    private final AtomicReference<Runnable> latest=new AtomicReference<>();
    private final AtomicBoolean posted=new AtomicBoolean();
    public LatestUiTask(Executor ui){this.ui=ui;}
    public void submit(Runnable update){latest.set(update);schedule();}
    public void clear(){latest.set(null);}
    private void schedule(){if(posted.compareAndSet(false,true))ui.execute(this::drain);}
    private void drain(){
        try{Runnable update=latest.getAndSet(null);if(update!=null)update.run();}
        finally{posted.set(false);if(latest.get()!=null)schedule();}
    }
}
