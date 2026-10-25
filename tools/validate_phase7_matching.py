"""Phase 7 source localization and leave-one-photo-out ORB regression.

Mirrors the Java policy against the raw assets. Requires numpy + OpenCV; accepts
--opencv-path for a temporary dependency directory. This does not benchmark a
tablet or certify mechanical fastening (the physical checklist does that).
"""
from pathlib import Path
from orb_match_policy import accepts
import sys, json, argparse, time
if '--opencv-path' in sys.argv:
    sys.path.insert(0, sys.argv[sys.argv.index('--opencv-path') + 1])
import cv2 as cv
import numpy as np
from reference_sampling import reference_pyramid
cv.setNumThreads(2)

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/references/Phase7'
ROLES = {
    'STEP7_1CASTERWHEELSCANS':'caster', 'STEP7_1CASTERWHEELSCANS_INVALID':'casterbad',
    'IRTRACKERS_INDIVID':'trackers', 'STEP7_2LINETRACKERSSCANS':'trackers',
    'IRTRACKERS_INDIVID_INVALID':'trackerbad', 'STEP7_3CASTER_BOTTOMSCANS':'casterwork',
    'STEP7_3CASTER_BOTTOMSCANS_INVALID':'casterworkbad',
    'STEP7_4DUALTRACKER SCANS_VALIDS':'trackerwork',
    'STEP7_4DUALTRACKER SCANS_INVALID':'trackerworkbad'}
CLAHE = cv.createCLAHE(2.0, (8,8))
REF = cv.ORB_create(1200, fastThreshold=20)
INSTALLED_REF = cv.ORB_create(3200, fastThreshold=10)
LIVE = cv.ORB_create(3200, fastThreshold=10)
BF = cv.BFMatcher(cv.NORM_HAMMING)

def read(path):
    im = cv.imdecode(np.fromfile(path, np.uint8), cv.IMREAD_COLOR)
    scale = min(1, 1280 / max(im.shape[:2]))
    return cv.resize(im, None, fx=scale, fy=scale, interpolation=cv.INTER_AREA) if scale < 1 else im

def nut_patch(gray,region,diam):
    h,w=gray.shape[:2];l,t,r,b=map(int,region);crop=gray[t:b,l:r];best=None
    if crop.size==0:return region
    edges=cv.Canny(cv.GaussianBlur(crop,(3,3),0),50,120)
    for c in cv.findContours(edges,cv.RETR_LIST,cv.CHAIN_APPROX_SIMPLE)[0]:
        x,y,rw,rh=cv.boundingRect(c);area=cv.contourArea(c);perimeter=cv.arcLength(c,True)
        if not perimeter or not diam*.22<min(rw,rh)<=max(rw,rh)<diam*1.3 or max(rw,rh)/min(rw,rh)>1.7 or 4*np.pi*area/(perimeter*perimeter)<.50:continue
        distance=np.linalg.norm(np.array([x+rw*.5,y+rh*.5])-np.array([crop.shape[1]*.5,crop.shape[0]*.5]))
        score=area/(1+distance/diam*2)
        if best is None or score>best[0]:best=(score,(max(0,l+x-rw*.15),max(0,t+y-rh*.15),min(w,l+x+rw*1.15),min(h,t+y+rh*1.15)))
    if best is None:
        circles=cv.HoughCircles(cv.GaussianBlur(crop,(3,3),0),cv.HOUGH_GRADIENT,1,max(3,diam*.3),param1=100,param2=12,minRadius=max(3,int(diam*.16)),maxRadius=max(4,int(diam*.60)))
        if circles is not None:
            for cx,cy,radius in circles[0]:
                if cx-radius<0 or cy-radius<0 or cx+radius>=crop.shape[1] or cy+radius>=crop.shape[0]:continue
                return (max(0,l+cx-radius*1.4),max(0,t+cy-radius*1.4),min(w,l+cx+radius*1.4),min(h,t+cy+radius*1.4))
    return best[1] if best else region

def tracker_parts(im, locate_nuts=True):
    """Source metadata: full sensor footprints and their fixed mounting-nut patches."""
    h,w=im.shape[:2];gray=cv.cvtColor(im,cv.COLOR_BGR2GRAY)
    blue=cv.inRange(cv.cvtColor(im,cv.COLOR_BGR2HSV),(85,100,70),(135,255,255))
    blue=cv.morphologyEx(blue,cv.MORPH_CLOSE,np.ones((3,3),np.uint8))
    blobs=[]
    for c in cv.findContours(blue,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)[0]:
        x,y,rw,rh=cv.boundingRect(c);area=cv.contourArea(c)
        if area<8 or min(rw,rh)<3:continue
        bright=(gray[y:y+rh,x:x+rw]>130).mean()
        blobs.append((area,np.array([x+rw*.5,y+rh*.5]),max(rw,rh),(x,y,rw,rh),bright))
    pots=sorted((b for b in blobs if b[4]>.20 and max(b[3][2:])/min(b[3][2:])<2.5 and b[2]<min(h,w)*.18),key=lambda b:b[0],reverse=True)[:2]
    bodies=[];nuts=[];identity=[]
    def bounded(points):
        lo=np.maximum(0,np.min(points,axis=0));hi=np.minimum([w,h],np.max(points,axis=0));return (*lo,*hi)
    for area,center,diam,rect,bright in pots:
        leds=[b for b in blobs if b[0]<area*.9 and 1.1*diam<np.linalg.norm(b[1]-center)<4.5*diam and b[2]<diam]
        if not leds:continue
        led=min(leds,key=lambda b:np.linalg.norm(b[1]-center))[1]
        axis=(led-center)/np.linalg.norm(led-center);cross=np.array([-axis[1],axis[0]])
        top=led+axis*diam*1.15;bottom=center-axis*diam*2.8
        bodies.append(bounded([top-cross*diam*.85,top+cross*diam*.85,bottom-cross*diam*.85,bottom+cross*diam*.85]))
        mount=led+axis*diam*.85
        seed=bounded([mount-np.array([diam*.75,diam*.75]),mount+np.array([diam*.75,diam*.75])])
        nuts.append(nut_patch(gray,seed,diam) if locate_nuts else seed)
        x,y,rw,rh=rect;identity.append((max(0,x-rw*.50),max(0,y-rh*.50),min(w,x+rw*1.50),min(h,y+rh*1.50)))
    while len(bodies)<2:bodies.append((0,0,w*.002,h*.002));nuts.append((0,0,w*.002,h*.002));identity.append((0,0,w*.002,h*.002))
    return bodies+nuts+identity

def regions(im, role):
    h,w = im.shape[:2]; installed = 'work' in role; bad = role.endswith('bad')
    if role=='trackerwork':return tracker_parts(im)
    if installed and bad: return [(w*.04,h*.04,w*.96,h*.96)]
    gray = cv.cvtColor(im, cv.COLOR_BGR2GRAY)
    blue = cv.inRange(cv.cvtColor(im,cv.COLOR_BGR2HSV),(85,60,30),(135,255,255))
    ball = None; best = 0; trackers = []
    for threshold in (80,110,140,170):
        mask = cv.threshold(gray,threshold,255,cv.THRESH_BINARY_INV)[1]
        mask = cv.morphologyEx(mask,cv.MORPH_CLOSE,np.ones((3,3),np.uint8))
        for c in cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)[0]:
            x,y,rw,rh = cv.boundingRect(c); area=cv.contourArea(c); aspect=max(rw,rh)/max(1,min(rw,rh))
            if not w*h*.0006 <= area <= w*h*.28: continue
            if aspect < 2 and area/(rw*rh) > .4 and (not installed or (.25<(x+rw*.5)/w<.75 and y+rh*.5<h*.45 and rw<w*.18 and rh<h*.18)):
                score=area/(1+3*abs((x+rw*.5)/w-.5)+3*abs((y+rh*.5)/h-(.22 if installed else .53)))
                if score>best: best=score; ball=(x,y,rw,rh)
            if 1.8<aspect<10 and area>w*h*.001 and (cv.countNonZero(blue[y:y+rh,x:x+rw])>=8 or bad and not installed):
                trackers.append((x,y,rw,rh))
    def box(r,pad):
        x,y,rw,rh=r
        return max(0,x-pad*rw),max(0,y-pad*rh),min(w,x+(1+pad)*rw),min(h,y+(1+pad)*rh)
    if role.startswith('caster'):
        return [(max(0,ball[0]-ball[2]*.85),max(0,ball[1]-ball[3]*.20),min(w,ball[0]+ball[2]*1.85),min(h,ball[1]+ball[3]*1.20)) if installed else box(ball,.12)] if ball else [(0,0,w*.002,h*.002)] if installed else [(w*.20,h*.20,w*.80,h*.82)]
    selected=[]
    for r in sorted(trackers,key=lambda r:r[2]*r[3],reverse=True):
        x,y,rw,rh=r
        if any(max(0,min(x+rw,a+c)-max(x,a))*max(0,min(y+rh,b+d)-max(y,b))/min(rw*rh,c*d)>.30 for a,b,c,d in selected): continue
        selected.append(r)
        if len(selected)==2: break
    result=[box(r,.01) for r in selected]
    if not result: result.append((0,0,w*.002,h*.002))
    return result

def features(im,orb,mask=None,invert=False):
    gray=CLAHE.apply(cv.cvtColor(im,cv.COLOR_BGR2GRAY))
    if invert: gray=cv.bitwise_not(gray)
    kp,desc=orb.detectAndCompute(gray,mask)
    return np.float32([p.pt for p in kp]),desc

def prepare(path,role):
    im=read(path); roi=regions(im,role); mask=None
    if 'work' not in role:
        mask=np.zeros(im.shape[:2],np.uint8)
        for l,t,r,b in roi: cv.rectangle(mask,(round(l),round(t)),(round(r),round(b)),255,-1)
    return dict(path=path,role=role,im=im,regions=roi,variants=[(features(im,INSTALLED_REF if 'work' in role else REF,mask,i) if role in ('trackers','trackerwork') else reference_pyramid(im,INSTALLED_REF if 'work' in role else REF,mask,i,features,(min(r[0] for r in roi),min(r[1] for r in roi),max(r[2] for r in roi),max(r[3] for r in roi)))) for i in (False,True)])

def inside(points,rect):
    l,t,r,b=rect
    return (points[:,0]>=l)&(points[:,0]<=r)&(points[:,1]>=t)&(points[:,1]<=b)

def match(ref,source,descriptors,live,desc,roi=None,force_loose=False,identity=None):
    if desc is None or descriptors is None: return None
    installed='work' in ref['role'] and not force_loose
    query_ids=np.arange(len(source)) if installed else np.flatnonzero(inside(source,roi))
    if len(query_ids)<(18 if installed else 10):return None
    query=descriptors if installed else ref.setdefault('_queries',{}).setdefault((id(descriptors),tuple(roi)),descriptors[query_ids])
    good=sorted((p[0] for p in BF.knnMatch(query,desc,k=2) if len(p)==2 and accepts(p[0].distance,p[1].distance)),key=lambda m:m.distance)
    used=set();a=[];b=[]
    for m in good:
        if m.trainIdx in used: continue
        used.add(m.trainIdx); a.append(source[query_ids[m.queryIdx]]); b.append(live[m.trainIdx])
    if not a:return None
    a=np.float32(a);b=np.float32(b); installed='work' in ref['role'] and not force_loose;bad=ref['role'].endswith('bad')
    selected=np.ones(len(a),bool) if installed and bad else ~np.logical_or.reduce([inside(a,r) for r in ref['regions']]) if installed else inside(a,roi)
    minimum=18 if installed else 10
    if selected.sum()<minimum: return None
    if not installed:
        l,t,r,bot=roi
        if np.ptp(a[selected,0])<(r-l)*.35 or np.ptp(a[selected,1])<(bot-t)*.35:return None
    if identity is not None:
        detail=a[inside(a,identity)];il,it,ir,ib=identity
        if len(detail)<4 or np.ptp(detail[:,0])<(ir-il)*.25 or np.ptp(detail[:,1])<(ib-it)*.25:return None
    h,mask=cv.findHomography(a[selected],b[selected],cv.RANSAC,3.5,maxIters=1600,confidence=.995)
    if h is None or mask.sum()<minimum or mask.sum()<selected.sum()*.45: return None
    score=int(mask.sum())
    if identity is not None:
        projected=cv.perspectiveTransform(a[None],h)[0];local=inside(a,identity)&(np.linalg.norm(projected-b,axis=1)<=4)
        il,it,ir,ib=identity
        if local.sum()<4 or np.ptp(a[local,0])<(ir-il)*.25 or np.ptp(a[local,1])<(ib-it)*.25:return None
        score+=int(local.sum())
    if installed and not bad:
        if len(ref['regions'])<(3 if ref['role']=='trackerwork' else 1):return None
        projected=cv.perspectiveTransform(a[None],h)[0]
        for l,t,r,bot in ref['regions']:
            local=inside(a,(l,t,r,bot))&(np.linalg.norm(projected-b,axis=1)<=4)
            if local.sum()<6 or np.ptp(a[local,0])<(r-l)*.30 or np.ptp(a[local,1])<(bot-t)*.30:return None
            score+=int(local.sum())
    elif not installed:
        l,t,r,bot=roi; inliers=a[selected][mask.ravel()!=0]
        if np.ptp(inliers[:,0])<(r-l)*.35 or np.ptp(inliers[:,1])<(bot-t)*.35:return None
    if installed:
        height,width=ref['im'].shape[:2]; l,t,r,bot=0,0,width,height
    else:l,t,r,bot=roi
    corners=cv.perspectiveTransform(np.float32([[[l,t],[r,t],[r,bot],[l,bot]]]),h)[0]
    if not np.isfinite(corners).all() or cv.contourArea(corners,oriented=True)<120 or np.abs(corners).max()>20000:return None
    return score,corners

def pairs_for(source,descriptors,live,desc):
    if descriptors is None or desc is None:return np.empty((0,2),np.float32),np.empty((0,2),np.float32)
    good=sorted((p[0] for p in BF.knnMatch(descriptors,desc,k=2) if len(p)==2 and accepts(p[0].distance,p[1].distance)),key=lambda m:m.distance)
    used=set();a=[];b=[]
    for m in good:
        if m.trainIdx in used:continue
        used.add(m.trainIdx);a.append(source[m.queryIdx]);b.append(live[m.trainIdx])
    return np.float32(a).reshape(-1,2),np.float32(b).reshape(-1,2)

def mount_inside(corners,point,vertical):
    h=cv.getPerspectiveTransform(np.float32(corners),np.float32([[0,0],[1,0],[1,1],[0,1]]))
    u,v=cv.perspectiveTransform(np.float32([[point]]),h)[0,0]
    along=v if vertical else u
    return -.15<=u<=1.15 and -.15<=v<=1.15 and min(abs(along),abs(1-along))<.35

def orb_nut_proof(source,descriptors,live,desc,roi,h):
    l,t,r,bot=roi;si=np.flatnonzero(inside(source,roi))
    canonical=cv.perspectiveTransform(live[None],np.linalg.inv(h))[0]
    li=np.flatnonzero(inside(canonical,(l-(r-l)*.15,t-(bot-t)*.15,r+(r-l)*.15,bot+(bot-t)*.15)))
    if len(si)<3 or len(li)<3:return False
    good=sorted((p[0] for p in BF.knnMatch(descriptors[si],desc[li],k=2) if len(p)==2 and accepts(p[0].distance,p[1].distance)),key=lambda m:m.distance)
    used=set();points=[]
    for m in good:
        if m.trainIdx in used:continue
        used.add(m.trainIdx);p=source[si[m.queryIdx]];q=live[li[m.trainIdx]]
        expected=cv.perspectiveTransform(np.float32([[p]]),h)[0,0]
        if np.linalg.norm(expected-q)<=4:points.append(p)
    return len(points)>=3 and np.ptp(np.float32(points)[:,0])>=(r-l)*.20 and np.ptp(np.float32(points)[:,1])>=(bot-t)*.20

def tracker_windows(im,color=True):
    h,w=im.shape[:2];windows=[]
    def overlap(a,b):
        l,t,r,bot=a;L,T,R,B=b
        return max(0,min(r,R)-max(l,L))*max(0,min(bot,B)-max(t,T))/max(1,min((r-l)*(bot-t),(R-L)*(B-T)))
    def padded(l,t,r,b):return max(0,int(l)-8),max(0,int(t)-8),min(w,int(r)+8),min(h,int(b)+8)
    if color:
        for l,t,r,b in tracker_parts(im,False)[:2]:
            if r-l>5 and b-t>5:windows.append(padded(l,t,r,b))
    if len(windows)<2:
        gray=cv.cvtColor(im,cv.COLOR_BGR2GRAY);candidates=[]
        blue=cv.inRange(cv.cvtColor(im,cv.COLOR_BGR2HSV),(85,50,20),(135,255,255)) if color else None
        for threshold in (45,65,85):
            mask=cv.morphologyEx(cv.threshold(gray,threshold,255,cv.THRESH_BINARY_INV)[1],cv.MORPH_CLOSE,np.ones((3,3),np.uint8))
            threshold_candidates=[]
            for c in cv.findContours(mask,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE)[0]:
                x,y,rw,rh=cv.boundingRect(c);area=cv.contourArea(c);aspect=max(rw,rh)/max(1,min(rw,rh));box=(x,y,x+rw,y+rh)
                if area<w*h*.001 or area>w*h*.20 or not 1.8<=aspect<=8 or area/(rw*rh)<.25:continue
                if blue is not None and cv.countNonZero(blue[y:y+rh,x:x+rw])<8:continue
                threshold_candidates.append(box)
            threshold_candidates.sort(key=lambda b:(b[2]-b[0])*(b[3]-b[1]),reverse=True)
            for box in threshold_candidates:
                if not any(overlap(box,previous)>.35 for previous in candidates):candidates.append(box)
        candidates.sort(key=lambda b:(b[2]-b[0])*(b[3]-b[1]),reverse=True)
        for box in candidates:
            if not any(overlap(box,previous)>.35 for previous in windows):windows.append(padded(*box))
            if len(windows)==(2 if color else 4):break
    windows.extend([(0,0,w*.56,h),(w*.44,0,w,h),(0,0,w,h*.56),(0,h*.44,w,h),(0,0,w,h)])
    return windows

def recognize_tracker_installation(im,refs,exclude=None,color=True,extent=600):
    live,desc=features(im,LIVE);remaining=desc; bodies=[];score=0
    candidates=sorted([r for r in refs if r['role'] in ('trackers','trackerwork') and (not exclude or r['path'].name!=exclude)],key=lambda r:(r['role']!='trackerwork',r['path'].name),reverse=False)
    windows=tracker_windows(im,color)
    localorb=cv.ORB_create(1200,edgeThreshold=12,fastThreshold=7)
    for l,t,r,b in windows:
        l,t,r,b=map(int,(l,t,r,b));patch=im[t:b,l:r];factor=1 if extent==0 else min(4,extent/max(patch.shape[:2]))
        patch=cv.resize(patch,None,fx=factor,fy=factor,interpolation=cv.INTER_LINEAR)
        local,ld=features(patch,localorb);local=local/factor+np.float32([l,t]);best=None
        if ld is None:continue
        for ref in candidates:
            for source,descriptors in ref['variants']:
                for part,roi in enumerate(ref['regions'][:2]):
                    found=match(ref,source,descriptors,local,ld,roi,force_loose=True,identity=ref['regions'][4+part] if ref['role']=='trackerwork' else None)
                    if found is None:continue
                    if any(cv.intersectConvexConvex(found[1],body[1])[0]>min(cv.contourArea(found[1]),cv.contourArea(body[1]))*.30 for body in bodies):continue
                    if best is None or found[0]>best[0]:best=(*found,(roi[3]-roi[1])>(roi[2]-roi[0]))
            if best is not None and best[0]>=10:break
        if best is None:continue
        bodies.append(best);score+=best[0]
        if len(bodies)==2:break
    mounts=0;context=False;nuts=[False,False]
    if len(bodies)==2:
        for ref in refs:
            if ref['role']!='trackerwork' or exclude and ref['path'].name==exclude:continue
            for source,descriptors in ref['variants']:
                a,b=pairs_for(source,descriptors,live,desc)
                if len(a)<18:continue
                outside=~np.logical_or.reduce([inside(a,roi) for roi in ref['regions']])
                if outside.sum()<18:continue
                h,mask=cv.findHomography(a[outside],b[outside],cv.RANSAC,3.5,maxIters=1600,confidence=.995)
                if h is None or mask.sum()<18 or mask.sum()<outside.sum()*.45:continue
                context=True;slots=[]
                for roi in ref['regions'][2:4]:
                    l,t,r,bot=roi
                    proof=orb_nut_proof(source,descriptors,live,desc,roi,h)
                    point=cv.perspectiveTransform(np.float32([[[(l+r)*.5,(t+bot)*.5]]]),h)[0,0]
                    slots.append([j for j,body in enumerate(bodies) if mount_inside(body[1],point,body[2])])
                    if proof:
                        for body in slots[-1]:nuts[body]=True

                count=2 if any(a!=b for a in slots[0] for b in slots[1]) else 1 if slots[0] or slots[1] else 0
                mounts=max(mounts,count)
                if mounts==2:return dict(count=1,invalid=False,score=score+int(mask.sum()),negative=0,bodies=2,mounts=2,context=True,nuts=sum(nuts))
    return dict(count=0,invalid=False,score=score,negative=0,bodies=len(bodies),mounts=mounts,context=context,nuts=sum(nuts))

def recognize(im,refs,step,exclude=None):
    if step==4:return recognize_tracker_installation(im,refs,exclude)
    live,desc=features(im,LIVE); located=[]; score=negative=0
    eligible={1:('caster','casterbad'),2:('trackers','trackerbad'),3:('casterwork','casterworkbad'),4:('trackerwork','trackerworkbad')}[step]
    for run in range(2 if step==2 else 1):
        best=None
        for ref in refs:
            if ref['role'] not in eligible or exclude and ref['path'].name==exclude:continue
            bad=ref['role'].endswith('bad')
            if run and bad:continue
            for source,descriptors in ref['variants']:
                for roi in ([None] if 'work' in ref['role'] else ref['regions']):
                    found=match(ref,source,descriptors,live,desc,roi)
                    if found is None:continue
                    if bad:negative=max(negative,found[0]);continue
                    if located and cv.intersectConvexConvex(found[1],located[0])[0]>min(cv.contourArea(found[1]),cv.contourArea(located[0]))*.30:continue
                    if best is None or found[0]>best[0]:best=found
        if best is None:break
        score+=best[0];located.append(best[1])
        if step==2 and run==0:
            desc=desc.copy()
            for i,p in enumerate(live):
                if cv.pointPolygonTest(best[1],tuple(map(float,p)),False)>=0:desc[i]=0
    invalid=not located and negative>=18 if step>=3 else negative>=10 and (not located or negative>score*1.25)
    return dict(count=len(located),invalid=bool(invalid),score=score,negative=negative)

def swivel(im,ref,angle):
    result=im.copy();h,w=im.shape[:2]
    for slot,roi in enumerate(ref['regions'][:2]):
        l,t,r,b=map(int,roi);nl,nt,nr,nb=ref['regions'][slot+2];pivot=((nl+nr)*.5,(nt+nb)*.5)
        mask=np.zeros((h,w),np.uint8);mask[t:b,l:r]=255
        transform=cv.getRotationMatrix2D(pivot,angle if slot==0 else -angle,1)
        layer=cv.warpAffine(im,transform,(w,h));rotated=cv.warpAffine(mask,transform,(w,h))
        result[t:b,l:r]=220;result[rotated>0]=layer[rotated>0]
        # The threaded mount stays fixed while the PCB rotates around it.
        nl,nt,nr,nb=map(int,(nl,nt,nr,nb));result[nt:nb,nl:nr]=im[nt:nb,nl:nr]
    return result

def check_tracker_camera_frames(refs):
    """Reproduce duplicate-feature competition at actual camera-sized resolutions.

    Host policy probes search all references without the Android frame-time budget;
    Camera/Instrumentation tests exercise the actual Java budget and continuation.
    """
    positives=[r for r in refs if r['role']=='trackerwork']
    results=[]
    for index in (0,len(positives)//2,len(positives)-1):
        ref=positives[index];im=ref['im'];h,w=im.shape[:2]
        twins=im.copy();l,t,r,b=map(int,ref['regions'][0]);L,T,R,B=map(int,ref['regions'][1])
        twins[T:B,L:R]=cv.resize(im[t:b,l:r],(R-L,B-T))
        def camera(scene):return cv.resize(scene,(640,round(h*640/w)),interpolation=cv.INTER_AREA)
        small=camera(twins)
        cases=[('camera',camera(im),True,True),('identicalPair',small,True,True),
               ('blurredIdenticalPair',cv.GaussianBlur(small,(3,3),.7),True,True),
               ('rotatedIdenticalPair',cv.rotate(small,cv.ROTATE_90_CLOCKWISE),True,True)]
        if index==len(positives)-1:
            cases.append(('grayOnlyIdenticalPair',small,True,False))
            for part,roi in enumerate(ref['regions'][:2]):
                missing=im.copy();l,t,r,b=map(int,roi);missing[t:b,l:r]=220
                cases.append((f'missingModule{part}',camera(missing),False,True))
            cases.append(('onlyOneInFrame',small[:,:small.shape[1]//2],False,True))
            cases.append(('swivelled',camera(swivel(im,ref,20)),True,True))
        for name,scene,wanted,color in cases:
            started=time.perf_counter();outcome=recognize_tracker_installation(scene,refs,ref['path'].name,color)
            # Java visits these sampling scales over separate fresh frames; it never unions
            # one module from one frame with a second module from another frame.
            for extent in (0,400,800):
                if wanted and outcome['count']:break
                alternative=recognize_tracker_installation(scene,refs,ref['path'].name,color,extent)
                if alternative['count']:outcome=alternative
            passed=(outcome['count']>0)==wanted
            row=dict(file=ref['path'].name,case=name,expected_accept=wanted,passed=passed,seconds=round(time.perf_counter()-started,3),**outcome)
            results.append(row);print(json.dumps(row),flush=True)
    invalid=[r for r in refs if r['role']=='trackerworkbad']
    for ref in invalid[::max(1,len(invalid)//3)]:
        im=ref['im'];h,w=im.shape[:2];scene=cv.resize(im,(640,round(h*640/w)))
        outcome=recognize_tracker_installation(scene,refs,ref['path'].name)
        row=dict(file=ref['path'].name,case='invalidCameraFrame',expected_accept=False,passed=outcome['count']==0,**outcome)
        results.append(row);print(json.dumps(row),flush=True)
    dest=ROOT/'app/build/tmp/agent-check/phase7-camera-frame-results.json';dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(json.dumps(results,indent=2))
    print(f'{sum(r["passed"] for r in results)}/{len(results)} camera-frame photo probes passed',flush=True)
    return 0 if all(r['passed'] for r in results) else 1

def main():
    args=argparse.ArgumentParser();args.add_argument('--opencv-path');args.add_argument('--quick',action='store_true');args.add_argument('--roles');args.add_argument('--geometry-only',action='store_true');args.add_argument('--only');args.add_argument('--stage4',action='store_true');args.add_argument('--camera-frames',action='store_true');opts=args.parse_args()
    refs=[prepare(p,role) for folder,role in ROLES.items() for p in sorted((ASSETS/folder).rglob('*')) if p.suffix.lower() in ('.jpg','.png','.jpeg')]
    print(f'Prepared {len(refs)} photos',flush=True)
    if opts.camera_frames:return check_tracker_camera_frames(refs)
    if opts.geometry_only:
        dest=ROOT/'app/build/tmp/agent-check/phase7-regions.json';dest.write_text(json.dumps({str(r['path'].relative_to(ASSETS)):r['regions'] for r in refs},indent=2));return 0
    results=[]
    for role in dict.fromkeys(ROLES.values()):
        if opts.roles and role not in opts.roles.split(','):continue
        if opts.stage4 and role not in ('trackerwork','trackerworkbad'):continue
        selected=[r for r in refs if r['role']==role and (not opts.only or opts.only in r['path'].name)]
        if opts.quick:selected=selected[::max(1,len(selected)//3)]
        step=3 if role.startswith('casterwork') else 4 if role.startswith('trackerwork') else 1 if role.startswith('caster') else 2
        for index,ref in enumerate(selected):
            cases=[('photo',ref['im'])]
            if index==0:
                cases += [('rotated90',cv.rotate(ref['im'],cv.ROTATE_90_CLOCKWISE)),('rotated180',cv.rotate(ref['im'],cv.ROTATE_180))]
                if role=='trackerwork':
                    h,w=ref['im'].shape[:2];crop=ref['im'][int(h*.06):int(h*.49),int(w*.24):int(w*.87)]
                    scale=1280/max(crop.shape[:2]);crop=cv.resize(crop,None,fx=scale,fy=scale)
                    cases += [('frontHalf',crop),('frontHalf90',cv.rotate(crop,cv.ROTATE_90_CLOCKWISE)),('swivel15',swivel(ref['im'],ref,15)),('swivel25',swivel(ref['im'],ref,25))]
                    for slot,(l,t,r,b) in enumerate(ref['regions'][2:4]):
                        missing=ref['im'].copy();l,t,r,b=map(int,(l,t,r,b));missing[t:b,l:r]=40
                        cv.circle(missing,((l+r)//2,(t+b)//2),max(1,int(min(r-l,b-t)*.15)),(220,220,220),-1)
                        cases.append((f'missingNutOpenHole{slot}',missing))
                    for slot,(l,t,r,b) in enumerate(ref['regions'][:2]):
                        missing=ref['im'].copy();l,t,r,b=map(int,(l,t,r,b));missing[t:b,l:r]=220
                        nl,nt,nr,nb=map(int,ref['regions'][slot+2]);missing[nt:nb,nl:nr]=ref['im'][nt:nb,nl:nr]
                        cases.append((f'missingBodyKeepNut{slot}',missing))
                if 'work' in role and not role.endswith('bad'):
                    for part,(l,t,r,b) in enumerate(ref['regions'][:4] if role=='trackerwork' else ref['regions']):
                        missing=ref['im'].copy();missing[max(0,int(t)):int(b),max(0,int(l)):int(r)]=220;cases.append((f'missing{part}',missing))
            for name,im in cases:
                outcome=recognize(im,refs,step,ref['path'].name)
                accepted=not outcome['invalid'] and outcome['count']>=(2 if step==2 else 1)
                hardware_only=role=='trackerwork' and (name.startswith('missingNut') or name in ('missing2','missing3'))
                want=hardware_only or not role.endswith('bad') and not name.startswith('missing') and (step!=2 or 'STEP7_2' in str(ref['path']))
                passed=accepted==want and (not hardware_only or outcome.get('nuts',0)<2)
                result=dict(file=str(ref['path'].relative_to(ASSETS)),case=name,expected_accept=want,**outcome,passed=passed);results.append(result);print(json.dumps(result),flush=True)
    dest=ROOT/'app/build/tmp/agent-check/phase7-photo-results.json';dest.write_text(json.dumps(results,indent=2))
    print(f'{sum(r["passed"] for r in results)}/{len(results)} photo probes passed',flush=True)
    return 0 if all(r['passed'] for r in results) else 1

if __name__=='__main__':sys.exit(main())
