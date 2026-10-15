package dev.chyken.assembl_ar.vision;

import android.content.res.AssetManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;

/**
 * Matches edges rendered from the project's nominal OBJ meshes against CURRENT camera edges.
 * Board-relative search narrows the location but supplies no positive component evidence.
 * Silhouette, internal edges, every quadrant, and competing model shapes must all agree.
 * A model match is an approximate visible footprint, never proof of insertion or pin direction.
 */
public final class ModelBasedPartRecognizer {
    private static final int WIDTH = 512, HEIGHT = 608, PAD = 112;
    private final Map<String, List<Template>> templates = new HashMap<>();

    public ModelBasedPartRecognizer(AssetManager assets) throws Exception {
        try (InputStream input = assets.open("models/recognition-templates.json")) {
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192]; int count;
            while((count=input.read(buffer))!=-1) data.write(buffer,0,count);
            JSONArray records = new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8))
                    .getJSONArray("templates");
            for (int index=0; index<records.length(); index++) {
                JSONObject record = records.getJSONObject(index);
                String id = record.getString("componentId");
                templates.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new Template(record));
            }
        }
    }

    public Set<String> supportedComponentIds() { return Collections.unmodifiableSet(templates.keySet()); }

    public RecognitionResult recognize(Mat gray, long timestamp, RecognitionRequest request,
                                       RecognitionResult primary, RecognitionResult.SpatialAnchor anchor) {
        if (request == null || request.getExpectation() == null || anchor == null || gray == null
                || gray.empty() || request.getAssemblyStepNumber() == 0
                || anchor.getProjectedCorners().size()!=4
                || !anchor.getComponentId().equals(request.getSpatialAnchorComponentId())) return primary;
        String id = request.getExpectation().getComponentId();
        if (!templates.containsKey(id)) return primary;
        if (primary.getRejectionEvidence() != null && primary.getRejectionEvidence().getReason()
                == RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS) return primary;
        boolean hasPose = LoosePartShapeRecognizer.hasUsableGeometry(primary);
        if (hasPose && !id.equals(primary.getComponentId())
                && !anchor.getComponentId().equals(primary.getComponentId())) return primary;
        if (hasPose && id.equals(primary.getComponentId())
                && primary.getInstanceObservations().size() >= request.getExpectedInstanceCount()) return primary;
        long started=System.nanoTime();

        MatOfPoint2f source = points(anchor.getProjectedCorners());
        MatOfPoint2f target = new MatOfPoint2f(new Point(PAD,PAD),new Point(PAD+WIDTH,PAD),
                new Point(PAD+WIDTH,PAD+HEIGHT),new Point(PAD,PAD+HEIGHT));
        Mat transform = Imgproc.getPerspectiveTransform(source, target);
        Mat inverse = new Mat(), rectified = new Mat(), edges = new Mat(), inverted = new Mat(), distance = new Mat();
        try {
            if (Core.invert(transform,inverse)==0) return primary;
            Imgproc.warpPerspective(gray,rectified,transform,new Size(WIDTH+2*PAD,HEIGHT+2*PAD),
                    Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,Scalar.all(245));
            Imgproc.GaussianBlur(rectified,rectified,new Size(3,3),0);
            Imgproc.Canny(rectified,edges,45,110);
            Core.bitwise_not(edges,inverted);
            Imgproc.distanceTransform(inverted,distance,Imgproc.DIST_L2,3);
            float[] pixels = new float[(int)distance.total()];
            distance.get(0,0,pixels);
            ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forNeobotStep(request.getAssemblyStepNumber());
            List<VisionPoint> canonical = Arrays.asList(new VisionPoint(PAD,PAD),new VisionPoint(PAD+WIDTH,PAD),
                    new VisionPoint(PAD+WIDTH,PAD+HEIGHT),new VisionPoint(PAD,PAD+HEIGHT));
            List<ChassisRelativePlacementGate.TargetProjection> locations = gate.projectTargets(canonical);
            if (request.getExpectedInstanceCount()==1 && locations.size()>1) locations = locations.subList(0,1);
            List<RecognitionResult.InstanceObservation> observations = new ArrayList<>();
            List<List<VisionPoint>> measured = new ArrayList<>();
            double confidence = 1;
            for (ChassisRelativePlacementGate.TargetProjection location : locations) {
                Match match = search(pixels,distance.cols(),distance.rows(),id,location);
                if (match == null) return primary;
                List<VisionPoint> cameraCorners = project(match.corners(),inverse);
                for (List<VisionPoint> other : measured) {
                    if (!ComponentRecognizer.areDistinctInstanceQuads(cameraCorners,other)) return primary;
                }
                measured.add(cameraCorners);
                confidence = Math.min(confidence,match.score);
                observations.add(new RecognitionResult.InstanceObservation("mesh:"+match.template.name,id,
                        RecognitionResult.DetectionMethod.MODEL_EDGE_MATCH,cameraCorners,match.score));
            }
            if (observations.size()!=request.getExpectedInstanceCount()) return primary;
            double[] dimensions = ModelRecognitionCatalog.nominalFaceDimensions(id);
            RecognitionResult.Pose pose = LoosePartShapeRecognizer.estimatePose(measured.get(0),dimensions[0],
                    dimensions[1],request.getCameraCalibration());
            if (pose == null) { Log.d("MeshRecognition", "No approximate pose for " + id); return primary; }
            RecognitionResult.Latency base=primary.getLatency();
            double elapsed=(System.nanoTime()-started)/1_000_000.0;
            RecognitionResult.Latency latency=new RecognitionResult.Latency(base.getFeatureExtractionMs(),
                    base.getMatchingMs(),base.getSpatialValidationMs(),base.getCircularFallbackMs()+elapsed,
                    base.getTotalMs()+elapsed);
            return RecognitionResult.builder(timestamp).identity(observations.get(0).getReferenceId(),id,
                            ModelRecognitionCatalog.displayName(id))
                    .method(RecognitionResult.DetectionMethod.MODEL_EDGE_MATCH)
                    .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                    .orientation(RecognitionResult.OrientationClassification.UNKNOWN,Double.NaN)
                    .polarity(RecognitionResult.PolarityState.NOT_REQUIRED).confidence(confidence)
                    .matches(primary.getFrameKeypointCount(),0,0,0)
                    .corners(measured.get(0)).instances(observations).pose(pose).latency(latency).build();
        } finally {
            distance.release(); inverted.release(); edges.release(); rectified.release(); inverse.release();
            transform.release(); target.release(); source.release();
        }
    }

    private Match search(float[] pixels,int width,int height,String id,
                         ChassisRelativePlacementGate.TargetProjection target) {
        Match best = null;
        List<Match> proposals = new ArrayList<>();
        double area = target.getArea();
        double radius = Math.sqrt(WIDTH*(double)HEIGHT)*.055;
        for (Template template:templates.get(id)) for(double scale:new double[]{.72,.86,1,1.15,1.32}) {
            double w=Math.sqrt(area*template.aspect)*scale, h=Math.sqrt(area/template.aspect)*scale;
            if (Math.min(w,h)<14) continue;
            for(double angle:new double[]{-18,0,18}) for(int y=-3;y<=3;y++) for(int x=-3;x<=3;x++) {
                double cx=target.getCenter().getX()+x*radius/3, cy=target.getCenter().getY()+y*radius/3;
                double fit=coarseFit(template,pixels,width,height,cx,cy,w,h,angle);
                if(fit>.35) {
                    proposals.add(new Match(template,cx,cy,w,h,angle,fit));
                    proposals.sort((a,b)->Double.compare(b.score,a.score));
                    int sameView=0;
                    for(int i=0;i<proposals.size();i++) if(proposals.get(i).template==template
                            && ++sameView>3) {proposals.remove(i);break;}
                }
            }
        }
        // Refine between grid centers, scales and rotations. A coarse search grid alone
        // only recognizes a component when it happens to land on authored sample pixels.
        for(Match proposal:proposals) {
            Match refined=proposal;
            double[] deltas={radius/6,radius/12,1,.5};
            for(int level=0;level<deltas.length;level++) {
                double delta=deltas[level],angleDelta=level==0?4:level==1?2:1;
                Match next=refined;
                for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++)
                    for(double scale:new double[]{1-.04/(level+1),1,1+.04/(level+1)})
                        for(double turn:new double[]{-angleDelta,0,angleDelta}) {
                        double cx=refined.x+x*delta,cy=refined.y+y*delta;
                        double w=refined.w*scale,h=refined.h*scale,angle=refined.angle+turn;
                        double fit=coarseFit(refined.template,pixels,width,height,cx,cy,w,h,angle);
                        if(fit>next.score) next=new Match(refined.template,cx,cy,w,h,angle,fit);
                    }
                refined=next;
            }
            double exact=score(refined.template,pixels,width,height,refined.x,refined.y,refined.w,refined.h,refined.angle);
            if(exact>=.82&&(best==null||exact>best.score))
                best=new Match(refined.template,refined.x,refined.y,refined.w,refined.h,refined.angle,exact);
            // Preserve a stricter supported original when antialiasing favors a softer neighbor.
            double original=score(proposal.template,pixels,width,height,proposal.x,proposal.y,proposal.w,proposal.h,proposal.angle);
            if(original>=.82&&(best==null||original>best.score))
                best=new Match(proposal.template,proposal.x,proposal.y,proposal.w,proposal.h,proposal.angle,original);
        }
        if(best==null) { Log.d("MeshRecognition", "No supported edges for " + id); return null; }
        // A generic rectangle/circle or busy texture must not win purely from the requested ID.
        for(Map.Entry<String,List<Template>> competitor:templates.entrySet()) {
            if(competitor.getKey().equals(id)) continue;
            for(Template other:competitor.getValue()) {
                double areaFound=best.w*best.h;
                double score=score(other,pixels,width,height,best.x,best.y,
                        Math.sqrt(areaFound*other.aspect),Math.sqrt(areaFound/other.aspect),best.angle);
                if(score>=best.score-.045) {
                    Log.d("MeshRecognition", "Ambiguous " + id + "=" + best.score
                            + " vs " + competitor.getKey() + "=" + score);
                    return null;
                }
            }
        }
        Log.d("MeshRecognition", "Matched " + id + "=" + best.score);
        return best;
    }

    private static double coarseFit(Template template,float[] pixels,int width,int height,
                                    double cx,double cy,double w,double h,double angle) {
        double c=Math.cos(Math.toRadians(angle)),s=Math.sin(Math.toRadians(angle));
        double tolerance=Math.max(3,Math.min(w,h)*.07),sum=0;
        for(int i=0;i<template.xy.length;i+=2) {
            double x=(template.xy[i]-.5)*w,y=(template.xy[i+1]-.5)*h;
            int px=(int)Math.round(cx+c*x-s*y),py=(int)Math.round(cy+s*x+c*y);
            if(px<0||py<0||px>=width||py>=height) return 0;
            sum+=Math.max(0,1-pixels[py*width+px]/tolerance);
        }
        return sum/(template.xy.length/2);
    }

    /** Direction-independent chamfer support with separate interior and four-quadrant checks. */
    static double score(Template template,float[] pixels,int width,int height,
                        double cx,double cy,double w,double h,double angle) {
        double radians=Math.toRadians(angle), c=Math.cos(radians), s=Math.sin(radians);
        double tolerance=Math.max(1.2,Math.min(2.4,Math.min(w,h)*.025));
        int[] totals=new int[4],hits=new int[4];
        int internal=0,internalHits=0,matched=0;
        double sum=0;
        for(int i=0;i<template.xy.length;i+=2) {
            double nx=template.xy[i],ny=template.xy[i+1];
            double x=(nx-.5)*w,y=(ny-.5)*h;
            int px=(int)Math.round(cx+c*x-s*y),py=(int)Math.round(cy+s*x+c*y);
            if(px<0||py<0||px>=width||py>=height) return 0;
            double distance=pixels[py*width+px];
            int quadrant=(nx<.5?0:1)+(ny<.5?0:2);
            totals[quadrant]++;
            boolean hit=distance<=tolerance;
            if(hit) { hits[quadrant]++; matched++; }
            if(nx>.15&&nx<.85&&ny>.15&&ny<.85) { internal++; if(hit) internalHits++; }
            sum+=Math.max(0,1-distance/(tolerance*2));
        }
        int count=template.xy.length/2;
        if(matched<count*.76||internal<12||internalHits<internal*.68) return 0;
        for(int i=0;i<4;i++) if(totals[i]<8||hits[i]<totals[i]*.65) return 0;
        // Dense texture can accidentally support all edges. Check the model's own clear
        // regions as negative evidence; a generic density cap rejects detailed valid parts.
        int dense=0,sampled=0;
        for(int i=0;i<template.clear.length;i+=2) {
            double x=(template.clear[i]-.5)*w,y=(template.clear[i+1]-.5)*h;
            int px=(int)Math.round(cx+c*x-s*y),py=(int)Math.round(cy+s*x+c*y);
            if(px>=0&&py>=0&&px<width&&py<height) {
                sampled++; if(pixels[py*width+px]<=tolerance) dense++;
            }
        }
        if(sampled<24||dense>sampled*.30) return 0;
        // Edge pixels move by a pixel under resizing and antialiasing. Rank primarily by
        // supported edge samples, retaining the chamfer term to break equally supported fits.
        return .9*matched/count + .1*sum/count;
    }

    static final class Template {
        final String name; final double aspect; final double[] xy,clear;
        Template(JSONObject record) throws Exception {
            name=record.getString("render"); aspect=record.getDouble("aspect");
            JSONArray points=record.getJSONArray("points"); xy=new double[points.length()*2];
            for(int i=0;i<points.length();i++) {xy[i*2]=points.getJSONArray(i).getDouble(0);xy[i*2+1]=points.getJSONArray(i).getDouble(1);}
            JSONArray empty=record.getJSONArray("clearPoints");clear=new double[empty.length()*2];
            for(int i=0;i<empty.length();i++) {clear[i*2]=empty.getJSONArray(i).getDouble(0);clear[i*2+1]=empty.getJSONArray(i).getDouble(1);}
        }
    }
    private static final class Match {
        final Template template; final double x,y,w,h,angle,score;
        Match(Template t,double x,double y,double w,double h,double angle,double score) {
            template=t;this.x=x;this.y=y;this.w=w;this.h=h;this.angle=angle;this.score=score;
        }
        List<VisionPoint> corners() {
            double c=Math.cos(Math.toRadians(angle)),s=Math.sin(Math.toRadians(angle));
            List<VisionPoint> out=new ArrayList<>();
            for(double[] p:new double[][]{{-.5,-.5},{.5,-.5},{.5,.5},{-.5,.5}})
                out.add(new VisionPoint(x+c*p[0]*w-s*p[1]*h,y+s*p[0]*w+c*p[1]*h));
            return out;
        }
    }
    private static MatOfPoint2f points(List<VisionPoint> input) {
        Point[] out=new Point[input.size()];for(int i=0;i<out.length;i++)out[i]=new Point(input.get(i).getX(),input.get(i).getY());
        return new MatOfPoint2f(out);
    }
    private static List<VisionPoint> project(List<VisionPoint> input,Mat inverse) {
        MatOfPoint2f source=points(input),out=new MatOfPoint2f();
        try { Core.perspectiveTransform(source,out,inverse);List<VisionPoint> points=new ArrayList<>();
            for(Point p:out.toArray())points.add(new VisionPoint(p.x,p.y));return points;
        } finally {out.release();source.release();}
    }
}
