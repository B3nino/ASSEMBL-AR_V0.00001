package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;
import dev.chyken.assembl_ar.model.*;
import static org.junit.Assert.*;

public final class WiringDiagramStateTest {
    @Test public void savedAppliedColorsSurviveAllControllerCheckpointWrappers(){
        MotorWireColorConfig motor=MotorWireColorConfig.defaults().with(MotorWireColorConfig.Terminal.LEFT_POSITIVE,MotorWireColorConfig.Swatch.BLUE);
        Phase4MotorWiringFlow four=new Phase4MotorWiringFlow();four.applyColors(motor);
        DriverWireColorConfig driver=DriverWireColorConfig.defaults().with(DriverWireColorConfig.Wire.A1A,MotorWireColorConfig.Swatch.YELLOW);
        Phase6DriverWiringFlow six=new Phase6DriverWiringFlow(0);six.applyColors(driver,1);
        ComponentWiringColorConfig components=ComponentWiringColorConfig.defaults().with(ComponentWiringColorConfig.Wire.TRIGGER,MotorWireColorConfig.Swatch.PURPLE);
        Phase8SensorWiringFlow eight=new Phase8SensorWiringFlow(0);eight.devJump(6,1);eight.applyColors(components,2);
        String a=four.snapshot(),b=six.snapshot(),c=eight.snapshot();
        WiringDiagramState state=WiringDiagramState.fromCheckpoints(a+"~draft~0^true",b+"~draft~0^true",c+"|true|4500|false|0");
        assertEquals(motor,state.motor);assertEquals(driver,state.driver);assertEquals(components,state.components);
        assertEquals(a,four.snapshot());assertEquals(b,six.snapshot());assertEquals(c,eight.snapshot());
    }
    @Test public void newOrInvalidSnapshotsUseDefaultsWithoutMarkingAnythingComplete(){
        WiringDiagramState a=WiringDiagramState.fromCheckpoints(null,null,null),b=WiringDiagramState.fromCheckpoints("bad","bad","bad");
        assertEquals(MotorWireColorConfig.defaults(),a.motor);assertEquals(DriverWireColorConfig.defaults(),a.driver);assertEquals(ComponentWiringColorConfig.defaults(),a.components);
        assertEquals(a.motor,b.motor);assertEquals(a.driver,b.driver);assertEquals(a.components,b.components);
    }
}
