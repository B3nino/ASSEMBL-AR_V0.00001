package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import java.util.*;
import java.io.*;
import java.security.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;

/** Discovers raw captures; preview artwork, scanning guides and movies never become ORB targets. */
public final class Phase8AssetCatalog {
    public static final String ROOT="references/Phase8",PREFIX="p8scene_";
    private Phase8AssetCatalog(){}
    public static String role(String folder){switch(Phase2AssetCatalog.normalize(folder)){
        case "STEP81SCANDISTANCESENSOR":return "front";
        case "STEP81SCANDISTANCESENSORINVALID":return "back";
        case "STEP82DISTANCESENSORHARDWARESCAN":return "assembly";
        case "STEP82DISTANCESENSORHARDWARESCANINVALID":return "invalid";
        default:return null;
    }}
    public static String referenceRole(String id){if(id==null||!id.startsWith(PREFIX))return null;int end=id.indexOf('_',PREFIX.length());return end<0?null:id.substring(PREFIX.length(),end);}
    public static boolean installed(String role){return "assembly".equals(role)||"invalid".equals(role);}
    public static List<ComponentReferenceSpec> references(AssetManager assets){
        List<ComponentReferenceSpec> result=new ArrayList<>();
        for(ComponentReferenceSpec s:referencesForPaths(Phase2AssetCatalog.files(assets,ROOT))){
            String path=s.getJpegAssetPath();
            if("invalid".equals(referenceRole(s.getReferenceId()))&&path.endsWith("/DISTANCESENSORWRONGORIENTATIONTOPVIEW (15).jpg")){
                try(InputStream input=assets.open(path)){
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[8192];int n;
                    while((n=input.read(block))!=-1)digest.update(block,0,n);
                    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
                    if(conflictingOrientationPhoto(path,hex.toString()))continue;
                }catch(IOException|NoSuchAlgorithmException failure){throw new IllegalStateException("Cannot review sensor reference: "+path,failure);}
            }
            result.add(s);
        }
        return Collections.unmodifiableList(result);
    }
    /** This supplied photo has outward-facing eyes despite its invalid-folder label.
     * A replacement image at the same path is automatically re-enabled when its bytes change. */
    public static boolean conflictingOrientationPhoto(String path,String sha256){
        return (ROOT+"/STEP8_2DISTANCESENSORHARDWARESCAN_INVALID/DISTANCESENSORWRONGORIENTATIONTOPVIEW (15).jpg").equals(path)
                &&"365e397da8047f3fcd4c0c01308a8fa97b231e623a5d301d152038cfa206cdce".equals(sha256);
    }
    public static boolean inwardReference(String path){
        if(path==null)return false;String name=Phase2AssetCatalog.normalize(path.substring(path.lastIndexOf('/')+1));
        return name.contains("WRONGORIENTATION")||name.contains("INWARD");
    }
    public static List<ComponentReferenceSpec> referencesForPaths(List<String> paths){
        List<ComponentReferenceSpec> result=new ArrayList<>();Set<String> seen=new HashSet<>();for(String path:paths){
            if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path)||!seen.add(path))continue;
            String role=role(path.substring(ROOT.length()+1).split("/")[0]);if(role==null)continue;
            ComponentReferenceSpec reviewed=Phase789VideoReferences.find(path);if(reviewed!=null){result.add(reviewed);continue;}
            result.add(ComponentReferenceSpec.builder(PREFIX+role+"_"+path,NeobotSeedData.DISTANCE_SENSOR,"Ultrasonic distance sensor",45,20)
                .jpegAssetPath(path).assemblyStepNumber(8).maximumImageDimension(1280).polarityAnchorsVerified(false).build());
        }return Collections.unmodifiableList(result);
    }
    public static List<String> videos(AssetManager assets,boolean routing){return videosForPaths(Phase2AssetCatalog.files(assets,ROOT),routing);}
    public static List<String> videosForPaths(List<String> paths,boolean routing){
        SortedSet<String> result=new TreeSet<>();for(String path:paths){if(!path.startsWith(ROOT+"/"))continue;String[] p=path.substring(ROOT.length()+1).split("/");String name=Phase2AssetCatalog.normalize(path),lower=path.toLowerCase(Locale.ROOT);
            if(p.length<2||!Phase2AssetCatalog.normalize(p[0]).equals("P8VIDEOGUIDE")||!(lower.endsWith(".mp4")||lower.endsWith(".webm")))continue;
            if(routing?name.contains("ARDUINOTOLINETRACKERS"):name.contains("DISTANCESENSORMOUNT")||name.contains("DISTANCESENSORPLACEMENT"))result.add(path);
        }return Collections.unmodifiableList(new ArrayList<>(result));
    }
    public static String guide(AssetManager assets,boolean installed){return guideForPaths(Phase2AssetCatalog.files(assets,ROOT),installed);}
    public static String guideForPaths(List<String> paths,boolean installed){List<String> sorted=new ArrayList<>(paths);Collections.sort(sorted);for(String path:sorted){if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path))continue;String[] p=path.substring(ROOT.length()+1).split("/");if(p.length>1&&Phase2AssetCatalog.normalize(p[0]).equals("P8SCANNINGGUIDE")&&Phase2AssetCatalog.normalize(p[1]).contains("HARDWARE")==installed)return path;}return null;}
}
