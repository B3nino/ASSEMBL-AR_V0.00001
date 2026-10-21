package dev.chyken.assembl_ar.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public final class LogoFormationSpecTest {
 @Test public void completedStrokeMasksCoverTheSuppliedEmblem()throws Exception{
     java.awt.image.BufferedImage logo=javax.imageio.ImageIO.read(new java.io.File("src/main/assets/branding/assembl_ar_logo.png"));
     assertEquals(LogoFormationSpec.WIDTH,logo.getWidth());assertEquals(LogoFormationSpec.HEIGHT,logo.getHeight());
     int visible=0,covered=0;
     for(int y=0;y<800;y++)for(int x=0;x<logo.getWidth();x++){
         int rgb=logo.getRGB(x,y);if(Math.min((rgb>>16)&255,Math.min((rgb>>8)&255,rgb&255))>=170)continue;
         visible++;boolean inside=false;
         for(LogoFormationSpec.Stroke stroke:LogoFormationSpec.STROKES){
             for(int i=2;i<stroke.points.length;i+=2){
                 double ax=stroke.points[i-2],ay=stroke.points[i-1],dx=stroke.points[i]-ax,dy=stroke.points[i+1]-ay;
                 double t=Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy)));
                 double ex=x-ax-t*dx,ey=y-ay-t*dy;
                 if(ex*ex+ey*ey<=stroke.width*stroke.width/4){inside=true;break;}
             }if(inside)break;
         }if(inside)covered++;
     }
     assertTrue(visible>1000);assertTrue("The emblem must form completely before the final bitmap frame",(double)covered/visible>.995);
 }
 @Test public void logoTracesBuildBeforeWordmarkAndTagline(){
     assertTrue(LogoFormationSpec.STROKES.length>10);
     for(LogoFormationSpec.Stroke stroke:LogoFormationSpec.STROKES){
         assertEquals(0,stroke.progress(0),0);assertEquals(1,stroke.progress(1),0);assertTrue(stroke.start<stroke.end);
         assertEquals(0,stroke.points.length%2);
         for(int i=0;i<stroke.points.length;i+=2){assertTrue(stroke.points[i]>=0&&stroke.points[i]<=LogoFormationSpec.WIDTH);assertTrue(stroke.points[i+1]>=0&&stroke.points[i+1]<=LogoFormationSpec.HEIGHT);}
     }
     assertEquals(0,LogoFormationSpec.wordmark(.6f),0);assertTrue(LogoFormationSpec.wordmark(.8f)>0);
     assertEquals(0,LogoFormationSpec.tagline(.8f),0);assertEquals(1,LogoFormationSpec.tagline(1),0);
 }
}

