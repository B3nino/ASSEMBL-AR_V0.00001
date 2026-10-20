package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import java.util.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;

/** Phase 5 photo roles are isolated from guide artwork and media assets. */
public final class Phase5AssetCatalog {
    public static final String ROOT="references/Phase5",PREFIX="p5scene_";
    private Phase5AssetCatalog(){}
    public static String role(String folder){
        switch(Phase2AssetCatalog.normalize(folder)){
            case "STEP51MICROCONTROLLERSCAN":return "front";
            case "MICROCONTROLLERBOTTOMINVALID":return "back";
            case "STEP53HARDWARESCAN":return "assembly";
            case "STEP53HARDWARESCANINVALIDS":return "invalid";
            default:return null;
        }
    }
    public static String referenceRole(String id){
        if(id==null||!id.startsWith(PREFIX))return null;
        int end=id.indexOf('_',PREFIX.length());return end<0?null:id.substring(PREFIX.length(),end);
    }
    public static boolean installed(String role){return "assembly".equals(role)||"invalid".equals(role);}
    public static List<ComponentReferenceSpec> references(AssetManager assets){return referencesForPaths(Phase2AssetCatalog.files(assets,ROOT));}
    public static List<ComponentReferenceSpec> referencesForPaths(List<String> paths){
        List<ComponentReferenceSpec> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String path:paths){
            if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path)||!seen.add(path))continue;
            String role=role(path.substring(ROOT.length()+1).split("/")[0]);if(role==null)continue;
            // This is the Nano footprint; the full assembly photo is context, not a 45mm board.
            result.add(ComponentReferenceSpec.builder(PREFIX+role+"_"+path,NeobotSeedData.MICROCONTROLLER,"Arduino Nano",18,45)
                    .jpegAssetPath(path).assemblyStepNumber(5).maximumImageDimension(1280).polarityAnchorsVerified(false).build());
        }
        return Collections.unmodifiableList(result);
    }
    public static List<String> videos(AssetManager assets){return videosForPaths(Phase2AssetCatalog.files(assets,ROOT));}
    public static List<String> videosForPaths(List<String> paths){
        SortedSet<String> result=new TreeSet<>();
        for(String path:paths){
            if(!path.startsWith(ROOT+"/"))continue;
            String folder=path.substring(ROOT.length()+1).split("/")[0],lower=path.toLowerCase(Locale.ROOT);
            if(Phase2AssetCatalog.normalize(folder).equals("P5VIDEOGUIDE")&&(lower.endsWith(".mp4")||lower.endsWith(".webm")))result.add(path);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
    public static String guide(AssetManager assets,int step){return guideForPaths(Phase2AssetCatalog.files(assets,ROOT),step);}
    public static String guideForPaths(List<String> paths,int step){
        if(step!=1&&step!=3)return null;
        List<String> sorted=new ArrayList<>(paths);Collections.sort(sorted);
        for(String path:sorted){
            if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path))continue;
            String folder=path.substring(ROOT.length()+1).split("/")[0];
            if(!Phase2AssetCatalog.normalize(folder).equals("P5SCANNINGGUIDE"))continue;
            boolean hardware=Phase2AssetCatalog.normalize(path).contains("HARDWARE");
            if(step==3?hardware:!hardware)return path;
        }
        return null;
    }
}
