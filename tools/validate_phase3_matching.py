"""Offline regression of Phase3ReferenceMatcher's photo/geometry policy.

Requires numpy and opencv-python-headless. Runs leave-one-photo-out probes against
the actual assets, plus camera rotations and synthetic wrong/missing module probes.
This is an algorithm check, not an Android camera/device benchmark.
"""
from pathlib import Path
from orb_match_policy import accepts
import json, sys
if "--opencv-path" in sys.argv:sys.path.insert(0,sys.argv[sys.argv.index("--opencv-path")+1])
import cv2 as cv
import numpy as np
from reference_sampling import reference_pyramid
cv.setNumThreads(2)

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets/references/Phase3'
CLAHE=cv.createCLAHE(2.0,(8,8))
REF_ORB=cv.ORB_create(1200,fastThreshold=20)
LIVE_ORB=cv.ORB_create(3200,fastThreshold=10)
BF=cv.BFMatcher(cv.NORM_HAMMING)

def read(path):
    im=cv.imdecode(np.fromfile(path,dtype=np.uint8),cv.IMREAD_COLOR)
    scale=min(1,1280/max(im.shape[:2]))
    return cv.resize(im,None,fx=scale,fy=scale) if scale<1 else im

def module_rect(im):
    mask=cv.inRange(cv.cvtColor(im,cv.COLOR_BGR2HSV),(82,65,25),(135,255,255))
    mask=cv.morphologyEx(mask,cv.MORPH_CLOSE,np.ones((13,13),np.uint8))
    contours,_=cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)
    valid=[c for c in contours if cv.contourArea(c)>im.shape[0]*im.shape[1]*.0005 and cv.boundingRect(c)[2]*cv.boundingRect(c)[3]<im.shape[0]*im.shape[1]*.9]
    x,y,w,h=cv.boundingRect(max(valid,key=cv.contourArea))
    return x,y,x+w,y+h

def features(im,orb,mask=None,inverted=False):
    gray=CLAHE.apply(cv.cvtColor(im,cv.COLOR_BGR2GRAY))
    if inverted:gray=cv.bitwise_not(gray)
    points,desc=orb.detectAndCompute(gray,mask)
    return np.float32([p.pt for p in points]),desc

def prepare(path,role):
    im=read(path);l,t,r,b=module_rect(im);h,w=im.shape[:2]
    mask=np.zeros((h,w),np.uint8)
    if role=='assembly':cv.rectangle(mask,(int(.02*w),int(.02*h)),(int(.98*w),int(.98*h)),255,-1)
    else:cv.rectangle(mask,(l,t),(r,b),255,-1)
    for x in (.12,.88):
        for y in (.18,.80):cv.circle(mask,(round(l+(r-l)*x),round(t+(b-t)*y)),max(3,int(min(r-l,b-t)*.105)),0,-1)
    variants=[(features(im,REF_ORB,mask,inverted) if role=="assembly" else reference_pyramid(im,REF_ORB,mask,inverted,features,(l,t,r,b))) for inverted in (False,True)]
    return dict(path=path,role=role,variants=variants,rect=(l,t,r,b),image=im)

def homography(a,b,minimum,min_width=15,min_height=15):
    if len(a)<minimum:return None
    h,mask=cv.findHomography(a,b,cv.RANSAC,3.5,maxIters=1800,confidence=.995)
    if h is None or mask.sum()<minimum or mask.sum()<len(a)*.5:return None
    pts=a[mask.ravel()!=0]
    if np.ptp(pts[:,0])<min_width or np.ptp(pts[:,1])<min_height:return None
    return h

def match(ref,live,desc,installed):
    if desc is None or ref['desc'] is None:return None
    pairs=BF.knnMatch(ref['desc'],desc,k=2)
    candidates=sorted((p[0] for p in pairs if len(p)==2 and accepts(p[0].distance,p[1].distance)),key=lambda p:p.distance)
    used=set();a=[];b=[]
    for p in candidates:
        if p.trainIdx in used:continue
        used.add(p.trainIdx);a.append(ref['points'][p.queryIdx]);b.append(live[p.trainIdx])
    if not a:return None
    a=np.float32(a);b=np.float32(b);l,t,r,bot=ref['rect']
    mod=(a[:,0]>=l)&(a[:,0]<=r)&(a[:,1]>=t)&(a[:,1]<=bot)
    outside=(a[:,0]<l-(r-l)*.15)|(a[:,0]>r+(r-l)*.15)|(a[:,1]<t-(bot-t)*.15)|(a[:,1]>bot+(bot-t)*.15)
    mh=homography(a[mod],b[mod],8 if installed else 12,max(15,(r-l)*.45),max(15,(bot-t)*.45))
    bh=homography(a[outside],b[outside],16) if installed else None
    if mh is None or installed and bh is None:return None
    corners=np.float32([[[l,t],[r,t],[r,bot],[l,bot]]])
    actual=cv.perspectiveTransform(corners,mh)[0]
    if not np.isfinite(actual).all() or cv.contourArea(actual,oriented=True)<100:return None
    if not installed:return int(mod.sum()),ref['role']
    expected=cv.perspectiveTransform(corners,bh)[0]
    if not np.isfinite(expected).all() or cv.contourArea(expected,oriented=True)<100:return None
    diagonal=np.linalg.norm(expected[0]-expected[2])
    if diagonal<24:return None
    aligned=np.linalg.norm(expected-actual,axis=1).mean()/diagonal
    reversed_=np.linalg.norm(expected-np.roll(actual,2,axis=0),axis=1).mean()/diagonal
    state='backward' if reversed_<.28 and reversed_+.12<aligned else 'placed' if aligned<.28 else 'misaligned'
    return int(mod.sum()),state

def recognize(im,refs,stage,exclude=None):
    points,desc=features(im,LIVE_ORB);scores={}
    for ref in refs:
        if exclude and ref['path'].name==exclude:continue
        if (stage==1)==(ref['role']=='assembly'):continue
        for source,descriptors in ref['variants']:
            variant=dict(ref,points=source,desc=descriptors)
            m=match(variant,points,desc,stage!=1)
            if m:scores[m[1]]=max(scores.get(m[1],0),m[0])
    pos,neg=('front','back') if stage==1 else ('placed','backward')
    p,n=scores.get(pos,0),scores.get(neg,0);minimum=12 if stage==1 else 8
    return pos if p>=minimum and p>n*1.15 else neg if n>=minimum and n>p*1.15 else 'searching',scores

def main():
    refs=[]
    for folder,role in [('MOTORDRIVER_FRONT','front'),('MOTORDRIVER_BACK_INVALID','back'),('STEP3_5ASSEMBLYSCAN','assembly')]:
        photos=sorted(p for p in (ASSETS/folder).rglob('*') if p.suffix.lower() in ('.jpg','.jpeg','.png'))
        if not photos:raise RuntimeError(f'No reference photos in {ASSETS/folder}')
        refs.extend(prepare(p,role) for p in photos)
    results=[]
    for role,stage,expected in [('front',1,'front'),('back',1,'back'),('assembly',4,'placed')]:
        selected=[r for r in refs if r['role']==role]
        for ref in selected:
            cases=[('photo',ref['image'])]
            if role=='assembly':
                cases.extend([('rotated90',cv.rotate(ref['image'],cv.ROTATE_90_CLOCKWISE)),('rotated180',cv.rotate(ref['image'],cv.ROTATE_180))])
                l,t,r,b=ref['rect'];wrong=ref['image'].copy();wrong[t:b,l:r]=cv.rotate(wrong[t:b,l:r],cv.ROTATE_180)
                missing=ref['image'].copy();missing[t:b,l:r]=220
                screws=ref['image'].copy()
                for x in (.12,.88):cv.circle(screws,(round(l+(r-l)*x),round(t+(b-t)*.18)),max(3,round(min(r-l,b-t)*.07)),(170,170,170),-1)
                cases.extend([('backward',wrong),('missing',missing),('screwheads',screws)])
            for name,im in cases:
                want='backward' if name=='backward' else 'searching' if name=='missing' else expected
                state,scores=recognize(im,refs,stage,ref['path'].name)
                ok=state==want or name=='backward' and state=='searching'
                result=dict(file=ref['path'].name,case=name,expected=want,result=state,scores=scores,passed=ok);results.append(result)
                print(json.dumps(result),flush=True)
    report=ROOT/'app/build/tmp/agent-check/phase3-photo-results.json';report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps(results,indent=2))
    print(f'{sum(r["passed"] for r in results)}/{len(results)} photo probes passed')
    return 0 if all(r['passed'] for r in results) else 1

if __name__=='__main__':sys.exit(main())
