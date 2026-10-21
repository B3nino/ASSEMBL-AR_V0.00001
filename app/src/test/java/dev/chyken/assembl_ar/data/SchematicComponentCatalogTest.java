package dev.chyken.assembl_ar.data;

import org.junit.Test;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal;
import dev.chyken.assembl_ar.data.SchematicComponentCatalog.*;
import static org.junit.Assert.*;

public final class SchematicComponentCatalogTest {
    @Test public void packagedArtworkRetainsTheOriginalPixelsAndCatalogDimensions()throws Exception{
        String[] hashes={"E538E4AABB0CADAF56AAE822900CFD6B7475AFDA27CD26E819DF5EF9A1B30AB4","24618C470E36A90C941AFAB190A7E94CCD6098AF58A21325CB948B60C8241CE4","A0CED76CDC51346AF4272DE128532BD1508027D23310D888E3400CC076C1510E"};int i=0;
        for(Component c:SchematicComponentCatalog.components()){
            byte[] png=Files.readAllBytes(Paths.get("src/main/assets",c.assetPath));
            java.nio.ByteBuffer dimensions=java.nio.ByteBuffer.wrap(png,16,8);
            assertEquals(c.width,dimensions.getInt());assertEquals(c.height,dimensions.getInt());
            StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(png))digest.append(String.format("%02X",b&255));
            assertEquals(hashes[i++],digest.toString());
        }
        assertEquals(3,i);
    }
    @Test public void motorHasTwoUnmarkedCopperTabsAndDriverSeparatesAllConnectorKinds(){
        Component motor=SchematicComponentCatalog.component(NeobotSeedData.GEAR_MOTOR),driver=SchematicComponentCatalog.component(NeobotSeedData.MOTOR_DRIVER);
        assertEquals(2,motor.pins().size());assertEquals(14,driver.pins().size());
        for(Pin p:motor.pins()){assertEquals(PinKind.MOTOR_TAB,p.kind);assertFalse(p.label.contains("positive"));assertFalse(p.label.contains("negative"));}
        assertEquals(PinKind.GROUND_HEADER,driver.pin("GND").kind);assertEquals(PinKind.POWER_HEADER,driver.pin("VCC").kind);
        for(String label:new String[]{"B-IA","B-IB","A-IA","A-IB"})assertEquals(PinKind.CONTROL_HEADER,driver.pin(label).kind);
        for(Component c:SchematicComponentCatalog.components())for(Pin p:c.pins())assertTrue(p.x>=0&&p.x<=1&&p.y>=0&&p.y<=1);
    }
    @Test public void eachWireUsesADistinctOpeningWithItsOwnScrewAndMatchingCopperTab(){
        Component motor=SchematicComponentCatalog.component(NeobotSeedData.GEAR_MOTOR),driver=SchematicComponentCatalog.component(NeobotSeedData.MOTOR_DRIVER);
        Set<String> outputs=new HashSet<>();
        for(Terminal terminal:Terminal.values()){
            Pin from=motor.pin(terminal.motorPinId),to=driver.pin(terminal.driverPinPrefix+"_opening"),screw=driver.pin(terminal.driverPinPrefix+"_screw");
            assertEquals(PinKind.MOTOR_TAB,from.kind);assertEquals(PinKind.WIRE_OPENING,to.kind);assertEquals(PinKind.SCREW_HEAD,screw.kind);
            assertTrue(outputs.add(to.id));assertEquals(to.x,screw.x,0);assertTrue(to.y<screw.y);
            assertFalse(terminal.label.contains("+"));assertFalse(terminal.output.contains("OUT"));
        }
    }
}
