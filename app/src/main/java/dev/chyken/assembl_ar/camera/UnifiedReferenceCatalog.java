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
        dev.chyken.assembl_ar.vision.RecognitionRequest r=new dev.chyken.assembl_ar.vision.RecognitionRequest(dev.chyken.assembl_ar.vision.RecognitionRequest.AssemblyExpectation.component(s.getComponentId()),c,null,null,p==2&&"brackets".equals(Phase2AssetCatalog.referenceRole(id))?4:p==2&&"motors".equals(Phase2AssetCatalog.referenceRole(id))?2:1,p);
        if(p==2){String role=Phase2AssetCatalog.referenceRole(id);return r.withPhase2Stage("brackets".equals(role)?1:"motors".equals(role)?2:5);}
        if(p==3)return r.withPhase3Stage(assembly(s)?4:1);
        if(p==5)return r.withPhase5Stage(assembly(s)?3:1);
        if(p==7){String role=Phase7AssetCatalog.referenceRole(id);return r.withPhase7Stage(role!=null&&role.startsWith("caster")?assembly(s)?3:1:assembly(s)?4:2);}
        if(p==8)return r.withPhase8Stage(assembly(s)?2:1);
        if(p==9){String role=Phase9AssetCatalog.referenceRole(id);return r.withPhase9Stage("standoff".equals(role)?1:assembly(s)?4:2);}
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
        String driver=Phase3AssetCatalog.referenceRole(s.getReferenceId());
        if(driver!=null)return "assembly".equals(driver);
        String nano=Phase5AssetCatalog.referenceRole(s.getReferenceId());
        if(nano!=null)return Phase5AssetCatalog.installed(nano);
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
