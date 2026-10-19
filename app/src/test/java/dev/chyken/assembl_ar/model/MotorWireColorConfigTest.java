package dev.chyken.assembl_ar.model;

import org.junit.Test;
import java.util.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Swatch;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal;
import static org.junit.Assert.*;

public final class MotorWireColorConfigTest {
    @Test public void paletteHasExactlyTheTenRequestedOpaqueColors(){
        Set<Integer> expected=new HashSet<>(Arrays.asList(0xFF000000,0xFFFF0000,0xFF8B4513,0xFFFFA500,0xFFFFFF00,0xFF008000,0xFF0000FF,0xFF800080,0xFF808080,0xFFFFFFFF));
        Set<Integer> actual=new HashSet<>();for(Swatch s:Swatch.values())assertTrue(actual.add(s.argb));assertEquals(expected,actual);
    }
    @Test public void eachTerminalChangesIndependentlyWithoutMutatingThePickerInitialValue(){
        MotorWireColorConfig original=MotorWireColorConfig.defaults();
        for(Terminal terminal:Terminal.values())for(Swatch swatch:Swatch.values()){
            MotorWireColorConfig changed=original.with(terminal,swatch);
            for(Terminal other:Terminal.values())assertEquals(other==terminal?swatch.argb:original.color(other),changed.color(other));
        }
        assertEquals(Swatch.RED.argb,original.leftMotorPositiveColor);assertEquals(Swatch.BLACK.argb,original.rightMotorNegativeColor);
    }
    @Test public void allFourLeadsCanHaveTheSamePhysicalInsulationColor(){
        MotorWireColorConfig c=MotorWireColorConfig.defaults();for(Terminal t:Terminal.values())c=c.with(t,Swatch.WHITE);
        assertEquals(c,MotorWireColorConfig.decode(c.encode()));
    }
    @Test public void arbitraryCombinationsSurviveSavedSessionSerialization(){
        MotorWireColorConfig c=new MotorWireColorConfig(Swatch.BROWN.argb,Swatch.ORANGE.argb,Swatch.PURPLE.argb,Swatch.WHITE.argb);
        assertEquals(c,MotorWireColorConfig.decode(c.encode()));assertEquals(c.hashCode(),MotorWireColorConfig.decode(c.encode()).hashCode());
    }
    @Test public void corruptOrNonPaletteConfigurationsAreRejected(){
        for(String bad:new String[]{null,"","red/black/red/black","ff123456/ff000000/ffff0000/ff000000","ffff0000/ff000000/ffff0000","00ff0000/ff000000/ffff0000/ff000000"}){
            try{MotorWireColorConfig.decode(bad);fail("Accepted "+bad);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void runtimeRoutesUseSelectedHexValuesAndPreserveTheReversedRightMotorPair(){
        MotorWireColorConfig c=new MotorWireColorConfig(Swatch.GREEN.argb,Swatch.GRAY.argb,Swatch.WHITE.argb,Swatch.BLUE.argb);
        List<PinConnection> pins=NeobotSeedData.getPhase4PinConnections(c);assertEquals(4,pins.size());
        String[] outputs={"Left pair · left opening","Left pair · right opening","Right pair · right opening","Right pair · left opening"};
        for(Terminal t:Terminal.values()){
            PinConnection pin=pins.get(t.ordinal());assertEquals(t.label,pin.getSourcePin());assertEquals(outputs[t.ordinal()],pin.getTargetPin());
            assertEquals(c.swatch(t).hex(),pin.getWireColor());assertEquals(NeobotSeedData.MOTOR_DRIVER,pin.getTargetComponentId());
        }
        assertEquals("#FF0000",NeobotSeedData.getPhase4PinConnections(MotorWireColorConfig.defaults()).get(0).getWireColor());
    }
    @Test public void legacySavedColorsKeepTheSameFourTabAssignments(){
        MotorWireColorConfig c=MotorWireColorConfig.decode("ff008000/ff808080/ffffffff/ff0000ff");
        assertEquals(Swatch.GREEN,c.swatch(Terminal.LEFT_POSITIVE));
        assertEquals(Swatch.GRAY,c.swatch(Terminal.LEFT_NEGATIVE));
        assertEquals(Swatch.WHITE,c.swatch(Terminal.RIGHT_POSITIVE));
        assertEquals(Swatch.BLUE,c.swatch(Terminal.RIGHT_NEGATIVE));
        assertEquals("ff008000/ff808080/ffffffff/ff0000ff",c.encode());
    }
    @Test(expected=UnsupportedOperationException.class)public void generatedRoutesCannotBeMutated(){NeobotSeedData.getPhase4PinConnections(MotorWireColorConfig.defaults()).clear();}
}
