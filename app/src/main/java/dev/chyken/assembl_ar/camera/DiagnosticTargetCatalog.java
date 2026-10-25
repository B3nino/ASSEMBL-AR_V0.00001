package dev.chyken.assembl_ar.camera;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.*;
import java.util.*;
import java.util.function.ToIntFunction;

/** Selectable identities and verification states over the shared prepared photo library. */
public final class DiagnosticTargetCatalog {
    private DiagnosticTargetCatalog() { }
    public static final class Target {
        public final String key,title,componentId,category;
        public final boolean assembly,specialized;
        public final ComponentReferenceSpec representative;
        public final List<ComponentReferenceSpec> references;
        public final int keypoints;
        private Target(String key,String title,boolean assembly,boolean specialized,List<ComponentReferenceSpec> refs,ToIntFunction<String> counts){
            this.key=key;this.title=title;this.assembly=assembly;this.specialized=specialized;
            category=assembly?"Assembly verification":specialized?"Hardware groups":"Individual components";
            references=Collections.unmodifiableList(new ArrayList<>(refs));representative=refs.get(0);componentId=representative.getComponentId();int sum=0;for(ComponentReferenceSpec s:refs)sum+=Math.max(0,counts.applyAsInt(s.getReferenceId()));keypoints=sum;
        }
        public String summary(){return category+" · "+references.size()+" references · "+keypoints+" keypoints";}
        public boolean matches(String query){String text=(title+" "+category+" "+componentId).toLowerCase(Locale.ROOT);for(String term:query.toLowerCase(Locale.ROOT).trim().split("\\s+"))if(!text.contains(term))return false;return true;}
        public RecognitionRequest request(CameraCalibration calibration){return specialized?UnifiedReferenceCatalog.request(representative,calibration):IndividualPartTestCatalog.requestFor(componentId,calibration);}
        @Override public String toString(){return title+"\n"+summary();}
    }
    public static boolean realPhoto(ComponentReferenceSpec s){
        if(s.isAdministratorReference())return true;
        String p=s.getJpegAssetPath();if(p==null)return false;
        // Learning-page crops and illustrations remain available in Learn, not as test targets.
        return !p.startsWith("references/learning/")&&!p.startsWith("references/instructional/")&&!p.startsWith("references/variants/")&&!p.startsWith("schematics/");
    }
    public static List<Target> build(List<ComponentReferenceSpec> all,ToIntFunction<String> counts){
        Map<String,String> names=new HashMap<>();for(Component c:NeobotSeedData.getComponents())names.put(c.getId(),c.getName());
        Map<String,List<ComponentReferenceSpec>> groups=new LinkedHashMap<>();
        Map<String,String> titles=new HashMap<>();Set<String> assemblies=new HashSet<>(),specialized=new HashSet<>();
        for(ComponentReferenceSpec s:all){
            if(!realPhoto(s)||UnifiedReferenceCatalog.negative(s)||counts.applyAsInt(s.getReferenceId())<1)continue;
            String role2=Phase2AssetCatalog.referenceRole(s.getReferenceId()),role7=Phase7AssetCatalog.referenceRole(s.getReferenceId());
            boolean hardware=role2!=null&&(role2.equals("brackets")||role2.equals("motors"))||"pair".equals(Phase9AssetCatalog.referenceRole(s.getReferenceId()));
            boolean assembled=UnifiedReferenceCatalog.assembly(s)&&!hardware;
            if(assembled&&UnifiedReferenceCatalog.phase(s)==0)continue;
            if(!assembled&&!hardware){String key="part:"+s.getComponentId();add(groups,key,s);titles.put(key,names.getOrDefault(s.getComponentId(),s.getDisplayName()));}
            if(assembled||hardware){
                RecognitionRequest request=UnifiedReferenceCatalog.request(s,null);
                String key="scan:"+ActiveReferenceScope.key(request);
                if(role2!=null&&request.getPhase2Stage()==5)key+=":"+role2;
                add(groups,key,s);specialized.add(key);if(assembled)assemblies.add(key);
                titles.put(key,title(s,hardware));
            }
            if(UnifiedReferenceCatalog.phase(s)==1&&NeobotSeedData.MAIN_BOARD.equals(s.getComponentId())){
                String key="scan:phase1:board";add(groups,key,s);specialized.add(key);assemblies.add(key);titles.put(key,"Phase 1 · Main board verification");
            }
            if("trackers".equals(role7)){
                String key="scan:dual-trackers";add(groups,key,s);specialized.add(key);titles.put(key,"Dual line trackers · Both modules");
            }
        }
        List<Target> result=new ArrayList<>();
        for(Map.Entry<String,List<ComponentReferenceSpec>> e:groups.entrySet())result.add(new Target(e.getKey(),titles.get(e.getKey()),assemblies.contains(e.getKey()),specialized.contains(e.getKey()),e.getValue(),counts));
        result.sort(Comparator.comparing((Target t)->t.assembly?1:0).thenComparingInt(t->t.assembly?UnifiedReferenceCatalog.phase(t.representative):0).thenComparing(t->t.title));
        return Collections.unmodifiableList(result);
    }
    private static void add(Map<String,List<ComponentReferenceSpec>> groups,String key,ComponentReferenceSpec s){
        List<ComponentReferenceSpec> refs=groups.computeIfAbsent(key,k->new ArrayList<>());
        for(ComponentReferenceSpec old:refs)if(Objects.equals(old.getJpegAssetPath(),s.getJpegAssetPath())&&Objects.equals(old.getLocalImageFilePath(),s.getLocalImageFilePath()))return;
        refs.add(s);
    }
    private static String title(ComponentReferenceSpec s,boolean hardware){
        int phase=UnifiedReferenceCatalog.phase(s);String role=phase==2?Phase2AssetCatalog.referenceRole(s.getReferenceId()):phase==7?Phase7AssetCatalog.referenceRole(s.getReferenceId()):null;
        if(hardware)return phase==2?"brackets".equals(role)?"Motor brackets · Four-piece group":"TT motors · Two-piece group":"Wheels · Both wheels";
        String name=phase==2?"Motor assembly · "+("left".equals(role)?"Left side":"right".equals(role)?"Right side":"Top view"):
            phase==3?"Motor driver placement":phase==5?"Arduino Nano placement":phase==7?"casterwork".equals(role)?"Caster installation reference":"Dual trackers and caster installation":phase==8?"Outward distance sensor installation":phase==9?"Wheels and center standoff":phase==10?"protected".equals(Phase10AssetCatalog.referenceRole(s.getReferenceId()))?"OLED protector subassembly":"Stacked extension board":phase==11?"Mounted OLED display":s.getDisplayName();
        return "Phase "+phase+" · "+name;
    }
}
