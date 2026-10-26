package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.UnifiedReferenceCatalog;
import org.opencv.core.*;
import org.opencv.calib3d.Calib3d;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.*;

/** Process-owned descriptor index; published only after preparation, queried without file I/O.
 * Representative descriptors shortlist references. Only a spatially distributed RANSAC fit can veto.
 * Full descriptors and exact keypoint counts stay in the shared persistent reference cache. */
public final class GlobalComponentRegistry {
    public static final GlobalComponentRegistry INSTANCE=new GlobalComponentRegistry();
    private final Map<String,Template> templates=new LinkedHashMap<>();
    private Mat pooled; private List<Template> owners=Collections.emptyList();
    private volatile boolean ready;
    private static final int ROWS_PER_VARIANT=96;
    private static final int COMPACT_VERSION=2;
    public static final class Match {
        public final String referenceId,componentId,name;public final int phase,good,total,inliers;public final boolean assembly,negative;public final double score;
        public final List<VisionPoint> targetCorners;
        public final List<List<VisionPoint>> bodyRegions;public final boolean identityCertain;
        Match(Template t,int g,int n,int i,int identity,Mat h){name=t.spec.getDisplayName();identityCertain=i>=15&&identity>=8;referenceId=t.spec.getReferenceId();componentId=t.spec.getComponentId();phase=UnifiedReferenceCatalog.phase(t.spec);assembly=UnifiedReferenceCatalog.assembly(t.spec);negative=UnifiedReferenceCatalog.negative(t.spec);good=g;total=n;inliers=i;score=identity*(i/(double)Math.max(1,g));
            MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();List<VisionPoint> corners=new ArrayList<>();
            try{List<Point> p=new ArrayList<>();for(VisionPoint c:t.spec.getPlanarCornersNormalized())p.add(new Point(c.getX()*t.width,c.getY()*t.height));src.fromList(p);Core.perspectiveTransform(src,dst,h);for(Point c:dst.toArray()){if(!Double.isFinite(c.x+c.y)){corners.clear();break;}corners.add(new VisionPoint(c.x,c.y));}}finally{src.release();dst.release();}
            targetCorners=Collections.unmodifiableList(corners);
            List<List<VisionPoint>> bodies=new ArrayList<>();
            for(List<VisionPoint> region:ComponentContourTemplates.forReference(t.spec)){
                MatOfPoint2f from=new MatOfPoint2f(),to=new MatOfPoint2f();try{
                    Point[] p=new Point[region.size()];for(int j=0;j<p.length;j++)p[j]=new Point(region.get(j).getX()*t.width,region.get(j).getY()*t.height);
                    from.fromArray(p);Core.perspectiveTransform(from,to,h);List<VisionPoint> mapped=new ArrayList<>();for(Point q:to.toArray())mapped.add(new VisionPoint(q.x,q.y));bodies.add(Collections.unmodifiableList(mapped));
                }finally{from.release();to.release();}
            }bodyRegions=Collections.unmodifiableList(bodies);
        }
        private Match(Match source,boolean certain){referenceId=source.referenceId;componentId=source.componentId;name=source.name;phase=source.phase;good=source.good;total=source.total;inliers=source.inliers;assembly=source.assembly;negative=source.negative;score=source.score;targetCorners=source.targetCorners;bodyRegions=source.bodyRegions;identityCertain=certain;}
        public boolean incompatible(int activePhase,String expected){
            if(expected==null)return false;
            // Loose parts are shared identities. An assembly photograph also contains reusable
            // parts, so a phase veto requires an assembly scan and a different assembly state.
            if(!assembly)return !componentId.equals(expected);
            return !componentId.equals(expected)||phase>0&&activePhase>0&&phase!=activePhase;
        }
    }
    private static final class Template {
        final ComponentReferenceSpec spec;final int width,height;final Mat descriptors=new Mat(),representative=new Mat();final List<Point> points=new ArrayList<>();
        Template(ReferenceDatabase.PreparedReference r){
            spec=r.spec;width=r.imageWidth;height=r.imageHeight;List<List<VisionPoint>> body=ComponentContourTemplates.forReference(spec);
            List<byte[]> samples=new ArrayList<>();
            for(ReferenceDatabase.PreparedOrbVariant v:r.orbVariants)if(v.hasOrbData()){
                List<Integer> eligible=new ArrayList<>();
                for(int j=0;j<v.keypoints.length;j++){Point p=v.keypoints[j].pt;if(inBody(p.x/width,p.y/height,body))eligible.add(j);}
                int n=Math.min(ROWS_PER_VARIANT,eligible.size());byte[] source=new byte[v.descriptors.rows()*32];v.descriptors.get(0,0,source);
                for(int j=0;j<n;j++){int row=eligible.get(j*eligible.size()/n);samples.add(Arrays.copyOfRange(source,row*32,row*32+32));Point p=v.keypoints[row].pt;points.add(new Point(p.x,p.y));}
            }
            if(samples.isEmpty())return;int count=samples.size();byte[] bytes=new byte[count*32];for(int j=0;j<count;j++)System.arraycopy(samples.get(j),0,bytes,j*32,32);
            descriptors.create(count,32,CvType.CV_8UC1);descriptors.put(0,0,bytes);
            int n=Math.min(16,count);byte[] sparse=new byte[n*32];for(int j=0;j<n;j++)System.arraycopy(bytes,(j*count/n)*32,sparse,j*32,32);
            representative.create(n,32,CvType.CV_8UC1);representative.put(0,0,sparse);
        }
        Template(ComponentReferenceSpec s,int w,int h,List<Point> p,byte[] bytes){
            spec=s;width=w;height=h;points.addAll(p);int count=p.size();
            if(count==0)return;
            descriptors.create(count,32,CvType.CV_8UC1);descriptors.put(0,0,bytes);
            int n=Math.min(16,count);byte[] sparse=new byte[n*32];
            for(int j=0;j<n;j++)System.arraycopy(bytes,(j*count/n)*32,sparse,j*32,32);
            representative.create(n,32,CvType.CV_8UC1);representative.put(0,0,sparse);
        }
        void release(){descriptors.release();representative.release();}
    }

    /** Small, versioned shortlist plus exact inventory. Full recognition banks stay in ASOR.
     * A warm process need not deserialize the entire full-descriptor library to rebuild this index. */
    synchronized void saveCompact(File file,ReferenceCacheIndex index,List<ComponentReferenceSpec> sources,
            VisionConfig config,KeypointInventoryManager inventory)throws IOException {
        if(!ready)throw new IOException("Global reference index is not published");
        Map<String,KeypointInventoryManager.Entry> counts=new HashMap<>();
        for(KeypointInventoryManager.Entry e:inventory.snapshot())counts.put(e.referenceId,e);
        File temp=File.createTempFile("global-index-",".tmp",file.getParentFile());
        try{
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(temp))))){
                out.writeInt(COMPACT_VERSION);out.writeUTF(fingerprint(index,sources,config));out.writeInt(sources.size());
                for(ComponentReferenceSpec source:sources){
                    KeypointInventoryManager.Entry entry=counts.get(source.getReferenceId());
                    if(!index.terminal(source,config)||entry==null||entry.variants.isEmpty())throw new IOException("Incomplete reference inventory: "+source.getReferenceId());
                    out.writeUTF(source.getReferenceId());out.writeInt(entry.variants.size());
                    for(Map.Entry<String,Integer> v:entry.variants.entrySet()){out.writeUTF(v.getKey());out.writeInt(v.getValue());}
                    Template t=templates.get(source.getReferenceId());out.writeBoolean(t!=null);
                    if(t==null)continue;
                    out.writeInt(t.width);out.writeInt(t.height);out.writeDouble(t.spec.getPhysicalWidthMm());out.writeDouble(t.spec.getPhysicalHeightMm());
                    out.writeInt(t.spec.getAssemblyStepNumber());out.writeBoolean(t.spec.hasVerifiedPolarityAnchors());
                    writePolygon(out,t.spec.getPlanarCornersNormalized());out.writeInt(t.spec.getFeatureRegionsNormalized().size());
                    for(List<VisionPoint> region:t.spec.getFeatureRegionsNormalized())writePolygon(out,region);
                    out.writeInt(t.points.size());for(Point p:t.points){out.writeDouble(p.x);out.writeDouble(p.y);}
                    byte[] bytes=new byte[t.points.size()*32];if(bytes.length>0)t.descriptors.get(0,0,bytes);out.write(bytes);
                }
            }
            try{Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException fallback){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{temp.delete();}
    }

    /** Decode and validate completely before publishing. Corruption never becomes partial evidence. */
    synchronized boolean restoreCompact(File file,ReferenceCacheIndex index,List<ComponentReferenceSpec> sources,
            VisionConfig config,KeypointInventoryManager inventory){
        if(ready||!file.isFile()||file.length()>64L*1024*1024)return false;
        Map<String,Template> restored=new LinkedHashMap<>();Map<String,Map<String,Integer>> counts=new LinkedHashMap<>();
        boolean committed=false;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))){
            if(in.readInt()!=COMPACT_VERSION||!in.readUTF().equals(fingerprint(index,sources,config))||in.readInt()!=sources.size())return false;
            for(ComponentReferenceSpec source:sources){
                if(!index.terminal(source,config)||!in.readUTF().equals(source.getReferenceId()))return false;
                int n=bounded(in.readInt(),1,8);Map<String,Integer> variants=new LinkedHashMap<>();
                for(int j=0;j<n;j++){String variant=in.readUTF();if(!variant.equals("UNUSABLE"))ReferenceDatabase.PhotometricVariant.valueOf(variant);int count=bounded(in.readInt(),-1,1000000);if(variants.put(variant,count)!=null)return false;}
                counts.put(source.getReferenceId(),variants);
                if(!in.readBoolean())continue;
                int width=bounded(in.readInt(),1,16384),height=bounded(in.readInt(),1,16384);
                double physicalW=in.readDouble(),physicalH=in.readDouble();int step=bounded(in.readInt(),0,18);boolean verified=in.readBoolean();
                List<VisionPoint> corners=readPolygon(in);if(corners.size()!=4)return false;
                int regions=bounded(in.readInt(),0,128);List<List<VisionPoint>> polygons=new ArrayList<>();for(int j=0;j<regions;j++)polygons.add(readPolygon(in));
                ComponentReferenceSpec spec=source.withPreparedGeometry(physicalW,physicalH,step,verified,corners,polygons);
                int rows=bounded(in.readInt(),0,4096);List<Point> points=new ArrayList<>();
                for(int j=0;j<rows;j++){double x=in.readDouble(),y=in.readDouble();if(!Double.isFinite(x+y)||x<0||y<0||x>width||y>height)return false;points.add(new Point(x,y));}
                byte[] bytes=new byte[rows*32];in.readFully(bytes);restored.put(source.getReferenceId(),new Template(spec,width,height,points,bytes));
            }
            if(in.read()!=-1)return false; // also validates the gzip CRC/trailer
            for(Template old:templates.values())old.release();templates.clear();templates.putAll(restored);
            for(ComponentReferenceSpec s:sources)inventory.restore(s,counts.get(s.getReferenceId()));
            for(Template t:templates.values())ComponentContourTemplates.register(t.spec);
            publish();committed=true;return true;
        }catch(IOException|IllegalArgumentException corrupt){return false;}
        finally{if(!committed)for(Template t:restored.values())t.release();}
    }
    private static int bounded(int value,int min,int max)throws IOException{if(value<min||value>max)throw new IOException("Invalid compact index length");return value;}
    private static void writePolygon(DataOutputStream out,List<VisionPoint> points)throws IOException{out.writeInt(points.size());for(VisionPoint p:points){out.writeDouble(p.getX());out.writeDouble(p.getY());}}
    private static List<VisionPoint> readPolygon(DataInputStream in)throws IOException{int n=bounded(in.readInt(),3,128);List<VisionPoint> p=new ArrayList<>();for(int i=0;i<n;i++){double x=in.readDouble(),y=in.readDouble();if(!Double.isFinite(x+y)||x< -1||x>2||y< -1||y>2)throw new IOException("Invalid compact geometry");p.add(new VisionPoint(x,y));}return p;}
    private static String fingerprint(ReferenceCacheIndex index,List<ComponentReferenceSpec> sources,VisionConfig config){
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(("edge:"+EdgeGradientDescriptorExtractor.VERSION).getBytes(StandardCharsets.UTF_8));for(ComponentReferenceSpec s:sources){digest.update(index.key(s,config).getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}return Base64.getEncoder().encodeToString(digest.digest());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static boolean inBody(double x,double y,List<List<VisionPoint>> polygons){
        for(List<VisionPoint> polygon:polygons){boolean inside=false;for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++){
            VisionPoint a=polygon.get(i),b=polygon.get(j);if((a.getY()>y)!=(b.getY()>y)&&x<(b.getX()-a.getX())*(y-a.getY())/(b.getY()-a.getY())+a.getX())inside=!inside;
        }if(inside)return true;}return false;
    }

    synchronized void prepared(ReferenceDatabase.PreparedReference r){ComponentContourTemplates.register(r.spec);if(!ready&&!templates.containsKey(r.spec.getReferenceId()))templates.put(r.spec.getReferenceId(),new Template(r));}
    public synchronized void publish(){
        if(ready)return;Mat all=new Mat();List<Template> rows=new ArrayList<>();
        for(Template t:templates.values())if(!t.descriptors.empty()){
            // A small representative index limits tablet work. Final evidence uses the larger
            // retained template and independent RANSAC, never these shortlist votes.
            for(int i=0;i<Math.min(16,t.descriptors.rows());i++){int row=i*t.descriptors.rows()/Math.min(16,t.descriptors.rows());Mat one=t.descriptors.row(row);try{all.push_back(one);rows.add(t);}finally{one.release();}}
        }
        pooled=all;owners=rows;ready=true;
    }
    public boolean isReady(){return ready;}
    public synchronized boolean hasReferences(){return !templates.isEmpty();}
    public synchronized int referenceCount(){return templates.size();}
    public synchronized Match query(Mat liveDescriptors,KeyPoint[] live){
        if(!ready||pooled==null||pooled.empty()||liveDescriptors.rows()<2)return null;
        Map<Template,Integer> votes=new HashMap<>();
        {
            NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(pooled,liveDescriptors);
            for(int row=0;row<pairs.size();row++)if(pairs.hasTwo(row)&&OrbMatchPolicy.accepts(pairs.bestDistance(row),pairs.secondDistance(row)))votes.merge(owners.get(row),1,Integer::sum);
            List<Template> ranked=new ArrayList<>(votes.keySet());ranked.removeIf(t->UnifiedReferenceCatalog.negative(t.spec));ranked.sort((a,b)->Integer.compare(votes.get(b),votes.get(a)));
            Match best=null;
            for(Template t:ranked.subList(0,Math.min(12,ranked.size()))){
                NearestDescriptorPairs localPairs=NearestDescriptorPairs.compute(t.descriptors,liveDescriptors);List<DMatch> filtered=new ArrayList<>();
                for(int i=0;i<localPairs.size();i++)if(localPairs.hasTwo(i)&&OrbMatchPolicy.accepts(localPairs.bestDistance(i),localPairs.secondDistance(i)))filtered.add(localPairs.best(i));
                List<DMatch> good=ComponentRecognizer.uniqueFrameMatches(filtered);if(good.size()<15)continue;
                List<Point> src=new ArrayList<>(),dst=new ArrayList<>();
                for(DMatch m:good){if(m.queryIdx<t.points.size()&&m.trainIdx<live.length){src.add(t.points.get(m.queryIdx));dst.add(live[m.trainIdx].pt);}}
                if(src.size()<15)continue;MatOfPoint2f a=new MatOfPoint2f(),b=new MatOfPoint2f();Mat mask=new Mat(),h=null;
                try{a.fromList(src);b.fromList(dst);h=Calib3d.findHomography(a,b,Calib3d.RANSAC,3.5,mask,1000,.995);
                    if(h.empty())continue;int in=Core.countNonZero(mask);if(in<15||in<src.size()*.6)continue;
                    double minX=Double.MAX_VALUE,minY=minX,maxX=-minX,maxY=-minX;
                    for(int i=0;i<src.size();i++)if(mask.get(i,0)[0]!=0){Point p=src.get(i);minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
                    if(maxX-minX<t.width*.2||maxY-minY<t.height*.2)continue;
                    List<VisionPoint> measured=new ArrayList<>();for(int j=0;j<src.size();j++)if(mask.get(j,0)[0]!=0)measured.add(new VisionPoint(src.get(j).x/t.width,src.get(j).y/t.height));
                    int identity=identitySupport(t.spec,measured);if(identity<0)continue;
                    Match m=new Match(t,good.size(),t.descriptors.rows(),in,identity,h);if(m.targetCorners.size()!=4)continue;if(best==null||m.score>best.score)best=m;
                }finally{a.release();b.release();mask.release();if(h!=null)h.release();}
            }return best;
        }
    }
    private int overlayCursor;
    /** Bounded, rotating all-phase visual search. Lower-support fits are ACQUIRING only;
     * they cannot reject a step, trigger a wrong-item voice prompt, or verify hardware. */
    public synchronized Match queryForOverlay(Mat liveDescriptors,KeyPoint[] live,String expected,int phase){
        if(templates.isEmpty()||liveDescriptors==null||liveDescriptors.rows()<2||live==null)return null;
        List<Template> preferred=new ArrayList<>(),others=new ArrayList<>();
        for(Template t:templates.values())if(!t.representative.empty()&&!UnifiedReferenceCatalog.negative(t.spec)){
            int p=UnifiedReferenceCatalog.phase(t.spec);boolean same=Objects.equals(expected,t.spec.getComponentId())&&(p==0||p==phase||phase==0&&!UnifiedReferenceCatalog.assembly(t.spec));
            (same?preferred:others).add(t);
        }
        int cursor=overlayCursor++;List<Template> candidates=new ArrayList<>();long deadline=android.os.SystemClock.elapsedRealtimeNanos()+120_000_000L;
        for(int i=0;i<24;i++){if(!preferred.isEmpty())candidates.add(preferred.get(Math.floorMod(cursor*12+i,preferred.size())));if(!others.isEmpty())candidates.add(others.get(Math.floorMod(cursor*24+i,others.size())));}
        Map<Template,Integer> scores=new IdentityHashMap<>();int visited=0;
        for(Template t:candidates){if(visited++>=8&&android.os.SystemClock.elapsedRealtimeNanos()>deadline)break;if(scores.containsKey(t))continue;
            NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(t.representative,liveDescriptors);int n=0;
            for(int j=0;j<pairs.size();j++)if(pairs.hasTwo(j)&&OrbMatchPolicy.accepts(pairs.bestDistance(j),pairs.secondDistance(j)))n++;if(n>=2)scores.put(t,n);
        }
        List<Template> ranked=new ArrayList<>(scores.keySet());ranked.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));Match best=null;Map<String,Match> identities=new HashMap<>();
        for(Template t:ranked.subList(0,Math.min(4,ranked.size()))){
            NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(t.descriptors,liveDescriptors);List<DMatch> raw=new ArrayList<>();for(int j=0;j<pairs.size();j++)if(pairs.hasTwo(j)&&OrbMatchPolicy.accepts(pairs.bestDistance(j),pairs.secondDistance(j)))raw.add(pairs.best(j));
            List<DMatch> good=ComponentRecognizer.uniqueFrameMatches(raw);if(good.size()<8)continue;
            MatOfPoint2f source=new MatOfPoint2f(),target=new MatOfPoint2f();Mat mask=new Mat(),h=null;
            try{List<Point> a=new ArrayList<>(),b=new ArrayList<>();for(DMatch m:good){a.add(t.points.get(m.queryIdx));b.add(live[m.trainIdx].pt);}source.fromList(a);target.fromList(b);
                DiagnosticHammingMatcher.context(t.spec,t.width,t.height);DiagnosticHammingMatcher.attempted(pairs.size());
                h=DiagnosticHammingMatcher.homography(source,target,Calib3d.RANSAC,3,mask,500,.99);if(h.empty())continue;
                int in=Core.countNonZero(mask);if(in<8||in<good.size()*.6)continue;byte[] keep=new byte[(int)mask.total()];mask.get(0,0,keep);
                List<VisionPoint> evidence=new ArrayList<>();for(int j=0;j<a.size();j++)if(keep[j]!=0)evidence.add(new VisionPoint(a.get(j).x/t.width,a.get(j).y/t.height));
                int identity=identitySupport(t.spec,evidence);if(identity<0||!distributed(t,evidence))continue;
                Match m=new Match(t,good.size(),t.descriptors.rows(),in,identity,h);if(m.targetCorners.size()!=4||!validOutline(m.targetCorners))continue;
                String key=m.componentId+(m.assembly?":"+m.phase:"");Match prior=identities.get(key);if(prior==null||m.score>prior.score)identities.put(key,m);
                if(best==null||m.score>best.score)best=m;
            }finally{source.release();target.release();mask.release();if(h!=null)h.release();}
        }
        if(best!=null){for(Match other:identities.values())if(other!=best&&(other.score>=best.score*.8||best.score-other.score<3))return new Match(best,false);}
        return best;
    }
    private static boolean distributed(Template template,List<VisionPoint> inliers){
        double lx=1,ly=1,rx=0,ry=0,bl=1,bt=1,br=0,bb=0;
        for(VisionPoint p:inliers){lx=Math.min(lx,p.getX());ly=Math.min(ly,p.getY());rx=Math.max(rx,p.getX());ry=Math.max(ry,p.getY());}
        for(List<VisionPoint> region:ComponentContourTemplates.forReference(template.spec))for(VisionPoint p:region){bl=Math.min(bl,p.getX());bt=Math.min(bt,p.getY());br=Math.max(br,p.getX());bb=Math.max(bb,p.getY());}
        return rx-lx>=(br-bl)*.2&&ry-ly>=(bb-bt)*.2;
    }
    private static boolean validOutline(List<VisionPoint> corners){double area=0;for(int i=0;i<4;i++){VisionPoint a=corners.get(i),b=corners.get((i+1)%4);if(!Double.isFinite(a.getX()+a.getY()))return false;area+=a.getX()*b.getY()-b.getX()*a.getY();}return area>32;}
    private static boolean compactIdentity(ComponentReferenceSpec s){
        return dev.chyken.assembl_ar.camera.Phase3AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.referenceRole(s.getReferenceId()))
                ||dev.chyken.assembl_ar.camera.Phase5AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.referenceRole(s.getReferenceId()));
    }
    /** -1 means shared PCB/context cannot establish this component's identity. */
    static int identitySupport(ComponentReferenceSpec s,List<VisionPoint> inliers){
        if(!UnifiedReferenceCatalog.assembly(s))return inliers.size();
        boolean compact=compactIdentity(s);
        // P3/P5 feature regions cover the PCB to compute a separate context homography.
        // Their planar quad, not that broad context, identifies the driver or Nano.
        List<List<VisionPoint>> regions=compact?Collections.singletonList(s.getPlanarCornersNormalized()):s.getFeatureRegionsNormalized();
        if(regions.isEmpty())return -1;int identity=inliers.size();
        for(List<VisionPoint> region:regions){
            double lx=1,ly=1,rx=0,ry=0;for(VisionPoint p:region){lx=Math.min(lx,p.getX());ly=Math.min(ly,p.getY());rx=Math.max(rx,p.getX());ry=Math.max(ry,p.getY());}
            int hits=0;double localL=1,localT=1,localR=0,localB=0;
            for(VisionPoint p:inliers){double x=p.getX(),y=p.getY();if(x>=lx&&x<=rx&&y>=ly&&y<=ry){hits++;localL=Math.min(localL,x);localT=Math.min(localT,y);localR=Math.max(localR,x);localB=Math.max(localB,y);}}
            if(hits<(compact?8:4)||compact&&(localR-localL<(rx-lx)*.35||localB-localT<(ry-ly)*.35))return -1;
            if(compact)identity=hits;
        }
        return identity;
    }
    /** Components elsewhere on the same assembled PCB are not competing identities. */
    static boolean competesWithLocalization(RecognitionResult local,List<VisionPoint> candidate){
        if(!local.hasLocatedComponent())return true;
        if(local.getProjectedCorners().size()==4)return samePhysicalTarget(local.getProjectedCorners(),candidate);
        for(RecognitionResult.InstanceObservation part:local.getInstanceObservations())if(samePhysicalTarget(part.getProjectedCorners(),candidate))return true;
        return false;
    }
    static boolean samePhysicalTarget(List<VisionPoint> expected,List<VisionPoint> candidate){
        if(expected.size()!=4||candidate.size()!=4)return false;
        double[] a=bounds(expected),b=bounds(candidate);double aa=(a[2]-a[0])*(a[3]-a[1]),ba=(b[2]-b[0])*(b[3]-b[1]);
        if(!Double.isFinite(aa+ba)||aa<=0||ba<=0||ba/aa<.3||ba/aa>3)return false;
        double overlap=Math.max(0,Math.min(a[2],b[2])-Math.max(a[0],b[0]))*Math.max(0,Math.min(a[3],b[3])-Math.max(a[1],b[1]));
        return overlap/Math.min(aa,ba)>=.6;
    }
    private static double[] bounds(List<VisionPoint> points){double[] b={Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};for(VisionPoint p:points){b[0]=Math.min(b[0],p.getX());b[1]=Math.min(b[1],p.getY());b[2]=Math.max(b[2],p.getX());b[3]=Math.max(b[3],p.getY());}return b;}
}
