package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.widget.ImageButton;
import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.diagnostics.PreferencesManager;
import java.util.function.Consumer;

/** One app-wide narration preference; every camera controls its existing voice owner. */
public final class NarrationMuteControl implements AutoCloseable {
    private final Activity owner;private final ImageButton button;private final Consumer<Boolean> changed;
    private final SharedPreferences prefs;private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private boolean closed;
    public NarrationMuteControl(Activity a,ImageButton view,Consumer<Boolean> changed){
        owner=a;button=view;this.changed=changed;prefs=PreferencesManager.narrationPreferences(a);
        listener=(p,key)->{if(PreferencesManager.KEY_TTS_MUTED.equals(key))a.runOnUiThread(this::refresh);};prefs.registerOnSharedPreferenceChangeListener(listener);
        button.setVisibility(android.view.View.VISIBLE);button.setOnClickListener(v->PreferencesManager.ttsMuted(a,!PreferencesManager.ttsMuted(a)));refresh();
    }
    public void refresh(){if(closed)return;boolean muted=PreferencesManager.ttsMuted(owner);
        button.setImageResource(muted?R.drawable.ic_volume_off:R.drawable.ic_volume_up);button.setImageTintList(ColorStateList.valueOf(muted?0xFF98A39D:0xFF00FF7F));
        button.setContentDescription(button.getContext().getString(muted?R.string.narration_unmute:R.string.narration_mute));button.setSelected(!muted);changed.accept(muted);
    }
    @Override public void close(){closed=true;prefs.unregisterOnSharedPreferenceChangeListener(listener);button.setOnClickListener(null);}
}
