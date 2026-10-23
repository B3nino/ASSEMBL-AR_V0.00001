"""Desktop parity checks for Phase3ReferenceMatcher, not a tablet performance claim.
Uses bundled photos at 640x400 with preserved aspect, held-out references, lighting
changes, partial wire occlusion, and missing/reversed module controls. No image writes.
"""
from pathlib import Path
import json, sys, time
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'app/build/tmp/cv-test-deps'))
import cv2 as cv
import numpy as np
cv.setNumThreads(2)
cv.setRNGSeed(7)
assets=root/'app/src/main/assets/references/Phase3'
clahe=cv.createCLAHE(2,(8,8))
ref_orb=cv.ORB_create(1200,1.2,8,31,0,2,cv.ORB_HARRIS_SCORE,31,20)
live_orb=cv.ORB_create(3200,1.2,8,31,0,2,cv.ORB_HARRIS_SCORE,31,10)
bf=cv.BFMatcher(cv.NORM_HAMMING)

def load(path):
    image=cv.imdecode(np.fromfile(str(path),np.uint8),cv.IMREAD_COLOR)
    s=min(1,1280/max(image.shape[:2]))
    return cv.resize(image,None,fx=s,fy=s,interpolation=cv.INTER_AREA) if s<1 else image

def box(image):
    mask=cv.inRange(cv.cvtColor(image,cv.COLOR_BGR2HSV),(82,65,25),(135,255,255))
    mask=cv.morphologyEx(mask,cv.MORPH_CLOSE,np.ones((13,13),np.uint8))
    contours,_=cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)
    candidates=[c for c in contours if cv.contourArea(c)>image.shape[0]*image.shape[1]*.0005 and cv.boundingRect(c)[2]*cv.boundingRect(c)[3]<image.shape[0]*image.shape[1]*.9]
    x,y,w,h=cv.boundingRect(max(candidates,key=cv.contourArea))
    return np.float32([[x,y],[x+w,y],[x+w,y+h],[x,y+h]])

def features(image,mask=None,reference=False):
    kp,d=(ref_orb if reference else live_orb).detectAndCompute(image,mask)
    return np.float32([p.pt for p in kp]).reshape(-1,2),d

def collect(source,live):
    p,d=source;q,e=live
    if d is None or e is None or len(e)<2:return np.empty((0,2),np.float32),np.empty((0,2),np.float32)
    good=[v[0] for v in bf.knnMatch(d,e,k=2) if len(v)==2 and v[0].distance<65 and v[0].distance<.76*v[1].distance]
    good.sort(key=lambda m:m.distance);seen=set();unique=[]
    for m in good:
        if m.trainIdx not in seen:seen.add(m.trainIdx);unique.append(m)
    return np.float32([p[m.queryIdx] for m in unique]).reshape(-1,2),np.float32([q[m.trainIdx] for m in unique]).reshape(-1,2)

def homography(a,b,n,w,h):
    if len(a)<n:return None,0
    H,mask=cv.findHomography(a,b,cv.RANSAC,3.5,maxIters=1800,confidence=.995)
    if H is None:return None,0
    keep=mask.ravel()!=0;count=int(keep.sum())
    if count<n or count<len(a)*.5:return None,0
    span=np.ptp(a[keep],axis=0)
    return (H,count) if span[0]>=w and span[1]>=h else (None,0)

def project(c,H):return cv.perspectiveTransform(c.reshape(-1,1,2),H).reshape(-1,2)
def state(expected,actual):
    diag=np.linalg.norm(expected[0]-expected[2])
    if diag<24:return 'SEARCHING'
    aligned=np.linalg.norm(expected-actual,axis=1).mean()/diag
    reversed=np.linalg.norm(expected-np.roll(actual,2,axis=0),axis=1).mean()/diag
    if reversed<.28 and reversed+.12<aligned:return 'BACKWARD'
    return 'PLACED' if aligned<.28 else 'MISALIGNED'

def prepare(path):
    image=load(path);c=box(image);x,y=c[0];w,h=c[2]-c[0]
    mask=np.zeros(image.shape[:2],np.uint8);mask[round(image.shape[0]*.02):round(image.shape[0]*.98),round(image.shape[1]*.02):round(image.shape[1]*.98)]=255
    for u in (.12,.88):
        for v in (.18,.80):cv.circle(mask,(round(x+w*u),round(y+h*v)),int(max(3,min(w,h)*.105)),0,-1)
    gray=clahe.apply(cv.cvtColor(image,cv.COLOR_BGR2GRAY));out=[]
    for photometric in (gray,cv.bitwise_not(gray)):
        p,d=features(photometric,mask,True)
        if d is None:continue
        inside=(p[:,0]>=x)&(p[:,0]<=x+w)&(p[:,1]>=y)&(p[:,1]<=y+h)
        outside=(p[:,0]<x-w*.15)|(p[:,0]>x+w*1.15)|(p[:,1]<y-h*.15)|(p[:,1]>y+h*1.15)
        out.append((path.name,c,(p[inside],d[inside]),(p[outside],d[outside])))
    return out

def inspect(gray,bank):
    live=features(clahe.apply(gray));scores={'PLACED':0,'BACKWARD':0};pending=[]
    for name,c,local,context in bank:
        w,h=c[2]-c[0];a,b=collect(local,live);H,n=homography(a,b,8,max(15,w*.45),max(15,h*.45))
        if H is None:pending.append((len(a),name,c,local,context));continue
        ba,bb=collect(context,live);B,bn=homography(ba,bb,16,15,15)
        if B is None:continue
        s=state(project(c,B),project(c,H))
        if s in scores:scores[s]=max(scores[s],n)
        if max(scores.values())>=16:break
    if max(scores.values())<8:
        tried=set()
        for bn,name,c,local,context in sorted(pending,key=lambda v:-v[0]):
            if name in tried:continue
            if len(tried)>=4:break
            tried.add(name);ba,bb=collect(context,live);B,bn=homography(ba,bb,16,15,15)
            if B is None:continue
            expected=project(c,B);l,t=expected.min(axis=0);r,b=expected.max(axis=0);px=(r-l)*.3;py=(b-t)*.3
            x=max(0,int(np.floor(l-px)));y=max(0,int(np.floor(t-py)));right=min(gray.shape[1],int(np.ceil(r+px)));bottom=min(gray.shape[0],int(np.ceil(b+py)))
            if right-x<24 or bottom-y<24:continue
            boost=min(4,max(1,360/max(right-x,bottom-y)));crop=cv.resize(gray[y:bottom,x:right],(round((right-x)*boost),round((bottom-y)*boost)),interpolation=cv.INTER_LINEAR)
            kp,d=features(clahe.apply(crop));kp[:,0]=x+kp[:,0]*(right-x)/crop.shape[1];kp[:,1]=y+kp[:,1]*(bottom-y)/crop.shape[0]
            a,b=collect(local,(kp,d));w,h=c[2]-c[0];H,n=homography(a,b,8,max(15,w*.45),max(15,h*.45))
            if H is None:continue
            s=state(expected,project(c,H))
            if s in scores:scores[s]=max(scores[s],n)
    return 'PLACED' if scores['PLACED']>=8 and scores['PLACED']>scores['BACKWARD']*1.15 else 'BACKWARD' if scores['BACKWARD']>=8 and scores['BACKWARD']>scores['PLACED']*1.15 else 'SEARCHING',scores

def frame(image,c):
    if image.shape[0]>image.shape[1]:
        h=image.shape[0];image=cv.rotate(image,cv.ROTATE_90_CLOCKWISE);c=np.stack([h-1-c[:,1],c[:,0]],axis=1)
    s=min(640/image.shape[1],400/image.shape[0]);w,h=round(image.shape[1]*s),round(image.shape[0]*s);x=(640-w)//2;y=(400-h)//2
    out=np.full((400,640),160,np.uint8);out[y:y+h,x:x+w]=cv.resize(cv.cvtColor(image,cv.COLOR_BGR2GRAY),(w,h),interpolation=cv.INTER_AREA)
    return out,c*s+np.float32([x,y])

def main():
    files=sorted((assets/'STEP3_5ASSEMBLYSCAN').glob('*.jpg'))+sorted((assets/'STEP3_1MOTORDRIVER').glob('*PLACEMENT*.jpg'))
    samples=[files[0],next(p for p in files if '(23)' in p.name)]
    bank=[ref for p in files if p not in samples for ref in prepare(p)]
    results=[]
    for sample in samples:
        image=load(sample);gray,c=frame(image,box(image));l,t=np.floor(c.min(axis=0)).astype(int);r,b=np.ceil(c.max(axis=0)).astype(int)
        wire=gray.copy();cv.line(wire,(l,t+3),(r,t+max(4,(b-t)//7)),35,3)
        missing=gray.copy();missing[t:b,l:r]=170
        backward=gray.copy();backward[t:b,l:r]=cv.rotate(backward[t:b,l:r],cv.ROTATE_180)
        uneven=np.clip(gray.astype(float)*np.linspace(.4,1.2,640)[None,:],0,255).astype(np.uint8)
        for label,im,expected in [('normal',gray,True),('weak',np.uint8(gray*.55),True),('bright',np.clip(gray.astype(int)+45,0,255).astype(np.uint8),True),('uneven',uneven,True),('wire_occlusion',wire,True),('missing',missing,False),('reversed',backward,False)]:
            start=time.perf_counter();actual,scores=inspect(im,bank);passed=(actual=='PLACED')==expected
            row=dict(source=sample.name,condition=label,state=actual,scores=scores,passed=passed,desktop_ms=round((time.perf_counter()-start)*1000))
            results.append(row);print(json.dumps(row),flush=True)
    out=root/'app/build/tmp/phase3-placement-parity.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(dict(opencv=cv.__version__,results=results),indent=2))
    print(f'{sum(r["passed"] for r in results)}/{len(results)} checks passed; {len(bank)} descriptor variants; {out}')
    return int(not all(r['passed'] for r in results))
if __name__=='__main__':raise SystemExit(main())
