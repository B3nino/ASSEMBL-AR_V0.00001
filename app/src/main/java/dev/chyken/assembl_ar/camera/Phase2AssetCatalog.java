package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import java.io.IOException;
import java.util.*;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

/** Discovers camera photos, alignment artwork and ordered offline clips independently. */
public final class Phase2AssetCatalog {
    public static final String ROOT="references/Phase2";
    public static final String PREFIX="p2scene_";
    private static final Map<AssetManager,Map<String,List<String>>> CACHE=new WeakHashMap<>();
    private Phase2AssetCatalog() { }
    public static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
    }
    public static List<String> files(AssetManager assets,String root) {
        if(assets==null)return Collections.emptyList();
        synchronized(CACHE){Map<String,List<String>> roots=CACHE.get(assets);if(roots!=null&&roots.containsKey(root))return roots.get(root);}
        List<String> result=new ArrayList<>();
        walk(assets,root,result,0); Collections.sort(result);result=Collections.unmodifiableList(result);
        synchronized(CACHE){CACHE.computeIfAbsent(assets,key->new HashMap<>()).put(root,result);}return result;
    }
    private static void walk(AssetManager assets,String root,List<String> result,int depth) {
        if(assets==null || depth>8) return;
        try {
            String[] children=assets.list(root);
            if(children==null || children.length==0) { result.add(root); return; }
            for(String child:children) walk(assets,root+"/"+child,result,depth+1);
        } catch(IOException failure) { android.util.Log.w("Phase2Assets","Cannot list "+root,failure); }
    }
    public static boolean image(String path) {
        String s=path.toLowerCase(Locale.ROOT); return s.endsWith(".jpg")||s.endsWith(".jpeg")||s.endsWith(".png");
    }
    /** Stable role tags are also used by the matcher; no scene photograph becomes an AR pose. */
    public static String role(String folder) {
        String n=normalize(folder);
        if(n.equals("STEP21BRACKETS")||n.equals("BRACKETSREFERENCE")) return "brackets";
        if(n.equals("STEP22MOTORSCANS")||n.equals("MOTORSREFERENCE")) return "motors";
        if(n.equals("STEP25HARDWARECHECKLEFTSIDE")) return "left";
        if(n.equals("STEP25HARDWARECHECKRIGHTSIDE")) return "right";
        if(n.equals("STEP25HARDWARECHECKTOPVIEW")) return "top";
        if(n.equals("STEP25HARDWARECHECKINVALIDSCANS")) return "invalid";
        return null;
    }
    public static List<ComponentReferenceSpec> references(AssetManager assets) {
        return referencesForPaths(files(assets,ROOT));
    }
    static List<ComponentReferenceSpec> referencesForPaths(List<String> paths) {
        List<ComponentReferenceSpec> result=new ArrayList<>();
        for(String path:paths) {
            if(!image(path)) continue;
            String relative=path.substring(ROOT.length()+1);
            String group=role(relative.split("/")[0]);
            if(group==null||group.equals("invalid")) continue;
            String id=group.equals("brackets")?MOTOR_BRACKET:group.equals("motors")?GEAR_MOTOR:MAIN_BOARD;
            result.add(ComponentReferenceSpec.builder(PREFIX+group+"_"+path,id,id,
                    group.equals("brackets")?25:group.equals("motors")?70:100,
                    group.equals("brackets")?35:group.equals("motors")?25:120)
                    .jpegAssetPath(path).assemblyStepNumber(2).maximumImageDimension(960)
                    .polarityAnchorsVerified(false).build());
        }
        return Collections.unmodifiableList(result);
    }
    public static String referenceRole(String id) {
        if(id==null || !id.startsWith(PREFIX)) return null;
        return id.substring(PREFIX.length(),id.indexOf('_',PREFIX.length()));
    }
    public static List<String> videos(AssetManager assets) {
        List<String> clips=new ArrayList<>();
        for(String p:files(assets,ROOT)) {
            String n=normalize(p.substring(ROOT.length()+1).split("/")[0]);
            String lower=p.toLowerCase(Locale.ROOT);
            if(n.equals("P2VIDEOGUIDE")&&(lower.endsWith(".mp4")||lower.endsWith(".webm"))) clips.add(p);
        }
        clips.sort(Comparator.comparingInt(Phase2AssetCatalog::clipOrder).thenComparing(p->p));
        return Collections.unmodifiableList(clips);
    }
    public static int clipOrder(String path) {
        String n=normalize(path.substring(path.lastIndexOf('/')+1));
        if(n.contains("BRACKET")||n.contains("CLIP1")) return 0;
        if(n.contains("INWARD")||n.contains("CLIP2")) return 1;
        if(n.contains("SCREW")||n.contains("CLIP3")) return 2;
        return 3;
    }
    public static String guide(AssetManager assets,int phase,int step) {
        return guide(assets,phase,step,"top");
    }
    public static String guide(AssetManager assets,int phase,int step,String hardwareView) {
        String root=phase==1?"references/Phase1/P1_SCANNINGGUIDE":ROOT;
        return guideForPaths(files(assets,root),phase,step,hardwareView);
    }
    static String guideForPaths(List<String> paths,int phase,int step,String hardwareView) {
        for(String p:paths) {
            if(!image(p)) continue;
            if(phase==1) return p;
            String parent=p.substring(ROOT.length()+1).split("/")[0];
            if(!normalize(parent).equals("P2SCANNINGGUIDE")) continue;
            String n=normalize(p);
            // Match side tokens in filenames, including the supplied RIIGHTT spelling.
            String side=n.replace("RIIGHTT","RIGHT");
            if(step==1&&n.contains("BRACKET")||step==2&&n.contains("MOTOR")&&!n.contains("LEFT")&&!side.contains("RIGHT")
                    ||step==5&&("left".equals(hardwareView)?n.contains("LEFT")
                    :"right".equals(hardwareView)?side.contains("RIGHT"):n.contains("ASSEMBLY")||n.contains("TOP"))) return p;
        }
        return null;
    }
}
