package dev.chyken.assembl_ar.vision;

import android.Manifest;
import android.content.*;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.util.Size;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;

/** Real sensor/buffer benchmark, not a recognition accuracy or preview-FPS claim.
 * Opens only the installed menu to keep camera access foreground; no scan or
 * workflow actions, installs, data clears or progress preference edits occur. */
public class InstalledCapturePerformanceInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    @Test public void actualTabletDetailStreamKeepsDeliveryAndTrackingBudget()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.CAMERA);
        Intent foreground=new Intent().setClassName(context.getPackageName(),"dev.chyken.assembl_ar.MainActivity");
        try(ActivityScenario<android.app.Activity> ignored=ActivityScenario.launch(foreground)){
            CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);String id=null;
            for(String candidate:manager.getCameraIdList())if(Objects.equals(manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)){id=candidate;break;}
            assertNotNull(id);Size[] supported=manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
            Size low=choose(supported,640),detail=choose(supported,1280);assertNotNull(low);assertNotNull(detail);assertTrue(detail.getWidth()>low.getWidth());
            Result baseline=measure(manager,id,low,false),nativeDetail=measure(manager,id,detail,false);
            android.util.Log.i("InstalledCapture","640="+baseline+" native="+nativeDetail);
            assertTrue("Native capture cannot consume the camera delivery budget: "+baseline+" -> "+nativeDetail,nativeDetail.fps>=baseline.fps*.80);
            assertTrue("Bounded camera/tracker P95 must stay below one 30fps frame: "+nativeDetail,nativeDetail.p95Ms<33.4);
        }
    }
    @Test public void previewAndDetailStreamsShareTheCameraWithoutDeliveryRegression()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.CAMERA);
        try(ActivityScenario<android.app.Activity> ignored=ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),"dev.chyken.assembl_ar.MainActivity"))){
            CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);String id=null;
            for(String candidate:manager.getCameraIdList())if(Objects.equals(manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)){id=candidate;break;}
            assertNotNull(id);Size[] supported=manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
            Result baseline=measure(manager,id,choose(supported,640),true),detail=measure(manager,id,choose(supported,1280),true);
            android.util.Log.i("InstalledCapture","SHARED_PREVIEW 640="+baseline+" native="+detail);
            assertTrue("Preview plus native detail must preserve delivery: "+baseline+" -> "+detail,detail.fps>=baseline.fps*.80);assertTrue("Native detail buffer/tracker cost: "+detail,detail.p95Ms<33.4);
        }
    }
    private static Size choose(Size[] sizes,int edge){Size best=null;for(Size s:sizes)if(s.getWidth()<=edge&&s.getHeight()<=edge&&(best==null||s.getWidth()*s.getHeight()>best.getWidth()*best.getHeight()))best=s;return best;}
    private Result measure(CameraManager manager,String id,Size size,boolean preview)throws Exception{
        HandlerThread worker=new HandlerThread("capture-budget-test");worker.start();Handler handler=new Handler(worker.getLooper());
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicReference<CameraDevice> camera=new AtomicReference<>();AtomicReference<CameraCaptureSession> session=new AtomicReference<>();
        CountDownLatch opened=new CountDownLatch(1),configured=new CountDownLatch(1);AtomicBoolean active=new AtomicBoolean(true);
        ImageReader reader=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.YUV_420_888,2);
        ImageReader previewReader=preview?ImageReader.newInstance(1280,720,ImageFormat.PRIVATE,2):null;
        BoundedOutlineTracker tracker=new BoundedOutlineTracker();Mat gray=new Mat(size.getHeight(),size.getWidth(),CvType.CV_8UC1);
        byte[] pixels=new byte[size.getWidth()*size.getHeight()];List<Double> costs=new ArrayList<>();long[] range={0,0};int[] frames={0};
        try{
            if(previewReader!=null)previewReader.setOnImageAvailableListener(source->{Image image=source.acquireLatestImage();if(image!=null)image.close();},handler);
            reader.setOnImageAvailableListener(source->{
                Image image=null;try{image=source.acquireLatestImage();if(image==null||!active.get())return;
                    long time=SystemClock.elapsedRealtimeNanos(),began=time;Image.Plane plane=image.getPlanes()[0];ByteBuffer y=plane.getBuffer().duplicate();
                    for(int row=0;row<size.getHeight();row++){y.position(row*plane.getRowStride());y.get(pixels,row*size.getWidth(),size.getWidth());}
                    gray.put(0,0,pixels);tracker.frame(gray,time,null);
                    if(++frames[0]>20){if(range[0]==0)range[0]=time;range[1]=time;costs.add((SystemClock.elapsedRealtimeNanos()-began)/1e6);}
                }catch(Throwable e){failure.compareAndSet(null,e);}finally{if(image!=null)image.close();}
            },handler);
            manager.openCamera(id,new CameraDevice.StateCallback(){public void onOpened(CameraDevice c){camera.set(c);opened.countDown();}public void onDisconnected(CameraDevice c){camera.set(c);failure.set(new IllegalStateException("Camera disconnected"));opened.countDown();}public void onError(CameraDevice c,int error){camera.set(c);failure.set(new IllegalStateException("Camera error "+error));opened.countDown();}},handler);
            assertTrue(opened.await(8,TimeUnit.SECONDS));assertNull(failure.get());assertNotNull(camera.get());
            List<android.view.Surface> surfaces=new ArrayList<>();surfaces.add(reader.getSurface());if(previewReader!=null)surfaces.add(previewReader.getSurface());
            camera.get().createCaptureSession(surfaces,new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession c){session.set(c);configured.countDown();}
                public void onConfigureFailed(CameraCaptureSession c){failure.set(new IllegalStateException("Unsupported native capture session"));configured.countDown();}
            },handler);
            assertTrue(configured.await(8,TimeUnit.SECONDS));assertNull(failure.get());assertNotNull(session.get());
            CaptureRequest.Builder request=camera.get().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);for(android.view.Surface surface:surfaces)request.addTarget(surface);session.get().setRepeatingRequest(request.build(),null,handler);
            SystemClock.sleep(8000);active.set(false);
            CountDownLatch drained=new CountDownLatch(1);handler.post(drained::countDown);assertTrue(drained.await(5,TimeUnit.SECONDS));assertNull(failure.get());assertTrue("Actual camera frames missing",costs.size()>30);
            Collections.sort(costs);return new Result(size,(costs.size()-1)*1e9/(range[1]-range[0]),costs.get((int)Math.ceil(costs.size()*.95)-1));
        }finally{
            active.set(false);if(session.get()!=null)session.get().close();if(camera.get()!=null)camera.get().close();reader.setOnImageAvailableListener(null,null);
            worker.quitSafely();worker.join(5000);reader.close();tracker.close();gray.release();
            if(previewReader!=null)previewReader.close();
        }
    }
    private static final class Result{final Size size;final double fps,p95Ms;Result(Size s,double f,double p){size=s;fps=f;p95Ms=p;}public String toString(){return size+" fps="+fps+" idleBufferPathP95Ms="+p95Ms;}}
}
