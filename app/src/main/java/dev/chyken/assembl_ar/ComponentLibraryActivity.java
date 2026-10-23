package dev.chyken.assembl_ar;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.render.ComponentModelCatalog;
import dev.chyken.assembl_ar.ui.AssetImageLoader;
import dev.chyken.assembl_ar.ui.ComponentModelDialog;
import dev.chyken.assembl_ar.ui.ManualReferenceDialog;
import dev.chyken.assembl_ar.ui.UiUtils;

public final class ComponentLibraryActivity extends AppCompatActivity {
    private final List<ComponentCard> componentCards = new ArrayList<>();
    private View emptyState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_component_library);
        UiUtils.applyStandardInsets(this);
        UiUtils.setupToolbar(this, getString(R.string.component_library));
        UiUtils.setupPrimaryNavigation(this, R.id.navComponentsButton);
        findViewById(R.id.testIndividualPartsButton).setOnClickListener(view ->
                startActivity(new Intent(this, DevDiagnosticsActivity.class)));
        populateComponents(NeobotSeedData.getComponents());
        setupSearch();
    }

    private void populateComponents(List<Component> components) {
        LinearLayout container = findViewById(R.id.componentsContainer);
        emptyState = findViewById(R.id.componentsEmptyState);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Component component : components) {
            View card = inflater.inflate(R.layout.item_component, container, false);
            ImageView image = card.findViewById(R.id.componentImage);
            TextView title = card.findViewById(R.id.componentTitle);
            TextView purpose = card.findViewById(R.id.componentPurpose);
            TextView description = card.findViewById(R.id.componentDescription);
            TextView usage = card.findViewById(R.id.componentUsage);
            TextView pins = card.findViewById(R.id.componentPins);

            title.setText(component.getName());
            purpose.setText(component.getCategory().name().replace('_', ' ')
                    + (component.getKitQuantity() > 0 ? " · Qty " + component.getKitQuantity() : " · Optional"));
            description.setText(component.getDescription() + "\n\nPurpose: " + component.getPurpose());
            usage.setText("Assembly usage · " + component.getAssemblyUsage());
            String pinText = pinMappings(component.getId());
            if (!pinText.isEmpty()) {
                pins.setText(pinText);
                pins.setVisibility(View.VISIBLE);
            }
            if (!component.getReferenceImagePaths().isEmpty()) {
                AssetImageLoader.load(image, component.getReferenceImagePaths().get(0));
            }
            configureTabletCard(card, image);
            if (ComponentModelCatalog.hasModel(component.getId())) {
                View modelButton = card.findViewById(R.id.componentModelButton);
                modelButton.setVisibility(View.VISIBLE);
                modelButton.setContentDescription(getString(R.string.view_named_component_model,
                        component.getName()));
                modelButton.setOnClickListener(view -> ComponentModelDialog.show(this,
                        component.getId(), component.getName()));
            }
            card.setOnClickListener(view ->
                    ManualReferenceDialog.show(this, component.getManualReference()));
            container.addView(card);
            componentCards.add(new ComponentCard(card, searchableText(component)));
        }
        emptyState.setVisibility(componentCards.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void configureTabletCard(View card, ImageView image) {
        if (!UiUtils.isExpandedTablet(this)) {
            return;
        }
        LinearLayout root = (LinearLayout) card;
        LinearLayout body = card.findViewById(R.id.componentCardBody);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(android.view.Gravity.TOP);

        LinearLayout.LayoutParams imageParams = (LinearLayout.LayoutParams) image.getLayoutParams();
        imageParams.width = getResources().getDimensionPixelSize(
                R.dimen.tablet_component_media_width);
        imageParams.height = getResources().getDimensionPixelSize(
                R.dimen.tablet_component_media_height);
        imageParams.gravity = android.view.Gravity.TOP;
        image.setLayoutParams(imageParams);

        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        bodyParams.setMarginStart(UiUtils.dp(this, 22));
        body.setLayoutParams(bodyParams);
    }

    private void setupSearch() {
        EditText searchInput = findViewById(R.id.componentSearchInput);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                // No-op.
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                filterComponents(text);
            }

            @Override
            public void afterTextChanged(Editable text) {
                // No-op.
            }
        });
    }

    private void filterComponents(CharSequence query) {
        String normalizedQuery = normalize(query == null ? "" : query.toString()).trim();
        String[] terms = normalizedQuery.isEmpty() ? new String[0] : normalizedQuery.split("\\s+");
        int visibleCount = 0;

        for (ComponentCard componentCard : componentCards) {
            boolean matches = true;
            for (String term : terms) {
                if (!componentCard.searchableText.contains(term)) {
                    matches = false;
                    break;
                }
            }
            componentCard.view.setVisibility(matches ? View.VISIBLE : View.GONE);
            if (matches) {
                visibleCount++;
            }
        }
        emptyState.setVisibility(visibleCount == 0 ? View.VISIBLE : View.GONE);
    }

    private static String searchableText(Component component) {
        return normalize(component.getName() + ' '
                + component.getCategory().name().replace('_', ' ') + ' '
                + component.getDescription() + ' '
                + component.getPurpose() + ' '
                + component.getAssemblyUsage());
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String pinMappings(String componentId) {
        Set<String> mappings = new LinkedHashSet<>();
        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            for (PinConnection connection : step.getPinConnections()) {
                if (componentId.equals(connection.getSourceComponentId())
                        || componentId.equals(connection.getTargetComponentId())) {
                    mappings.add(connection.getSourcePin() + " → " + connection.getTargetPin()
                            + (connection.getSignal().isEmpty() ? "" : "  [" + connection.getSignal() + "]"));
                }
            }
        }
        return android.text.TextUtils.join("\n", mappings);
    }

    private static final class ComponentCard {
        private final View view;
        private final String searchableText;

        private ComponentCard(View view, String searchableText) {
            this.view = view;
            this.searchableText = searchableText;
        }
    }
}
