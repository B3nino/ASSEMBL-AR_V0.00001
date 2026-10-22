package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.AssetManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.Phase9AssetCatalog;
import org.junit.*;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Run from Android Studio on the target ABI. No camera, installs or learner-state changes. */
@RunWith(AndroidJUnit4.class)
public final class Phase9ReferenceMatcherInstrumentedTest {
    @BeforeClass public static void initialize(){assertTrue(OpenCVLoader.initLocal());}
    @Test public void heldOutCameraSizedViewsRequireBothLocalWheelsAndKeepOrientationInvariance()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();AssetManager assets=app.getAssets();
        List<ComponentReferenceSpec> all=Phase9AssetCatalog.references(assets);Map<String,ComponentReferenceSpec> probes=new LinkedHashMap<>();
        for(ComponentReferenceSpec s:all){String role=Phase9AssetCatalog.referenceRole(s.getReferenceId());String path=s.getJpegAssetPath();
            if(role.equals("standoff")||role.equals("pair")||role.equals("assembly"))probes.putIfAbsent(role,s);
            else if(role.equals("invalid")){String name=path.substring(path.lastIndexOf('/')+1);probes.putIfAbsent(name.startsWith("INVALIDNOLEFT")?"no-left":name.startsWith("INVALIDNORIGHT")?"no-right":"no-wheels",s);}
        }
        assertEquals(6,probes.size());List<ComponentReferenceSpec> training=new ArrayList<>(all);training.removeAll(probes.values());VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase db=ReferenceDatabase.load(assets,training,config,new File(app.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase9ReferenceMatcher matcher=new Phase9ReferenceMatcher(db,config)){
            assertEquals("No silent source geometry/preparation failures",training.size(),db.size());
            for(ReferenceDatabase.PreparedReference reference:db.preparedReferences())assertEquals("Normal and inverted descriptors retained",2,reference.orbVariants.size());
            for(Map.Entry<String,ComponentReferenceSpec> probe:probes.entrySet()){
                Mat source=read(assets,probe.getValue().getJpegAssetPath()),camera=new Mat();try{
                    double scale=640.0/Math.max(source.cols(),source.rows());Imgproc.resize(source,camera,new Size(),scale,scale,Imgproc.INTER_AREA);
                    for(int turn:new int[]{-1,Core.ROTATE_90_CLOCKWISE,Core.ROTATE_180}){
                        Mat view=new Mat(),gray=new Mat(),rgb=new Mat();try{
                            if(turn<0)camera.copyTo(view);else Core.rotate(camera,view,turn);Imgproc.cvtColor(view,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.cvtColor(view,rgb,Imgproc.COLOR_BGR2RGB);
                            int stage=probe.getKey().equals("standoff")?1:probe.getKey().equals("pair")?2:4;boolean expected=!probe.getKey().startsWith("no-");int hits=0;
                            // Runtime visits candidates under a deadline; two complete current
                            // frames must be available, rather than unioning left/right sightings.
                            for(int frame=0;frame<12;frame++){
                                RecognitionResult result=matcher.inspect(gray,rgb,100+frame,stage);assertNull(result.getPose());Phase9Observation o=result.getPhase9Observation();
                                android.util.Log.i("Phase9Replay",probe.getKey()+" turn="+turn+" frame="+frame+" wheels="+o.leftWheel+","+o.rightWheel+" stand="+o.standoff+" accepted="+o.accepted(stage)+" ms="+result.getLatency().getTotalMs());
                                if(expected){hits=o.accepted(stage)?hits+1:0;if(hits==2)break;}
                                else assertFalse("Missing wheel never passes: "+probe.getKey(),o.accepted(4));
                            }
                            if(expected)assertEquals(probe.getKey()+" turn="+turn+" must unlock from two complete frames",2,hits);
                        }finally{view.release();gray.release();rgb.release();}
                    }
                }finally{source.release();camera.release();}
            }
            Mat blank=new Mat(400,640,CvType.CV_8UC1,new Scalar(210)),rgb=new Mat(400,640,CvType.CV_8UC3,new Scalar(210,210,210));try{
                for(int stage=1;stage<=4;stage++)assertFalse("Blank frame",matcher.inspect(blank,rgb,200,stage).getPhase9Observation().accepted(stage));
            }finally{blank.release();rgb.release();}
        }
    }
    @Test public void oneWheelDoesNotCountAsTwoAndBothHubFacesCanBeUsed()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();AssetManager assets=app.getAssets();VisionConfig config=VisionConfig.defaults();
        List<ComponentReferenceSpec> refs=new ArrayList<>();for(ComponentReferenceSpec s:Phase9AssetCatalog.references(assets))if("wheel".equals(Phase9AssetCatalog.referenceRole(s.getReferenceId()))||"pair".equals(Phase9AssetCatalog.referenceRole(s.getReferenceId())))refs.add(s);
        for(String face:new String[]{"WHEELSFRONTREF","WHEELSBACKREF"}){
            ComponentReferenceSpec probe=null;for(ComponentReferenceSpec s:refs)if(s.getJpegAssetPath().contains(face)){probe=s;break;}assertNotNull(probe);
            List<ComponentReferenceSpec> training=new ArrayList<>(refs);training.remove(probe);
            try(ReferenceDatabase db=ReferenceDatabase.load(assets,training,config,new File(app.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase9ReferenceMatcher matcher=new Phase9ReferenceMatcher(db,config)){
                Mat photo=read(assets,probe.getJpegAssetPath()),small=new Mat(),gray=new Mat(),rgb=new Mat();try{
                    double scale=320.0/Math.max(photo.cols(),photo.rows());Imgproc.resize(photo,small,new Size(),scale,scale,Imgproc.INTER_AREA);Imgproc.cvtColor(small,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.cvtColor(small,rgb,Imgproc.COLOR_BGR2RGB);
                    for(int i=0;i<3;i++)assertFalse("A single "+face+" cannot unlock 2/2",matcher.inspect(gray,rgb,300+i,2).getPhase9Observation().accepted(2));
                    // Two separate image instances exercise independent descriptor pools.
                    Mat pair=new Mat(),pairGray=new Mat(),pairRgb=new Mat();try{
                        Core.hconcat(Arrays.asList(small,small),pair);Imgproc.cvtColor(pair,pairGray,Imgproc.COLOR_BGR2GRAY);Imgproc.cvtColor(pair,pairRgb,Imgproc.COLOR_BGR2RGB);boolean accepted=false;
                        for(int i=0;i<12;i++)if(matcher.inspect(pairGray,pairRgb,400+i,2).getPhase9Observation().accepted(2)){accepted=true;break;}assertTrue(face+" pair",accepted);
                    }finally{pair.release();pairGray.release();pairRgb.release();}
                }finally{photo.release();small.release();gray.release();rgb.release();}
            }
        }
    }
    private Mat read(AssetManager assets,String path)throws IOException{
        try(InputStream in=assets.open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);MatOfByte encoded=new MatOfByte(out.toByteArray());try{return Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);}finally{encoded.release();}
        }
    }
}
