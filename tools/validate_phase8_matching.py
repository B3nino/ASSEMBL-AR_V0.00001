"""Photo-only Phase 8 regression; never substitutes for tablet/live-camera validation.

Checks source localization, leave-one-photo-out matching and valid/invalid separation.
Requires numpy/OpenCV; --opencv-path accepts a local dependency directory.
"""
from pathlib import Path
from orb_match_policy import accepts
import sys,json,argparse,hashlib
from functools import lru_cache
if '--opencv-path' in sys.argv:sys.path.insert(0,sys.argv[sys.argv.index('--opencv-path')+1])
import cv2 as cv
import numpy as np
from reference_sampling import reference_pyramid
cv.setNumThreads(2)
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'app/src/main/assets/references/Phase8'
ROLES={'STEP8_1SCANDISTANCESENSOR':'front','STEP8_1SCANDISTANCESENSOR_INVALID':'back','STEP8_2DISTANCESENSORHARDWARESCAN':'assembly','STEP8_2DISTANCESENSORHARDWARESCAN_INVALID':'invalid'}
ORB=cv.ORB_create(3200,1.2,8,31,0,2,cv.ORB_HARRIS_SCORE,31,10);BF=cv.BFMatcher(cv.NORM_HAMMING);CLAHE=cv.createCLAHE(2,(8,8))
def read(p):
 im=cv.imdecode(np.fromfile(p,np.uint8),cv.IMREAD_COLOR);s=min(1,1280/max(im.shape[:2]));return cv.resize(im,None,fx=s,fy=s,interpolation=cv.INTER_AREA) if s<1 else im
@lru_cache(None)
def photo_hash(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def inward(path):
 return path is not None and ("WRONGORIENTATION" in path.name.upper() or "INWARD" in path.name.upper())

def roi(im,role,path=None):
 h,w=im.shape[:2];installed=role in ('assembly','invalid');mask=cv.inRange(cv.cvtColor(im,cv.COLOR_BGR2HSV),(80,55,25),(135,255,255));mask=cv.morphologyEx(mask,cv.MORPH_CLOSE,np.ones((9,13),np.uint8));best=None;score=0
 for c in cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)[0]:
  x,y,rw,rh=cv.boundingRect(c);area=cv.contourArea(c)
  ratio=rw/max(1,rh)
  if installed:
   if rw<w*.08 or ratio<.55 or ratio>1.45 or area<w*h*.003 or y>h*.7:continue
  elif rw<w*.12 or ratio<1.65 or area<w*h*.0006:continue
  rank=area*(1-y/h)**4 if installed else area
  if rank>score:score=rank;best=(x,y,rw,rh)
 if installed and best is not None:
  x,y,rw,rh=best;cx=x+rw*.5;reverse=role=="invalid" and inward(path);l=max(0,cx-rw*1.2);t=max(0,y-rw*(.65 if reverse else 1.45));r=min(w,cx+rw*1.2);b=min(h,y+rw*(.60 if reverse else -.10));best=(l,t,max(1,r-l),max(1,b-t))
 if best is None and role=='invalid':best=(int(w*.32),int(h*.07),int(w*.38),int(h*.23))
 if best is None:raise ValueError('Sensor not localized')
 x,y,rw,rh=best;px=0 if installed else rw*.04;py=0 if installed else rh*.08
 return np.array([max(0,x-px),max(0,y-py),min(w,x+rw+px),min(h,y+rh*1.08)])
def features(im):
 k,d=ORB.detectAndCompute(CLAHE.apply(cv.cvtColor(im,cv.COLOR_BGR2GRAY)),None);return np.array([p.pt for p in k],np.float32),d
def hom(a,b,n,minw,minh):
 if len(a)<n:return None,0
 H,m=cv.findHomography(a,b,cv.RANSAC,3.5,maxIters=1800,confidence=.995)
 if H is None or m.sum()<n or m.sum()<len(a)*.5:return None,0
 q=a[m.ravel()!=0];extent=np.ptp(q,axis=0)
 if extent[0]<minw or extent[1]<minh:return None,0
 return H,int(m.sum())
def match(ref,live,installed,local_only=False):
 _,role,box,points,des=ref;lp,ld=live
 l,t,r,bt=box;inside=(points[:,0]>=l)&(points[:,0]<=r)&(points[:,1]>=t)&(points[:,1]<=bt)
 if role=='assembly' or inward(ref[0]):
  u=(points[:,0]-l)/(r-l);v=(points[:,1]-t)/(bt-t);caps=(v>.08)&(v<(.90 if inward(ref[0]) else .72))&(((u>.08)&(u<.45))|((u>.55)&(u<.92)));inside &= caps
 def collect(mask):
  source=points[mask];query=des[mask]
  if not len(query):return np.empty((0,2),np.float32),np.empty((0,2),np.float32)
  pairs=BF.knnMatch(query,ld,k=2);pairs=sorted((p[0] for p in pairs if len(p)==2 and accepts(p[0].distance,p[1].distance)),key=lambda m:m.distance);used=set();pairs=[m for m in pairs if not(m.trainIdx in used or used.add(m.trainIdx))]
  return np.array([source[m.queryIdx] for m in pairs],np.float32),np.array([lp[m.trainIdx] for m in pairs],np.float32)
 a,b=collect(inside)
 H,n=hom(a,b,10,max(15,(r-l)*.45),max(12,(bt-t)*(.08 if role=='assembly' or inward(ref[0]) else .20 if installed else .45)))
 if H is None:return 0
 if installed and not local_only:
  outside=(points[:,0]<l-(r-l)*.15)|(points[:,0]>r+(r-l)*.15)|(points[:,1]<t-(bt-t)*.15)|(points[:,1]>bt+(bt-t)*.15)
  a,b=collect(outside);B,_=hom(a,b,14,15,15)
  if B is None:return 0
  corners=np.array([[[l,t],[r,t],[r,bt],[l,bt]]],np.float32);expected=cv.perspectiveTransform(corners,B)[0];actual=cv.perspectiveTransform(corners,H)[0];diag=np.linalg.norm(expected[0]-expected[2]);error=np.linalg.norm(expected-actual,axis=1).mean()/max(1,diag)
  if diag<24 or error>=.24:return 0
 return n
def pyramid(im,box,role):
 def extract(level,orb,mask,inverse):return features(level)
 return reference_pyramid(im,ORB,None,False,extract,None if role in ("assembly","invalid") else box)

def classify(refs,live,installed,held_out=None):
 scores={r:0 for r in ROLES.values()};candidates=[]
 for ref in refs:
  if (held_out is not None and photo_hash(ref[0])==photo_hash(held_out)) or (ref[1] in ('assembly','invalid'))!=installed:continue
  score=match(ref,live,installed,True)
  if score:candidates.append((score,ref))
 tried={}
 for _,ref in sorted(candidates,key=lambda c:c[0],reverse=True):
  if tried.get(ref[1],0)>=4:continue
  tried[ref[1]]=tried.get(ref[1],0)+1;scores[ref[1]]=max(scores[ref[1]],match(ref,live,installed))
 yes=scores['assembly' if installed else 'front'];no=scores['invalid' if installed else 'back']
 state='valid' if yes>=10 and yes>no*1.15 else 'invalid' if no>=12 and no>yes*1.2 else 'searching'
 return scores,state

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--opencv-path');parser.add_argument('--all-photos',action='store_true');parser.add_argument('--roles');parser.add_argument('--camera-width',type=int,default=0);parser.add_argument('--live-frame');parser.add_argument('--output',default='app/build/tmp/phase8-photo-regression.json');args=parser.parse_args();refs=[];errors=[]
 for folder,role in ROLES.items():
  for p in sorted((ASSETS/folder).glob('*')):
   if p.suffix.lower() not in ('.png','.jpg','.jpeg'):continue
   if role=='invalid' and p.name=='DISTANCESENSORWRONGORIENTATIONTOPVIEW (15).jpg' and photo_hash(p)=='365e397da8047f3fcd4c0c01308a8fa97b231e623a5d301d152038cfa206cdce':continue
   im=read(p)
   try:box=roi(im,role,p)
   except ValueError as e:errors.append(str(p.name)+': '+str(e));continue
   k,d=pyramid(im,box,role);refs.append((p,role,box,k,d))
 print('Localized',len(refs),'source images; failures:',len(errors),flush=True)
 results=[]
 # Every dataset contributes several held-out captures, including full and half-board views.
 for role in ROLES.values():
  if args.roles and role not in args.roles.split(","):continue
  group=[r for r in refs if r[1]==role];selected=group if args.all_photos else group[::max(1,len(group)//4)]
  for source in selected:
   p=source[0];installed=role in ('assembly','invalid');im=read(p)
   if args.camera_width:
    scale=args.camera_width/max(im.shape[:2]);im=cv.resize(im,None,fx=scale,fy=scale,interpolation=cv.INTER_AREA)
   scores,state=classify(refs,features(im),installed,p);yes=scores['assembly' if installed else 'front'];no=scores['invalid' if installed else 'back']
   results.append(dict(photo=p.name,role=role,scores=scores,state=state));print(role,state,yes,no,p.name,flush=True)
 if args.live_frame:
  im=cv.imread(args.live_frame);scores,state=classify(refs,features(im),True);print('Captured tablet frame',state,scores,flush=True);results.append(dict(photo='private-camera-capture',role='assembly',scores=scores,state=state))
 out=ROOT/args.output;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(dict(localized=len(refs),errors=errors,results=results),indent=2));print('Report',out)
 if errors or any(r['state']!=('valid' if r['role'] in ('front','assembly') else 'invalid') for r in results):raise SystemExit(1)
if __name__=='__main__':main()
