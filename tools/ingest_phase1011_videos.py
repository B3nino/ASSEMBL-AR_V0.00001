"""Reviewed real-video pixels and hardware masks. Offline authoring, never app I/O.

No training pixels are synthesized. Crops retain REAL padding; illumination
normalization is applied symmetrically by the existing descriptor pipeline.
"""
from pathlib import Path
import argparse, hashlib, json, sys
p=argparse.ArgumentParser()
p.add_argument('--source-dir');p.add_argument('--opencv-path');p.add_argument('--verify',action='store_true')
args=p.parse_args();root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets'
manifest_path=root/'tools/phase1011_video_manifest.json'
if args.verify:
    m=json.loads(manifest_path.read_text());train=set();paths=set()
    for r in m['references']:
        assert r['path'] not in paths;paths.add(r['path']);train.add((r['video'],r['time']))
        assert hashlib.sha256((assets/r['path']).read_bytes()).hexdigest()==r['sha256'],r['path']
        for poly in [r['body']]+r['regions']+r['identity']+r['context']+r.get('anchors',[]):
            assert len(poly)==4 and all(0<=x<=1 and 0<=y<=1 for x,y in poly),r['path']
    assert not train.intersection((r['video'],r['time']) for r in m['held_out'])
    for row in m['held_out']:
        fixture=root/'tools/replay_fixtures/phase1011'/row['path']
        assert fixture.is_file() and hashlib.sha256(fixture.read_bytes()).hexdigest()==row['sha256'],row['path']
        if row['video'] in {'kKnJe78e','2ubF9XWd','y5NK9Z5i'}:
            assert all(abs(row['time']-ref['time'])>=2 for ref in m['references'] if ref['video']==row['video']),row['path']
    print(f"Verified {len(paths)} JPEG hashes/masks and {len(m['held_out'])} disjoint replay timestamps")
    raise SystemExit(0)
if not args.source_dir:p.error('--source-dir is required')
if args.opencv_path:sys.path.insert(0,args.opencv_path)
import cv2 as cv
import numpy as np
from PIL import Image,ImageDraw
cv.setNumThreads(2)
out=root/'app/build/tmp/phase1011-raw';out.mkdir(parents=True,exist_ok=True)
records=[];held=[];pending={}
def q(l,t,r,b):return [[l,t],[r,t],[r,b],[l,b]]
def read(v,t):
    c=cv.VideoCapture(str(Path(args.source_dir)/(v+'.mp4')))
    try:
        c.set(cv.CAP_PROP_POS_MSEC,t*1000);ok,image=c.read()
        if not ok:raise RuntimeError(f'Cannot decode {v} at {t}')
        return image
    finally:c.release()
def bounds(poly,pad=0):
    a=np.array(poly);l,t=a.min(axis=0)-pad;r,b=a.max(axis=0)+pad
    return q(int(l),int(t),int(r),int(b))
def add(v,t,phase,role,body,identity=None,context=None,regions=None,crop=False,anchors=None,source_extent=0):
    im=read(v,t)
    if source_extent:im=cv.resize(im,(source_extent,round(im.shape[0]*source_extent/im.shape[1])),interpolation=cv.INTER_AREA)
    identity=identity or [body];context=context or [];regions=regions or [body]
    mask=np.zeros(im.shape[:2],np.uint8)
    for r in identity:cv.fillConvexPoly(mask,np.array(r,np.int32),255)
    lap=cv.Laplacian(cv.cvtColor(im,cv.COLOR_BGR2GRAY),cv.CV_32F);sharp=float(lap[mask!=0].var())
    if crop:
        box=bounds(body,40);l=max(0,box[0][0]);top=max(0,box[0][1]);right=min(im.shape[1],box[2][0]);bottom=min(im.shape[0],box[2][1]);im=im[top:bottom,l:right].copy()
        def shift(p):return [[x-l,y-top] for x,y in p]
        body=shift(body);identity=[shift(r) for r in identity];context=[shift(r) for r in context];regions=[shift(r) for r in regions]
    h,w=im.shape[:2]
    def norm(p):return [[round(x/w,8),round(y/h,8)] for x,y in p]
    folders={'cable':'STEP10_1DATACABLESCANNING','extension':'STEP10_1EXTENSIONBOARDSCAN','extensionbad':'STEP10_1EXTENSIONBOARDSCAN_INVALID','protector':'ISTEP10_1OLEDPROTECTORSCAN','protected':'ISTEP10_3OLEDPROTECTORASSEMBLYSCAN','stacked':'STEP10_4EXTENSIONBOARDMOUNTINGSCAN','front':'STEP11_1OLEDDISPLAYSCAN','back':'STEP11_1OLEDDISPLAYSCAN_INVALID','battery':'STEP11_2BATTERYSCAN','assembly':'STEP11_3OLED_MOUNTED_TOPVIEW'}
    path=f'references/Phase{phase}/{folders[role]}/var_light/VIDEO1011_{v}_{t:g}_{role}.jpg'
    ok,data=cv.imencode('.jpg',im,[cv.IMWRITE_JPEG_QUALITY,97]);assert ok;pending[path]=data.tobytes()
    record=dict(path=path,video=v,time=t,phase=phase,role=role,body=norm(body),regions=[norm(r) for r in regions],identity=[norm(r) for r in identity],context=[norm(r) for r in context],width=w,height=h,sharpness=round(sharp,3),sha256=hashlib.sha256(pending[path]).hexdigest())
    if source_extent:record['source_extent']=source_extent
    if anchors:record['anchors']=[norm(r) for r in anchors]
    records.append(record)

# The real protector is a black printed U-shaped frame. These rotations show
# its known front face; no invented underside/flip rejection label is assigned.
for t,body,rails in [
 (10,q(416,146,704,375),[q(445,150,506,365),q(601,153,674,369),q(469,147,665,212),q(417,306,506,370),q(599,308,703,374)]),
 (12,q(474,166,751,400),[q(510,183,573,370),q(650,188,747,386),q(535,168,733,228)]),
 (23,q(475,178,746,390),[q(500,199,561,374),q(647,204,714,377),q(518,181,716,233)]),
 (31,q(510,225,811,463),[q(534,247,594,439),q(715,253,775,449),q(548,414,752,459)])]:add('j1H57AGW',t,10,'protector',body,rails,crop=True)
# Two actual IDC heads plus the flat ribbon; the surrounding table is excluded.
for t,body,ends in [
 (80,q(138,222,865,367),[q(139,249,233,366),q(752,222,865,343)]),
 (46,[[331,453],[836,94],[884,167],[381,503]],[q(332,394,449,503),q(766,94,884,210)]),
 (55,q(263,200,870,318),[q(263,200,350,306),q(773,215,870,318)]),
 (77,q(190,218,890,341),[q(190,218,280,330),q(769,233,890,341)])]:add('j1H57AGW',t,10,'cable',body,ends+[body],crop=True)
# Component-side headers/buttons, distributed across the physical board. Bottom
# samples are genuine solder-face observations, not rotated front-face copies.
for t,role,body,identity in [
 (90,'extension',q(551,46,940,506),[q(645,155,697,254),q(716,288,780,326),q(582,288,637,440),q(593,462,658,504)]),
 (100,'extension',[[352,50],[682,72],[631,480],[313,441]],[q(465,192,517,245),q(531,279,586,374),q(596,105,661,248),q(600,71,643,111)]),
 (103,'extension',[[263,101],[551,0],[715,329],[414,456]],[q(454,157,495,198),q(536,199,592,276),q(528,46,599,156),q(407,354,498,429)]),
 (107,'extension',[[341,76],[663,56],[685,451],[364,477]],[q(371,173,422,253),q(451,288,507,332),q(381,280,438,423),q(383,424,430,466)]),
 (118,'extensionbad',[[354,82],[681,67],[707,487],[361,491]],[]),
 (123,'extensionbad',[[366,115],[676,98],[693,351],[379,368]],[]),
 (128,'extensionbad',[[414,12],[660,125],[511,486],[211,359]],[])]:
    # Broad fixed-board identity retains distributed silkscreen/solder features;
    # the detector still compares front and bottom candidate families separately.
    add('j1H57AGW',t,10,role,body,identity)
# Top-board-only protector subassembly. Each black rail is independent of the
# unchanged PCB visible through its open center. Fastening remains a checklist.
for t,board,prot,rails,context in [
 (144,[[344,0],[769,0],[782,490],[354,499]],q(442,40,670,222),[q(450,44,489,205),q(611,48,653,206),q(477,42,627,85)],[q(682,37,735,210),q(600,236,658,349),q(449,417,648,466)]),
 (176,[[205,121],[714,89],[741,575],[226,575]],q(341,171,608,367),[q(350,183,397,342),q(548,174,592,342),q(391,171,563,222)],[q(617,147,689,356),q(292,390,540,526),q(555,439,628,575)]),
 (199,[[554,87],[901,120],[864,555],[509,510]],q(619,151,798,291),[q(627,165,663,274),q(749,169,786,277),q(657,154,775,190)],[q(822,168,878,308),q(765,316,820,401),q(591,448,768,512)])]:add('j1H57AGW',t,10,'protected',board,rails,context,[board,prot])

for t,body in [(4,q(414,201,689,357)),(23,[[357,326],[608,298],[620,435],[366,467]]),(28,[[366,357],[580,300],[619,427],[391,487]]),(34,q(404,123,633,287))]:add('FZcZdrAF',t,11,'battery',body,crop=True)
for t,role,body in [
 (40,'front',q(382,125,619,386)),(49,'front',q(415,169,717,500)),(55,'front',[[361,100],[619,45],[674,330],[411,390]]),
 (63,'back',q(420,111,610,307)),(70,'back',q(464,168,689,414))]:add('FZcZdrAF',t,11,role,body,crop=True)
for t,oled,context in [
 (89,q(537,283,689,449),[q(488,127,539,253),q(415,277,478,493),q(681,77,733,160)]),
 (96,q(451,245,610,417),[q(438,98,490,236),q(354,248,417,430),q(634,55,692,139)]),
 (113,q(507,282,663,451),[q(502,104,551,266),q(402,280,472,493),q(677,63,730,153)]),
 (121,q(320,237,458,370),[q(294,100,340,209),q(207,243,270,405),q(499,64,552,142)])]:add('FZcZdrAF',t,11,'assembly',oled,[oled],context)

# Targeted real variants retain a >=2s temporal gap from failed replay frames.
# Hardware anchors bias extraction, not match counts. Nuts hidden below the
# board remain the mandatory physical check; both black rails must still exist.
for t,board,prot,rails,context,anchors in [
 (162,[[477,87],[857,61],[914,512],[520,552]],q(584,131,789,292),[q(595,153,646,283),q(742,141,785,280),q(625,133,760,174)],[q(794,130,834,275),q(744,317,801,398),q(552,444,767,507)],[q(591,246,626,283),q(749,233,790,278)]),
 (187,q(391,96,786,573),q(493,171,686,324),[q(501,186,548,315),q(642,184,682,314),q(530,173,655,211)],[q(695,177,751,315),q(626,353,688,436),q(441,478,659,541)],[q(488,273,531,317),q(650,274,690,317)]),
 (193,q(324,100,746,550),q(435,140,636,296),[q(442,163,493,289),q(588,163,631,291),q(470,141,607,180)],[q(644,154,688,282),q(576,308,626,396),q(377,453,607,503)],[q(434,247,479,290),q(592,248,638,290)])]:
    add('j1H57AGW',t,10,'protected',board,rails,context,[board,prot],anchors=anchors)
for t,oled,context,anchors in [
 (80,q(502,301,647,444),[q(447,168,506,292),q(390,306,438,455),q(653,133,707,218)],[q(522,298,625,327),q(504,311,524,431),q(622,311,647,431),q(517,397,634,440)]),
 (85,q(519,255,667,402),[q(479,113,531,247),q(405,262,453,409),q(678,91,736,206)],[q(540,255,646,283),q(522,267,543,390),q(642,266,666,390),q(535,354,655,399)])]:
    add('FZcZdrAF',t,11,'assembly',oled,[oled],context,anchors=anchors)

# Additional reviewed room recordings; masks are authored at the tablet pixel scale.
for row in json.loads((root/'tools/phase10_followup_annotations.json').read_text())['references']:
    add(row['video'],row['time'],10,row['role'],row['body'],row['identity'],row['context'],row['regions'],anchors=row['anchors'],source_extent=row['source_extent'])

# Real held-out timestamps, including missing OLED/protector/top-board states.
for v,phase,role,times in [
 ('j1H57AGW',10,'protector',[9,27]),('j1H57AGW',10,'cable',[51,79]),('j1H57AGW',10,'extension',[94,104]),('j1H57AGW',10,'extensionbad',[120,129]),('j1H57AGW',10,'protected',[159,190]),('j1H57AGW',10,'missingprotector',[94]),
 ('nHkNoO6g',10,'missingtop',[14,47]),('FZcZdrAF',11,'battery',[9,29]),('FZcZdrAF',11,'batteryblur',[30]),('FZcZdrAF',11,'front',[44,52]),('FZcZdrAF',11,'back',[65,72]),('FZcZdrAF',11,'assembly',[83,92,118]),('j1H57AGW',11,'missingoled',[159,190]),
 ('kKnJe78e',10,'roomcable',[18,27,40,52]),('kKnJe78e',10,'clippedcable',[34]),('2ubF9XWd',10,'roomprotected',[12,20,26,35]),('y5NK9Z5i',10,'roomstacked',[12,18,28,36])]:
    for t in times:
        im=read(v,t);name=f'heldout-p{phase}-{role}-{t}.jpg';ok,data=cv.imencode('.jpg',im,[cv.IMWRITE_JPEG_QUALITY,97]);assert ok;(out/name).write_bytes(data.tobytes());held.append(dict(path=name,video=v,phase=phase,role=role,time=t,sha256=hashlib.sha256(data.tobytes()).hexdigest()))
for path,data in pending.items():target=assets/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
sources=[]
for v in sorted({r['video'] for r in records}|{r['video'] for r in held}):
    path=Path(args.source_dir)/(v+'.mp4');sources.append(dict(file=path.name,bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
manifest_path.write_text(json.dumps(dict(version=1,sources=sources,references=records,held_out=held,absent_video_categories=['distinctly identified protector underside','visible hidden M3 nut tightening','verified battery plug contact or strap tension']),indent=2)+'\n',encoding='utf-8')

def jq(poly):return 'q('+','.join(str(v) for point in poly for v in point)+')'
def jl(polys):return 'Arrays.asList('+','.join(jq(p) for p in polys)+')'
lines=['package dev.chyken.assembl_ar.camera;','import java.util.*;','import dev.chyken.assembl_ar.vision.*;','/** Reviewed real video pixels; generated by tools/ingest_phase1011_videos.py. */','public final class Phase1011VideoReferences {',' public static final int VERSION=6;',' private Phase1011VideoReferences(){}',' private static final Map<String,ComponentReferenceSpec> REFS=new LinkedHashMap<>();',' private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();',' private static final List<ComponentReferenceSpec> INDIVIDUAL=new ArrayList<>();',' static {']
for r in records:
    phase,role=r['phase'],r['role'];component={'cable':'extension_data_cable','extension':'extension_circuit_board','extensionbad':'extension_circuit_board','protector':'oled_protector','protected':'extension_circuit_board','stacked':'extension_circuit_board','front':'oled_096_i2c','back':'oled_096_i2c','battery':'battery_pack_7v_12v','assembly':'oled_096_i2c'}[role]
    w,h=(150,20) if role=='cable' else (40,50) if role=='protector' else (100,180) if phase==10 else (50,35) if role=='battery' else (27,27)
    extra=","+jl(r["anchors"]) if r.get("anchors") else ""
    lines.append(f'  add("{r["path"]}",{phase},"{role}","{component}",{w},{h},{jq(r["body"])},{jl(r["regions"])},{jl(r["identity"])},{jl(r["context"])}{extra});')
lines+=[' }',' public static final class Entry {public final int phase;public final String role;public final List<VisionPoint> body;public final List<List<VisionPoint>> regions,identity,context,anchors;Entry(int p,String r,List<VisionPoint>b,List<List<VisionPoint>>f,List<List<VisionPoint>>i,List<List<VisionPoint>>c,List<List<VisionPoint>>a){phase=p;role=r;body=b;regions=f;identity=i;context=c;anchors=a;}}',' private static void add(String path,int phase,String role,String component,int w,int h,List<VisionPoint>b,List<List<VisionPoint>>f,List<List<VisionPoint>>i,List<List<VisionPoint>>c){','  add(path,phase,role,component,w,h,b,f,i,c,Collections.emptyList());',' }',' private static void add(String path,int phase,String role,String component,int w,int h,List<VisionPoint>b,List<List<VisionPoint>>f,List<List<VisionPoint>>i,List<List<VisionPoint>>c,List<List<VisionPoint>>a){','  ComponentReferenceSpec s=spec("p"+phase+"scene_"+role+"_"+path,component,w,h,path,phase,b,f);REFS.put(path,s);ENTRIES.put(path,new Entry(phase,role,b,f,i,c,a));','  if(!role.equals("protected")&&!role.equals("stacked")&&!role.equals("assembly")&&!role.endsWith("bad")&&!role.equals("back"))INDIVIDUAL.add(spec("video1011_"+path,component,w,h,path,0,b,Collections.singletonList(b)));',' }',' private static ComponentReferenceSpec spec(String id,String component,int w,int h,String path,int phase,List<VisionPoint>b,List<List<VisionPoint>>f){return ComponentReferenceSpec.builder(id,component,"Reviewed "+component,w,h).jpegAssetPath(path).assemblyStepNumber(phase).maximumImageDimension(1280).planarReferenceCornersNormalized(b.get(0),b.get(1),b.get(2),b.get(3)).featureRegionsNormalized(f).polarityAnchorsVerified(false).build();}',' public static ComponentReferenceSpec find(String path){return REFS.get(path);}',' public static Entry annotation(String path){return ENTRIES.get(path);}',' public static Collection<ComponentReferenceSpec> all(){return Collections.unmodifiableCollection(REFS.values());}',' public static List<ComponentReferenceSpec> individual(){return Collections.unmodifiableList(INDIVIDUAL);}',' private static List<VisionPoint> q(double...v){List<VisionPoint> p=new ArrayList<>();for(int n=0;n<v.length;n+=2)p.add(new VisionPoint(v[n],v[n+1]));return Collections.unmodifiableList(p);}','}']
(root/'app/src/main/java/dev/chyken/assembl_ar/camera/Phase1011VideoReferences.java').write_text('\n'.join(lines)+'\n',encoding='utf-8')
sheet=Image.new('RGB',(1536,320*((len(records)+2)//3)),'white');d=ImageDraw.Draw(sheet)
for n,r in enumerate(records):
    im=Image.open(assets/r['path']).convert('RGB');im.thumbnail((508,286));x=n%3*512;y=n//3*320+24;sheet.paste(im,(x,y));d.text((x+3,y-21),f"{n} P{r['phase']} {r['role']} {r['time']}s",fill='black')
    for groups,color in [(r['identity'],'lime'),(r['context'],'orange'),([r['body']],'cyan')]:
        for poly in groups:
            pts=[(x+u*im.width,y+v*im.height) for u,v in poly];d.line(pts+[pts[0]],fill=color,width=2)
sheet.save(out/'reviewed-contact.jpg')
print(json.dumps(dict(references=len(records),held_out=len(held),asset_bytes=sum(map(len,pending.values())))))
