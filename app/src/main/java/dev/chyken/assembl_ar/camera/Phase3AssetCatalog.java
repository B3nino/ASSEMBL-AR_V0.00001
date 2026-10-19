package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import java.util.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;

/** Raw photo discovery. Alignment art and clips never become recognition descriptors. */
public final class Phase3AssetCatalog {
    public static final String ROOT="references/Phase3",PREFIX="p3scene_";
    private Phase3AssetCatalog(){}
    public static String role(String folder){
        String n=Phase2AssetCatalog.normalize(folder);
        if(n.equals("STEP31MOTORDRIVER")||n.equals("MOTORDRIVERFRONT"))return "front";
        if(n.equals("MOTORDRIVERBACKINVALID"))return "back";
        if(n.equals("STEP35ASSEMBLYSCAN"))return "assembly";
        return null;
    }
    public static String referenceRole(String id){
        if(id==null||!id.startsWith(PREFIX))return null;
        int end=id.indexOf('_',PREFIX.length());return end<0?null:id.substring(PREFIX.length(),end);
    }
    public static List<ComponentReferenceSpec> references(AssetManager assets){return referencesForPaths(Phase2AssetCatalog.files(assets,ROOT));}
    public static List<ComponentReferenceSpec> referencesForPaths(List<String> paths){
        List<ComponentReferenceSpec> specs=new ArrayList<>();
        for(String path:paths){
            if(!Phase2AssetCatalog.image(path)||!path.startsWith(ROOT+"/"))continue;
            String role=role(path.substring(ROOT.length()+1).split("/")[0]);if(role==null)continue;
            specs.add(ComponentReferenceSpec.builder(PREFIX+role+"_"+path,NeobotSeedData.MOTOR_DRIVER,"Motor Driver",28,37)
                    .jpegAssetPath(path).assemblyStepNumber(3).maximumImageDimension(1280).polarityAnchorsVerified(false).build());
        }
        return Collections.unmodifiableList(specs);
    }
    public static List<String> videos(AssetManager assets){
        List<String> result=new ArrayList<>();
        for(String path:Phase2AssetCatalog.files(assets,ROOT)){
            String relative=path.substring(ROOT.length()+1),lower=path.toLowerCase(Locale.ROOT);
            if(Phase2AssetCatalog.normalize(relative.split("/")[0]).equals("P3VIDEOGUIDE")&&(lower.endsWith(".mp4")||lower.endsWith(".webm")))result.add(path);
        }
        result.sort(Comparator.comparingInt(Phase3AssetCatalog::clipOrder).thenComparing(p->p));return Collections.unmodifiableList(result);
    }
    public static int clipOrder(String path){String n=Phase2AssetCatalog.normalize(path);return n.contains("SEAT")||n.contains("HEADER")?0:n.contains("SCREW")||n.contains("NUT")?1:2;}
    public static String guide(AssetManager assets,int step){
        return guideForPaths(Phase2AssetCatalog.files(assets,ROOT),step);
    }
    public static String guideForPaths(List<String> paths,int step){
        for(String path:paths){
            if(!path.startsWith(ROOT+"/"))continue;
            String relative=path.substring(ROOT.length()+1),folder=Phase2AssetCatalog.normalize(relative.split("/")[0]);
            // The provided directory is P3_SCANNINGUIDE (one G); also accept the requested spelling.
            if(!Phase2AssetCatalog.image(path)||!(folder.equals("P3SCANNINGUIDE")||folder.equals("P3SCANNINGGUIDE")))continue;
            boolean installed=Phase2AssetCatalog.normalize(path).contains("INSTALLATION");
            if((step==1&&!installed)||(step>=4&&installed))return path;
        }
        return null;
    }
}
