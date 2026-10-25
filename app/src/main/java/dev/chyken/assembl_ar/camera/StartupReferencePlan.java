package dev.chyken.assembl_ar.camera;

import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import java.util.*;

/** Small warm windows for the saved phase and its next camera target. No evidence changes. */
public final class StartupReferencePlan {
    private StartupReferencePlan() { }
    public static List<ComponentReferenceSpec> select(List<ComponentReferenceSpec> catalog,PhaseSessionCheckpoint saved){
        int phase=saved==null?1:AssemblyPhaseCatalog.byId(saved.phaseId).getNumber();
        String bits=saved==null||saved.gearState==null?null:saved.gearState.split("\\|",-1)[0];
        RecognitionRequest request;
        switch(phase){
            case 2:int step=Phase2GearMotorFlow.restore(bits,0).step();request=base(step==1?NeobotSeedData.MOTOR_BRACKET:step==2?NeobotSeedData.GEAR_MOTOR:NeobotSeedData.MAIN_BOARD,2).withPhase2Stage(step<=2?step:5);break;
            case 3:request=base(NeobotSeedData.MOTOR_DRIVER,3).withPhase3Stage(Phase3MotorDriverFlow.restore(bits,0).step()==1?1:4);break;
            case 4:case 5:request=base(NeobotSeedData.MICROCONTROLLER,5).withPhase5Stage(phase==4||Phase5MicrocontrollerFlow.restore(bits,0).step()==1?1:3);break;
            case 6:case 7:int p7=phase==6?1:Phase7CasterTrackerFlow.restore(bits,0).step();request=base(p7==1?NeobotSeedData.CASTER_WHEEL:p7==2?NeobotSeedData.LINE_TRACKER:NeobotSeedData.MAIN_BOARD,7).withPhase7Stage(p7<=2?p7:4);break;
            case 8:request=base(NeobotSeedData.DISTANCE_SENSOR,8).withPhase8Stage(Phase8SensorWiringFlow.restore(bits,0).screen()==Phase8SensorWiringFlow.Screen.SENSOR_SCAN?1:2);break;
            case 9:int p9=Phase9StandoffWheelsFlow.restore(bits,0).screen().ordinal();int stage9=p9<3?2:p9<6?3:4;request=base(stage9==2?NeobotSeedData.WHEEL:NeobotSeedData.MAIN_BOARD,9).withPhase9Stage(stage9);break;
            case 10:int p10=Phase10ExtensionBoardFlow.restore(bits,0).screen().ordinal();int stage10=p10==0?1:p10<=2?2:p10==3?3:p10<10?4:5;request=base(Phase10AssetCatalog.target(stage10),10).withPhase10Stage(stage10);break;
            case 11:int p11=Phase11OledBatteryFlow.restore(bits,0).screen().ordinal();int stage11=p11==0?1:p11<=2?2:3;request=base(Phase11AssetCatalog.target(stage11),11).withPhase11Stage(stage11);break;
            default:request=base(NeobotSeedData.MAIN_BOARD,1);
        }
        LinkedHashMap<String,ComponentReferenceSpec> warm=new LinkedHashMap<>();
        RecognitionRequest next=ActiveReferenceScope.next(request);if(next!=null)addWindow(warm,ActiveReferenceScope.select(catalog,next));
        addWindow(warm,ActiveReferenceScope.select(catalog,request));
        return Collections.unmodifiableList(new ArrayList<>(warm.values()));
    }
    private static RecognitionRequest base(String id,int phase){int count=phase==2&&NeobotSeedData.MOTOR_BRACKET.equals(id)?4:phase==2&&NeobotSeedData.GEAR_MOTOR.equals(id)||phase==7&&NeobotSeedData.LINE_TRACKER.equals(id)||phase==9&&NeobotSeedData.WHEEL.equals(id)?2:1;return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,count,phase);}
    private static void addWindow(Map<String,ComponentReferenceSpec> warm,List<ComponentReferenceSpec> specs){int positive=0,negative=0;for(ComponentReferenceSpec s:specs){boolean bad=UnifiedReferenceCatalog.negative(s);if(bad?negative>=4:positive>=8)continue;if(bad)negative++;else positive++;warm.put(s.getReferenceId(),s);}}
}
