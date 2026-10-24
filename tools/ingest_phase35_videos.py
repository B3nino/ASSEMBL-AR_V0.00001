"""Reviewed Phase 3/5 camera-video references. Never used by a live camera thread.

Only the reviewed windows below are eligible. Original JPEG pixels, separate body
and fixed-board regions, provenance, and disjoint replay timestamps are retained.
No Nano underside is fabricated from the supplied front-face footage.
"""
from pathlib import Path
import argparse, hashlib, json, sys
p=argparse.ArgumentParser();p.add_argument('--opencv-path');p.add_argument('--source-dir',required=True);args=p.parse_args()
if args.opencv_path:sys.path.insert(0,args.opencv_path)
import cv2 as cv
import numpy as np
from PIL import Image,ImageDraw
cv.setNumThreads(2)
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets';out=root/'app/build/tmp/phase35-raw';out.mkdir(parents=True,exist_ok=True)
records=[];probes=[];pending={}
def frame(video,time,sharp=True):
 c=cv.VideoCapture(str(Path(args.source_dir)/(video+'.mp4')));options=[]
 for t in ([time-.16,time,time+.16] if sharp else [time]):
  c.set(cv.CAP_PROP_POS_MSEC,max(0,t)*1000);ok,im=c.read()
  if not ok:raise RuntimeError(f'Cannot decode {video} at {t}')
  gray=cv.cvtColor(im,cv.COLOR_BGR2GRAY);score=float(cv.Laplacian(gray[20:350,160:550],cv.CV_32F).var());options.append((score,t,im))
 c.release();score,t,im=max(options,key=lambda a:a[0]);return im,t,score
def quad(l,t,r,b):return [[l,t],[r,t],[r,b],[l,b]]
def norm(points,w,h):return [[round(float(x)/w,8),round(float(y)/h,8)] for x,y in points]
def board(im,part,installed):
 # Reviewed search lanes exclude the blue tiled tabletop and the other blue PCB.
 h,w=im.shape[:2]
 if part=='driver':lane=(180,20,465,300) if not installed else (230,15,455,245)
 else:lane=(180,20,580,330) if not installed else (225,160,455,368)
 x,y,r,b=lane;patch=im[y:b,x:r];hsv=cv.cvtColor(patch,cv.COLOR_BGR2HSV)
 mask=cv.inRange(hsv,np.array([82,65 if part=='nano' else 35,22]),np.array([138,255,255]));mask=cv.morphologyEx(mask,cv.MORPH_CLOSE,np.ones((7,7),np.uint8))
 cs,_=cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)
 choices=[]
 for c in cs:
  rect=cv.minAreaRect(c);short,long=sorted(rect[1]);area=cv.contourArea(c)
  if short<10 or area<150:continue
  if part=='nano' and not 1.65<long/short<4.5:continue
  choices.append((area,c))
 if not choices:raise RuntimeError(f'No complete {part} inside reviewed lane')
 c=max(choices,key=lambda a:a[0])[1];l,t,rw,bh=cv.boundingRect(c);l+=x;t+=y;r=l+rw;b=t+bh
 # Keep actual chip/PCB identity separate from protruding plugs and terminals.
 identity=quad(l+2,t+2,r-2,b-2)
 # Include the rigid USB/terminal/header extent in the displayed component box.
 margin=3 if installed else 6
 body=quad(max(0,l-margin),max(0,t-margin),min(w-1,r+margin),min(h-1,b+margin+(5 if part=='driver' else 0)))
 return body,identity
def put(video,part,role,time,index):
 im,t,score=frame(video,time,not(video=='JlWZAbKu' and (role=='front' and time in (7,24,30) or role=='assembly' and time in (121.5,123))));h,w=im.shape[:2];installed=role in ('assembly','invalid')
 if video=='JlWZAbKu' and role=='front' and time==7:
  # Reviewed glare splits the blue mask across the USB/chip area. Author the
  # complete rigid body explicitly instead of treating one blue fragment as it.
  body=quad(422,99,500,264);identity=quad(432,105,494,250)
 elif video=='JlWZAbKu' and role=='assembly' and time==121.5:
  body=[[357,169],[410,134],[522,256],[469,294]]
  identity=[[369,173],[407,146],[509,253],[471,282]]
 elif video=='JlWZAbKu' and role=='assembly' and time==123:
  body=[[326,108],[383,80],[457,219],[394,247]]
  identity=[[335,115],[380,91],[445,216],[397,237]]
 elif role=='back':
  # Actual solder-side segment is in deep shadow. Keep it as negative evidence;
  # insufficient texture must remain SEARCHING, never an invented orientation.
  body=quad(296,197,424,352);identity=quad(302,203,414,322)
 else:
  try:body,identity=board(im,part,installed)
  except RuntimeError as e:raise RuntimeError(f'{video} {role} {t}: {e}') from e
 contexts=[]
 if installed:
  l=min(p[0] for p in body);top=min(p[1] for p in body);r=max(p[0] for p in body);b=max(p[1] for p in body);bw=r-l;bh=b-top
  def box(l,t,r,b):return quad(max(1,l),max(1,t),min(w-2,r),min(h-2,b))
  contexts=[box(l-bw*.65,top+bh*.1,l-5,b),box(r+5,top+bh*.1,r+bw*.45,b),box(l,top-bh*.65,r,top-5),box(l,b+5,r,b+bh*.5)]
 else:
  # ORB's 31px border must not erase the narrow Nano's outer pin rows.
  # Retain real surrounding pixels; identity masks still exclude that padding.
  pad=40 if part=='nano' else 10
  l,top=body[0];r,b=body[2];l=max(0,l-pad);top=max(0,top-pad);r=min(w,r+pad);b=min(h,b+pad)
  im=im[top:b,l:r].copy();body=[[px-l,py-top] for px,py in body];identity=[[px-l,py-top] for px,py in identity];h,w=im.shape[:2]
 folder={('driver','front'):'Phase3/STEP3_1MOTORDRIVER',('driver','back'):'Phase3/STEP3_1MOTORDRIVER_INVALID',('driver','assembly'):'Phase3/STEP3_5ASSEMBLYSCAN',('driver','invalid'):'Phase3/STEP3_1MOTORDRIVER_INVALID',('nano','front'):'Phase5/STEP5_1ARDUINONANO',('nano','assembly'):'Phase5/STEP5_3HARDWARESCAN',('nano','invalid'):'Phase5/STEP5_3HARDWARESCANINVALIDS'}[(part,role)]
 path=f'references/{folder}/var_light/VIDEO35_{video}_{part}_{role}_{index}.jpg'
 ok,encoded=cv.imencode('.jpg',im,[cv.IMWRITE_JPEG_QUALITY,96]);assert ok
 pending[path]=encoded.tobytes()
 records.append(dict(path=path,video=video,time=round(t,3),sharpness=score,part=part,role=role,body=norm(body,w,h),identity=norm(identity,w,h),context=[norm(c,w,h) for c in contexts],sha256=hashlib.sha256(pending[path]).hexdigest()))
for video,part,role,times in [('k19nwhAO','driver','front',[4,13,16,36]),('k19nwhAO','driver','back',[24.5]),('k19nwhAO','driver','assembly',[54,64,78,83]),('dn8RmIeI','driver','invalid',[4,9,17,22]),('JlWZAbKu','nano','front',[4,7,16,24,28,30,40]),('JlWZAbKu','nano','invalid',[88,94]),('JlWZAbKu','nano','assembly',[104,116,121.5,123,132,136])]:
 for i,t in enumerate(times):put(video,part,role,t,i)
for video,part,role,times in [('k19nwhAO','driver','front',[7,12,34]),('k19nwhAO','driver','back',[23.5]),('k19nwhAO','driver','assembly',[58,68,80]),('dn8RmIeI','driver','invalid',[6,12,20]),('JlWZAbKu','nano','front',[8,20,37]),('JlWZAbKu','nano','invalid',[90,97]),('JlWZAbKu','nano','assembly',[108,122,139]),('JlWZAbKu','nano','missing',[54,72])]:
 for t in times:
  im,actual,s=frame(video,t,False);name=f'heldout-{part}-{role}-{t}.jpg';cv.imwrite(str(out/name),im,[cv.IMWRITE_JPEG_QUALITY,97]);probes.append(dict(path=name,video=video,part=part,role=role,time=actual))
# Publish only after every reviewed window has decoded and localized successfully.
for path,data in pending.items():
 target=assets/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
manifest=dict(version=1,description='Reviewed actual kit footage; installed reversal differs from loose underside; heldout timestamps are never registered',references=records,held_out=probes)
(root/'tools/phase35_video_manifest.json').write_text(json.dumps(manifest,indent=2))
lines=['package dev.chyken.assembl_ar.camera;','import java.util.*;','import dev.chyken.assembl_ar.vision.*;','/** Reviewed real-video masks. Generated by tools/ingest_phase35_videos.py. */','public final class Phase35VideoReferences {',' private Phase35VideoReferences(){}',' private static final Map<String,ComponentReferenceSpec> REFS=new LinkedHashMap<>();',' private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();',' private static final List<ComponentReferenceSpec> INDIVIDUAL=new ArrayList<>();',' static {']
def javaquad(p):return 'q('+','.join(str(v) for point in p for v in point)+')'
for row in records:
 part=row['part'];role=row['role'];phase=3 if part=='driver' else 5;component='dual_motor_driver' if part=='driver' else 'arduino_nano_microcontroller';name='Motor Driver' if part=='driver' else 'Arduino Nano';w,h=(28,37) if part=='driver' else (18,45);path=row['path'];rid=f'p{phase}scene_{role}_{path}'
 body=javaquad(row['body']);identity=javaquad(row['identity']);regions='Arrays.asList('+','.join([identity]+[javaquad(c) for c in row['context']])+')'
 lines.append(f'  register("{path}","{part}","{role}",{body},{regions},"{rid}","{component}","{name}",{w},{h},{phase});')
lines+=[' }',' public static final class Entry {public final String part,role;public final List<VisionPoint> body;public final List<List<VisionPoint>> regions;Entry(String p,String r,List<VisionPoint>b,List<List<VisionPoint>>f){part=p;role=r;body=b;regions=f;}}',' private static void register(String path,String part,String role,List<VisionPoint>b,List<List<VisionPoint>>f,String id,String component,String name,int w,int h,int phase){','  ComponentReferenceSpec s=ComponentReferenceSpec.builder(id,component,name,w,h).jpegAssetPath(path).assemblyStepNumber(phase).maximumImageDimension(1280).planarReferenceCornersNormalized(b.get(0),b.get(1),b.get(2),b.get(3)).featureRegionsNormalized(f).polarityAnchorsVerified(false).build();REFS.put(path,s);ENTRIES.put(path,new Entry(part,role,b,f));','  if(role.equals("front"))INDIVIDUAL.add(ComponentReferenceSpec.builder("video35_"+part+"_"+path,component,name,w,h).jpegAssetPath(path).maximumImageDimension(1280).planarReferenceCornersNormalized(b.get(0),b.get(1),b.get(2),b.get(3)).featureRegionsNormalized(Collections.singletonList(b)).polarityAnchorsVerified(false).build());',' }',' public static ComponentReferenceSpec find(String path){return REFS.get(path);}',' public static Entry annotation(String path){return ENTRIES.get(path);}',' public static Collection<ComponentReferenceSpec> all(){return Collections.unmodifiableCollection(REFS.values());}',' public static List<ComponentReferenceSpec> individual(){return Collections.unmodifiableList(INDIVIDUAL);}',' private static List<VisionPoint> q(double... v){List<VisionPoint> p=new ArrayList<>();for(int i=0;i<v.length;i+=2)p.add(new VisionPoint(v[i],v[i+1]));return Collections.unmodifiableList(p);}','}']
(root/'app/src/main/java/dev/chyken/assembl_ar/camera/Phase35VideoReferences.java').write_text('\n'.join(lines)+'\n')
sheet=Image.new('RGB',(480*4,300*((len(records)+3)//4)),'white');draw=ImageDraw.Draw(sheet)
for i,row in enumerate(records):
 image=Image.open(assets/row['path']).convert('RGB');w,h=image.size;image.thumbnail((475,270));x=i%4*480;y=i//4*300+24;sheet.paste(image,(x,y));draw.text((x+3,y-22),f"{i} {row['part']} {row['role']} {row['time']:.2f}s",fill='black')
 for j,region in enumerate([row['body']]+row['context']):
  pts=[(x+round(px*image.width),y+round(py*image.height)) for px,py in region];draw.line(pts+[pts[0]],fill=(0,255,120) if j==0 else (255,100,0),width=2)
sheet.save(out/'reviewed-contact.jpg');print(json.dumps(dict(references=len(records),isolated=sum(r['role']=='front' for r in records),held_out=len(probes))))
