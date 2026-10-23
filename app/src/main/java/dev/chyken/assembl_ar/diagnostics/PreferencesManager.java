package dev.chyken.assembl_ar.diagnostics;
import android.content.Context;
public final class PreferencesManager {
    private PreferencesManager(){}
    public static boolean logging(Context c){return c.getSharedPreferences("diagnostic_settings",0).getBoolean("logging",false);}
    public static void logging(Context c,boolean enabled){c.getSharedPreferences("diagnostic_settings",0).edit().putBoolean("logging",enabled).apply();DiagnosticLogger.get(c).setEnabled(enabled);}
}
