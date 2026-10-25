package dev.chyken.assembl_ar;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Locale;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.LearningModule;
import dev.chyken.assembl_ar.model.LearningStep;
import dev.chyken.assembl_ar.ui.UiUtils;

public final class LearningActivity extends AppCompatActivity {
    public static final String EXTRA_MODULE_ID = "module_id";

    private final List<LearningModule> modules = NeobotSeedData.getLearningModules();
    private LinearLayout modulesContainer;
    private View curriculumSequenceCard;
    private View emptyState;
    private dev.chyken.assembl_ar.ui.DeferredCardBinder<LearningModule> cardBinder;
    private final java.util.List<ModuleCard> cards=new java.util.ArrayList<>();
    private LinearLayout currentTabletRow;
    private String activeQuery="";
    private static final class ModuleCard {final View view,row;final String search;ModuleCard(View view,View row,String search){this.view=view;this.row=row;this.search=search;}}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_learning);
        UiUtils.applyStandardInsets(this);
        UiUtils.setupToolbar(this, getString(R.string.learning_title));
        UiUtils.setupPrimaryNavigation(this, R.id.navLearningButton);

        modulesContainer = findViewById(R.id.modulesContainer);
        curriculumSequenceCard = findViewById(R.id.curriculumSequenceCard);
        emptyState = findViewById(R.id.learningEmptyState);
        EditText searchInput = findViewById(R.id.learningSearchInput);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                // No-op.
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                filterModules(text.toString());
            }

            @Override
            public void afterTextChanged(Editable editable) {
                // No-op.
            }
        });
        populateModules("");
    }

    private void populateModules(String query) {
        activeQuery=query;modulesContainer.removeAllViews();cards.clear();currentTabletRow=null;
        if(cardBinder!=null)cardBinder.close();
        cardBinder=new dev.chyken.assembl_ar.ui.DeferredCardBinder<>(modulesContainer,modules,this::bindModule,()->filterModules(activeQuery));
    }
    private void bindModule(LearningModule module){
        LayoutInflater inflater=LayoutInflater.from(this);
            View card = inflater.inflate(R.layout.item_learning_module, modulesContainer, false);
            TextView eyebrow = card.findViewById(R.id.moduleEyebrow);
            TextView title = card.findViewById(R.id.moduleTitle);
            TextView description = card.findViewById(R.id.moduleDescription);
            TextView meta = card.findViewById(R.id.moduleMeta);
            eyebrow.setText("Module " + module.getOrder());
            title.setText(module.getTitle());
            description.setText(module.getOverview());
            String pages = module.getManualReference().getPageStart()
                    == module.getManualReference().getPageEnd()
                    ? "Manual page " + module.getManualReference().getPageStart()
                    : "Manual pages " + module.getManualReference().getPageStart()
                    + "–" + module.getManualReference().getPageEnd();
            meta.setText(module.getSteps().size() + " sections  ·  " + pages);
            card.setOnClickListener(view -> {
                Intent intent = new Intent(this, LearningDetailActivity.class);
                intent.putExtra(EXTRA_MODULE_ID, module.getId());
                startActivity(intent);
            });
            if (UiUtils.isLargeTabletWindow(this)) {
                if (cards.size() % 2 == 0) {
                    currentTabletRow = new LinearLayout(this);
                    currentTabletRow.setOrientation(LinearLayout.HORIZONTAL);
                    currentTabletRow.setGravity(android.view.Gravity.TOP);
                    modulesContainer.addView(currentTabletRow, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f);
                int gutter = UiUtils.dp(this, 7);
                cardParams.setMargins(gutter, 0, gutter, UiUtils.dp(this, 14));
                currentTabletRow.addView(card, cardParams);
            } else {
                modulesContainer.addView(card);
            }

        cards.add(new ModuleCard(card,(View)card.getParent(),searchableModule(module)));
        filterModules(activeQuery);
    }
    private void filterModules(String query){
        activeQuery=query;String normalized=normalize(query).trim();String[] terms=normalized.isEmpty()?new String[0]:normalized.split("\\s+");
        curriculumSequenceCard.setVisibility(normalized.isEmpty()?View.VISIBLE:View.GONE);
        java.util.Map<View,Integer> rowCounts=new java.util.HashMap<>();int visible=0;
        for(ModuleCard card:cards){boolean matches=true;for(String term:terms)if(!card.search.contains(term)){matches=false;break;}card.view.setVisibility(matches?View.VISIBLE:View.GONE);if(matches)visible++;if(card.row!=modulesContainer)rowCounts.put(card.row,rowCounts.getOrDefault(card.row,0)+(matches?1:0));}
        for(java.util.Map.Entry<View,Integer> row:rowCounts.entrySet())row.getKey().setVisibility(row.getValue()>0?View.VISIBLE:View.GONE);
        boolean pending=cardBinder!=null&&!cardBinder.complete();modulesContainer.setVisibility(visible>0||pending?View.VISIBLE:View.GONE);emptyState.setVisibility(visible==0&&!pending?View.VISIBLE:View.GONE);
    }
    @Override protected void onDestroy(){if(cardBinder!=null)cardBinder.close();super.onDestroy();}

    private static String searchableModule(LearningModule module) {
        StringBuilder searchableText = new StringBuilder()
                .append(module.getTitle()).append(' ')
                .append(module.getOverview()).append(' ')
                .append(module.getId()).append(' ')
                .append(module.getPrerequisiteModuleId());
        for (LearningStep step : module.getSteps()) {
            searchableText.append(' ').append(step.getTitle())
                    .append(' ').append(step.getContent())
                    .append(' ').append(step.getCodeSnippet())
                    .append(' ').append(step.getWarning())
                    .append(' ').append(step.getDependency())
                    .append(' ').append(step.getContentType().name());
        }

        return normalize(searchableText.toString());
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
