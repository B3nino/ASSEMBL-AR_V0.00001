package dev.chyken.assembl_ar;

import android.app.Activity;import android.content.Intent;import android.os.*;import android.view.*;import android.widget.*;
import androidx.fragment.app.Fragment;import androidx.annotation.*;import java.util.*;
import dev.chyken.assembl_ar.vision.*;import dev.chyken.assembl_ar.camera.*;import dev.chyken.assembl_ar.diagnostics.*;

/** Academic runner and catalog browser shared with the standalone camera. */
public final class DevDiagnosticsFragment extends Fragment {
    private final Handler ui=new Handler(Looper.getMainLooper());private TextView report,inventory;private Button references;private List<ComponentReferenceSpec> all=Collections.emptyList();private List<DiagnosticTargetCatalog.Target> shown=Collections.emptyList();private DiagnosticTargetCatalog.Target selectedTarget;private String selectedKey;private boolean assemblies,inventoryRefreshed;
    @Override public View onCreateView(LayoutInflater inflater,ViewGroup parent,Bundle saved){
        Activity a=requireActivity();if(saved!=null){assemblies=saved.getBoolean("assemblies");selectedKey=saved.getString("target");}LinearLayout body=column(a);ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.addView(body);
        button(body,"Back",a::finish);
        text(body,"Diagnostic test suite",23);text(body,"All assembly and individual references · A: recognition · B: measured pose · C: motion stability",14);
        text(body,"Phases 4 and 6 are wiring/checklist phases without camera reference datasets. B and C remain unmeasured wherever a reference has no calibrated physical pose plane.",12);
        LinearLayout tabs=new LinearLayout(a);body.addView(tabs);button(tabs,"Individual Component Tests",()->{assemblies=false;populate();});button(tabs,"Assembly Tests",()->{assemblies=true;populate();});
        references=new com.google.android.material.button.MaterialButton(a,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);references.setAllCaps(false);references.setTextColor(a.getColor(R.color.ink));references.setText("Choose a library target");body.addView(references,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));references.setOnClickListener(v->dev.chyken.assembl_ar.ui.DiagnosticTargetPicker.show(a,shown,target->{selectedTarget=target;selectedKey=target.key;references.setText(target.toString());}));inventory=text(body,"Opening prepared keypoint inventory…",13);
        button(body,"Open selected camera test",()->{if(shown.isEmpty())return;DiagnosticTargetCatalog.Target target=selectedTarget;if(target==null)return;startActivity(new Intent(a,PartRecognitionTestActivity.class).putExtra(PartRecognitionTestActivity.EXTRA_COMPONENT_ID,target.componentId).putExtra("reference_id",target.representative.getReferenceId()).putExtra("assembly_test",target.specialized));});
        button(body,"Diagnostic control guide",()->dev.chyken.assembl_ar.ui.DiagnosticControlsHelp.show(a));
        button(body,"Benchmark & logging",()->showControls(a));button(body,"Download diagnostic logs",()->DiagnosticLogger.get(a).chooseExport(a,DiagnosticSession.INSTANCE.runner.report()));
        report=text(body,"",13);report.setTypeface(android.graphics.Typeface.MONOSPACE);
        VisionPreloadManager.get(a).start().whenComplete((list,error)->ui.post(()->{if(!isAdded()||inventory==null)return;if(error!=null)inventory.setText("Catalog unavailable: "+error.getMessage());else{all=list;populate();}}));
        return scroll;
    }
    private void populate(){
        if(references==null)return;List<DiagnosticTargetCatalog.Target> targets=new ArrayList<>();
        for(DiagnosticTargetCatalog.Target target:IndividualPartTestCatalog.selectableTargets(all,KeypointInventoryManager.INSTANCE::count))if(target.assembly==assemblies)targets.add(target);
        shown=targets;selectedTarget=null;
        for(DiagnosticTargetCatalog.Target target:targets)if(target.key.equals(selectedKey))selectedTarget=target;
        if(selectedTarget==null&&!targets.isEmpty())selectedTarget=targets.get(0);
        if(selectedTarget!=null){selectedKey=selectedTarget.key;references.setText(selectedTarget.toString());}
        references.setEnabled(!targets.isEmpty());inventory.setText(targets.size()+" library targets · "+KeypointInventoryManager.INSTANCE.preparedCount()+" prepared references");
    }
    @Override public void onSaveInstanceState(Bundle state){state.putBoolean("assemblies",assemblies);state.putString("target",selectedKey);super.onSaveInstanceState(state);}
    @Override public void onDestroyView(){ui.removeCallbacks(refresh);references=null;report=null;inventory=null;super.onDestroyView();}
    private final Runnable refresh=new Runnable(){public void run(){if(!isAdded())return;if(report!=null){String text=DiagnosticSession.INSTANCE.runner.report();if(!android.text.TextUtils.equals(report.getText(),text))report.setText(text);}if(!inventoryRefreshed&&GlobalComponentRegistry.INSTANCE.isReady()){inventoryRefreshed=true;populate();}if(inventory!=null)inventory.setText(shown.size()+" library targets · "+KeypointInventoryManager.INSTANCE.preparedCount()+" prepared references · registry "+(GlobalComponentRegistry.INSTANCE.isReady()?"ready":"preparing"));ui.postDelayed(this,1000);}};
    @Override public void onResume(){super.onResume();ui.post(refresh);}
    @Override public void onPause(){ui.removeCallbacks(refresh);super.onPause();}
    public static void showControls(Activity a){
        boolean resumePart=a instanceof PartRecognitionTestActivity&&((PartRecognitionTestActivity)a).pauseForDiagnostics();
        if(a instanceof AssemblyActivity)((AssemblyActivity)a).pauseForDiagnostics();
        DiagnosticSession session=DiagnosticSession.INSTANCE;session.attach(a);LinearLayout b=column(a);ScrollView scroll=new ScrollView(a);scroll.addView(b);
        androidx.appcompat.widget.SwitchCompat logging=new androidx.appcompat.widget.SwitchCompat(a);logging.setText("Developer Diagnostic Logging");logging.setChecked(PreferencesManager.logging(a));logging.setOnCheckedChangeListener((v,enabled)->PreferencesManager.logging(a,enabled));b.addView(logging);
        logging.setTextColor(a.getColor(R.color.ink));
        androidx.appcompat.widget.SwitchCompat trained=new androidx.appcompat.widget.SwitchCompat(a);
        trained.setText("Trained component recognition (ONNX first)");trained.setTextColor(a.getColor(R.color.ink));
        trained.setMinHeight(Math.round(52*a.getResources().getDisplayMetrics().density));trained.setChecked(PreferencesManager.trainedModels(a));
        trained.setOnCheckedChangeListener((v,enabled)->PreferencesManager.trainedModels(a,enabled));b.addView(trained);
        text(b,"Uses the trained component models in guided scans and the individual tester, including installed-part searches. Photo and geometry checks verify orientation and mounting. Progress is retained.",13);
        button(b,"Diagnostic control guide",()->dev.chyken.assembl_ar.ui.DiagnosticControlsHelp.show(a));
        text(b,"Logging saves future scan measurements. Export shares recorded data; it does not start collection. Opening these controls pauses camera analysis until you return.",13);
        text(b,"Lighting is estimated automatically from the camera. Run 30 fresh scans in the room you are testing; no lighting selection is needed. These image-based categories are estimates, not lux measurements. The ORB benchmark and assembly verification have separate outcomes.",14);
        text(b,"Movement sequence (perform this movement yourself)",13);
        Spinner motion=new Spinner(a);motion.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,AcademicBenchmarkRunner.MOTIONS));b.addView(motion);
        button(b,"Start 30-trial batch",()->{session.motion=(String)motion.getSelectedItem();logging.setChecked(true);session.runner.start(30,session.motion,android.os.SystemClock.elapsedRealtimeNanos());Toast.makeText(a,"Batch running · automatic lighting estimate",Toast.LENGTH_SHORT).show();});
        button(b,"Stop batch",session.runner::stop);button(b,"Clear benchmark results",()->new androidx.appcompat.app.AlertDialog.Builder(a)
            .setTitle("Clear benchmark results?").setMessage("Stops the batch and clears its in-memory trials and reports. Assembly progress, references and exported files are retained.")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear results",(d,w)->{session.runner.stop();session.runner.clear();}).show());
        button(b,"Configure checkerboard ground truth",()->calibration(a));
        button(b,"View live report",()->new androidx.appcompat.app.AlertDialog.Builder(a).setTitle("Academic measurements").setMessage(session.runner.report()).setPositiveButton("Close",null).show());
        button(b,"Download Diagnostic Logs",()->DiagnosticLogger.get(a).chooseExport(a,session.runner.report()));
        if(a instanceof AssemblyActivity)button(b,"Capture current scan frame",()->((AssemblyActivity)a).captureDiagnosticScanFrame());
        text(b,"Frame recording: use Save sample on the test camera. Each capture is retained as a replayable .arpart.zip in the session bundle. Camera trials never modify assembly progress.",13);
        androidx.appcompat.app.AlertDialog controls=new androidx.appcompat.app.AlertDialog.Builder(a).setTitle("Benchmark & logging").setView(scroll).setPositiveButton(a instanceof AssemblyActivity||a instanceof PartRecognitionTestActivity?"Return to camera":"Done",null).create();
        controls.setOnDismissListener(d->{if(a instanceof AssemblyActivity)((AssemblyActivity)a).resumeAfterDiagnostics();else if(resumePart&&!a.isFinishing())((PartRecognitionTestActivity)a).resumeAfterDiagnostics();});controls.show();
    }
    private static void calibration(Activity a){LinearLayout b=column(a);text(b,"Use a printed checkerboard coplanar with the target. Mark the first inner corner and measure target CENTER X/Y from it. Keep the same grid orientation. Values are millimeters. The grid must remain visible. These values must be measured independently of the AR overlay.",14);
        String[] labels={"Inner columns","Inner rows","Square size (mm)","Target center X (mm)","Target center Y (mm)"},defaults={"7","5","10","100","50"};EditText[] f=new EditText[5];
        for(int i=0;i<5;i++){text(b,labels[i],13);f[i]=new EditText(a);f[i].setInputType(8194|4096);f[i].setText(defaults[i]);b.addView(f[i]);}
        Spinner orientation=new Spinner(a);RecognitionResult.OrientationClassification[] orientations=RecognitionResult.OrientationClassification.values();orientation.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,orientations));b.addView(orientation);orientation.setSelection(1);
        ScrollView form=new ScrollView(a);form.addView(b);
        androidx.appcompat.app.AlertDialog d=new androidx.appcompat.app.AlertDialog.Builder(a).setTitle("Measured checkerboard reference").setView(form).setNegativeButton("Disable",(x,w)->DiagnosticSession.INSTANCE.groundTruth=null).setPositiveButton("Apply",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{try{DiagnosticSession.INSTANCE.groundTruth=new CheckerboardGroundTruth(Integer.parseInt(f[0].getText().toString()),Integer.parseInt(f[1].getText().toString()),Double.parseDouble(f[2].getText().toString()),Double.parseDouble(f[3].getText().toString()),Double.parseDouble(f[4].getText().toString()),(RecognitionResult.OrientationClassification)orientation.getSelectedItem());d.dismiss();}catch(RuntimeException bad){f[0].setError("Check the measurements");}}));d.show();
    }
    private static LinearLayout column(Activity a){LinearLayout b=new LinearLayout(a);b.setOrientation(LinearLayout.VERTICAL);b.setBackgroundColor(a.getColor(R.color.canvas));int p=Math.round(16*a.getResources().getDisplayMetrics().density);b.setPadding(p,p,p,p);return b;}
    private static TextView text(LinearLayout b,String s,int size){TextView t=new TextView(b.getContext());t.setText(s);t.setTextSize(size);t.setTextColor(b.getContext().getColor(size>=18?R.color.ink:R.color.ink_soft));t.setPadding(0,8,0,8);b.addView(t);return t;}
    private static void button(LinearLayout b,String s,Runnable r){
        com.google.android.material.button.MaterialButton v=new com.google.android.material.button.MaterialButton(b.getContext(),null,
                s.equals("Open selected camera test")||s.equals("Start 30-trial batch")?com.google.android.material.R.attr.materialButtonStyle:com.google.android.material.R.attr.materialButtonOutlinedStyle);
        v.setText(s);v.setAllCaps(false);v.setTextColor(b.getContext().getColor(s.equals("Open selected camera test")||s.equals("Start 30-trial batch")?R.color.white:R.color.ink));
        v.setMinHeight(Math.round(52*b.getResources().getDisplayMetrics().density));
        b.addView(v,b.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1):new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));v.setOnClickListener(x->r.run());
    }
}
