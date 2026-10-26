package dev.chyken.assembl_ar.ui;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;

/** Isometric 27-block assembly loop. Real preparation progress controls assembly tightness/speed. */
public final class AssemblyLoadingCubeView extends LifecycleAnimatedView {
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),edge=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path face=new Path();
    private final float[][] px=new float[27][8],py=new float[27][8];
    private final float[] depth=new float[27];private final int[] order=new int[27];
    private static final int[][] FACES={{0,1,3,2},{0,2,6,4},{0,4,5,1},{7,5,4,6},{7,6,2,3},{7,3,1,5}};
    private float time,progress;
    public AssemblyLoadingCubeView(Context c){this(c,null);}
    public AssemblyLoadingCubeView(Context c,AttributeSet a){super(c,a);edge.setStyle(Paint.Style.STROKE);edge.setStrokeWidth(1.2f*c.getResources().getDisplayMetrics().density);}
    public void setProgress(int done,int total){progress=total>0?Math.max(0,Math.min(1,(float)done/total)):0;invalidate();}
    @Override protected void advance(float dt){time+=dt*(.65f+.35f*progress);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float scale=Math.min(getWidth(),getHeight())*.13f,cx=getWidth()*.5f,cy=getHeight()*.52f;
        double yaw=.65+time*.35,cs=Math.cos(yaw),sn=Math.sin(yaw);float phase=(time%5)/5,split=(float)Math.pow(Math.sin(Math.PI*phase),4)*(.65f-.35f*progress),turn=(float)(Math.PI*.5*(phase<.5?phase*2:1));
        int index=0;for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
            float centerX=x*(1.04f+split),centerY=y*(1.04f+split),centerZ=z*(1.04f+split);depth[index]=0;
            for(int v=0;v<8;v++){double a=centerX+((v&1)==0?-.47:.47),b=centerY+((v&2)==0?-.47:.47),d=centerZ+((v&4)==0?-.47:.47);
                if(y==1){double old=a;a=a*Math.cos(turn)-d*Math.sin(turn);d=old*Math.sin(turn)+d*Math.cos(turn);}
                double rx=a*cs+d*sn,rz=-a*sn+d*cs;px[index][v]=cx+(float)rx*scale;py[index][v]=cy+(float)(b*.82-rz*.58)*scale;depth[index]+=(float)(rz*.82+b*.58)/8;
            }order[index]=index;index++;
        }
        for(int i=1;i<27;i++){int v=order[i],j=i-1;while(j>=0&&depth[order[j]]>depth[v]){order[j+1]=order[j];j--;}order[j+1]=v;}
        for(int n=0;n<27;n++){int i=order[n];for(int f=0;f<FACES.length;f++){int[] q=FACES[f];float cross=(px[i][q[1]]-px[i][q[0]])*(py[i][q[2]]-py[i][q[0]])-(py[i][q[1]]-py[i][q[0]])*(px[i][q[2]]-px[i][q[0]]);if(cross<=0)continue;
            face.rewind();face.moveTo(px[i][q[0]],py[i][q[0]]);for(int k=1;k<4;k++)face.lineTo(px[i][q[k]],py[i][q[k]]);face.close();fill.setColor(f%2==0?0xF5DDECE5:0xF5CBE2D9);edge.setColor(f%2==0?0xFF3F7D14:0xFF267F88);c.drawPath(face,fill);c.drawPath(face,edge);
        }}
    }
}
