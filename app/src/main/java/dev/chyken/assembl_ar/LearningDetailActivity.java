package dev.chyken.assembl_ar;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.LearningModule;
import dev.chyken.assembl_ar.model.LearningStep;
import dev.chyken.assembl_ar.model.ManualReference;
import dev.chyken.assembl_ar.ui.AssetImageLoader;
import dev.chyken.assembl_ar.ui.ManualReferenceDialog;
import dev.chyken.assembl_ar.ui.UiUtils;

public final class LearningDetailActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_learning_detail);
        UiUtils.applyStandardInsets(this);

        String moduleId = getIntent().getStringExtra(LearningActivity.EXTRA_MODULE_ID);
        LearningModule module = findModule(moduleId);
        if (module == null) {
            finish();
            return;
        }
        UiUtils.setupToolbar(this, module.getTitle());
        bindModule(module);
    }

    private void bindModule(LearningModule module) {
        ImageView image = findViewById(R.id.moduleImage);
        TextView type = findViewById(R.id.moduleType);
        TextView title = findViewById(R.id.moduleTitle);
        TextView description = findViewById(R.id.moduleDescription);
        List<String> assets = module.getManualReference().getAssetPaths();
        if (!assets.isEmpty()) {
            image.setVisibility(View.VISIBLE);
            AssetImageLoader.load(image, assets.get(0));
            image.setOnClickListener(view ->
                    ManualReferenceDialog.show(this, module.getManualReference()));
        } else {
            image.setVisibility(View.GONE);
        }
        type.setText("2026 Personal Robot Kit manual");
        title.setText(module.getTitle());
        String overview = module.getOverview();
        if (!module.getPrerequisiteModuleId().isEmpty()) {
            overview += "\n\nPrerequisite: " + module.getPrerequisiteModuleId().replace('_', ' ');
        }
        description.setText(overview);

        LinearLayout container = findViewById(R.id.learningStepsContainer);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (LearningStep step : module.getSteps()) {
            View item = inflater.inflate(R.layout.item_learning_step, container, false);
            TextView number = item.findViewById(R.id.learningStepNumber);
            TextView stepTitle = item.findViewById(R.id.learningStepTitle);
            TextView body = item.findViewById(R.id.learningStepBody);
            TextView code = item.findViewById(R.id.learningCodeBlock);
            TextView warning = item.findViewById(R.id.learningWarning);
            TextView pageLink = item.findViewById(R.id.learningPageLink);

            number.setText(step.getContentType().name().replace('_', ' ')
                    + " · " + step.getOrder());
            stepTitle.setText(step.getTitle());
            String content = step.getContent();
            if (!step.getDependency().isEmpty()) {
                content += "\n\nDepends on: " + step.getDependency();
            }
            body.setText(content);
            if (!step.getCodeSnippet().isEmpty()) {
                code.setText(step.getCodeSnippet());
                code.setVisibility(View.VISIBLE);
            }
            if (!step.getWarning().isEmpty()) {
                warning.setText("Important · " + step.getWarning());
                warning.setVisibility(View.VISIBLE);
            }
            ManualReference reference = step.getManualReference();
            pageLink.setText(reference.getPageStart() == reference.getPageEnd()
                    ? "View manual page " + reference.getPageStart()
                    : "View manual pages " + reference.getPageStart() + "–" + reference.getPageEnd());
            pageLink.setOnClickListener(view -> ManualReferenceDialog.show(this, reference));
            container.addView(item);
        }
    }

    private static LearningModule findModule(String id) {
        if (id == null) {
            return null;
        }
        for (LearningModule module : NeobotSeedData.getLearningModules()) {
            if (id.equals(module.getId())) {
                return module;
            }
        }
        return null;
    }
}
