package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.*;
import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.AppSettingsDialog;
import dev.chyken.assembl_ar.diagnostics.PreferencesManager;

/** Shared standard-screen chrome. Camera views remain inside their original body hierarchy. */
public final class AppShellController implements DefaultLifecycleObserver {
    private final Activity activity;
    private final ElectronParticleBackgroundView animation;
    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final boolean cameraWorkspace;
    private boolean closed;

    private AppShellController(Activity activity,ElectronParticleBackgroundView animation){
        this.activity=activity;this.animation=animation;prefs=PreferencesManager.visibilityPreferences(activity);
        String name=activity.getClass().getSimpleName();cameraWorkspace=name.equals("PartRecognitionTestActivity")||name.equals("AdminRegistrationActivity");
        listener=(p,key)->activity.runOnUiThread(()->{if(!closed)refreshVisibility(activity);});
        prefs.registerOnSharedPreferenceChangeListener(listener);
        ((LifecycleOwner)activity).getLifecycle().addObserver(this);refreshVisibility(activity);
    }

    public static void install(Activity activity){
        ViewGroup host=activity.findViewById(android.R.id.content);
        if(host==null||host.getChildCount()==0||host.getChildAt(0).getId()==R.id.appShell)return;
        View body=host.getChildAt(0);Context c=body.getContext();
        View navigation=body.findViewById(R.id.primaryNavigation);
        if(navigation!=null)((ViewGroup)navigation.getParent()).removeView(navigation);
        else navigation=LayoutInflater.from(c).inflate(R.layout.view_primary_navigation,host,false);
        boolean hasBackground=body.findViewById(R.id.electronBackground)!=null;
        host.removeView(body);body.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout shell=new FrameLayout(c);shell.setId(R.id.appShell);shell.setBackgroundColor(c.getColor(R.color.canvas));
        ElectronParticleBackgroundView background=null;
        if(!hasBackground){background=new ElectronParticleBackgroundView(c);background.setId(R.id.electronBackground);shell.addView(background,new FrameLayout.LayoutParams(-1,-1));}
        LinearLayout column=new LinearLayout(c);column.setOrientation(LinearLayout.VERTICAL);shell.addView(column,new FrameLayout.LayoutParams(-1,-1));
        if(body.findViewById(R.id.toolbarTitle)==null&&body.findViewById(R.id.headerWordmark)==null){
            column.addView(LayoutInflater.from(c).inflate(R.layout.view_toolbar,column,false));
        }
        column.addView(body,new LinearLayout.LayoutParams(-1,0,1));column.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        host.addView(shell,new ViewGroup.LayoutParams(-1,-1));
        new AppShellController(activity,background);
        UiUtils.setupPrimaryNavigation(activity,selectedDestination(activity));
        if(activity.findViewById(R.id.toolbarTitle)!=null&&body.findViewById(R.id.toolbarTitle)==null)UiUtils.setupToolbar(activity,activity.getTitle().toString());
    }

    public static int selectedDestination(Activity activity){
        String name=activity.getClass().getSimpleName();
        if(name.startsWith("Admin"))return R.id.navAdminButton;
        if(name.startsWith("Learning")||name.startsWith("Programming")||name.equals("AssemblyRetrospectiveActivity"))return R.id.navLearningButton;
        if(name.equals("ComponentLibraryActivity")||name.equals("PartRecognitionTestActivity")||name.equals("DevDiagnosticsActivity"))return R.id.navComponentsButton;
        return R.id.navAssemblyButton;
    }

    /** Full-screen assembly keeps its camera layout, with the same Settings and visibility policy. */
    public static void bindAssemblyControls(Activity activity){
        View settings=activity.findViewById(R.id.btnAppSettings);
        if(settings!=null)settings.setOnClickListener(v->AppSettingsDialog.show(activity,v.getContext()));
        new AppShellController(activity,null);
    }

    public static void refreshVisibility(Activity activity){
        View content=activity.findViewById(android.R.id.content);
        View admin=content.findViewById(R.id.navAdminButton),dev=content.findViewById(R.id.devButton);
        if(admin!=null){boolean show=PreferencesManager.administratorVisible(activity);admin.setVisibility(show?View.VISIBLE:View.GONE);admin.setEnabled(show);}
        if(dev!=null){boolean show=PreferencesManager.assemblyDeveloperVisible(activity);dev.setVisibility(show?View.VISIBLE:View.GONE);dev.setEnabled(show);}
    }
    @Override public void onResume(LifecycleOwner owner){refreshVisibility(activity);if(animation!=null&&!cameraWorkspace)animation.resumeAnimation();}
    @Override public void onPause(LifecycleOwner owner){if(animation!=null)animation.pauseAnimation();}
    @Override public void onDestroy(LifecycleOwner owner){closed=true;prefs.unregisterOnSharedPreferenceChangeListener(listener);if(animation!=null)animation.close();owner.getLifecycle().removeObserver(this);}
}
