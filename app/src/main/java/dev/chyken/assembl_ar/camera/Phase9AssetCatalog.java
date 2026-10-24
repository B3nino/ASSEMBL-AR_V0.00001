package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import java.util.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;

/** Raw photos only; guides and movies remain guidance assets. */
public final class Phase9AssetCatalog {
    public static final String ROOT="references/Phase9",PREFIX="p9scene_";
    private Phase9AssetCatalog(){}
    public static String role(String folder){switch(Phase2AssetCatalog.normalize(folder)){
        case "STEP91STANDOFFSCANNING":return "standoff";
        case "STEP92DUALWHEELS":return "pair";
        case "WHEELSREF":return "wheel";
        case "STEP94ASSEMBLYSCAN":return "assembly";
        case "STEP94ASSEMBLYSCANINVALID":return "invalid";
        default:return null;
    }}
    public static String referenceRole(String id){if(id==null||!id.startsWith(PREFIX))return null;int end=id.indexOf('_',PREFIX.length());return end<0?null:id.substring(PREFIX.length(),end);}
    public static boolean installed(String role){return "assembly".equals(role)||"invalid".equals(role);}
    public static List<ComponentReferenceSpec> references(AssetManager assets){return referencesForPaths(Phase2AssetCatalog.files(assets,ROOT));}
    public static List<ComponentReferenceSpec> referencesForPaths(List<String> paths){
        List<ComponentReferenceSpec> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String path:paths){if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path)||!seen.add(path))continue;
            String role=role(path.substring(ROOT.length()+1).split("/")[0]);if(role==null)continue;
            ComponentReferenceSpec reviewed=Phase789VideoReferences.find(path);if(reviewed!=null){result.add(reviewed);continue;}
            String id=role.equals("standoff")?NeobotSeedData.STANDOFF:installed(role)?NeobotSeedData.MAIN_BOARD:NeobotSeedData.WHEEL;
            result.add(ComponentReferenceSpec.builder(PREFIX+role+"_"+path,id,"Standoff and wheels",installed(role)?180:role.equals("standoff")?5:65,installed(role)?180:role.equals("standoff")?40:65)
                .jpegAssetPath(path).assemblyStepNumber(9).maximumImageDimension(1280).polarityAnchorsVerified(false).build());
        }return Collections.unmodifiableList(result);
    }
    public static List<String> videos(AssetManager assets,boolean wheels){return videosForPaths(Phase2AssetCatalog.files(assets,ROOT),wheels);}
    public static List<String> videosForPaths(List<String> paths,boolean wheels){
        List<String> result=new ArrayList<>();for(String path:paths){if(!path.startsWith(ROOT+"/"))continue;String[] p=path.substring(ROOT.length()+1).split("/");String lower=path.toLowerCase(Locale.ROOT);
            if(p.length<2||!Phase2AssetCatalog.normalize(p[0]).equals("P9VIDEOGUIDE")||!(lower.endsWith(".mp4")||lower.endsWith(".webm")))continue;
            if(Phase2AssetCatalog.normalize(p[1]).contains(wheels?"WHEELSPLACEMENT":"STANDOFFPLACEMENT"))result.add(path);
        }Collections.sort(result);return Collections.unmodifiableList(result);
    }
    public static String guide(AssetManager assets,int stage){return guideForPaths(Phase2AssetCatalog.files(assets,ROOT),stage);}
    public static String guideForPaths(List<String> paths,int stage){
        List<String> sorted=new ArrayList<>(paths);Collections.sort(sorted);
        // A no-wheel chassis is correct at 9.3, before the wheel demo. Reuse that supplied
        // photo as guidance rather than showing already-mounted wheels prematurely.
        if(stage==3)for(String path:sorted)if(path.startsWith(ROOT+"/STEP9_4ASSEMBLYSCANINVALID/")&&Phase2AssetCatalog.image(path)&&Phase2AssetCatalog.normalize(path).contains("INVALIDNOWHEELS"))return path;
        String token=stage==1?"STANDOFF":stage==2?"2XWHEELS":"TOPVIEWWHEELS";
        for(String path:sorted){if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path))continue;String[] p=path.substring(ROOT.length()+1).split("/");
            if(p.length>1&&Phase2AssetCatalog.normalize(p[0]).equals("P9SCANNINGGUIDE")&&Phase2AssetCatalog.normalize(p[1]).contains(token))return path;
        }return null;
    }
}
