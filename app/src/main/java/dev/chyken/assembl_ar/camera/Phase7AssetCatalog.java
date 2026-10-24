package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import java.util.*;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

/** Raw Phase 7 photographs, guide artwork and explicitly ordered video segments. */
public final class Phase7AssetCatalog {
    public static final String ROOT="references/Phase7",PREFIX="p7scene_";
    private Phase7AssetCatalog(){}
    public static String role(String folder){
        switch(Phase2AssetCatalog.normalize(folder)){
            case "STEP71CASTERWHEEL":case "STEP71CASTERWHEELSCANS":return "caster";
            case "STEP71CASTERWHEELSCANSINVALID":return "casterbad";
            case "STEP72LINETRACKERS":case "STEP72LINETRACKERSSCANS":case "IRTRACKERSINDIVID":return "trackers";
            case "IRTRACKERSINDIVIDINVALID":return "trackerbad";
            case "STEP73CASTERBOTTOMSCANS":return "casterwork";
            case "STEP73CASTERBOTTOMSCANSINVALID":return "casterworkbad";
            case "STEP7TOPVIEW":case "STEP74DUALTRACKERSCANSVALIDS":return "trackerwork";
            case "STEP74DUALTRACKERSCANSINVALID":return "trackerworkbad";
            default:return null;
        }
    }
    public static String referenceRole(String id){if(id==null||!id.startsWith(PREFIX))return null;int end=id.indexOf('_',PREFIX.length());return end<0?null:id.substring(PREFIX.length(),end);}
    public static boolean installed(String role){return role!=null&&role.contains("work");}
    public static boolean negative(String role){return role!=null&&role.endsWith("bad");}
    public static boolean eligible(String role,int step){return step==1?role!=null&&role.startsWith("caster")&&!installed(role):step==2?"trackers".equals(role)||"trackerbad".equals(role):step==3?role!=null&&role.startsWith("casterwork"):step==4&&role!=null&&role.startsWith("trackerwork");}
    public static List<ComponentReferenceSpec> references(AssetManager assets){return referencesForPaths(Phase2AssetCatalog.files(assets,ROOT));}
    public static List<ComponentReferenceSpec> referencesForPaths(List<String> paths){
        List<ComponentReferenceSpec> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String path:paths){
            if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path)||!seen.add(path))continue;
            String role=role(path.substring(ROOT.length()+1).split("/")[0]);if(role==null)continue;
            ComponentReferenceSpec reviewed=Phase789VideoReferences.find(path);if(reviewed!=null){result.add(reviewed);continue;}
            String component=installed(role)?MAIN_BOARD:role.startsWith("caster")?CASTER_WHEEL:LINE_TRACKER;
            result.add(ComponentReferenceSpec.builder(PREFIX+role+"_"+path,component,installed(role)?"Bottom-view installation":role.startsWith("caster")?"Caster wheel":"Line tracker",
                    installed(role)?100:role.startsWith("caster")?48:14,installed(role)?120:role.startsWith("caster")?24:50)
                .jpegAssetPath(path).assemblyStepNumber(7).maximumImageDimension(1280).polarityAnchorsVerified(false).build());
        }return Collections.unmodifiableList(result);
    }
    public static int clipOrder(String path){
        String name=Phase2AssetCatalog.normalize(path.substring(path.lastIndexOf('/')+1));
        if(name.contains("WHERETOPLACESCREWSFORCASTER"))return 0;
        if(name.contains("ASSEMBLYOFWHEELCASTER"))return 1;
        if(name.contains("WHERETOINSERTSCREWSFORDUALTRACKERS"))return 2;
        if(name.contains("LINETRACKERASSEMBLY"))return 3;
        return -1;
    }
    public static List<String> videos(AssetManager assets,int step){return videosForPaths(Phase2AssetCatalog.files(assets,ROOT),step);}
    public static List<String> videosForPaths(List<String> paths,int step){
        SortedMap<Integer,String> clips=new TreeMap<>();
        for(String p:paths){if(!p.startsWith(ROOT+"/"))continue;String folder=p.substring(ROOT.length()+1).split("/")[0],lower=p.toLowerCase(Locale.ROOT);
            int order=clipOrder(p);if(Phase2AssetCatalog.normalize(folder).equals("P7VIDEOGUIDE")&&(lower.endsWith(".mp4")||lower.endsWith(".webm"))&&order>=0&&(step==3?order<2:step==4&&order>=2))clips.putIfAbsent(order,p);}
        return Collections.unmodifiableList(new ArrayList<>(clips.values()));
    }
    public static String guide(AssetManager assets,int step){return guideForPaths(Phase2AssetCatalog.files(assets,ROOT),step);}
    public static String guideForPaths(List<String> paths,int step){
        if(step<1||step>4)return null;
        List<String> sorted=new ArrayList<>(paths);Collections.sort(sorted);
        for(String p:sorted){if(!p.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(p))continue;
            String folder=Phase2AssetCatalog.normalize(p.substring(ROOT.length()+1).split("/")[0]);
            if(!folder.equals("P7SCANNINGGUIDE")&&!folder.equals("P7SCANNINGUIDE"))continue;
            String name=Phase2AssetCatalog.normalize(p.substring(p.lastIndexOf('/')+1));boolean caster=name.contains("CASTER"),assembly=name.contains("ASSEMBLY");
            if((step==1||step==3)==caster&&(step>=3)==assembly)return p;
        }return null;
    }
}

