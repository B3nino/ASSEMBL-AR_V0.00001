package dev.chyken.assembl_ar;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.ProjectProgress;
import dev.chyken.assembl_ar.ui.AssetImageLoader;
import dev.chyken.assembl_ar.ui.UiUtils;

public final class ProjectSelectionActivity extends AppCompatActivity {
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private LinearProgressIndicator progressIndicator;
    private TextView progressLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_project_selection);
        UiUtils.applyStandardInsets(this);
        UiUtils.setupToolbar(this, getString(R.string.choose_project));
        UiUtils.setupPrimaryNavigation(this, R.id.navAssemblyButton);
        progressIndicator = findViewById(R.id.projectProgress);
        progressLabel = findViewById(R.id.projectProgressLabel);
        ImageView projectImage = findViewById(R.id.projectImage);
        configureTabletCard();
        AssetImageLoader.load(projectImage, "manuals/assembly/page-30.jpg");
        findViewById(R.id.neobotProjectCard).setOnClickListener(view ->
                startActivity(new Intent(this, ProjectDetailActivity.class)));
    }

    private void configureTabletCard() {
        if (!UiUtils.isExpandedTablet(this)) {
            return;
        }
        LinearLayout card = findViewById(R.id.neobotProjectCard);
        FrameLayout media = findViewById(R.id.projectCardMedia);
        LinearLayout body = findViewById(R.id.projectCardBody);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams mediaParams = (LinearLayout.LayoutParams) media.getLayoutParams();
        mediaParams.width = getResources().getDimensionPixelSize(R.dimen.tablet_project_media_width);
        mediaParams.height = getResources().getDimensionPixelSize(R.dimen.tablet_project_media_height);
        mediaParams.gravity = Gravity.CENTER_VERTICAL;
        mediaParams.topMargin = 0;
        media.setLayoutParams(mediaParams);

        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        bodyParams.setMarginStart(UiUtils.dp(this, 26));
        body.setLayoutParams(bodyParams);
    }

    @Override
    protected void onResume() {
        super.onResume();
        databaseExecutor.execute(() -> {
            ProjectProgress progress = AssemblArRepository.getInstance(this)
                    .getProjectProgress(NeobotSeedData.PROJECT_ID);
            runOnUiThread(() -> renderProgress(progress));
        });
    }

    private void renderProgress(ProjectProgress progress) {
        progressIndicator.setProgressCompat(progress.getPercentComplete(), true);
        progressLabel.setText(progress.getCompletedSteps() == 0
                ? "Ready to begin"
                : progress.getCompletedSteps() + " of " + progress.getTotalSteps()
                + " manual sections complete");
    }

    @Override
    protected void onDestroy() {
        databaseExecutor.shutdownNow();
        super.onDestroy();
    }
}
