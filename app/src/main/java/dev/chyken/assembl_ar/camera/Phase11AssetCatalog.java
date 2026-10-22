package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import android.util.Log;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import java.util.*;
import java.io.*;
import java.security.*;

/** Raw Phase 11 photos; alignment guides and videos never enter recognition. */
public final class Phase11AssetCatalog {
    public static final String ROOT="references/Phase11",PREFIX="p11scene_";
    private Phase11AssetCatalog(){}
    public static String role(String folder){String n=Phase2AssetCatalog.normalize(folder);if(n.startsWith("ISTEP"))n=n.substring(1);
        switch(n){
            case "STEP111OLEDDISPLAYSCAN":case "STEP111OLEDSCAN":return "front";
            case "STEP111OLEDSCANINVALID":case "STEP111OLEDDISPLAYSCANINVALID":return "back";
            case "STEP112BATTERYSCAN":case "STEP112BATTERYPACKSCAN":return "battery";
            case "STEP113OLEDMOUNTEDTOPVIEW":case "STEP113OLEDASSEMBLYSCAN":return "assembly";
            default:return null;
        }
    }
    public static String referenceRole(String id){if(id==null||!id.startsWith(PREFIX))return null;int end=id.indexOf('_',PREFIX.length());return end<0?null:id.substring(PREFIX.length(),end);}
    public static boolean eligible(String role,int stage){return role!=null&&(stage==1?role.equals("front")||role.equals("back"):stage==2?role.equals("battery"):stage==3&&role.equals("assembly"));}
    public static String target(int stage){return stage==2?NeobotSeedData.BATTERY_PACK:NeobotSeedData.OLED_DISPLAY;}
    public static List<ComponentReferenceSpec> references(AssetManager assets){
        List<ComponentReferenceSpec> specs=referencesForPaths(Phase2AssetCatalog.files(assets,ROOT));Set<String> positives=new HashSet<>(),negativeRoles=new HashSet<>();
        for(ComponentReferenceSpec s:specs){String role=referenceRole(s.getReferenceId());if(role.equals("back"))negativeRoles.add("front");}
        for(ComponentReferenceSpec s:specs)if(negativeRoles.contains(referenceRole(s.getReferenceId())))positives.add(digest(assets,s.getJpegAssetPath()));
        List<ComponentReferenceSpec> result=new ArrayList<>();for(ComponentReferenceSpec s:specs){if(referenceRole(s.getReferenceId()).equals("back")&&positives.contains(digest(assets,s.getJpegAssetPath()))){Log.w("Phase11Assets","Skipping negative photo identical to a positive: "+s.getJpegAssetPath());continue;}result.add(s);}return Collections.unmodifiableList(result);
    }
    private static String digest(AssetManager assets,String path){try(InputStream in=assets.open(path)){MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);return Base64.getEncoder().encodeToString(md.digest());}catch(IOException|NoSuchAlgorithmException failure){throw new IllegalStateException("Cannot validate Phase 11 photo: "+path,failure);}}
    public static List<ComponentReferenceSpec> referencesForPaths(List<String> paths){List<ComponentReferenceSpec> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String path:paths){if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path)||!seen.add(path))continue;String role=role(path.substring(ROOT.length()+1).split("/")[0]);if(role==null)continue;
            int stage=role.equals("battery")?2:1;
            result.add(ComponentReferenceSpec.builder(PREFIX+role+"_"+path,target(stage),"OLED display and battery",stage==2?50:27,stage==2?35:27).jpegAssetPath(path).assemblyStepNumber(11).maximumImageDimension(1280).polarityAnchorsVerified(false).build());
        }return Collections.unmodifiableList(result);
    }
    private static final String[] VIDEO={"P11OLEDINSERTIONMP4","P11BATTERYPLACEMENTMP4"};
    public static List<String> videos(AssetManager assets,int index){return videosForPaths(Phase2AssetCatalog.files(assets,ROOT),index);}
    public static List<String> videosForPaths(List<String> paths,int index){List<String> result=new ArrayList<>();if(index<0||index>=VIDEO.length)return result;for(String path:paths){if(!path.startsWith(ROOT+"/"))continue;String[] p=path.substring(ROOT.length()+1).split("/");if(p.length==2&&Phase2AssetCatalog.normalize(p[0]).equals("P11VIDEOGUIDE")&&Phase2AssetCatalog.normalize(p[1]).replace("WEBM","MP4").equals(VIDEO[index]))result.add(path);}Collections.sort(result);return Collections.unmodifiableList(result);}
    public static String guide(AssetManager assets,int stage){return guideForPaths(Phase2AssetCatalog.files(assets,ROOT),stage);}
    public static String guideForPaths(List<String> paths,int stage){if(stage<1||stage>3)return null;String token=stage==1?"OLEDSCANNING":stage==2?"BATTERYPACK":"OLEDASSEMBLY";List<String> sorted=new ArrayList<>(paths);Collections.sort(sorted);for(String path:sorted)if(path.startsWith(ROOT+"/P11_SCANNINGGUIDE/")&&Phase2AssetCatalog.image(path)&&Phase2AssetCatalog.normalize(path.substring(path.lastIndexOf('/')+1)).contains(token))return path;return null;}
}
