"""Photo-level parity check for Phase 2's six hardware ROIs; not an Android timing benchmark."""
from pathlib import Path
import argparse, sys, json
parser=argparse.ArgumentParser();parser.add_argument('--opencv-path');parser.add_argument('--output');args=parser.parse_args()
if args.opencv_path:sys.path.insert(0,args.opencv_path)
import cv2 as cv
import numpy as np
from reference_sampling import reference_pyramid
cv.setNumThreads(2)
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets/references/Phase2'
orb=cv.ORB_create(3200,1.2,8,31,0,2,cv.ORB_HARRIS_SCORE,31,10);bf=cv.BFMatcher(cv.NORM_HAMMING);clahe=cv.createCLAHE(2,(8,8))
def read(p):return cv.imdecode(np.fromfile(p,np.uint8),cv.IMREAD_COLOR)
def resize(im,extent=960):
 scale=min(1,extent/max(im.shape[:2]));return cv.resize(im,None,fx=scale,fy=scale,interpolation=cv.INTER_AREA)
def regions(image):
 h,w=image.shape[:2];mask=cv.inRange(cv.cvtColor(image,cv.COLOR_BGR2HSV),np.array([18,110,95]),np.array([42,255,255]));mask=cv.morphologyEx(mask,cv.MORPH_CLOSE,np.ones((5,5),np.uint8));contours,_=cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)
 boxes=[cv.boundingRect(c) for c in contours if cv.contourArea(c)>=h*w*.0015 and cv.boundingRect(c)[2]*cv.boundingRect(c)[3]<=h*w*.85];boxes.sort(key=lambda b:b[2]*b[3],reverse=True)
 if len(boxes)<2:raise ValueError('Reference does not expose two motor housings')
 result=[]
 for x,y,bh,bv in boxes[:2]:
  result.append((x/w,y/h,(x+bh)/w,(y+bv)/h));pad=min(bh,bv)*.35
  if bv>=bh:result.extend([(max(0,(x-pad)/w),(y+bv*.15)/h,x/w,(y+bv*.85)/h),((x+bh)/w,(y+bv*.15)/h,min(1,(x+bh+pad)/w),(y+bv*.85)/h)])
  else:result.extend([((x+bh*.15)/w,max(0,(y-pad)/h),(x+bh*.85)/w,y/h),((x+bh*.15)/w,(y+bv)/h,(x+bh*.85)/w,min(1,(y+bv+pad)/h))])
 for i,(l,t,r,b) in enumerate(result):
  if i%3==0:continue
  x,y=int(l*w),int(t*h);right,bottom=min(w,int(np.ceil(r*w))),min(h,int(np.ceil(b*h)));crop=image[y:bottom,x:right]
  gray=cv.cvtColor(crop,cv.COLOR_BGR2GRAY);mask=cv.threshold(gray,110,255,cv.THRESH_BINARY_INV)[1];contours,_=cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)
  candidates=[c for c in contours if cv.contourArea(c)>crop.shape[0]*crop.shape[1]*.02 and max(cv.boundingRect(c)[2:])>max(crop.shape[:2])*.2]
  if not candidates:raise ValueError(f'No bracket silhouette at {i}, boxes={boxes[:2]}, region={(x,y,right,bottom)}, minmax={gray.min()},{gray.max()}')
  xx,yy,ww,hh=cv.boundingRect(max(candidates,key=cv.contourArea));result[i]=((x+xx)/w,(y+yy)/h,(x+xx+ww)/w,(y+yy+hh)/h)
 return result
def extract(image,orb,mask,inverted):
 gray=cv.cvtColor(image,cv.COLOR_BGR2GRAY) if image.ndim==3 else image;gray=clahe.apply(gray)
 if inverted:gray=cv.bitwise_not(gray)
 keys,rows=orb.detectAndCompute(gray,mask);return np.array([k.pt for k in keys],np.float32).reshape(-1,2),rows
photos=sorted((assets/'STEP2_5_HARDWARE_CHECK_TOPVIEW').glob('*.jpg'));bank=[]
for p in photos[:4]:
 image=resize(read(p));h,w=image.shape[:2];rs=regions(image);mask=np.zeros((h,w),np.uint8)
 for l,t,r,b in rs:cv.rectangle(mask,(round(l*(w-1)),round(t*(h-1))),(round(r*(w-1)),round(b*(h-1))),255,-1)
 for inverted in [False,True]:
  keys,rows=reference_pyramid(image,orb,mask,inverted,extract)
  normalized=clahe.apply(cv.cvtColor(image,cv.COLOR_BGR2GRAY));patches=[]
  for l,t,r,b in rs:
   crop=normalized[int(t*h):min(h,int(np.ceil(b*h))),int(l*w):min(w,int(np.ceil(r*w)))];patches.append(cv.resize(crop,(32,64),interpolation=cv.INTER_AREA))
  bank.append((p.name,w,h,rs,keys,rows,patches))
def inspect(frame):
 gray=clahe.apply(cv.cvtColor(frame,cv.COLOR_BGR2GRAY));keys,rows=orb.detectAndCompute(gray,None);live=np.array([k.pt for k in keys],np.float32)
 best=0;counts=[];debug=[]
 if rows is None:return best,counts
 for name,w,h,rs,source,descriptors,patches in bank:
  pairs=bf.knnMatch(descriptors,rows,k=2);good=[m[0] for m in pairs if len(m)==2 and m[0].distance<=64 and m[0].distance<.76*m[1].distance];good.sort(key=lambda m:m.distance);used=set();unique=[]
  for m in good:
   if m.trainIdx not in used:used.add(m.trainIdx);unique.append(m)
  if len(unique)<16:continue
  a=np.array([source[m.queryIdx] for m in unique],np.float32);b=np.array([live[m.trainIdx] for m in unique],np.float32);H,mask=cv.findHomography(a,b,cv.RANSAC,3.5,maxIters=1500,confidence=.995)
  if H is None:continue
  accepted=mask.ravel()!=0;n=int(accepted.sum());ratio=n/len(unique)
  if n<16 or ratio<.5:continue
  points=a[accepted]/[w,h];local=[];looks=[];valid=True
  for i,(l,t,r,b) in enumerate(rs):
   part=points[(points[:,0]>=l)&(points[:,0]<=r)&(points[:,1]>=t)&(points[:,1]<=b)];local.append(len(part));span=.12 if i%3==0 else .05;minimum=6 if i%3==0 else 4
   if i%3:
    def appearance(box):
     ll,tt,rr,bb=box;sourcebox=np.array([[ll*w,tt*h],[rr*w,tt*h],[rr*w,bb*h],[ll*w,bb*h]],np.float32);projected=cv.perspectiveTransform(sourcebox[None,:,:],H)[0]
     if np.any(projected<0) or np.any(projected[:,0]>=gray.shape[1]) or np.any(projected[:,1]>=gray.shape[0]):return None
     patch=cv.warpPerspective(gray,cv.getPerspectiveTransform(projected,np.array([[0,0],[31,0],[31,63],[0,63]],np.float32)),(32,64));return patch
    bracket=appearance(rs[i]);reference=patches[i]
    if bracket is None:valid=False
    elif reference.std()<4:
     if bracket.std()>=15 or bracket.mean()>=130 or abs(bracket.mean()-reference.mean())>=45:valid=False
    elif bracket.std()<4:valid=False
    else:
     score=float(cv.matchTemplate(cv.copyMakeBorder(reference,3,3,3,3,cv.BORDER_REPLICATE),bracket,cv.TM_CCOEFF_NORMED).max());looks.append(score)
     if score<.35:valid=False
   else:
    box=np.array([[l*w,t*h],[r*w,t*h],[r*w,b*h],[l*w,b*h]],np.float32);mapped=cv.perspectiveTransform(box[None,:,:],H)[0]
    if np.any(mapped<0) or np.any(mapped[:,0]>=frame.shape[1]) or np.any(mapped[:,1]>=frame.shape[0]):valid=False
    else:
     patch=cv.warpPerspective(frame,cv.getPerspectiveTransform(mapped,np.array([[0,0],[31,0],[31,63],[0,63]],np.float32)),(32,64)).astype(np.float32)
     blue,green,red=patch[:,:,0],patch[:,:,1],patch[:,:,2];maximum=patch.max(axis=2);minimum=patch.min(axis=2)
     yellow=(maximum>35)&(maximum-minimum>maximum*.3)&(green>=red*.57)&(green<=red*1.35)&(blue<red*.65)
     if yellow.mean()<.18:valid=False
    if len(part)<6 or np.ptp(part[:,0])<(r-l)*span or np.ptp(part[:,1])<(b-t)*span:valid=False
  debug.append((name,n*ratio,local,looks))
  if valid and (local[1]+local[2]<4 or local[4]+local[5]<4):valid=False
  if valid and n*ratio>best:best=n*ratio;counts=local
 if best==0:print('closest local evidence:',sorted(debug,key=lambda r:r[1],reverse=True)[:2],flush=True)
 return best,counts
cases=[]
for p in [photos[0],photos[2],photos[4]]:
 frame=cv.resize(read(p),(640,400));cases.append((p.name,frame,True));cases.append((p.name+' rotated180',cv.rotate(frame,cv.ROTATE_180),True))
for p in sorted((assets/'STEP2_BARE_BOARD_INVALID').glob('*.jpg')):cases.append(('bare '+p.name,cv.resize(read(p),(640,400)),False))
frame=cv.resize(read(photos[0]),(640,400));rs=regions(resize(read(photos[0])))
for name,ids in [('covered motor',[0,1,2]),('covered outer bracket',[1])]:
 probe=frame.copy()
 for i in ids:
  l,t,r,b=rs[i];cv.rectangle(probe,(int(l*640),int(t*400)),(int(r*640),int(b*400)),(128,128,128),-1)
 cases.append((name,probe,False))
report=[]
for name,image,expected in cases:
 score,count=inspect(image);passed=(score>=16)==expected;report.append(dict(case=name,expected=expected,accepted=score>=16,score=score,roi_inliers=count,test_passed=passed));print(report[-1],flush=True)
if args.output:Path(args.output).write_text(json.dumps(dict(opencv=cv.__version__,scope='desktop photo parity; not tablet validation',cases=report),indent=2),encoding='utf-8')
raise SystemExit(0 if all(r['test_passed'] for r in report) else 1)
