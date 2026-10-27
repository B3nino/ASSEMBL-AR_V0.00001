package dev.chyken.assembl_ar;

import android.app.Activity;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.ui.*;
import dev.chyken.assembl_ar.diagnostics.PreferencesManager;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** UI-only fixtures; no assembly activity, progress reset, image bank or real camera setup. */
public final class UiBeautificationInstrumentedTest {
    private Context screen(Context source,int width,int height,float font){
        Configuration config=new Configuration(source.getResources().getConfiguration());config.screenWidthDp=width;config.screenHeightDp=height;config.smallestScreenWidthDp=Math.min(width,height);config.orientation=width>height?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
        return candidate(source.createConfigurationContext(config),font);
    }
    private static void settleAssembly(PhoneAssemblyLayout ui,View root,int width,int height,boolean first){for(int i=0;i<8;i++){layout(root,width,height);ui.layout(androidx.core.graphics.Insets.NONE,first,!first);}layout(root,width,height);}
    private static void settleTester(PhonePartTesterLayout ui,View root,int width,int height){for(int i=0;i<8;i++){layout(root,width,height);ui.refresh();}layout(root,width,height);}
    private static android.graphics.Rect localBounds(ViewGroup root,View child){android.graphics.Rect rect=new android.graphics.Rect(0,0,child.getWidth(),child.getHeight());root.offsetDescendantRectToMyCoords(child,rect);return rect;}

    @Test public void phoneAssemblyReservesCameraAndDoesNotAlterWorkflowButtons(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context source=InstrumentationRegistry.getInstrumentation().getTargetContext();
            for(int[] size:new int[][]{{360,780},{780,360},{320,568}})for(float font:new float[]{1,2}){
                Context c=screen(source,size[0],size[1],font);FrameLayout root=(FrameLayout)LayoutInflater.from(c).inflate(R.layout.activity_assembly,null);
                int width=UiUtils.dp(c,size[0]),height=UiUtils.dp(c,size[1]);
                CompactGuideCard card=root.findViewById(R.id.phaseTwoGuide);PhaseOneGuideView first=root.findViewById(R.id.phaseOneGuide);
                root.findViewById(R.id.instructionPanel).setVisibility(View.GONE);first.setVisibility(View.GONE);card.setVisibility(View.VISIBLE);
                card.render(34,"Phase 3 · Motor driver placement","Show the seated motor driver and nearby board markings.","Orientation and physical checks remain required.","",false,true,false);card.action.setVisibility(View.GONE);
                ComponentPreviewWindow preview=root.findViewById(R.id.componentPreviewWindow);preview.showContent(new TextView(c),"Component preview");
                TargetAlignmentBoxView reference=root.findViewById(R.id.targetAlignmentBox);reference.setVisibility(View.VISIBLE);
                AtomicInteger settings=new AtomicInteger();root.findViewById(R.id.btnAppSettings).setOnClickListener(v->settings.incrementAndGet());
                PhoneAssemblyLayout ui=new PhoneAssemblyLayout(c,root,null,()->{});
                try{
                    ui.setScanContext(true,"p3:4");settleAssembly(ui,root,width,height,false);
                    ui.layout(androidx.core.graphics.Insets.NONE,false,true);assertFalse("Phone layout must settle without requesting endless layouts",root.isLayoutRequested());
                    assertEquals(View.GONE,preview.getVisibility());assertEquals(View.GONE,reference.getVisibility());
                    assertFalse("Layout cannot unlock a step",card.next.isEnabled());assertTrue(card.previous.isEnabled());
                    assertEquals(View.VISIBLE,root.findViewById(R.id.phoneScanSteps).getVisibility());
                    android.graphics.Rect bounds=localBounds(root,card.next);assertTrue(bounds.right<=width&&bounds.bottom<=height);
                    if(size[0]>size[1])assertTrue("Landscape camera needs 60% width",card.getLeft()>=width*.58);
                    else assertTrue("Portrait camera must stay clear",card.getTop()>=height*.65);
                    assertEquals(View.GONE,((View)field(card,"scroll")).getVisibility());
                    root.findViewById(R.id.phoneScanSteps).performClick();settleAssembly(ui,root,width,height,false);
                    assertEquals(View.VISIBLE,((View)field(card,"scroll")).getVisibility());assertFalse(card.next.isEnabled());
                    root.findViewById(R.id.phoneScanSteps).performClick();
                    card.render(34,"Phase 3 · Placement verified","Continue or rescan.","Details","",true,true,true);card.action.setVisibility(View.GONE);
                    settleAssembly(ui,root,width,height,false);assertTrue(card.next.isEnabled());assertTrue(card.rescan.getVisibility()==View.VISIBLE);
                    AtomicInteger rescans=new AtomicInteger();card.rescan.setOnClickListener(v->rescans.incrementAndGet());card.rescan.performClick();assertEquals("Original rescan action stays available through Scan tools",1,rescans.get());
                    root.findViewById(R.id.btnAppSettings).performClick();assertEquals(1,settings.get());
                    render(c,root,"phone-assembly-"+size[0]+"x"+size[1]+"-font"+font+".png");
                    card.setVisibility(View.GONE);first.setVisibility(View.VISIBLE);first.setScanned(true,false);
                    ui.setScanContext(true,"p1");settleAssembly(ui,root,width,height,true);assertTrue(first.isNextEnabled());
                    assertTrue(first.getBottom()<=height);assertEquals(View.GONE,((View)field(first,"scroll")).getVisibility());
                    first.setVisibility(View.GONE);card.setVisibility(View.VISIBLE);ui.setScanContext(false,"p3:demo");settleAssembly(ui,root,width,height,false);
                    assertEquals(View.VISIBLE,preview.getVisibility());
                    assertFalse("Demo preview must not overlap instructions",android.graphics.Rect.intersects(localBounds(root,preview),localBounds(root,card)));
                }finally{ui.close();preview.dispose();}
            }
        });
    }

    @Test public void phoneTesterKeepsCameraAndFullToolsWithoutFloatingMetrics(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context source=InstrumentationRegistry.getInstrumentation().getTargetContext();
            for(int[] size:new int[][]{{360,780},{780,360},{320,568}})for(float font:new float[]{1,2}){
                Context c=screen(source,size[0],size[1],font);FrameLayout root=(FrameLayout)LayoutInflater.from(c).inflate(R.layout.activity_part_recognition_test,null);
                int width=UiUtils.dp(c,size[0]),height=UiUtils.dp(c,size[1]);
                Button start=root.findViewById(R.id.partTestStartButton);TextView select=root.findViewById(R.id.partTestSelectButton);select.setText("5 / 32 · Motor driver on main board");start.setEnabled(false);
                TextView navigation=new TextView(c);DevDiagnosticsOverlayView hud=root.findViewById(R.id.devDiagnosticsOverlay);hud.setText("Scanned component: Unverified\nLive keypoints: 300\nOutput: Mismatch");
                PhonePartTesterLayout ui=new PhonePartTesterLayout(c,root,navigation,null);
                try{
                    settleTester(ui,root,width,height);View camera=root.findViewById(R.id.partTestCameraPane);
                    ui.refresh();assertFalse("Tester layout must settle without a feedback loop",root.isLayoutRequested());
                    assertEquals(width,camera.getWidth());assertTrue("Phone camera is cramped",camera.getHeight()>=height*.50);
                    assertSame(start,root.findViewById(R.id.partTestStartButton));assertSame(select,root.findViewById(R.id.partTestSelectButton));assertFalse(start.isEnabled());
                    assertEquals(View.GONE,root.findViewById(R.id.partTestControls).getVisibility());assertEquals(View.GONE,navigation.getVisibility());
                    assertTrue((Boolean)field(hud,"inlinePresentation"));assertNotSame(root,hud.getParent());
                    View tools=root.findViewById(R.id.phoneTesterTools);tools.performClick();settleTester(ui,root,width,height);
                    assertEquals(View.VISIBLE,root.findViewById(R.id.partTestControls).getVisibility());assertTrue(camera.getHeight()>=height*.30);
                    assertNotNull(root.findViewById(R.id.partTestCaptureButton));assertNotNull(root.findViewById(R.id.partTestExportButton));
                    assertTrue(hud.getText().toString().contains("300"));
                    tools.performClick();settleTester(ui,root,width,height);
                    render(c,root,"phone-tester-"+size[0]+"x"+size[1]+"-font"+font+".png");
                    assertTrue(localBounds(root,start).bottom<=height);
                }finally{ui.close();}
                assertFalse((Boolean)field(hud,"inlinePresentation"));assertSame(root,hud.getParent());assertEquals(View.VISIBLE,navigation.getVisibility());
            }
        });
    }

    @Test public void tabletScanningLayoutSurvivesPhoneReflowWithoutChangingControls(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context source=InstrumentationRegistry.getInstrumentation().getTargetContext();Context c=screen(source,1280,800,1);
            FrameLayout assembly=(FrameLayout)LayoutInflater.from(c).inflate(R.layout.activity_assembly,null);LinearLayout toolbar=assembly.findViewById(R.id.cameraToolbar);
            int children=toolbar.getChildCount();ComponentPreviewWindow preview=assembly.findViewById(R.id.componentPreviewWindow);View settings=assembly.findViewById(R.id.btnAppSettings);
            FrameLayout.LayoutParams previewParams=new FrameLayout.LayoutParams((FrameLayout.LayoutParams)preview.getLayoutParams());
            PhoneAssemblyLayout a=new PhoneAssemblyLayout(c,assembly,null,()->{});
            try{
                settleAssembly(a,assembly,UiUtils.dp(c,1280),UiUtils.dp(c,800),false);assertFalse(a.isActive());assertEquals(children,toolbar.getChildCount());assertSame(toolbar,settings.getParent());
                for(int i=0;i<2;i++){
                    a.setScanContext(true,"p5:3");settleAssembly(a,assembly,UiUtils.dp(c,390),UiUtils.dp(c,780),false);assertTrue(a.isActive());
                    settleAssembly(a,assembly,UiUtils.dp(c,1280),UiUtils.dp(c,800),false);assertFalse(a.isActive());assertSame(toolbar,settings.getParent());assertEquals(children,toolbar.getChildCount());
                    FrameLayout.LayoutParams restored=(FrameLayout.LayoutParams)preview.getLayoutParams();assertEquals(previewParams.gravity,restored.gravity);assertEquals(previewParams.width,restored.width);
                }
            }finally{a.close();preview.dispose();}
            FrameLayout tester=(FrameLayout)LayoutInflater.from(c).inflate(R.layout.activity_part_recognition_test,null);LinearLayout panes=tester.findViewById(R.id.partTestPanes);int orientation=panes.getOrientation();
            View camera=tester.findViewById(R.id.partTestCameraPane);LinearLayout.LayoutParams original=new LinearLayout.LayoutParams((LinearLayout.LayoutParams)camera.getLayoutParams());View select=tester.findViewById(R.id.partTestSelectButton);
            PhonePartTesterLayout t=new PhonePartTesterLayout(c,tester,null,null);
            try{
                settleTester(t,tester,UiUtils.dp(c,1280),UiUtils.dp(c,800));assertNull(tester.findViewById(R.id.phoneTesterDock));
                for(int i=0;i<2;i++){
                    settleTester(t,tester,UiUtils.dp(c,390),UiUtils.dp(c,780));assertNotNull(tester.findViewById(R.id.phoneTesterDock));
                    settleTester(t,tester,UiUtils.dp(c,1280),UiUtils.dp(c,800));assertNull(tester.findViewById(R.id.phoneTesterDock));assertSame(select,tester.findViewById(R.id.partTestSelectButton));
                    assertEquals(orientation,panes.getOrientation());assertEquals(original.weight,((LinearLayout.LayoutParams)camera.getLayoutParams()).weight,0);assertEquals(original.width,camera.getLayoutParams().width);
                }
            }finally{t.close();}
        });
    }
    @Test public void planFLoadsABoundedRealPhotoAndRendersItsHelpSurface(){
        PlanFScanHelpController[] controller={null};
        try(ActivityScenario<Activity> host=ActivityScenario.launch(host())){
            for(float scale:new float[]{1,2}){
            try{
                host.onActivity(a->{
                    Context c=candidate(a,scale);TextView button=new TextView(c);
                    java.util.List<dev.chyken.assembl_ar.vision.VisionPoint> body=java.util.Arrays.asList(
                        new dev.chyken.assembl_ar.vision.VisionPoint(.1,.1),new dev.chyken.assembl_ar.vision.VisionPoint(.9,.1),
                        new dev.chyken.assembl_ar.vision.VisionPoint(.9,.9),new dev.chyken.assembl_ar.vision.VisionPoint(.1,.9));
                    dev.chyken.assembl_ar.vision.ComponentReferenceSpec photo=dev.chyken.assembl_ar.vision.ComponentReferenceSpec.builder("board-help","main_board","Main board",100,120)
                        .jpegAssetPath("references/Phase1/MainboardFront/Mainboard_FRONTPOV8.jpg").featureRegionsNormalized(java.util.Collections.singletonList(body)).build();
                    controller[0]=new PlanFScanHelpController(a,c,button,()->java.util.Collections.singletonList(photo),()->0L);
                    dev.chyken.assembl_ar.vision.RecognitionRequest request=new dev.chyken.assembl_ar.vision.RecognitionRequest(dev.chyken.assembl_ar.vision.RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,1);
                    controller[0].update("phase1",1,request,"Main board",true,true,false);button.performClick();
                });
                boolean[] loaded={false};
                for(int i=0;i<40&&!loaded[0];i++){
                    host.onActivity(a->{ImageView image=(ImageView)field(controller[0],"image");loaded[0]=image!=null&&image.getDrawable()!=null&&image.isLaidOut()&&image.getHeight()>0;});
                    if(!loaded[0])android.os.SystemClock.sleep(100);
                }
                assertTrue("Real reference thumbnail must load off the UI thread",loaded[0]);
                host.onActivity(a->{
                    ImageView image=(ImageView)field(controller[0],"image");Bitmap pixels=((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap();
                    assertTrue(Math.max(pixels.getWidth(),pixels.getHeight())<=768);
                    androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field(controller[0],"dialog");
                    View decor=dialog.getWindow().getDecorView();
                    for(int which:new int[]{-1,-2,-3}){
                        Button action=dialog.getButton(which);android.graphics.Rect visible=new android.graphics.Rect();
                        assertTrue("Action is clipped at font scale "+scale,action.getGlobalVisibleRect(visible));
                        assertTrue("Action is cut off at font scale "+scale,visible.height()>=action.getHeight()*.9);
                    }
                    render(a,decor,scale==1?"plan-f-reference-help.png":"plan-f-large-font.png");
                });
            }finally{host.onActivity(a->{if(controller[0]!=null)controller[0].close();});}
            }
        }
    }
    @Test public void planFOffersHelpOnceWithoutCompletingAnythingAndClosesOnSuccess(){
        try(ActivityScenario<Activity> host=ActivityScenario.launch(host())){
            host.onActivity(a->{
                Context c=candidate(a,1);android.widget.TextView button=new android.widget.TextView(c);
                dev.chyken.assembl_ar.vision.ComponentReferenceSpec photo=dev.chyken.assembl_ar.vision.ComponentReferenceSpec.builder("board-help","main_board","Main board",100,120)
                    .jpegAssetPath("references/Phase1/MainboardFront/Mainboard_FRONTPOV8.jpg").build();
                java.util.concurrent.atomic.AtomicLong time=new java.util.concurrent.atomic.AtomicLong();
                PlanFScanHelpController controller=new PlanFScanHelpController(a,c,button,()->java.util.Collections.singletonList(photo),time::get);
                dev.chyken.assembl_ar.vision.RecognitionRequest request=new dev.chyken.assembl_ar.vision.RecognitionRequest(dev.chyken.assembl_ar.vision.RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,1);
                try{
                    controller.update("phase1",1,request,"Main board",true,true,false);
                    assertEquals(View.VISIBLE,button.getVisibility());assertNull(field(controller,"dialog"));
                    time.set(4_999);controller.update("phase1",1,request,"Main board",true,true,false);assertNull(field(controller,"dialog"));
                    time.set(5_000);controller.update("phase1",1,request,"Main board",true,true,false);
                    androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field(controller,"dialog");assertTrue(dialog.isShowing());
                    assertNotNull(dialog.findViewById(R.id.planFReferenceImage));
                    dialog.getButton(-1).performClick();
                    assertTrue(((TextView)dialog.findViewById(R.id.planFInstructions)).getText().toString().contains("focus"));
                    assertTrue("Help must not suspend readiness",(Boolean)field(controller,"ready"));
                    dialog.getButton(-2).performClick();assertNull(field(controller,"dialog"));
                    time.set(20_000);controller.update("phase1",1,request,"Main board",true,true,false);assertNull(field(controller,"dialog"));
                    button.performClick();assertNotNull(field(controller,"dialog"));
                    controller.update("phase1",1,request,"Main board",true,false,true);
                    assertNull(field(controller,"dialog"));assertEquals(View.GONE,button.getVisibility());
                    controller.update("phase1",2,request,"Main board",true,true,false);assertNull(field(controller,"dialog"));
                    time.set(25_000);controller.update("phase1",2,request,"Main board",true,true,false);assertNotNull(field(controller,"dialog"));
                    controller.suspend();assertNull(field(controller,"dialog"));
                    time.set(90_000);controller.update("phase1",3,request,"Main board",true,true,false);assertNull(field(controller,"dialog"));
                    time.set(95_000);controller.update("phase1",3,request,"Main board",true,true,false);assertNull(field(controller,"dialog"));
                }finally{controller.close();}
            });
        }
    }

    @Test public void planFSettingsPersistWithoutChangingExistingPreferencesAndLargeFontHelpIsReachable(){
        try(ActivityScenario<Activity> host=ActivityScenario.launch(host())){
            host.onActivity(a->{
                android.content.SharedPreferences prefs=PreferencesManager.visibilityPreferences(a);java.util.Map<String,?> before=new java.util.HashMap<>(prefs.getAll());
                boolean old=PreferencesManager.planFEnabled(a),had=prefs.contains(PreferencesManager.KEY_PLAN_F_ENABLED);
                androidx.appcompat.app.AlertDialog settings=null;PlanFScanHelpController controller=null;
                try{
                    Context c=candidate(a,2);
                    settings=AppSettingsDialog.show(a,c);androidx.appcompat.widget.SwitchCompat toggle=settings.findViewById(R.id.settingsPlanFSwitch);assertNotNull(toggle);
                    toggle.setChecked(!old);assertEquals(!old,PreferencesManager.planFEnabled(a));
                    settings.dismiss();settings=null;
                    android.widget.TextView button=new android.widget.TextView(c);
                    controller=new PlanFScanHelpController(a,c,button,java.util.Collections::emptyList,()->0L);
                    dev.chyken.assembl_ar.vision.RecognitionRequest request=new dev.chyken.assembl_ar.vision.RecognitionRequest(dev.chyken.assembl_ar.vision.RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,1);
                    controller.update("phase1",1,request,"Main board",true,true,false);button.performClick();
                    androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)field(controller,"dialog");
                    for(int which:new int[]{-1,-2,-3}){Button action=dialog.getButton(which);assertEquals(View.VISIBLE,action.getVisibility());assertTrue(action.getMinimumHeight()>=UiUtils.dp(c,48));}
                    assertFalse(dialog.getButton(-2).getCurrentTextColor()==0xFFFFFFFF);
                    dialog.getButton(-2).performClick();assertNull(field(controller,"dialog"));
                }finally{
                    if(settings!=null)settings.dismiss();if(controller!=null)controller.close();
                    if(had)PreferencesManager.planFEnabled(a,old);else prefs.edit().remove(PreferencesManager.KEY_PLAN_F_ENABLED).commit();
                }
                assertEquals(before,prefs.getAll());
            });
        }
    }
    private Context candidate(Context source,float fontScale){
        Configuration config=new Configuration(source.getResources().getConfiguration());config.fontScale=fontScale;
        Context c=source.createConfigurationContext(config);String path=InstrumentationRegistry.getArguments().getString("candidateDex");
        if(path!=null)try{android.content.res.loader.ResourcesLoader loader=new android.content.res.loader.ResourcesLoader();try(android.os.ParcelFileDescriptor fd=android.os.ParcelFileDescriptor.open(new File(path),android.os.ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(android.content.res.loader.ResourcesProvider.loadFromApk(fd));}c.getResources().addLoaders(loader);}catch(IOException e){throw new AssertionError(e);}
        return new androidx.appcompat.view.ContextThemeWrapper(new ContextWrapper(c){
            @Override public ClassLoader getClassLoader(){return UiBeautificationInstrumentedTest.this.getClass().getClassLoader();}
            @Override public Object getSystemService(String name){if(WINDOW_SERVICE.equals(name))return source.getSystemService(name);return LAYOUT_INFLATER_SERVICE.equals(name)?isolatedInflater(this):super.getSystemService(name);}
        },R.style.Theme_ASSEMBLAR);
    }
    private LayoutInflater isolatedInflater(Context context){
        LayoutInflater inflater=new LayoutInflater(context){
            @Override public LayoutInflater cloneInContext(Context c){return isolatedInflater(c);}
            @Override protected View onCreateView(String name,android.util.AttributeSet attrs)throws ClassNotFoundException{try{return createView(name,"android.widget.",attrs);}catch(ClassNotFoundException missing){return super.onCreateView(name,attrs);}}
        };
        // Platform LayoutInflater's static constructor cache otherwise reuses views
        // from the installed app's AppCompat loader with the same class name.
        inflater.setFactory2(new LayoutInflater.Factory2(){
            @Override public View onCreateView(View parent,String name,Context c,android.util.AttributeSet attrs){return onCreateView(name,c,attrs);}
            @Override public View onCreateView(String name,Context c,android.util.AttributeSet attrs){if(!name.startsWith("androidx.appcompat.")&&!name.startsWith("com.google.android.material."))return null;try{return (View)Class.forName(name,true,UiBeautificationInstrumentedTest.this.getClass().getClassLoader()).getConstructor(Context.class,android.util.AttributeSet.class).newInstance(c,attrs);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
        });return inflater;
    }
    private Intent host(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();return new Intent().setClassName(c,"dev.chyken.assembl_ar.PartRecognitionTestActivity").putExtra("test_mode",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
    private static void layout(View v,int w,int h){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}
    private static Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private static void render(Context c,View v,String name){Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);try{v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}catch(IOException e){throw new AssertionError(e);}finally{b.recycle();}}

    @Test public void graphicsStopSchedulingWhenHiddenPausedDetachedAndClosed(){
        ElectronParticleBackgroundView[] nodes={null};AssemblyLoadingCubeView[] cube={null};
        try(ActivityScenario<Activity> host=ActivityScenario.launch(host())){
            host.onActivity(a->{Context c=candidate(a,1);FrameLayout pane=new FrameLayout(c);nodes[0]=new ElectronParticleBackgroundView(c);cube[0]=new AssemblyLoadingCubeView(c);pane.addView(nodes[0],new FrameLayout.LayoutParams(-1,-1));pane.addView(cube[0],new FrameLayout.LayoutParams(400,400));((ViewGroup)a.findViewById(android.R.id.content)).addView(pane,new ViewGroup.LayoutParams(-1,-1));nodes[0].resumeAnimation();cube[0].resumeAnimation();});
            for(int i=0;i<30;i++){boolean[] focused={false};host.onActivity(a->focused[0]=nodes[0].hasWindowFocus());if(focused[0])break;android.os.SystemClock.sleep(100);}
            host.onActivity(a->{assertTrue("Test host needs window focus",nodes[0].hasWindowFocus());assertEquals("Visible particles follow system motion preference",BrandingAssets.motion(),nodes[0].isFrameScheduled());assertEquals("Visible cube follows system motion preference",BrandingAssets.motion(),cube[0].isFrameScheduled());nodes[0].pauseAnimation();cube[0].pauseAnimation();assertFalse(nodes[0].isFrameScheduled());assertFalse(cube[0].isFrameScheduled());nodes[0].resumeAnimation();nodes[0].setVisibility(View.GONE);assertFalse(nodes[0].isFrameScheduled());cube[0].resumeAnimation();((ViewGroup)cube[0].getParent()).removeView(cube[0]);assertFalse(cube[0].isFrameScheduled());nodes[0].close();cube[0].close();nodes[0].resumeAnimation();assertFalse(nodes[0].isFrameScheduled());});
        }
    }
    @Test public void cubeRendersAndUsesRealCountsWithoutBypassingReferenceGate(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context c=candidate(InstrumentationRegistry.getInstrumentation().getTargetContext(),1);AssemblyLoadingCubeView v=new AssemblyLoadingCubeView(c);v.setProgress(387,1000);assertEquals(.387f,(Float)field(v,"progress"),.0001);v.setProgress(2000,1000);assertEquals(1f,(Float)field(v,"progress"),0);v.setProgress(0,0);assertEquals(0f,(Float)field(v,"progress"),0);layout(v,500,380);render(c,v,"beautification-loading-cube.png");assertFalse(v.isFrameScheduled());v.close();
            StartupRevealGate gate=new StartupRevealGate(true);gate.introFinished();gate.frameReady();assertFalse(gate.takeReveal());gate.referencesReady();assertTrue(gate.takeReveal());assertFalse(gate.takeReveal());
            View menu=LayoutInflater.from(c).inflate(R.layout.activity_main,null);layout(menu,1600,1000);assertNotNull(menu.findViewById(R.id.electronBackground));render(c,menu,"beautification-menu.png");
        });
    }
    @Test public void checklistKeepsExplicitCallbacksCorrectionsAndReachableButtons(){
        try(ActivityScenario<Activity> host=ActivityScenario.launch(host())){
            for(float scale:new float[]{1,2}){
                PhysicalChecklistBottomSheetDialog[] dialog={null};AtomicInteger correct=new AtomicInteger(),adjust=new AtomicInteger();
                host.onActivity(a->{
                    Context c=candidate(a,scale);StringBuilder text=new StringBuilder();for(int i=0;i<25;i++)text.append("Check both 20mm screws and M3 nuts physically. Confirm alignment and wire clearance.\n\n");
                    TextView prompt=PhysicalCheckUi.prompt(c,text.toString());PhysicalChecklistBottomSheetDialog d=new PhysicalChecklistBottomSheetDialog.Builder(c).setTitle("Fasteners and alignment").setView(prompt).setPositiveButton("Firm & Secure",null).setNegativeButton("Needs Adjustment",null).setNeutralButton("Check later",null).create();dialog[0]=d;
                    d.setOnShowListener(shown->{d.getButton(-1).setOnClickListener(v->correct.incrementAndGet());d.getButton(-2).setOnClickListener(v->{adjust.incrementAndGet();prompt.setText("Re-seat and tighten the fasteners.\n\n"+text);});});d.show();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                host.onActivity(a->{PhysicalChecklistBottomSheetDialog d=dialog[0];try{
                    View root=d.getWindow().getDecorView();assertEquals(Gravity.BOTTOM,d.getWindow().getAttributes().gravity&Gravity.VERTICAL_GRAVITY_MASK);assertEquals(0,correct.get());d.getButton(-2).performClick();assertEquals(1,adjust.get());assertTrue(d.isShowing());assertEquals(0,correct.get());
                    for(int which:new int[]{-1,-2,-3}){Button b=d.getButton(which);assertTrue(b.getHeight()>=48*b.getResources().getDisplayMetrics().density);android.graphics.Rect visible=new android.graphics.Rect();assertTrue("Answer must be visible at font scale "+scale,b.getGlobalVisibleRect(visible));assertEquals("Answer cannot be clipped",b.getHeight(),visible.height());}
                    render(a,root,scale==1?"beautification-checklist.png":"beautification-checklist-large-font.png");d.getButton(-1).performClick();assertEquals(1,correct.get());
                }finally{d.dismiss();}});
            }
        }
    }
    @Test public void bothToolbarsShareMutePreferenceWithoutWritingAssemblyProgress(){
        try(ActivityScenario<Activity> host=ActivityScenario.launch(host())){
            host.onActivity(a->{SharedPreferences prefs=PreferencesManager.narrationPreferences(a);boolean existed=prefs.contains(PreferencesManager.KEY_TTS_MUTED),before=prefs.getBoolean(PreferencesManager.KEY_TTS_MUTED,false);prefs.edit().putBoolean(PreferencesManager.KEY_TTS_MUTED,false).commit();
                try{Context c=candidate(a,1);for(int layout:new int[]{R.layout.activity_assembly,R.layout.activity_part_recognition_test}){
                    View root=LayoutInflater.from(c).inflate(layout,null);ImageButton button=root.findViewById(R.id.btnTtsMuteToggle);assertNotNull(button);AtomicInteger updates=new AtomicInteger();
                    try(NarrationMuteControl control=new NarrationMuteControl(a,button,muted->updates.incrementAndGet())){
                        boolean initial=PreferencesManager.ttsMuted(a);assertEquals(!initial,button.isSelected());button.performClick();assertEquals(!initial,PreferencesManager.ttsMuted(a));assertEquals(initial,button.isSelected());assertTrue(updates.get()>=2);
                    }
                }}finally{if(existed)prefs.edit().putBoolean(PreferencesManager.KEY_TTS_MUTED,before).commit();else prefs.edit().remove(PreferencesManager.KEY_TTS_MUTED).commit();}
            });
        }
    }
    @Test public void sharedLightShellKeepsOneMenuAndUsableTesterViewport(){
        try(ActivityScenario<Activity> host=ActivityScenario.launch(host())){
            host.onActivity(a->{Context c=candidate(a,1);a.setContentView(LayoutInflater.from(c).inflate(R.layout.activity_part_recognition_test,null));AppShellController.install(a);AppShellController.install(a);
                View shell=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);assertEquals("Shared shell is the content root",R.id.appShell,shell.getId());layout(shell,a.getResources().getDisplayMetrics().widthPixels,a.getResources().getDisplayMetrics().heightPixels);assertNotNull(shell);assertNotNull("Navigation root missing",shell.findViewById(R.id.primaryNavigation));assertNotNull("Settings entry missing",shell.findViewById(R.id.navSettingsButton));assertTrue(a.findViewById(R.id.navComponentsButton).isSelected());
                int cameraHeight=a.findViewById(R.id.partTestCameraPane).getHeight();assertTrue("Tester camera must remain a usable viewport",cameraHeight>=UiUtils.dp(c,200));
                assertFalse("Camera workspace uses a static circuit background",((ElectronParticleBackgroundView)a.findViewById(R.id.electronBackground)).isFrameScheduled());
                double contrast=androidx.core.graphics.ColorUtils.calculateContrast(c.getColor(R.color.ink),c.getColor(R.color.canvas));assertTrue("Text contrast must exceed 7:1",contrast>=7);
                render(a,shell,"light-shell-tester.png");
                // Existing manual and menu hierarchies retain their original scroll content.
                for(int resource:new int[]{R.layout.activity_main,R.layout.activity_project_detail,R.layout.activity_learning,R.layout.activity_component_library}){a.setContentView(LayoutInflater.from(c).inflate(resource,null));AppShellController.install(a);AppShellController.install(a);View screen=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);assertEquals("Only one primary menu per section",1,countId(screen,R.id.primaryNavigation));assertEquals(1,countId(screen,R.id.navSettingsButton));layout(screen,1600,1000);render(a,screen,"light-shell-"+resource+".png");}
            });
        }
    }
    @Test public void settingsTogglesChangeOnlyAdministratorAndAssemblyDeveloperVisibility(){
        try(ActivityScenario<Activity> host=ActivityScenario.launch(host())){
            androidx.appcompat.app.AlertDialog[] dialog={null};Context[] configured={null};java.util.concurrent.atomic.AtomicReference<java.util.Map<String,?>> before=new java.util.concurrent.atomic.AtomicReference<>();SharedPreferences[] prefs={null};
            host.onActivity(a->{Context c=candidate(a,1);configured[0]=c;prefs[0]=PreferencesManager.visibilityPreferences(a);before.set(prefs[0].getAll());prefs[0].edit().remove(PreferencesManager.KEY_ADMIN_VISIBLE).remove(PreferencesManager.KEY_ASSEMBLY_DEV_VISIBLE).commit();a.setContentView(LayoutInflater.from(c).inflate(R.layout.activity_part_recognition_test,null));AppShellController.install(a);assertEquals(View.GONE,a.findViewById(R.id.navAdminButton).getVisibility());a.findViewById(R.id.navAdminButton).performClick();assertFalse(a.isFinishing());dialog[0]=AppSettingsDialog.show(a,c);});
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            host.onActivity(a->{Context c=configured[0];androidx.appcompat.app.AlertDialog d=dialog[0];try{
                androidx.appcompat.widget.SwitchCompat admin=d.findViewById(R.id.settingsAdminSwitch),dev=d.findViewById(R.id.settingsDeveloperSwitch);assertNotNull(admin);assertNotNull(dev);assertFalse(admin.isChecked());assertFalse(dev.isChecked());
                for(int which:new int[]{-1,-3}){Button button=d.getButton(which);assertTrue("Settings footer button has a visible touch target",button.getHeight()>=UiUtils.dp(c,48));android.graphics.Rect visible=new android.graphics.Rect();assertTrue(button.getGlobalVisibleRect(visible));assertTrue("Settings footer text contrasts with white",androidx.core.graphics.ColorUtils.calculateContrast(button.getCurrentTextColor(),android.graphics.Color.WHITE)>=4.5);}
                admin.setChecked(true);assertTrue(PreferencesManager.administratorVisible(a));assertEquals(View.VISIBLE,a.findViewById(R.id.navAdminButton).getVisibility());dev.setChecked(true);assertTrue(PreferencesManager.assemblyDeveloperVisible(a));render(a,d.getWindow().getDecorView(),"light-shell-settings.png");admin.setChecked(false);assertEquals(View.GONE,a.findViewById(R.id.navAdminButton).getVisibility());d.dismiss();
                a.setContentView(LayoutInflater.from(c).inflate(R.layout.activity_assembly,null));AppShellController.bindAssemblyControls(a);assertEquals(View.VISIBLE,a.findViewById(R.id.devButton).getVisibility());PreferencesManager.assemblyDeveloperVisible(a,false);assertEquals(View.GONE,a.findViewById(R.id.devButton).getVisibility());assertNotNull(a.findViewById(R.id.btnAppSettings));
            }finally{d.dismiss();SharedPreferences.Editor edit=prefs[0].edit();for(String key:new String[]{PreferencesManager.KEY_ADMIN_VISIBLE,PreferencesManager.KEY_ASSEMBLY_DEV_VISIBLE}){if(before.get().containsKey(key))edit.putBoolean(key,(Boolean)before.get().get(key));else edit.remove(key);}edit.commit();}});
        }
    }

    private static int countId(View view,int id){int count=view.getId()==id?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countId(((ViewGroup)view).getChildAt(i),id);return count;}

}
