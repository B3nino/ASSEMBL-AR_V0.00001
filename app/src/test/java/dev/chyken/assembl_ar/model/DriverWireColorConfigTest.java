package dev.chyken.assembl_ar.model;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.chyken.assembl_ar.data.*;
import dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Swatch;
public final class DriverWireColorConfigTest {
    @Test public void sixConnectionsUseExactPinMappingAndUserColors(){
        DriverWireColorConfig config=DriverWireColorConfig.defaults().with(Wire.A1A,Swatch.ORANGE).with(Wire.B1B,Swatch.WHITE);
        String[] sources={"VCC","GND","A-IA","A-IB","B-IA","B-IB"},targets={"VIN","GND","D5","D9","D6","D10"};
        java.util.List<PinConnection> connections=NeobotSeedData.getPhase6PinConnections(config);assertEquals(6,connections.size());
        for(int i=0;i<6;i++){PinConnection c=connections.get(i);assertEquals(sources[i],c.getSourcePin());assertEquals(targets[i],c.getTargetPin());assertEquals(config.swatch(Wire.values()[i]).hex(),c.getWireColor());
            SchematicComponentCatalog.component(c.getSourceComponentId()).pin(c.getSourcePin());
            SchematicComponentCatalog.component(c.getTargetComponentId()).pin(c.getTargetPin());}
        assertEquals(config,DriverWireColorConfig.decode(config.encode()));assertEquals(Swatch.RED,config.swatch(Wire.VCC));assertEquals(Swatch.BLACK,config.swatch(Wire.GND));
        assertEquals(Swatch.ORANGE,config.swatch(Wire.A1A));assertEquals(Swatch.WHITE,config.swatch(Wire.B1B));assertEquals(10,Swatch.values().length);
    }
    @Test public void mountedNanoTransformKeepsUsbDownAndSelectedDigitalPinsOnLeft(){
        SchematicComponentCatalog.Component nano=SchematicComponentCatalog.component(NeobotSeedData.MICROCONTROLLER);
        assertEquals(30,nano.pins().size());SchematicPlacement placed=SchematicPlacement.fitCrop(nano,0,0,230,560,3,277.0/1496,329.0/1102,1230.0/1496,720.0/1102);
        assertTrue(placed.map(nano.pin("D5")).x<placed.map(nano.pin("VIN")).x);
        assertTrue(placed.map(nano.pin("D5")).y<placed.map(nano.pin("D10")).y); // checked against image-native order, see rotation.
    }
}

