package dev.chyken.assembl_ar.model;

import org.junit.Test;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.data.SchematicComponentCatalog;
import dev.chyken.assembl_ar.data.SchematicComponentCatalog.Component;
import dev.chyken.assembl_ar.model.SchematicPlacement.Point;
import static org.junit.Assert.*;

public final class SchematicPlacementTest {
    private final Component driver=SchematicComponentCatalog.component(NeobotSeedData.MOTOR_DRIVER);
    private final Component motor=SchematicComponentCatalog.component(NeobotSeedData.GEAR_MOTOR);
    @Test public void allQuarterTurnsShareTheSameImageAndPinTransform(){
        double[][] expected={{.2,.3},{.7,.2},{.8,.7},{.3,.8}};
        for(int turns=0;turns<4;turns++){
            SchematicPlacement p=SchematicPlacement.fit(driver,10,20,700,500,turns);
            Point point=p.map(.2,.3);
            assertEquals(p.left+expected[turns][0]*p.width,point.x,1e-8);
            assertEquals(p.top+expected[turns][1]*p.height,point.y,1e-8);
            double ratio=turns%2==0?765.0/1024:1024.0/765;
            assertEquals(ratio,p.width/p.height,1e-8);
            assertTrue(p.left>=10&&p.top>=20&&p.left+p.width<=710&&p.top+p.height<=520);
        }
    }
    @Test public void mountedDriverPlacesHeaderAwayAndOutputPairsInTheRightOrder(){
        SchematicPlacement p=SchematicPlacement.fit(driver,0,0,765,1024,2);
        String[] order={"a_right","a_left","b_right","b_left"};double previous=-1;
        for(String name:order){
            Point opening=p.map(driver.pin(name+"_opening"));
            Point screw=p.map(driver.pin(name+"_screw"));
            assertTrue(opening.x>previous);previous=opening.x;
            assertEquals(opening.x,screw.x,0);assertTrue(opening.y>screw.y);
            assertTrue(p.map(driver.pin("GND")).y<screw.y);
        }
    }
    @Test public void clockwiseMotorExposesUpperOriginalTabOnRightAndLowerOnLeft(){
        SchematicPlacement p=SchematicPlacement.fit(motor,0,0,291,605,1);
        Point upper=p.map(motor.pin("upper_tab")),lower=p.map(motor.pin("lower_tab"));
        assertEquals(upper.y,lower.y,0);assertTrue(upper.x>lower.x);
        assertEquals(220,upper.x,1e-8);assertEquals(71,lower.x,1e-8);assertEquals(27,upper.y,1e-8);
    }
    @Test public void enlargedScrewBlockCropKeepsEveryOpeningAndScrewOnTheImage(){
        SchematicPlacement p=SchematicPlacement.fitCrop(driver,280,0,330,151,2,0,0,1,350.0/1024);
        for(SchematicComponentCatalog.Pin pin:driver.pins()){
            Point point=p.map(pin);
            if(pin.id.endsWith("_opening")||pin.id.endsWith("_screw")){
                assertTrue(point.x>=p.left&&point.x<=p.left+p.width);
                assertTrue(point.y>=p.top&&point.y<=p.top+p.height);
            }else assertTrue("Headers must not become motor-wire targets in the crop",point.y<p.top);
        }
        // The Canvas full-image affine matrix must agree with cropped pin anchors.
        Point origin=p.map(0,0),right=p.map(1,0),bottom=p.map(0,1);
        for(SchematicComponentCatalog.Pin pin:driver.pins()){
            Point point=p.map(pin);
            assertEquals(point.x,origin.x+(right.x-origin.x)*pin.x+(bottom.x-origin.x)*pin.y,1e-8);
            assertEquals(point.y,origin.y+(right.y-origin.y)*pin.x+(bottom.y-origin.y)*pin.y,1e-8);
        }
    }
    @Test(expected=IllegalArgumentException.class)public void emptyCropIsRejected(){SchematicPlacement.fitCrop(driver,0,0,100,100,2,0,.3,1,.3);}
}
