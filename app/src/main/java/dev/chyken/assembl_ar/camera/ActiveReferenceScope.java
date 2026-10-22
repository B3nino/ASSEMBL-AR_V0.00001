package dev.chyken.assembl_ar.camera;

import dev.chyken.assembl_ar.vision.*;
import java.util.*;
import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

/** A scan's full evidence set, including competing/negative views. No unrelated phase preloading. */
public final class ActiveReferenceScope {
    private ActiveReferenceScope(){}
    public static String key(RecognitionRequest r){
        if(r==null)return null;
        if(r.getPhase10Stage()>0)return "p10:"+r.getPhase10Stage();
        if(r.getPhase9Stage()>0)return "p9:"+(r.getPhase9Stage()>=3?3:r.getPhase9Stage());
        if(r.getPhase8Stage()>0)return "p8:"+r.getPhase8Stage();
        if(r.getPhase7Stage()>0)return "p7:"+r.getPhase7Stage();
        if(r.getPhase5Stage()>0)return "p5:"+(r.getPhase5Stage()==1?1:3);
        if(r.getPhase3Stage()>0)return "p3:"+(r.getPhase3Stage()==1?1:3);
        String target=r.getExpectation()==null?"":r.getExpectation().getComponentId();
        return "generic:"+r.getAssemblyStepNumber()+":"+r.getPhase2Stage()+":"+target+":"+r.getSpatialAnchorComponentId()+":"+r.getGuidedBracketSlot();
    }
    public static List<ComponentReferenceSpec> select(List<ComponentReferenceSpec> catalog,RecognitionRequest r){
        if(r==null)return Collections.emptyList();List<ComponentReferenceSpec> result=new ArrayList<>();Map<String,Integer> competitors=new HashMap<>();
        String expected=r.getExpectation()==null?null:r.getExpectation().getComponentId();
        Set<String> targets=new HashSet<>(Arrays.asList(expected,r.getSpatialAnchorComponentId(),MAIN_BOARD,EXTENSION_BOARD));
        for(ComponentReferenceSpec s:catalog){
            String id=s.getReferenceId();boolean take;
            if(r.getPhase10Stage()>0){take=Phase10AssetCatalog.eligible(Phase10AssetCatalog.referenceRole(id),r.getPhase10Stage());}
            else if(r.getPhase9Stage()>0){String role=Phase9AssetCatalog.referenceRole(id);int stage=r.getPhase9Stage();take=role!=null&&(stage==1?role.equals("standoff"):stage==2?role.equals("wheel")||role.equals("pair"):Phase9AssetCatalog.installed(role));}
            else if(r.getPhase8Stage()>0){String role=Phase8AssetCatalog.referenceRole(id);take=role!=null&&((r.getPhase8Stage()==1)!=Phase8AssetCatalog.installed(role));}
            else if(r.getPhase7Stage()>0){String role=Phase7AssetCatalog.referenceRole(id);take=Phase7AssetCatalog.eligible(role,r.getPhase7Stage())||r.getPhase7Stage()==4&&"trackers".equals(role);}
            else if(r.getPhase5Stage()>0){String role=Phase5AssetCatalog.referenceRole(id);take=role!=null&&((r.getPhase5Stage()==1)!=Phase5AssetCatalog.installed(role));}
            else if(r.getPhase3Stage()>0){String role=Phase3AssetCatalog.referenceRole(id);take=role!=null&&(r.getPhase3Stage()==1?!role.equals("assembly"):role.equals("assembly"));}
            else{
                String p2=Phase2AssetCatalog.referenceRole(id);
                if(p2!=null){take=r.getPhase2Stage()==1&&p2.equals("brackets")||r.getPhase2Stage()==2&&p2.equals("motors")||r.getPhase2Stage()==5&&(p2.equals("top")||p2.equals("left")||p2.equals("right"));}
                else if(specialized(id))take=false;
                else if(r.getPhase2Stage()==5)take=false;
                else if(s.getAssemblyStepNumber()!=0&&s.getAssemblyStepNumber()!=r.getAssemblyStepNumber())take=false;
                else if(s.isInstalledBracketView()&&r.getGuidedBracketSlot()==0)take=false;
                else if(targets.contains(s.getComponentId()))take=true;
                else{int n=competitors.getOrDefault(s.getComponentId(),0);take=n<2;if(take)competitors.put(s.getComponentId(),n+1);}
            }
            if(take)result.add(s);
        }
        return Collections.unmodifiableList(result);
    }
    private static boolean specialized(String id){return id.startsWith(Phase10AssetCatalog.PREFIX)||id.startsWith(Phase2AssetCatalog.PREFIX)||id.startsWith(Phase3AssetCatalog.PREFIX)||id.startsWith(Phase5AssetCatalog.PREFIX)||id.startsWith(Phase7AssetCatalog.PREFIX)||id.startsWith(Phase8AssetCatalog.PREFIX)||id.startsWith(Phase9AssetCatalog.PREFIX);}
}
