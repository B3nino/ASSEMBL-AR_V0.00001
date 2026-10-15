package dev.chyken.assembl_ar;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.util.TypedValue;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import dev.chyken.assembl_ar.render.HomeRobotSurfaceView;
import dev.chyken.assembl_ar.ui.UiUtils;

public final class MainActivity extends AppCompatActivity {
    private HomeRobotSurfaceView robotModelView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_main);
        UiUtils.applyStandardInsets(this);
        UiUtils.setupPrimaryNavigation(this, R.id.navAssemblyButton);
        robotModelView = findViewById(R.id.robotModelView);
        styleHeaderWordmark();
        configureTabletHero();

        findViewById(R.id.startAssemblyButton).setOnClickListener(view ->
                startActivity(new Intent(this, ProjectSelectionActivity.class)));
        findViewById(R.id.resetRobotViewButton).setOnClickListener(view ->
                robotModelView.resetView());
    }

    private void styleHeaderWordmark() {
        TextView wordmark = findViewById(R.id.headerWordmark);
        SpannableString label = new SpannableString(getString(R.string.app_name));
        int accentStart = Math.max(0, label.length() - 2);
        label.setSpan(
                new ForegroundColorSpan(ContextCompat.getColor(this, R.color.brand_green_dark)),
                accentStart,
                label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        wordmark.setText(label);
    }

    private void configureTabletHero() {
        if (!UiUtils.isTablet(this)
                || getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_PORTRAIT) {
            return;
        }
        LinearLayout hero = findViewById(R.id.tabletHeroContainer);
        LinearLayout copy = findViewById(R.id.tabletHeroCopy);
        FrameLayout model = findViewById(R.id.tabletRobotContainer);
        TextView title = findViewById(R.id.tabletHeroTitle);
        if (hero == null || copy == null || model == null || title == null) {
            return;
        }

        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        hero.setMinimumHeight(0);

        copy.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        copy.setPadding(0, 0, 0, 0);

        LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.tablet_home_model_height));
        modelParams.topMargin = UiUtils.dp(this, 30);
        model.setLayoutParams(modelParams);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (robotModelView != null) {
            robotModelView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (robotModelView != null) {
            robotModelView.onPause();
        }
        super.onPause();
    }
}
