package dev.chyken.assembl_ar;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

import dev.chyken.assembl_ar.ui.UiUtils;

public final class CompletionActivity extends AppCompatActivity {
    private dev.chyken.assembl_ar.ui.AssemblyVoiceGuide voice;
    public static final String EXTRA_VERIFIED="verified";
    public static final String EXTRA_ELAPSED_MILLIS = "elapsed_millis";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_completion);
        UiUtils.applyStandardInsets(this);
        long elapsed = Math.max(0L, getIntent().getLongExtra(EXTRA_ELAPSED_MILLIS, 0L));
        TextView time = findViewById(R.id.completionTime);
        time.setText(elapsed > 0 ? formatDuration(elapsed) : "100% complete");

        boolean verified=getIntent().getBooleanExtra(EXTRA_VERIFIED,true);
        if(!verified){((TextView)findViewById(R.id.completionTitle)).setText("Completion preview · DEV");((TextView)findViewById(R.id.completionVerification)).setText("Preview only · Learner completion was not recorded");time.setText("Developer preview");}
        voice=new dev.chyken.assembl_ar.ui.AssemblyVoiceGuide(this,status->{});
        voice.speak(verified?"Congratulations! You have successfully assembled your Neobot! Locate the power switch to turn on your robot, and choose where to go next.":"Developer preview of the completion hub. Learner completion was not recorded.","completion:welcome");
        if(dev.chyken.assembl_ar.ui.BrandingAssets.motion()){findViewById(R.id.completionTitle).setAlpha(0);findViewById(R.id.completionTitle).animate().alpha(1).setDuration(650).start();}
        findViewById(R.id.programmingPracticeButton).setOnClickListener(v->startActivity(new Intent(this,ProgrammingPracticeActivity.class)));
        findViewById(R.id.reviewProjectButton).setOnClickListener(view -> {
            Intent intent = new Intent(this, AssemblyRetrospectiveActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });
        findViewById(R.id.homeButton).setOnClickListener(view -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }

    @Override protected void onPause(){if(voice!=null)voice.stop();super.onPause();}
    @Override protected void onDestroy(){if(voice!=null)voice.close();super.onDestroy();}
    private static String formatDuration(long elapsedMillis) {
        long totalMinutes = elapsedMillis / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours > 0
                ? String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
                : String.format(Locale.getDefault(), "%d min", Math.max(1, minutes));
    }
}
