package dev.chyken.assembl_ar.ui;

import android.view.View;
import java.util.*;
import java.util.function.Consumer;

/** Bind one card per animation frame. Android views stay on the UI thread. */
public final class DeferredCardBinder<T> implements AutoCloseable {
    private final View host;
    private final List<T> items;
    private final Consumer<T> bind;
    private final Runnable finished;
    private int cursor;
    private boolean closed;
    private final Runnable tick=this::frame;
    public DeferredCardBinder(View host,List<T> items,Consumer<T> bind,Runnable finished){this.host=host;this.items=new ArrayList<>(items);this.bind=bind;this.finished=finished;host.postOnAnimation(tick);}
    private void frame(){if(closed)return;if(cursor<items.size())bind.accept(items.get(cursor++));if(cursor<items.size())host.postOnAnimation(tick);else finished.run();}
    public boolean complete(){return cursor==items.size();}
    @Override public void close(){closed=true;host.removeCallbacks(tick);items.clear();}
}
