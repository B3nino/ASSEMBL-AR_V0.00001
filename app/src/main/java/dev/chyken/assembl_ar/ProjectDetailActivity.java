package dev.chyken.assembl_ar;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyProject;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.ProjectProgress;
import dev.chyken.assembl_ar.model.StepProgress;
import dev.chyken.assembl_ar.model.StepStatus;
import dev.chyken.assembl_ar.ui.AssetImageLoader;
import dev.chyken.assembl_ar.ui.UiUtils;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;
import dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore;

public final class ProjectDetailActivity extends AppCompatActivity {
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private AssemblArRepository repository;
    private MaterialButton startButton;
    private TextView restartButton;
    private LinearProgressIndicator progressIndicator;
    private TextView progressLabel;
    private LinearLayout stepsContainer;
    private ProjectProgress currentProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_project_detail);
        UiUtils.applyStandardInsets(this);
        UiUtils.setupToolbar(this, getString(R.string.project_details));
        repository = AssemblArRepository.getInstance(this);
        startButton = findViewById(R.id.startProjectButton);
        restartButton = findViewById(R.id.restartProjectButton);
        progressIndicator = findViewById(R.id.detailProgress);
        progressLabel = findViewById(R.id.detailProgressLabel);
        stepsContainer = findViewById(R.id.stepsContainer);
        AssetImageLoader.load(findViewById(R.id.manualCoverImage),
                "manuals/assembly/page-30.jpg");
        startButton.setOnClickListener(view -> launchCurrentStep());
        restartButton.setOnClickListener(view -> confirmRestart());
        findViewById(R.id.videoAssemblyButton).setOnClickListener(view ->
                openVideo("https://www.youtube.com/watch?v=qgz6hEFhUT8"));
        findViewById(R.id.videoWiringButton).setOnClickListener(view ->
                openVideo("https://www.youtube.com/watch?v=T68on9DXoBs"));
        findViewById(R.id.videoCombinedButton).setOnClickListener(view ->
                openVideo("https://www.youtube.com/watch?v=HsJZsCteQq8"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        databaseExecutor.execute(() -> {
            AssemblyProject project = repository.getProject(NeobotSeedData.PROJECT_ID);
            ProjectProgress progress = repository.getProjectProgress(NeobotSeedData.PROJECT_ID);
            runOnUiThread(() -> {
                if (project != null) {
                    render(project, progress);
                }
            });
        });
    }

    private void render(AssemblyProject project, ProjectProgress progress) {
        currentProgress = progress;
        progressIndicator.setProgressCompat(progress.getPercentComplete(), true);
        progressLabel.setText(progress.getCompletedSteps() + " of " + progress.getTotalSteps()
                + " manual sections verified · " + progress.getPercentComplete() + "%");
        if (progress.isComplete()) {
            startButton.setText(R.string.review_project);
        } else if (progress.isStarted()) {
            startButton.setText(R.string.resume_assembly);
        } else {
            startButton.setText(R.string.begin_assembly);
        }
        restartButton.setVisibility(progress.isStarted() ? View.VISIBLE : View.GONE);
        renderSteps(project.getSteps(), progress.getSteps());
    }

    private void renderSteps(List<AssemblyStep> steps, List<StepProgress> statuses) {
        Map<String, StepStatus> statusByStep = new HashMap<>();
        for (StepProgress status : statuses) {
            statusByStep.put(status.getStepId(), status.getStatus());
        }
        stepsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (dev.chyken.assembl_ar.workflow.phase.AssemblyPhase phase : dev.chyken.assembl_ar.workflow.phase.AssemblyPhaseCatalog.phases()) {
            View item = inflater.inflate(R.layout.item_assembly_step, stepsContainer, false);
            TextView number = item.findViewById(R.id.itemNumber);
            TextView title = item.findViewById(R.id.itemTitle);
            TextView subtitle = item.findViewById(R.id.itemSubtitle);
            ImageView status = item.findViewById(R.id.itemStatusIcon);
            number.setText(String.valueOf(phase.getNumber()));
            number.setContentDescription(getString(R.string.assembly_stage_number, phase.getNumber()));
            title.setText("Phase "+phase.getNumber()+" · "+phase.getTitle());
            if(dev.chyken.assembl_ar.workflow.phase.PhaseGuideMetadata.overhauled(phase)){
                int count=dev.chyken.assembl_ar.workflow.phase.PhaseGuideMetadata.subSteps(phase).size();
                subtitle.setText(count+(count==1?" step · ":" steps · ")+dev.chyken.assembl_ar.workflow.phase.PhaseGuideMetadata.summary(phase));
            }else{
                // Retain later-phase instructions until their new grouping is supplied.
                subtitle.setText(phase.getSummary());
            }
            boolean anyStarted=false,sectionsDone=true;
            for(int section:phase.getSections()){
                for(AssemblyStep step:steps)if(step.getStepNumber()==section){
                    StepStatus saved=statusByStep.get(step.getId());
                    sectionsDone&=saved==StepStatus.COMPLETED;anyStarted|=saved!=null&&saved!=StepStatus.NOT_STARTED;
                }
            }
            boolean complete=sectionsDone||phase.getSections().size()==1&&PhaseProgressStore.records(this,NeobotSeedData.PROJECT_ID).isPhaseComplete(phase.getId());
            status.setVisibility(complete?View.VISIBLE:View.GONE);
            item.setAlpha(complete||anyStarted?1f:.72f);stepsContainer.addView(item);
        }
    }

    private void launchCurrentStep() {
        if (currentProgress == null) {
            return;
        }
        if (currentProgress.isComplete()) {
            startActivity(new Intent(this, CompletionActivity.class));
            return;
        }
        startButton.setEnabled(false);
        databaseExecutor.execute(() -> {
            AssemblyStep step = repository.getCurrentStep(NeobotSeedData.PROJECT_ID);
            if (step != null) {
                repository.startStep(step.getId());
            }
            runOnUiThread(() -> {
                startButton.setEnabled(true);
                Intent intent = new Intent(this, AssemblyActivity.class);
                intent.putExtra(AssemblyActivity.EXTRA_PROJECT_ID, NeobotSeedData.PROJECT_ID);
                startActivity(intent);
            });
        });
    }

    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_assembly)
                .setMessage("This clears every verified manual section and returns to guided step 1.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.restart_assembly, (dialog, which) -> {
                    databaseExecutor.execute(() -> {
                        repository.resetProjectProgress(NeobotSeedData.PROJECT_ID);
                        GuidedStepProgressStore.clearProject(
                                this, NeobotSeedData.PROJECT_ID);
                        PhaseProgressStore.clearProject(this, NeobotSeedData.PROJECT_ID);
                        runOnUiThread(this::refresh);
                    });
                })
                .show();
    }

    private void openVideo(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.no_video_app, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        databaseExecutor.shutdownNow();
        super.onDestroy();
    }
}
