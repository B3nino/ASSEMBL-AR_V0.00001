package dev.chyken.assembl_ar;
import android.content.*;import android.content.res.*;import android.content.res.loader.*;import android.os.ParcelFileDescriptor;import android.view.*;import android.widget.*;
import androidx.appcompat.view.ContextThemeWrapper;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.filters.SdkSuppress;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;

/** Native layout/contrast checks against the exact newly packaged resources. */
@RunWith(AndroidJUnit4.class) @SdkSuppress(minSdkVersion=30)
public final class DynamicOutlineUiInstrumentedTest {
    @Test public void welcomeFooterIsVisibleEnabledAndContrastingAtLargeFonts(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            for(int[] size:new int[][]{{1024,600},{640,360},{400,640}}){
                Context context=themed(size);float d=context.getResources().getDisplayMetrics().density;int w=(int)(Math.min(600,size[0]-32)*d),h=(int)((size[1]-48)*d);
                View root=LayoutInflater.from(context).inflate(R.layout.dialog_welcome_assembly,null,false);layout(root,w,h);
                Button action=root.findViewById(R.id.welcomeStartButton);assertNotNull(action);assertTrue(action.isEnabled());assertEquals("Let's Assemble",action.getText().toString());
                android.graphics.Rect visible=new android.graphics.Rect();action.getDrawingRect(visible);((android.view.ViewGroup)root).offsetDescendantRectToMyCoords(action,visible);
                assertTrue("Footer fits "+size[0]+"x"+size[1],visible.top>=0&&visible.bottom<=h);assertTrue(action.getHeight()>=48*d);
                assertEquals(0xFFFFFFFF,action.getCurrentTextColor());assertEquals(0xFF326A32,action.getBackgroundTintList().getDefaultColor());
                android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(image));
                assertEquals("The action paints green on screen",0xFF326A32,image.getPixel(visible.left+(int)(24*d),visible.centerY()));image.recycle();
                final boolean[] tapped={false};action.setOnClickListener(v->tapped[0]=true);action.performClick();assertTrue(tapped[0]);
            }
        });
    }
    @Test public void bothCamerasHaveAnIndependentFullSizeDynamicOverlay(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context c=themed(new int[]{1024,600});for(int resource:new int[]{R.layout.activity_assembly,R.layout.activity_part_recognition_test}){
                View root=LayoutInflater.from(c).inflate(resource,null,false);layout(root,1536,900);
                View overlay=root.findViewById(R.id.dynamicOutlineOverlay);assertNotNull(overlay);assertTrue(overlay.getWidth()>500);assertTrue(overlay.getHeight()>400);assertFalse(overlay.isClickable());
            }
        });
    }
    @Test public void phaseOneDeveloperCompletionShowsAnEnabledFooterWithoutCameraEvidence(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context c=themed(new int[]{1024,600});dev.chyken.assembl_ar.ui.PhaseOneGuideView card=new dev.chyken.assembl_ar.ui.PhaseOneGuideView(c,null);
            card.setScanned(false,false,false);assertFalse(card.isNextEnabled());
            dev.chyken.assembl_ar.workflow.phase.PhaseFlow flow=new dev.chyken.assembl_ar.workflow.phase.PhaseFlow(dev.chyken.assembl_ar.workflow.phase.AssemblyPhaseCatalog.phases().get(0),1);
            flow.devComplete(2);assertFalse(flow.collectsEvidence());card.setScanned(flow.getMode()==dev.chyken.assembl_ar.workflow.phase.PhaseFlow.Mode.COMPLETE,false,true);layout(card,700,450);
            assertTrue(card.isNextEnabled());final boolean[] clicked={false};card.setNextAction(()->clicked[0]=true);
            java.util.ArrayDeque<View> views=new java.util.ArrayDeque<>();views.add(card);boolean found=false;
            while(!views.isEmpty()){View v=views.removeFirst();if(v instanceof Button&&v.isEnabled()&&v.getVisibility()==View.VISIBLE&&((Button)v).getText().toString().startsWith("Next Step")){assertTrue(v.getHeight()>0);v.performClick();found=true;}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)views.add(((ViewGroup)v).getChildAt(i));}
            assertTrue("Visible developer Next action",found);assertTrue(clicked[0]);
        });
    }
    private Context themed(int[] size){
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();Configuration config=new Configuration(target.getResources().getConfiguration());config.screenWidthDp=size[0];config.screenHeightDp=size[1];config.fontScale=1.5f;config.orientation=size[0]>size[1]?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
        Context base=target.createConfigurationContext(config);String candidate=InstrumentationRegistry.getArguments().getString("candidateDex");
        if(candidate!=null)try{ResourcesLoader loader=new ResourcesLoader();try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new java.io.File(candidate),ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(ResourcesProvider.loadFromApk(fd));}base.getResources().addLoaders(loader);}catch(java.io.IOException failure){throw new AssertionError(failure);}
        Context wrapper=new ContextWrapper(base){@Override public ClassLoader getClassLoader(){return DynamicOutlineUiInstrumentedTest.this.getClass().getClassLoader();}@Override public Object getSystemService(String name){if(LAYOUT_INFLATER_SERVICE.equals(name))return LayoutInflater.from(getBaseContext()).cloneInContext(this);return super.getSystemService(name);}};
        return new ContextThemeWrapper(wrapper,R.style.Theme_ASSEMBLAR);
    }
    private void layout(View root,int width,int height){root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}
}
