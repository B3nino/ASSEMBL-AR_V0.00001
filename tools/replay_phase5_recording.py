"""Regression on UI-masked camera areas from a recording, never training assets.
Desktop OpenCV parity only: these are not raw camera frames or device timings.
"""
from pathlib import Path
from orb_match_policy import accepts
import argparse,json,time,sys
ap=argparse.ArgumentParser();ap.add_argument('--input-dir',type=Path,required=True);ap.add_argument('--opencv-path');ap.add_argument('--controls',action='store_true');ap.add_argument('--baseline',action='store_true');args=ap.parse_args()
if args.opencv_path:sys.path.insert(0,args.opencv_path)
import validate_phase5_matching as p
cv,np=p.cv,p.np
cv.setNumThreads(2);cv.setRNGSeed(7)
photo_orb=cv.ORB_create(2400,1.2,8,12,0,2,cv.ORB_HARRIS_SCORE,31,7)
detail_orb=cv.ORB_create(1200,1.2,8,12,0,2,cv.ORB_HARRIS_SCORE,31,7)
clahe=cv.createCLAHE(2,(8,8));recovery=False

def norm(g,reference=False):
 if recovery and not reference:
  illumination=cv.GaussianBlur(g,(0,0),max(5,min(21,min(g.shape)/18)))
  flat=cv.divide(g,np.maximum(illumination,24),scale=128);g=cv.addWeighted(g,.35,flat,.65,0)
 return clahe.apply(g)

def extract(g,orb,mask=None,reference=False,inverted=False):
 work=norm(g,reference)
 if inverted:work=cv.bitwise_not(work)
 keys,d=orb.detectAndCompute(work,mask)
 return np.float32([k.pt for k in keys]).reshape(-1,2),d

def pairs(source,live,radius=None):
 a,d=source;b,e=live
 if d is None or e is None or len(d)==0 or len(e)<2:return np.empty((0,2),np.float32),np.empty((0,2),np.float32)
 mask=None if radius is None else (np.sum((a[:,None,:]-b[None,:,:])**2,axis=2)<radius*radius).astype(np.uint8)
 matches=cv.BFMatcher(cv.NORM_HAMMING).knnMatch(d,e,k=2,mask=mask)
 good=sorted([m[0] for m in matches if len(m)==2 and accepts(m[0].distance,m[1].distance)],key=lambda m:m.distance)
 used=set();good=[m for m in good if m.trainIdx not in used and not used.add(m.trainIdx)]
 return np.float32([a[m.queryIdx] for m in good]).reshape(-1,2),np.float32([b[m.trainIdx] for m in good]).reshape(-1,2)

def inliers(a,z,H):return int((np.linalg.norm(cv.perspectiveTransform(a[None,:,:],H)[0]-z,axis=1)<=3.5).sum())
def quad(ref):l,t,r,b=ref['rect'];return np.float32([[l,t],[r,t],[r,b],[l,b]])
def project(c,H):return cv.perspectiveTransform(c[None,:,:],H)[0]
def window(ref):
 l,t,r,b=ref['rect'];w=r-l;h=b-t;fh,fw=ref['image'].shape[:2]
 return max(0,int(l-.9*w)),max(0,int(t-.3*h)),min(fw,int(r+.9*w)),min(fh,int(b+.25*h))
def mask(ref):
 l,t,r,b=ref['rect'];x,y,rr,bb=window(ref);m=np.full((bb-y,rr-x),255,np.uint8)
 cv.rectangle(m,(int(l-.15*(r-l)-x),int(t-.15*(b-t)-y)),(int(r+.15*(r-l)-x),int(b+.15*(b-t)-y)),0,-1)
 return m

def prepare(ref):
 l,t,r,b=ref['rect'];x,y,rr,bb=window(ref);g=cv.cvtColor(ref['image'],cv.COLOR_BGR2GRAY);ref['parts']=[]
 for inverted,(pts,d) in enumerate(ref['variants']):
  inside=(pts[:,0]>=l)&(pts[:,0]<=r)&(pts[:,1]>=t)&(pts[:,1]<=b);own=(pts[inside],d[inside]);work=norm(g[y:bb,x:rr],True);work=cv.bitwise_not(work) if inverted else work;kk,e=photo_orb.detectAndCompute(work,mask(ref));ids0=[i for i,k in enumerate(kk) if k.pt[0]+x+k.size*.71<l or k.pt[0]+x-k.size*.71>r or k.pt[1]+y+k.size*.71<t or k.pt[1]+y-k.size*.71>b];q=np.float32([kk[i].pt for i in ids0]).reshape(-1,2)+[x,y];e=e[ids0];count=min(32,len(own[0]));ids=np.arange(count)*len(own[0])//max(1,count)
  ref['parts'].append((own,(q,e),(own[0][ids],own[1][ids])))

def context(g,ref,source,H):
 x,y,rr,bb=window(ref);S=np.eye(3);S[0,2]=-x;S[1,2]=-y
 crop=cv.warpPerspective(g,S@np.linalg.inv(H),(rr-x,bb-y),borderValue=128);q,d=extract(crop,photo_orb,mask(ref));q+=np.float32([x,y])
 l,t,r,b=ref['rect'];a,z=pairs(source,(q,d),np.hypot(r-l,b-t)*.12)
 if len(a)<16:return None
 z=project(z,H);B=p.homography(a,z,16)
 return None if B is None else (B,inliers(a,z,B))

def probe_score(source,live):
 if source[1] is None or live[1] is None or len(source[1])==0 or len(live[1])<2:return 0
 return sum(len(m)==2 and accepts(m[0].distance,m[1].distance) for m in cv.BFMatcher(cv.NORM_HAMMING).knnMatch(source[1],live[1],k=2))

def inspect(g,refs,cursor,preferred):
 live=extract(g,p.LIVE_ORB);rank=[]
 for ref in refs:
  score=max(probe_score(part[2],live) for part in ref['parts']);rank.append((score,ref))
 positive=sorted([v for v in rank if v[1]['role']=='assembly'],key=lambda v:-v[0]);negative=sorted([v for v in rank if v[1]['role']=='invalid'],key=lambda v:-v[0]);chosen=[]
 for group in [positive,negative]:
  chosen+=group[:2]
  for i in range(len(group)):
   v=group[(cursor+i)%len(group)]
   if v not in chosen:chosen.append(v);break
 if preferred:
  for v in positive:
   if str(v[1]['path'])==preferred and v not in chosen:chosen.append(v)
 chosen.sort(key=lambda v:-v[0]);scores={};explicit_invalid=False;body_recovery=0;context_recovery=0;best_id=None
 for probe,ref in chosen:
  if ref['role']=='assembly' and scores.get('placed',0)>=20:continue
  l,t,r,b=ref['rect'];c=quad(ref)
  for own,ctx,_ in ref['parts']:
   a,z=pairs(own,live);H=p.homography(a,z,10,max(15,(r-l)*.45),max(15,(b-t)*.45))
   if H is None and len(a)>=6 and body_recovery<2:
    body_recovery+=1;A,keep=cv.estimateAffinePartial2D(a,z,method=cv.RANSAC,ransacReprojThreshold=3.5,maxIters=600,confidence=.99,refineIters=5)
    if A is not None and keep.sum()>=4:
     c0=project(c,np.vstack([A,[0,0,1]]));lo=c0.min(axis=0);hi=c0.max(axis=0);pad=(hi-lo)*.3;x=max(0,int(np.floor(lo[0]-pad[0])));y=max(0,int(np.floor(lo[1]-pad[1])));rr=min(g.shape[1],int(np.ceil(hi[0]+pad[0])));bb=min(g.shape[0],int(np.ceil(hi[1]+pad[1])))
     if rr-x>=16 and bb-y>=16:
      scale=min(4,max(1,480/max(rr-x,bb-y)));crop=cv.resize(g[y:bb,x:rr],None,fx=scale,fy=scale,interpolation=cv.INTER_LINEAR);q,d=extract(crop,detail_orb);q[:,0]=x+q[:,0]*(rr-x)/crop.shape[1];q[:,1]=y+q[:,1]*(bb-y)/crop.shape[0];a,z=pairs(own,(q,d));H=p.homography(a,z,10,max(15,(r-l)*.45),max(15,(b-t)*.45))
   if H is None:continue
   B=None;cn=0
   if context_recovery<4:
    context_recovery+=1;first=context(g,ref,ctx,H);second=context(g,ref,ctx,H@np.float64([[-1,0,l+r],[0,-1,t+b],[0,0,1]]))
    if first and second:
     if first[1]>second[1]*1.15:B,cn=first
     elif second[1]>first[1]*1.15:B,cn=second
     else:continue
    elif first:B,cn=first
    elif second:B,cn=second
   if B is None:
    ba,bz=pairs(ctx,live);B=p.homography(ba,bz,16)
    if B is not None:cn=inliers(ba,bz,B)
   if B is None:continue
   actual=project(c,H);expected=project(c,B)
   if cv.contourArea(actual,oriented=True)<100 or cv.contourArea(expected,oriented=True)<100:continue
   diag=np.linalg.norm(expected[0]-expected[2]);aligned=np.linalg.norm(expected-actual,axis=1).mean()/diag;rev=np.linalg.norm(expected-np.roll(actual,2,axis=0),axis=1).mean()/diag
   state='backward' if rev<.16 and rev+.12<aligned else 'placed' if aligned<.08 else 'misaligned'
   if ref['role']=='invalid':
    if state!='placed':continue
    state='misaligned';explicit_invalid=True
   n=min(inliers(a,z,H),cn)
   if n>scores.get(state,0):scores[state]=n
   if state=='placed':best_id=str(ref['path'])
 p0=scores.get('placed',0);r0=max(scores.get('backward',0),scores.get('misaligned',0));state='placed' if p0>=10 and p0>r0*1.15 else 'rejected' if r0>=10 and r0>p0*1.15 else 'searching'
 if state=='rejected' and scores.get('misaligned',0)>=scores.get('backward',0) and not explicit_invalid:state='searching'
 return state,scores,best_id


def run_controls(refs):
 positive=next(r for r in refs if r['role']=='assembly');negative=next(r for r in refs if r['role']=='invalid')
 bank=[r for r in refs if r is not positive and r is not negative]
 def small(ref):
  im=ref['image'];scale=min(640/im.shape[1],400/im.shape[0]);g=cv.resize(cv.cvtColor(im,cv.COLOR_BGR2GRAY),None,fx=scale,fy=scale,interpolation=cv.INTER_AREA);return g,quad(ref)*scale
 gray,c=small(positive);bad,_=small(negative);l,t=np.floor(c.min(axis=0)).astype(int);r,b=np.ceil(c.max(axis=0)).astype(int);r=min(r,gray.shape[1]);b=min(b,gray.shape[0])
 missing=gray.copy();missing[t:b,l:r]=128
 reversed_=gray.copy();reversed_[t:b,l:r]=cv.rotate(gray[t:b,l:r],cv.ROTATE_180)
 shifted=missing.copy();dx=max(20,int((r-l)*.35));right=min(gray.shape[1],r+dx);shifted[t:b,l+dx:right]=gray[t:b,l:l+right-l-dx]
 only=np.full(gray.shape,128,np.uint8);only[t:b,l:r]=gray[t:b,l:r]
 weak=cv.convertScaleAbs(gray,alpha=.5,beta=8);side=np.clip(gray.astype(float)*np.linspace(.4,1.05,gray.shape[1])[None,:]+8,0,255).astype(np.uint8)
 results=[]
 for label,g,want in [('held_out_correct',gray,True),('held_out_weak',weak,True),('held_out_side_light',side,True),('held_out_real_invalid',bad,False),('missing_nano',missing,False),('reversed_nano',reversed_,False),('shifted_nano',shifted,False),('nano_without_pcb',only,False)]:
  observations=[];accepted=False
  for attempt in range(6):
   global recovery;recovery=bool(attempt%2);state,scores,found=inspect(g,bank,attempt,None);observations.append(dict(state=state,scores=scores,recovery=recovery));accepted|=state=='placed'
   if accepted:break
  row=dict(control=label,held_out=label.startswith('held_out'),expected_acceptance=want,accepted=accepted,passed=accepted==want,attempts=observations);results.append(row);print(json.dumps(row),flush=True)
 return results

def main():
 refs=[]
 for folder,role in [('STEP5_3HARDWARESCAN','assembly'),('STEP5_3HARDWARESCANINVALIDS','invalid')]:
  for path in sorted((p.ASSETS/folder).glob('*.jpg')):ref=p.prepare(path,role);prepare(ref);refs.append(ref)
 results=[]
 for path in sorted(args.input_dir.glob('camera-area-*.png')):
  gray=cv.imread(str(path),0);preferred=None;out=[];start=time.perf_counter()
  for attempt in range(6):
   global recovery;recovery=bool(attempt%2);state,scores,found=inspect(gray,refs,attempt,preferred);out.append(dict(attempt=attempt,recovery=recovery,state=state,scores=scores))
   if state=='placed':break
  after_ms=round((time.perf_counter()-start)*1000)
  baseline=[]
  if args.baseline:
   for mode in (False,True):
    original=gray
    if mode:
     illumination=cv.GaussianBlur(gray,(0,0),max(5,min(21,min(gray.shape)/18)));original=cv.addWeighted(gray,.35,cv.divide(gray,np.maximum(illumination,24),scale=128),.65,0)
    baseline_start=time.perf_counter();before,bs=p.recognize(cv.cvtColor(original,cv.COLOR_GRAY2BGR),refs,3);baseline.append(dict(recovery=mode,state=before,scores=bs,desktop_ms=round((time.perf_counter()-baseline_start)*1000)))
  row=dict(frame=path.name,baseline=baseline,accepted=state=='placed',attempts=out,after_desktop_ms=after_ms,total_replay_desktop_ms=round((time.perf_counter()-start)*1000));results.append(row);print(json.dumps(row),flush=True)
 controls=[]
 if args.controls:controls=run_controls(refs)
 report=dict(controls=controls,provenance='Compressed recording camera areas; UI masked; regression only, never training. Desktop parity, not a live/native benchmark.',results=results)
 (args.input_dir/'nano-replay-report.json').write_text(json.dumps(report,indent=2));print('accepted',sum(r['accepted'] for r in results),'of',len(results),flush=True)
 if any(not row['passed'] for row in controls):raise SystemExit(1)
if __name__=='__main__':main()
