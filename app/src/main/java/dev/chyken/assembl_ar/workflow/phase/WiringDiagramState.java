package dev.chyken.assembl_ar.workflow.phase;

import dev.chyken.assembl_ar.model.*;

/** Read-only applied colors; draft picker values and completion flags are never written by review. */
public final class WiringDiagramState {
    public final MotorWireColorConfig motor;public final DriverWireColorConfig driver;public final ComponentWiringColorConfig components;
    public WiringDiagramState(MotorWireColorConfig motor,DriverWireColorConfig driver,ComponentWiringColorConfig components){this.motor=motor;this.driver=driver;this.components=components;}
    public static WiringDiagramState fromCheckpoints(String motor,String driver,String components){
        return new WiringDiagramState(Phase4MotorWiringFlow.restore(base(motor)).colors(),Phase6DriverWiringFlow.restore(base(driver),0).colors(),Phase8SensorWiringFlow.restore(components==null?null:components.split("[|]",-1)[0],0).colors());
    }
    private static String base(String value){if(value==null)return null;int cut=value.indexOf('^');if(cut>=0)value=value.substring(0,cut);return value.split("~",-1)[0];}
}
