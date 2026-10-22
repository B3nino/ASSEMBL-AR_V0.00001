package dev.chyken.assembl_ar.model;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.chyken.assembl_ar.data.*;
import dev.chyken.assembl_ar.model.ComponentWiringColorConfig.Wire;
public final class ComponentWiringColorConfigTest {
    @Test public void allTenElectricalDestinationsAreFixedWhileColorsRoundTrip(){ComponentWiringColorConfig c=ComponentWiringColorConfig.defaults();for(Wire w:Wire.values())c=c.with(w,MotorWireColorConfig.Swatch.WHITE);assertEquals(c,ComponentWiringColorConfig.decode(c.encode()));assertEquals(10,NeobotSeedData.getPhase8PinConnections(c).size());assertEquals("D2",Wire.TRIGGER.target);assertEquals("D4",Wire.ECHO.target);assertEquals("A1",Wire.LED.target);assertEquals("D3",Wire.SPEAKER.target);assertEquals("A2",Wire.LEFT_SIGNAL.target);assertEquals("A3",Wire.RIGHT_SIGNAL.target);}
    @Test public void railAndSensorPinsUseInspectedPhysicalOrder(){SchematicComponentCatalog.Component sensor=SchematicComponentCatalog.component(NeobotSeedData.DISTANCE_SENSOR),rail=SchematicComponentCatalog.component("ground_signal_rail"),tracker=SchematicComponentCatalog.component(NeobotSeedData.LINE_TRACKER);assertTrue(sensor.pin("VCC").x<sensor.pin("Trigger").x);assertTrue(sensor.pin("Echo").x<sensor.pin("GND").x);assertTrue(rail.pin("SPKR").x<rail.pin("LED").x);assertTrue(tracker.pin("G").x<tracker.pin("V+").x);assertTrue(tracker.pin("V+").x<tracker.pin("S").x);}
    @Test(expected=IllegalArgumentException.class)public void invalidColorsCannotEnterState(){new ComponentWiringColorConfig(1,2,3,4,5,6,7,8,9,10);}
}
