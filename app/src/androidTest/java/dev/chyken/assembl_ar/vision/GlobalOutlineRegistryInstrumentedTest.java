package dev.chyken.assembl_ar.vision;
import android.content.Context;import androidx.test.platform.app.InstrumentationRegistry;import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;import org.junit.runner.RunWith;import org.opencv.android.OpenCVLoader;import org.opencv.core.*;import org.opencv.features2d.ORB;import org.opencv.imgproc.Imgproc;import org.opencv.imgcodecs.Imgcodecs;
import java.io.File;import java.util.*;import static org.junit.Assert.*;

/** Shared PCB texture must not identify a missing installed component. */
@RunWith(AndroidJUnit4.class)
public final class GlobalOutlineRegistryInstrumentedTest {
    @BeforeClass public static void nativeRuntime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    @Test public void bodyDescriptorsIdentifyTheWrongPartAndRejectItsEmptyBoardContext()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Mat nano=scene(91),driver=scene(190);File a=File.createTempFile("outline-nano-",".png",c.getCacheDir()),b=File.createTempFile("outline-driver-",".png",c.getCacheDir());
        List<VisionPoint> body=Arrays.asList(new VisionPoint(.25,.2),new VisionPoint(.70,.2),new VisionPoint(.70,.8),new VisionPoint(.25,.8));
        List<ComponentReferenceSpec> refs=Arrays.asList(ComponentReferenceSpec.builder("outline-nano","arduino_nano_microcontroller","Nano",30,60).localImageFilePath(a.getPath()).planarReferenceCornersNormalized(body.get(0),body.get(1),body.get(2),body.get(3)).build(),ComponentReferenceSpec.builder("outline-driver","dual_motor_driver","Driver",40,40).localImageFilePath(b.getPath()).planarReferenceCornersNormalized(body.get(0),body.get(1),body.get(2),body.get(3)).build());
        Imgcodecs.imwrite(a.getPath(),nano);Imgcodecs.imwrite(b.getPath(),driver);GlobalComponentRegistry registry=new GlobalComponentRegistry();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,VisionConfig.defaults(),null)){
            for(ReferenceDatabase.PreparedReference ref:db.preparedReferences())registry.prepared(ref);registry.publish();
            GlobalComponentRegistry.Match correct=query(registry,nano);assertNotNull(correct);assertEquals("arduino_nano_microcontroller",correct.componentId);assertTrue(correct.identityCertain);
            GlobalComponentRegistry.Match wrong=query(registry,driver);assertNotNull(wrong);assertEquals("dual_motor_driver",wrong.componentId);assertTrue(wrong.identityCertain);assertTrue(wrong.incompatible(5,"arduino_nano_microcontroller"));assertEquals(1,wrong.bodyRegions.size());
            Mat empty=nano.clone();try{Imgproc.rectangle(empty,new Point(160,80),new Point(448,320),new Scalar(180),-1);assertNull("Unchanged board background is not the missing module",query(registry,empty));}finally{empty.release();}
        }finally{nano.release();driver.release();a.delete();b.delete();}
    }
    @Test public void ambiguousBodyIdentityStaysAcquiringInsteadOfNamingAWrongItem()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Mat source=scene(812);File file=File.createTempFile("outline-ambiguous-",".png",c.getCacheDir());Imgcodecs.imwrite(file.getPath(),source);
        List<ComponentReferenceSpec> refs=new ArrayList<>();for(String id:new String[]{"arduino_nano_microcontroller","dual_motor_driver"})refs.add(ComponentReferenceSpec.builder("ambiguous-"+id,id,id,40,40).localImageFilePath(file.getPath()).planarReferenceCornersNormalized(new VisionPoint(.25,.2),new VisionPoint(.70,.2),new VisionPoint(.70,.8),new VisionPoint(.25,.8)).build());
        GlobalComponentRegistry registry=new GlobalComponentRegistry();try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,VisionConfig.defaults(),null)){
            for(ReferenceDatabase.PreparedReference ref:db.preparedReferences())registry.prepared(ref);registry.publish();GlobalComponentRegistry.Match found=query(registry,source);assertNotNull(found);assertFalse("A tied visual match cannot trigger a named correction",found.identityCertain);
        }finally{source.release();file.delete();}
    }
    private Mat scene(int seed){Mat image=new Mat(400,640,CvType.CV_8UC1);Core.setRNGSeed(4);Core.randu(image,130,220);Mat body=image.submat(new Rect(160,80,288,240));try{Core.setRNGSeed(seed);Core.randu(body,10,240);Imgproc.GaussianBlur(body,body,new Size(3,3),.7);}finally{body.release();}return image;}
    private GlobalComponentRegistry.Match query(GlobalComponentRegistry registry,Mat gray){ORB orb=OpenCvVisionUtils.createOrb(VisionConfig.defaults());org.opencv.imgproc.CLAHE clahe=OpenCvVisionUtils.createClahe(VisionConfig.defaults());Mat normal=new Mat(),mask=new Mat(),rows=new Mat();MatOfKeyPoint keys=new MatOfKeyPoint();
        try{clahe.apply(gray,normal);orb.detectAndCompute(normal,mask,keys,rows);return registry.queryForOverlay(rows,keys.toArray(),"arduino_nano_microcontroller",5);}finally{normal.release();mask.release();rows.release();keys.release();orb.clear();clahe.clear();}}
}
