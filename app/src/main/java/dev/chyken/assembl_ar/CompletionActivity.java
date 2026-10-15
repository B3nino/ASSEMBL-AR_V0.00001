package dev.chyken.assembl_ar;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

import dev.chyken.assembl_ar.ui.UiUtils;

public final class CompletionActivity extends AppCompatActivity {
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

        findViewById(R.id.reviewProjectButton).setOnClickListener(view -> {
            Intent intent = new Intent(this, ProjectDetailActivity.class);
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

    private static String formatDuration(long elapsedMillis) {
        long totalMinutes = elapsedMillis / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours > 0
                ? String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
                : String.format(Locale.getDefault(), "%d min", Math.max(1, minutes));
    }
}
