package dev.chyken.assembl_ar.camera;

import org.junit.Test;
import java.util.*;
import dev.chyken.assembl_ar.vision.*;
import static org.junit.Assert.*;
import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

public final class ActiveReferenceScopeTest {
    private ComponentReferenceSpec ref(String id,String component,int section){return ComponentReferenceSpec.builder(id,component,id,20,40).assemblyStepNumber(section).jpegAssetPath("test.jpg").build();}
    private RecognitionRequest p9(int stage){return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(stage==1?STANDOFF:stage==2?WHEEL:MAIN_BOARD),null,null,null,stage==2?2:1,9).withPhase9Stage(stage);}
    @Test public void phaseNineDoesNotLoadAnyEarlierPhaseOrFutureChassisDuringLooseScan(){
        List<ComponentReferenceSpec> all=Arrays.asList(ref("p9scene_standoff_one",STANDOFF,9),ref("p9scene_wheel_one",WHEEL,9),ref("p9scene_pair_one",WHEEL,9),ref("p9scene_assembly_one",MAIN_BOARD,9),ref("p9scene_invalid_one",MAIN_BOARD,9),ref("p7scene_caster_one",CASTER_WHEEL,7),ref("p8scene_assembly_one",MAIN_BOARD,8));
        assertEquals(1,ActiveReferenceScope.select(all,p9(1)).size());assertEquals(2,ActiveReferenceScope.select(all,p9(2)).size());assertEquals(2,ActiveReferenceScope.select(all,p9(3)).size());
        assertEquals(ActiveReferenceScope.key(p9(3)),ActiveReferenceScope.key(p9(4)));
        assertFalse(ActiveReferenceScope.key(p9(1)).equals(ActiveReferenceScope.key(p9(2))));
    }
    @Test public void phaseEightRetainsBothPositiveAndNegativeEvidenceForTheActiveStage(){
        List<ComponentReferenceSpec> all=Arrays.asList(ref("p8scene_front_one",DISTANCE_SENSOR,8),ref("p8scene_back_one",DISTANCE_SENSOR,8),ref("p8scene_assembly_one",DISTANCE_SENSOR,8),ref("p8scene_invalid_one",DISTANCE_SENSOR,8),ref("p9scene_standoff_one",STANDOFF,9));
        for(int stage=1;stage<=2;stage++){RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(DISTANCE_SENSOR),null,null,null,1,8).withPhase8Stage(stage);List<ComponentReferenceSpec> refs=ActiveReferenceScope.select(all,r);assertEquals(2,refs.size());assertEquals(stage==1?"front":"assembly",Phase8AssetCatalog.referenceRole(refs.get(0).getReferenceId()));}
    }
    @Test public void installedTrackerScopeKeepsLooseModuleReferencesNeededByDualDetection(){
        List<ComponentReferenceSpec> all=Arrays.asList(ref("p7scene_trackers_one",LINE_TRACKER,7),ref("p7scene_trackerwork_one",MAIN_BOARD,7),ref("p7scene_trackerworkbad_one",MAIN_BOARD,7),ref("p7scene_caster_one",CASTER_WHEEL,7));
        RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(MAIN_BOARD),null,null,null,1,7).withPhase7Stage(4);
        assertEquals(3,ActiveReferenceScope.select(all,r).size());
    }
    @Test public void genericScopesKeepAllTargetAnglesBoardCompetitorsAndAdminRefsButNoFutureScenes(){
        List<ComponentReferenceSpec> all=Arrays.asList(ref("board_a",MAIN_BOARD,0),ref("board_b",MAIN_BOARD,0),ref("bottom",MAIN_BOARD,0),ref("extension",EXTENSION_BOARD,0),ref("admin",MAIN_BOARD,0),ref("p9scene_assembly_x",MAIN_BOARD,9),ref("p8scene_assembly_x",MAIN_BOARD,8),ref("future",MAIN_BOARD,13));
        RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(MAIN_BOARD),null,null,null,1,1);
        assertEquals(5,ActiveReferenceScope.select(all,r).size());assertTrue(ActiveReferenceScope.select(all,r).contains(all.get(4)));
    }
    @Test public void inactiveScreensPrepareNothingAndFrameTagsDoNotReloadAnIdenticalScope(){
        assertNull(ActiveReferenceScope.key(null));assertTrue(ActiveReferenceScope.select(Collections.emptyList(),null).isEmpty());
        assertEquals(ActiveReferenceScope.key(p9(1).withRequestTag("old")),ActiveReferenceScope.key(p9(1).withRequestTag("new")));
    }
}
