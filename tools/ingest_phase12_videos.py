"""Reviewed Phase 1/2 raw-video ingestion. No video decode runs on a camera thread.

Select sharp frames only inside the manually reviewed windows below. Save original
pixels (no synthetic edits or baked-in contrast), isolated loose-part crops, authored
hardware masks, provenance, and temporally separate replay probes. Re-run only when
deliberately updating this reviewed dataset. Requires cv2/numpy/Pillow.
"""
from pathlib import Path
import argparse,json,hashlib,sys
p=argparse.ArgumentParser();p.add_argument('--opencv-path');p.add_argument('--source-dir',required=True);args=p.parse_args()
if args.opencv_path:sys.path.insert(0,args.opencv_path)
import cv2 as cv,numpy as np
from PIL import Image,ImageDraw
cv.setNumThreads(2)
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets';tmp=root/'app/build/tmp/phase12-raw';tmp.mkdir(parents=True,exist_ok=True)
records=[];probes=[]
def frame(video,time,sharp=False):
 cap=cv.VideoCapture(str(Path(args.source_dir)/(video+'.mp4')));fps=cap.get(cv.CAP_PROP_FPS);choices=[]
 for t in ([time-.18,time,time+.18] if sharp else [time]):
  cap.set(cv.CAP_PROP_POS_MSEC,t*1000);ok,im=cap.read()
  if not ok:raise RuntimeError(f'Cannot decode {video} {t}')
  gray=cv.cvtColor(im,cv.COLOR_BGR2GRAY);score=cv.Laplacian(gray[40:450,130:900],cv.CV_32F).var();choices.append((score,t,im))
 cap.release();score,t,im=max(choices,key=lambda x:x[0]);return im,t,float(score)
def boxes(im,kind):
 if kind=='bracket':
  gray=cv.cvtColor(im,cv.COLOR_BGR2GRAY);mask=cv.threshold(gray,80,255,cv.THRESH_BINARY_INV)[1];mask[350:]=0;mask[:65]=0;mask[:,:65]=0;mask[:,960:]=0
 else:
  hsv=cv.cvtColor(im,cv.COLOR_BGR2HSV);mask=cv.inRange(hsv,np.array([15,70,40]),np.array([45,255,255]));mask=cv.morphologyEx(mask,cv.MORPH_OPEN,np.ones((5,5),np.uint8))
 mask=cv.morphologyEx(mask,cv.MORPH_CLOSE,np.ones((3,3),np.uint8));cs,_=cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)
 found=[cv.boundingRect(c) for c in cs if cv.contourArea(c)>200 and cv.boundingRect(c)[2]*cv.boundingRect(c)[3]<im.shape[0]*im.shape[1]*.18 and (kind=='bracket' or cv.contourArea(c)>cv.boundingRect(c)[2]*cv.boundingRect(c)[3]*.4)]
 found=sorted(found,key=lambda b:b[2]*b[3],reverse=True)[:4 if kind=='bracket' else 2];return sorted(found,key=lambda b:b[0])
def rect(x,y,r,b,w,h):return [round(x/w,7),round(y/h,7),round(r/w,7),round(b/h,7)]
def put(im,video,t,sharpness,role,index,regions,corners=None):
 folder={'board':'Phase1/STEP1_1MAINBOARDSCAN','bracket':'Phase2/STEP2_1_BRACKETS','brackets':'Phase2/STEP2_1_BRACKETS','motor':'Phase2/STEP2_2_MOTORSCANS','top':'Phase2/STEP2_TOPVIEW_FULL','left':'Phase2/STEP2_TOPVIEW_LEFT','right':'Phase2/STEP2_TOPVIEW_RIGHT'}[role]
 relative=f'references/{folder}/var_light/VIDEO_{video}_{index}.jpg';target=assets/relative;target.parent.mkdir(parents=True,exist_ok=True);cv.imwrite(str(target),im,[cv.IMWRITE_JPEG_QUALITY,96]);records.append(dict(path=relative,video=video,time=round(t,3),sharpness=sharpness,role=role,regions=regions,corners=corners,sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
for video,role,times in [('pETy-FHZ','bracket',[2,12]),('b189KSO5','motor',[.6,17,31])]:
 for n,time in enumerate(times):
  im,t,s=frame(video,time,True)
  objects=boxes(im,'bracket' if role=='bracket' else 'motor')
  if role=='bracket' and n==0:objects=[(167,157,144,182),(335,154,140,186),(494,150,142,185),(650,135,145,190)]
  if role=='bracket':put(im,video,t,s,'brackets',n,[rect(x,y,x+w,y+h,1024,576) for x,y,w,h in objects])
  for j,(x,y,w,h) in enumerate(objects):
   if role=='motor':
    # Silver motor can is always on the right in these reviewed loose-part frames.
    left=max(0,x-16);top=max(0,y-14);right=min(1024,x+w+int(w*.48));bottom=min(576,y+h+14)
   else:left=max(0,x-16);top=max(0,y-12);right=min(1024,x+w+16);bottom=min(576,y+h+12)
   crop=im[top:bottom,left:right].copy();ch,cw=crop.shape[:2];bounds=rect(x-left,y-top,x+w-left,y+h-top,cw,ch)
   if role=='motor':bounds=rect(x-left,y-top,min(cw-5,x+w+int(w*.42)-left),y+h-top,cw,ch)
   put(crop,video,t,s,role,f'{n}_{j}',[],bounds)
# Sharp oblique specular view, complementing the early front-on windows.
# The neighboring 28 s frame remains replay-only, never a registered template.
im,t,s=frame('pETy-FHZ',27)
put(im,'pETy-FHZ',t,s,'brackets',2,[rect(*b,1024,576) for b in [(128,146,335,406),(354,132,556,391),(560,111,763,365),(756,88,969,342)]])
for n,time in enumerate([4,12,23]):
 im,t,s=frame('UZLNjaoq',time,True)
 # Coordinates manually reviewed at these windows; component only, no tablet shadow.
 points=[(340,65,675,462),(305,55,687,505),(292,22,695,490)][n]
 # Third view is rotated; its actual mask is obtained from the bright board silhouette.
 x,y,r,b=points;crop=im[y:b,x:r].copy();put(crop,'UZLNjaoq',t,s,'board',n,[],[.025,.025,.975,.975])
for role,times in [('top',[2,10,16]),('left',[25,30]),('right',[38,41])]:
 for n,time in enumerate(times):
  im,t,s=frame('K-zzGzAP',time,True);h,w=im.shape[:2];found=boxes(im,'motor')
  if role=='top':
   # Manual per-photo motor/canister and four bracket faces. A yellow mask
   # includes shafts/slots and shifts bracket bands onto unchanged PCB printing.
   bounds=[
    [(311,175,420,449),(285,277,311,399),(394,258,417,357),(629,171,739,442),(585,277,627,411),(726,253,752,356)],
    [(348,214,449,463),(320,292,350,405),(424,290,445,389),(634,208,741,456),(608,286,660,397),(728,285,757,378)],
    [(308,203,406,457),(278,286,308,414),(383,284,405,378),(587,194,698,445),(566,279,623,381),(688,278,717,371)]
   ][n]
   regions=[rect(*b,w,h) for b in bounds]
  else:
   # Use the complete rigid side motor: yellow gearbox, silver can, white shaft;
   # exclude the PCB/tabletop and loose leads. The bracket may occlude its center.
   bounds={'left':[(466,245,822,377),(425,191,822,337)],'right':[(241,192,666,347),(133,202,603,374)]}[role][n]
   regions=[rect(*bounds,w,h)]
  put(im,'K-zzGzAP',t,s,role,n,regions)
for video,role,times in [('UZLNjaoq','board',[7,18,29]),('pETy-FHZ','brackets',[8,21,28]),('b189KSO5','motors',[14,19,35]),('K-zzGzAP','top',[6,13,18]),('K-zzGzAP','left',[23,28,33]),('K-zzGzAP','right',[37,40,42])]:
 for time in times:
  im,t,s=frame(video,time);path=tmp/f'heldout-{role}-{time}.jpg';cv.imwrite(str(path),im,[cv.IMWRITE_JPEG_QUALITY,97]);probes.append(dict(path=path.name,video=video,role=role,time=t))
manifest=dict(version=1,description='Manually reviewed raw camera windows; source pixels retained; held-out timestamps never registered',references=records,held_out=probes)
(root/'tools/phase12_video_manifest.json').write_text(json.dumps(manifest,indent=2))
lines=['package dev.chyken.assembl_ar.camera;','import java.util.*;','import dev.chyken.assembl_ar.vision.*;','/** Reviewed raw-video registrations. Generated by tools/ingest_phase12_videos.py. */','public final class Phase12VideoReferences {',' private Phase12VideoReferences(){}',' private static final Map<String,ComponentReferenceSpec> REFS=new LinkedHashMap<>();',' static {']
for row in records:
 role=row['role'];path=row['path'];part={'board':'main_board','bracket':'motor_bracket','brackets':'motor_bracket','motor':'gear_motor_6v'}.get(role,'main_board');width,height={'board':(100,120),'bracket':(25,35),'brackets':(25,35),'motor':(70,25)}.get(role,(100,120));rid=('video12_'+role+'_'+Path(path).stem) if role in ['board','bracket','motor'] else 'p2scene_'+role+'_'+path
 text=f'  add(ComponentReferenceSpec.builder("{rid}","{part}","{part}",{width},{height}).jpegAssetPath("{path}").maximumImageDimension(960).polarityAnchorsVerified(false)'
 if role not in ['board','bracket','motor']:text+='.assemblyStepNumber(2).featureRegionsNormalized(Arrays.asList('+','.join('box('+','.join(map(str,r))+')' for r in row['regions'])+'))'
 if row['corners']:
  l,t,r,b=row['corners'];text+=f'.planarReferenceCornersNormalized(new VisionPoint({l},{t}),new VisionPoint({r},{t}),new VisionPoint({r},{b}),new VisionPoint({l},{b}))'
 if role in ['bracket','motor']:text+='.descriptorRegionNormalization(true)'
 lines.append(text+'.build());')
lines+=[' }',' private static void add(ComponentReferenceSpec s){REFS.put(s.getJpegAssetPath(),s);}',' public static ComponentReferenceSpec find(String path){return REFS.get(path);}',' public static List<ComponentReferenceSpec> phase1(){List<ComponentReferenceSpec> out=new ArrayList<>();for(ComponentReferenceSpec s:REFS.values())if(s.getJpegAssetPath().contains("/Phase1/"))out.add(s);return Collections.unmodifiableList(out);}',' public static Collection<ComponentReferenceSpec> all(){return Collections.unmodifiableCollection(REFS.values());}',' private static List<VisionPoint> box(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}','}']
(root/'app/src/main/java/dev/chyken/assembl_ar/camera/Phase12VideoReferences.java').write_text('\n'.join(lines)+'\n')
sheet=Image.new('RGB',(480*4,300*((len(records)+3)//4)),'white');draw=ImageDraw.Draw(sheet)
for i,row in enumerate(records):
 im=Image.open(assets/row['path']);im.thumbnail((470,270));x=i%4*480;y=i//4*300;sheet.paste(im,(x,y+22));draw.text((x+3,y+3),f"{i} {row['role']} {row['time']:.2f}s",fill='black')
sheet.save(tmp/'registered-contact.jpg');print(json.dumps(dict(references=len(records),held_out=len(probes)),indent=2))
