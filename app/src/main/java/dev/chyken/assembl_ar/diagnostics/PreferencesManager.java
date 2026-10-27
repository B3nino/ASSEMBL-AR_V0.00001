package dev.chyken.assembl_ar.diagnostics;
import android.content.Context;
public final class PreferencesManager {
    private PreferencesManager(){}
    public static final String KEY_ADMIN_VISIBLE="administrator_visible";
    public static final String KEY_ASSEMBLY_DEV_VISIBLE="assembly_dev_visible";
    public static final String KEY_PLAN_F_ENABLED="plan_f_enabled";
    public static boolean planFEnabled(Context c){return visibilityPreferences(c).getBoolean(KEY_PLAN_F_ENABLED,false);}
    public static void planFEnabled(Context c,boolean enabled){visibilityPreferences(c).edit().putBoolean(KEY_PLAN_F_ENABLED,enabled).apply();}
    public static android.content.SharedPreferences visibilityPreferences(Context c){return c.getSharedPreferences("interface_settings",0);}
    public static boolean administratorVisible(Context c){return visibilityPreferences(c).getBoolean(KEY_ADMIN_VISIBLE,false);}
    public static void administratorVisible(Context c,boolean visible){visibilityPreferences(c).edit().putBoolean(KEY_ADMIN_VISIBLE,visible).apply();}
    public static boolean assemblyDeveloperVisible(Context c){return visibilityPreferences(c).getBoolean(KEY_ASSEMBLY_DEV_VISIBLE,false);}
    public static void assemblyDeveloperVisible(Context c,boolean visible){visibilityPreferences(c).edit().putBoolean(KEY_ASSEMBLY_DEV_VISIBLE,visible).apply();}
    public static final String KEY_TTS_MUTED="tts_muted";
    public static final String KEY_TRAINED_MODELS="trained_component_assistance";
    public static boolean trainedModels(Context c){return c.getSharedPreferences("diagnostic_settings",0).getBoolean(KEY_TRAINED_MODELS,true);}
    public static void trainedModels(Context c,boolean enabled){c.getSharedPreferences("diagnostic_settings",0).edit().putBoolean(KEY_TRAINED_MODELS,enabled).apply();}
    public static android.content.SharedPreferences narrationPreferences(Context c){return c.getSharedPreferences("diagnostic_settings",0);}
    public static boolean ttsMuted(Context c){
        android.content.SharedPreferences p=narrationPreferences(c);
        if(!p.contains(KEY_TTS_MUTED))p.edit().putBoolean(KEY_TTS_MUTED,c.getSharedPreferences("AssemblyActivity",0).getBoolean("voice_guide_muted",false)).apply();
        return p.getBoolean(KEY_TTS_MUTED,false);
    }
    public static void ttsMuted(Context c,boolean muted){narrationPreferences(c).edit().putBoolean(KEY_TTS_MUTED,muted).apply();}
    public static boolean logging(Context c){return c.getSharedPreferences("diagnostic_settings",0).getBoolean("logging",false);}
    public static void logging(Context c,boolean enabled){c.getSharedPreferences("diagnostic_settings",0).edit().putBoolean("logging",enabled).apply();DiagnosticLogger.get(c).setEnabled(enabled);}
}
