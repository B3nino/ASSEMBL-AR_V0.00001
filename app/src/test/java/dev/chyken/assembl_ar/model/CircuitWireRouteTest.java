package dev.chyken.assembl_ar.model;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import dev.chyken.assembl_ar.data.*;
import dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire;
import dev.chyken.assembl_ar.model.SchematicPlacement.Point;
public final class CircuitWireRouteTest {
 @Test public void everyDriverLeadUsesOnlyRightAnglesAndItsActualPinAnchors(){
     SchematicComponentCatalog.Component driver=SchematicComponentCatalog.component(NeobotSeedData.MOTOR_DRIVER),nano=SchematicComponentCatalog.component(NeobotSeedData.MICROCONTROLLER);
     SchematicPlacement d=SchematicPlacement.fit(driver,40,145,350,265,3),n=SchematicPlacement.fitCrop(nano,620,20,230,560,3,277.0/1496,329.0/1102,1230.0/1496,720.0/1102);
     for(Wire wire:Wire.values()){
         Point from=d.map(driver.pin(wire.driverPin)),to=n.map(nano.pin(wire.nanoPin));
         List<Point> route=wire==Wire.VCC?CircuitWireRoute.perimeter(from,to,412,0,900):CircuitWireRoute.elbow(from,to,446+wire.ordinal()*34);
         assertSame(from,route.get(0));assertSame(to,route.get(route.size()-1));
         for(int i=1;i<route.size();i++){Point a=route.get(i-1),b=route.get(i);assertTrue(a.x==b.x||a.y==b.y);assertEquals(0,CircuitWireRoute.distanceSquared(route,(a.x+b.x)/2,(a.y+b.y)/2),1e-9);}
         if(wire==Wire.VCC){assertTrue(route.get(1).x<n.left);assertTrue(route.get(2).y<n.top);assertTrue(route.get(3).x>n.left+n.width);}
     }
 }
 @Test public void longRailsAndElbowsHaveExactHitTargets(){
     List<Point> route=CircuitWireRoute.elbow(new Point(0,0),new Point(1000,200),400);
     assertEquals(9,CircuitWireRoute.distanceSquared(route,333,3),1e-9);
     assertEquals(0,CircuitWireRoute.distanceSquared(route,400,150),1e-9);
     assertEquals(16,CircuitWireRoute.distanceSquared(route,750,204),1e-9);
 }
}

