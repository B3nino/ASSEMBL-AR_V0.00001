package dev.chyken.assembl_ar.camera;

import android.content.res.AssetManager;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import java.util.*;
import java.util.regex.*;

/** One registration graph. Consumers select views; they never author a second set of photos. */
public final class UnifiedReferenceCatalog {
    private UnifiedReferenceCatalog() { }
    public static List<ComponentReferenceSpec> build(AssemblArRepository repository, AssetManager assets) {
        List<ComponentReferenceSpec> base=VisionReferenceFactory.buildRegistered(repository,assets);
        return mergeRegistered(base);
    }
    public static List<ComponentReferenceSpec> mergeRegistered(List<ComponentReferenceSpec> base){
        Map<String,ComponentReferenceSpec> all=new LinkedHashMap<>();
        for(ComponentReferenceSpec s:base)all.put(s.getReferenceId(),s);
        for(ComponentReferenceSpec s:IndividualPartTestCatalog.filter(base))all.putIfAbsent(s.getReferenceId(),s);
        return Collections.unmodifiableList(new ArrayList<>(all.values()));
    }
    public static dev.chyken.assembl_ar.vision.RecognitionRequest request(ComponentReferenceSpec s,dev.chyken.assembl_ar.vision.CameraCalibration c){
        String id=s.getReferenceId();int p=phase(s);
        String role2=Phase2AssetCatalog.referenceRole(id),role7=Phase7AssetCatalog.referenceRole(id),role9=Phase9AssetCatalog.referenceRole(id);
        int count=p==2&&"brackets".equals(role2)?4:p==2&&"motors".equals(role2)||p==7&&("trackers".equals(role7)||"trackerbad".equals(role7))||p==9&&("wheel".equals(role9)||"pair".equals(role9))?2:1;
        dev.chyken.assembl_ar.vision.RecognitionRequest r=new dev.chyken.assembl_ar.vision.RecognitionRequest(dev.chyken.assembl_ar.vision.RecognitionRequest.AssemblyExpectation.component(s.getComponentId()),c,null,null,count,p);
        if(id.startsWith("video1011_")&&s.getAssemblyStepNumber()==0)return r;
        if(id.startsWith("video789_")&&s.getAssemblyStepNumber()==0)return r;
        if(p==2){String role=Phase2AssetCatalog.referenceRole(id);if(role==null)return r;int stage="brackets".equals(role)?1:"motors".equals(role)?2:5;r=r.withPhase2Stage(stage);return stage==5?r.withPhase2VerificationView("left".equals(role)?"left":"right".equals(role)?"right":"top"):r;}
        if(p==3)return s.getReferenceId().startsWith("video35_")?r:r.withPhase3Stage(assembly(s)?4:1);
        if(p==5)return s.getReferenceId().startsWith("video35_")?r:r.withPhase5Stage(assembly(s)?3:1);
        if(p==7){String role=Phase7AssetCatalog.referenceRole(id);return r.withPhase7Stage(role!=null&&role.startsWith("caster")?assembly(s)?3:1:assembly(s)?4:2);}
        if(p==8)return r.withPhase8Stage(assembly(s)?2:1);
        if(p==9){String role=Phase9AssetCatalog.referenceRole(id);return r.withPhase9Stage("standoff".equals(role)?1:"wheel".equals(role)||"pair".equals(role)?2:4);}
        if(p==10){String role=Phase10AssetCatalog.referenceRole(id);return r.withPhase10Stage("cable".equals(role)?1:role!=null&&role.startsWith("extension")?2:role!=null&&role.startsWith("protector")?3:"protected".equals(role)?4:5);}
        if(p==11){String role=Phase11AssetCatalog.referenceRole(id);return r.withPhase11Stage("battery".equals(role)?2:"assembly".equals(role)?3:1);}
        return r;
    }
    public static int phase(ComponentReferenceSpec s) {
        String path=s.getJpegAssetPath();
        Matcher m=Pattern.compile("(?i)(?:references/Phase|^p)(\\d+)").matcher(path==null?s.getReferenceId():path);
        if(m.find()) {int p=Integer.parseInt(m.group(1));if(p>=1&&p<=11)return p;}
        // Unversioned manual sections are not necessarily current phase numbers.
        return 0;
    }
    public static boolean negative(ComponentReferenceSpec s) {
        String text=(s.getReferenceId()+" "+s.getJpegAssetPath()).toUpperCase(Locale.ROOT);
        return text.contains("INVALID")||!assembly(s)&&(s.isBottomFace()||text.contains("UNDERSIDE"));
    }
    public static boolean assembly(ComponentReferenceSpec s) {
        // Isolated, reviewed crops live beside their phase's scene photographs.
        // Folder names must not turn a loose part into an installed assembly.
        if(s.getAssemblyStepNumber()==0&&(s.getReferenceId().startsWith("video12_")||s.getReferenceId().startsWith("video35_")||s.getReferenceId().startsWith("video789_")||s.getReferenceId().startsWith("video1011_")))return false;
        String p2=Phase2AssetCatalog.referenceRole(s.getReferenceId());
        // Phase 2 scene photos include contextual multi-part inventory groups;
        // a group must not become one giant standalone-component homography.
        if(p2!=null)return true;
        String driver=Phase3AssetCatalog.referenceRole(s.getReferenceId());
        if(driver!=null)return Phase3AssetCatalog.installed(driver);
        String nano=Phase5AssetCatalog.referenceRole(s.getReferenceId());
        if(nano!=null)return Phase5AssetCatalog.installed(nano);
        String p7=Phase7AssetCatalog.referenceRole(s.getReferenceId());
        if(p7!=null)return Phase7AssetCatalog.installed(p7);
        String path=String.valueOf(s.getJpegAssetPath()).toUpperCase(Locale.ROOT);
        return s.getAssemblyStepNumber()>0&&path.contains("REFERENCES/ASSEMBLY/")||path.contains("STEP9_2DUALWHEELS")||path.contains("STEP2_1_BRACKETS")||path.contains("STEP2_2_MOTORSCANS")||path.contains("ASSEMBLYSCAN")||path.contains("HARDWARE")||path.contains("MOUNTED")
                ||path.contains("BOTTOMSCANS")||path.contains("DUALTRACKER SCANS")||path.contains("MOUNTINGSCAN")
                ||path.contains("BARE_BOARD_INVALID");
    }
    public static List<ComponentReferenceSpec> individual(List<ComponentReferenceSpec> all) {
        List<ComponentReferenceSpec> result=new ArrayList<>();
        for(ComponentReferenceSpec s:all) if(!assembly(s))result.add(s);
        return Collections.unmodifiableList(result);
    }
}
