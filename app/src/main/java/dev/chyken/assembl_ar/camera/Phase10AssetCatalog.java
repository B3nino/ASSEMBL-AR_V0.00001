package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import android.util.Log;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import java.util.*;
import java.io.*;
import java.security.*;

/** Raw Phase 10 photos; alignment guides and videos never enter recognition. */
public final class Phase10AssetCatalog {
    public static final String ROOT="references/Phase10",PREFIX="p10scene_";
    private Phase10AssetCatalog(){}
    public static String role(String folder){String n=Phase2AssetCatalog.normalize(folder);if(n.startsWith("ISTEP"))n=n.substring(1);
        switch(n){
            case "STEP101DATACABLESCANNING":return "cable";
            case "STEP101EXTENSIONBOARDSCAN":return "extension";
            case "STEP101EXTENSIONBOARDSCANINVALID":return "extensionbad";
            case "STEP101OLEDPROTECTORSCAN":return "protector";
            case "STEP101OLEDPROTECTORSCANINVALID":return "protectorbad";
            case "STEP103OLEDPROTECTORASSEMBLYSCAN":return "protected";
            case "STEP104EXTENSIONBOARDMOUNTINGSCAN":return "stacked";
            default:return null;
        }
    }
    public static String referenceRole(String id){if(id==null||!id.startsWith(PREFIX))return null;int end=id.indexOf('_',PREFIX.length());return end<0?null:id.substring(PREFIX.length(),end);}
    public static boolean eligible(String role,int stage){return role!=null&&(stage==1?role.equals("cable"):stage==2?role.equals("extension")||role.equals("extensionbad"):stage==3?role.equals("protector")||role.equals("protectorbad"):stage==4?role.equals("protected"):stage==5&&role.equals("stacked"));}
    public static String target(int stage){return stage==1?NeobotSeedData.DATA_CABLE:stage==3?NeobotSeedData.OLED_PROTECTOR:NeobotSeedData.EXTENSION_BOARD;}
    public static List<ComponentReferenceSpec> references(AssetManager assets){
        List<ComponentReferenceSpec> specs=referencesForPaths(Phase2AssetCatalog.files(assets,ROOT));Set<String> positives=new HashSet<>(),negativeRoles=new HashSet<>();
        for(ComponentReferenceSpec s:specs){String role=referenceRole(s.getReferenceId());if(role.endsWith("bad"))negativeRoles.add(role.substring(0,role.length()-3));}
        for(ComponentReferenceSpec s:specs)if(negativeRoles.contains(referenceRole(s.getReferenceId())))positives.add(digest(assets,s.getJpegAssetPath()));
        List<ComponentReferenceSpec> result=new ArrayList<>();for(ComponentReferenceSpec s:specs){if(referenceRole(s.getReferenceId()).endsWith("bad")&&positives.contains(digest(assets,s.getJpegAssetPath()))){Log.w("Phase10Assets","Skipping negative photo identical to a positive: "+s.getJpegAssetPath());continue;}result.add(s);}return Collections.unmodifiableList(result);
    }
    private static String digest(AssetManager assets,String path){try(InputStream in=assets.open(path)){MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);return Base64.getEncoder().encodeToString(md.digest());}catch(IOException|NoSuchAlgorithmException failure){throw new IllegalStateException("Cannot validate Phase 10 photo: "+path,failure);}}
    public static List<ComponentReferenceSpec> referencesForPaths(List<String> paths){List<ComponentReferenceSpec> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String path:paths){if(!path.startsWith(ROOT+"/")||!Phase2AssetCatalog.image(path)||!seen.add(path))continue;String role=role(path.substring(ROOT.length()+1).split("/")[0]);if(role==null)continue;
            int stage=role.equals("cable")?1:role.startsWith("protector")?3:2;
            result.add(ComponentReferenceSpec.builder(PREFIX+role+"_"+path,target(stage),"Extension board installation",stage==1?150:stage==3?40:100,stage==1?20:stage==3?50:180).jpegAssetPath(path).assemblyStepNumber(10).maximumImageDimension(1280).polarityAnchorsVerified(false).build());
        }return Collections.unmodifiableList(result);
    }
    private static final String[] VIDEO={"P10DATACABLEMAINBOARDCONNECTIONMP4","P10OLEDPROTECTORASSEMBLYMP4","P10EXTENSIONBOARDANDMAINBOARDCONNECTIONMP4","P10LASTEXTENSIONCABLECONNECTIONMP4"};
    public static List<String> videos(AssetManager assets,int index){return videosForPaths(Phase2AssetCatalog.files(assets,ROOT),index);}
    public static List<String> videosForPaths(List<String> paths,int index){List<String> result=new ArrayList<>();if(index<0||index>=VIDEO.length)return result;for(String path:paths){if(!path.startsWith(ROOT+"/"))continue;String[] p=path.substring(ROOT.length()+1).split("/");if(p.length==2&&Phase2AssetCatalog.normalize(p[0]).equals("P10VIDEOGUIDE")&&Phase2AssetCatalog.normalize(p[1]).replace("WEBM","MP4").equals(VIDEO[index]))result.add(path);}Collections.sort(result);return Collections.unmodifiableList(result);}
    public static String guide(AssetManager assets,int stage){return guideForPaths(Phase2AssetCatalog.files(assets,ROOT),stage);}
    public static String guideForPaths(List<String> paths,int stage){String[] tokens={"DATACABLE","FOREXTENSIONBOARD","OLEDPROTECTORSCANNING","OLEDPROTECTORASSEMBLY","MAINBOARDANDEXTENSIONBOARD"};if(stage<1||stage>5)return null;List<String> sorted=new ArrayList<>(paths);Collections.sort(sorted);for(String path:sorted)if(path.startsWith(ROOT+"/P10_SCANNINGGUIDE/")&&Phase2AssetCatalog.image(path)&&Phase2AssetCatalog.normalize(path.substring(path.lastIndexOf('/')+1)).contains(tokens[stage-1]))return path;return null;}
}
