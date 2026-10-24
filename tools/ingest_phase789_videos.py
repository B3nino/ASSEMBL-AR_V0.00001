"""Publish reviewed REAL camera pixels and hardware masks for Phases 7--9.

Offline authoring utility only. Nothing in the APK opens a source MP4/Downloads
path. Existing normalization runs symmetrically at descriptor preparation time;
JPEGs retain real lighting, metal reflections and shadows. Replays use disjoint
timestamps. Missing categories retain their existing photograph datasets.
"""
from pathlib import Path
import argparse, hashlib, json, sys

p = argparse.ArgumentParser()
p.add_argument('--opencv-path')
p.add_argument('--source-dir')
p.add_argument('--verify',action='store_true',help='Verify packaged JPEG provenance without the original videos or OpenCV')
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
if args.verify:
    manifest=json.loads((root/'tools/phase789_video_manifest.json').read_text())
    references=manifest['references'];seen=set();train=set()
    for row in references:
        assert row['path'] not in seen;seen.add(row['path']);train.add((row['video'],row['time']))
        assert hashlib.sha256((root/'app/src/main/assets'/row['path']).read_bytes()).hexdigest()==row['sha256'],row['path']
        for poly in [row['body']]+row['regions']+row['identity']+row['context']:
            assert len(poly)==4 and all(0<=x<=1 and 0<=y<=1 for x,y in poly),row['path']
    assert not train.intersection((r['video'],r['time']) for r in manifest['held_out'])
    print(f'Verified {len(references)} real JPEG hashes/masks and {len(manifest["held_out"])} disjoint replay timestamps')
    raise SystemExit(0)
if not args.source_dir:p.error('--source-dir is required when ingesting videos')
if args.opencv_path:
    sys.path.insert(0, args.opencv_path)
import cv2 as cv
import numpy as np
from PIL import Image, ImageDraw

cv.setNumThreads(2)
assets = root / 'app/src/main/assets'
out = root / 'app/build/tmp/phase789-raw'
out.mkdir(parents=True, exist_ok=True)
records, heldout, pending = [], [], {}

def q(l, t, r, b):
    return [[l, t], [r, t], [r, b], [l, b]]

def read(video, time):
    capture = cv.VideoCapture(str(Path(args.source_dir) / (video + '.mp4')))
    try:
        capture.set(cv.CAP_PROP_POS_MSEC, time * 1000)
        ok, image = capture.read()
        if not ok:
            raise RuntimeError(f'Cannot decode {video} at {time}')
        return image
    finally:
        capture.release()

def bounds(regions, pad=0):
    points = np.array([v for region in regions for v in region])
    l, t = points.min(axis=0) - pad
    r, b = points.max(axis=0) + pad
    return q(int(l), int(t), int(r), int(b))

def norm(region, w, h):
    return [[round(x/w, 8), round(y/h, 8)] for x, y in region]

def add(video, time, phase, role, regions, body=None, identity=None, context=None, crop=False):
    image = read(video, time)
    body = body or bounds(regions)
    identity = identity or regions
    context = context or []
    # A body-local sharpness measurement avoids rewarding a sharp tiled table.
    mask = np.zeros(image.shape[:2], np.uint8)
    for region in regions:
        cv.fillConvexPoly(mask, np.array(region, np.int32), 255)
    lap = cv.Laplacian(cv.cvtColor(image, cv.COLOR_BGR2GRAY), cv.CV_32F)
    sharpness = float(lap[mask != 0].var())
    if crop:
        # Preserve real padding for ORB's border, particularly narrow trackers.
        box = bounds([body], 40)
        l, t = [max(0, v) for v in box[0]]
        r = min(image.shape[1], box[2][0]); b = min(image.shape[0], box[2][1])
        image = image[t:b, l:r].copy()
        def shift(poly): return [[x-l, y-t] for x, y in poly]
        body = shift(body)
        regions = [shift(region) for region in regions]
        identity = [shift(region) for region in identity]
        context = [shift(region) for region in context]
    h, w = image.shape[:2]
    folder = {
        (7, 'caster'): 'STEP7_1CASTERWHEEL', (7, 'trackers'): 'STEP7_2LINETRACKERS',
        # Legacy filename requested by the user; pixels are BOTTOM-view work.
        (7, 'trackerwork'): 'STEP7_TOPVIEW',
        (8, 'assembly'): 'STEP8_2DISTANCESENSORHARDWARESCAN',
        (8, 'invalid'): 'STEP8_2DISTANCESENSORHARDWARESCAN_INVALID',
        (9, 'standoff'): 'STEP9_1STANDOFFSCANNING', (9, 'wheel'): 'WHEELSREF',
        (9, 'pair'): 'STEP9_2DUALWHEELS', (9, 'assembly'): 'STEP9_4ASSEMBLYSCAN',
    }[(phase, role)]
    suffix = '_INWARD' if phase == 8 and role == 'invalid' else ''
    path = f'references/Phase{phase}/{folder}/var_light/VIDEO789_{video}_{time:g}_{role}{suffix}.jpg'
    ok, data = cv.imencode('.jpg', image, [cv.IMWRITE_JPEG_QUALITY, 97])
    assert ok
    pending[path] = data.tobytes()
    records.append(dict(path=path, video=video, time=time, phase=phase, role=role,
        body=norm(body,w,h), regions=[norm(v,w,h) for v in regions],
        identity=[norm(v,w,h) for v in identity], context=[norm(v,w,h) for v in context],
        width=w, height=h, sharpness=round(sharpness,3),
        sha256=hashlib.sha256(pending[path]).hexdigest()))

# Each installed tracker has its own body, mounting stud and rigid identity area.
# The seventh region is the caster plate. No fixed module rotation is assumed.
for time, boxes in [
    (6, [(126,135,269,382),(588,127,699,369),(137,136,184,181),(644,129,695,177),(140,175,237,300),(605,175,684,297),(277,96,562,250)]),
    (20,[(308,138,436,352),(695,124,810,341),(310,141,355,180),(763,125,806,166),(328,176,405,289),(715,164,796,281),(430,114,700,241)]),
    (58,[(235,84,340,313),(609,83,730,323),(239,85,283,128),(675,86,724,128),(253,119,320,242),(637,124,709,254),(350,69,589,193)]),
    (76,[(339,91,479,374),(833,96,955,380),(342,92,394,139),(895,99,948,145),(361,134,451,294),(854,142,937,300),(492,42,835,236)]),
    (90,[(250,146,378,374),(666,157,790,401),(252,146,302,188),(739,160,785,205),(271,187,351,306),(695,204,770,330),(383,129,646,268)])]:
    bodies={
        6:[[[128,150],[176,130],[267,350],[219,371]],[[644,127],[699,140],[644,367],[588,354]]],
        20:[[[308,158],[356,138],[433,330],[388,350]],[[752,124],[810,137],[756,340],[699,327]]],
        58:[[[236,101],[279,85],[340,298],[291,315]],[[679,83],[730,96],[666,323],[610,308]]],
        76:[[[339,109],[393,91],[479,350],[423,372]],[[899,97],[955,109],[894,380],[835,368]]],
        90:[[[251,167],[303,146],[378,354],[321,374]],[[737,158],[790,173],[720,400],[666,385]]],
    }[time]
    pots={6:[(155,216,218,289),(622,211,686,279)],20:[(341,220,398,281),(739,211,797,267)],58:[(244,157,299,217),(640,167,702,229)],76:[(366,162,432,231),(877,181,944,243)],90:[(273,230,338,292),(701,247,768,315)]}[time]
    add('b_60MVLl', time, 7, 'trackerwork', bodies+[q(*b) for b in boxes[2:4]]+[q(*b) for b in pots]+[q(*boxes[6])], body=q(110 if time==6 else 230,45,970,550))
for time,bodies,nuts,pots,plate in [
    (10,[[[361,157],[416,132],[523,348],[462,378]],[[841,105],[916,108],[904,341],[825,335]]],[(371,134,420,178),(875,96,921,137)],[(417,210,479,280),(875,187,926,242)],(495,73,817,239)),
    (64,[[[231,125],[272,109],[327,293],[283,310]],[[638,117],[686,129],[636,312],[591,300]]],[(235,110,277,145),(647,113,683,144)],[(242,157,298,216),(620,168,675,227)],(346,86,581,215))]:
    add('b_60MVLl',time,7,'trackerwork',bodies+[q(*v) for v in nuts]+[q(*v) for v in pots]+[q(*plate)],body=q(190,60,990,555))
# Oblique metal-glare view reviewed separately; source timestamps stay disjoint.
add('b_60MVLl',80,7,'trackerwork',[
    [[215,180],[275,143],[443,394],[381,433]],[[770,66],[839,60],[866,320],[791,327]],
    q(232,145,282,197),q(783,53,831,94),q(294,236,374,307),q(802,132,869,201),q(397,40,705,246)
],body=q(210,40,900,545))
add('b_60MVLl',85,7,'trackerwork',[
    [[286,23],[348,35],[305,264],[240,247]],[[742,218],[805,246],[661,454],[592,414]],
    q(292,19,337,57),q(760,211,805,252),q(252,88,307,149),q(662,286,727,346),q(389,52,665,245)
],body=q(220,10,830,555))

# Loose sensors, actual component face and both-module scenes. Pins are not used
# as mounting evidence. Single-module references can locate either of two bodies.
for video, time, boxes in [
    ('Uzk5p7AF',5,[(159,126,238,375),(325,125,399,373)]),
    ('Uzk5p7AF',20,[(445,145,520,400),(589,147,674,403)]),
    ('Uzk5p7AF',43,[(390,32,504,420),(619,38,764,425)]),
    ('DUsK9roS',12,[(558,91,866,371)]),
    ('DUsK9roS',39,[(405,94,507,460)]),
]:
    regions=[q(*b) for b in boxes]
    if video=='DUsK9roS' and time==12:regions=[[[558,307],[810,94],[864,155],[612,370]]]
    add(video,time,7,'trackers',regions,crop=True)
for time, box in [(4,(482,189,848,420)),(17,(487,230,799,405)),(19,(346,232,750,456)),(28,(379,241,700,404)),(34,(334,220,723,457)),(58,(313,108,826,390))]:
    add('pJxv8DuC',time,7,'caster',[q(*box)],crop=True)

# Distinct cylinder masks plus independent fixed-board identity. Wired views
# contain real wire clutter; none are labelled as proof of electrical continuity.
for video, time, role, body, caps, board in [
    ('fDOmql_e',70,'assembly',(441,165,704,292),[(470,166,558,241),(580,163,679,239)],[(456,303,650,365),(480,390,660,530)]),
    ('fDOmql_e',95,'assembly',(456,129,664,242),[(473,132,541,194),(574,139,648,204)],[(490,247,603,355),(493,412,600,563)]),
    ('fDOmql_e',80,'assembly',(350,280,641,471),[(369,282,475,374),(527,293,631,388)],[(421,451,550,574),(258,301,335,463),(649,339,733,471)]),
    ('vuE18C1A',6,'assembly',(421,16,640,122),[(438,20,506,88),(550,17,620,86)],[(495,324,596,515),(344,104,412,186),(623,106,708,177)]),
    ('vuE18C1A',46,'assembly',(496,64,701,155),[(516,66,581,124),(621,65,687,122)],[(565,344,659,516),(419,112,488,185),(702,106,765,189)]),
    ('fDOmql_e',166,'invalid',(382,123,592,213),[(390,128,470,204),(496,130,578,208)],[(431,218,550,291),(420,385,541,530)]),
    ('fDOmql_e',168,'invalid',(414,148,623,274),[(427,148,520,225),(534,169,619,249)],[(471,257,578,318),(413,353,516,523)]),
    ('fDOmql_e',173,'invalid',(441,287,622,362),[(458,288,526,344),(552,288,615,345)],[(485,70,574,205),(360,317,416,445),(635,330,675,450)]),
    ('fDOmql_e',184,'invalid',(378,349,654,454),[(387,355,491,446),(537,353,643,444)],[(487,229,602,335),(459,5,568,139)]),
    ('fDOmql_e',199,'invalid',(369,318,676,419),[(380,328,493,412),(541,323,654,410)],[(445,177,581,288),(445,1,564,69)]),
    ('fDOmql_e',202,'invalid',(374,373,632,474),[(380,388,475,463),(539,386,630,459)],[(435,243,558,351),(437,2,536,134)]),
    ('fDOmql_e',203,'invalid',(411,349,665,451),[(415,349,517,450),(550,349,660,449)],[(460,244,577,338),(438,1,566,119)]),
]:
    add(video,time,8,role,[q(*body)],body=q(*body),identity=[q(*v) for v in caps],context=[q(*v) for v in board])

for time, box in [(10,(496,94,563,471)),(38,(569,173,631,498))]:
    add('Zi6cD-KG',time,9,'standoff',[q(*box)],crop=True)
for time, boxes, role in [
    (64,[(365,127,786,547)],'wheel'),(86,[(331,83,716,457)],'wheel'),
    (110,[(208,66,562,428),(594,107,973,483)],'pair'),
    (126,[(158,105,523,465),(550,103,933,463)],'pair'),
]:
    add('Zi6cD-KG',time,9,role,[q(*v) for v in boxes],crop=True)

for time, boxes in [
    (6,[(167,274,351,575),(746,251,934,564),(522,264,558,300),(330,90,722,537)]),
    (23,[(83,221,300,555),(713,197,910,553),(490,202,531,239),(279,56,748,551)]),
    (46,[(265,285,426,568),(787,279,979,569),(589,280,625,317),(399,110,790,541)]),
    (62,[(190,287,349,550),(673,286,859,550),(505,272,541,307),(344,131,691,531)]),
]:
    add('vuE18C1A',time,9,'assembly',[q(*v) for v in boxes],body=q(70,10,990,575))

# Real held-out video pixels only; not registered or copied into reference assets.
for video, phase, role, times in [
    ('b_60MVLl',7,'trackerwork',[12,35,65,83]),('Uzk5p7AF',7,'trackers',[10,44]),
    ('DUsK9roS',7,'single',[15,42]),('pJxv8DuC',7,'caster',[20,48]),
    ('fDOmql_e',8,'assembly',[75,82]),('fDOmql_e',8,'invalid',[150,170,188,201]),
    ('fDOmql_e',8,'occluded',[100]),
    ('fDOmql_e',8,'missing',[8,25]),('vuE18C1A',8,'wired',[10,42]),
    ('Zi6cD-KG',9,'wheel',[70,90]),('Zi6cD-KG',9,'pair',[114,129]),
    ('Zi6cD-KG',9,'standoff',[15,42]),('vuE18C1A',9,'assembly',[10,31,55]),
]:
    for time in times:
        path=f'heldout-p{phase}-{role}-{time}.jpg'
        image=read(video,time)
        cv.imwrite(str(out/path),image,[cv.IMWRITE_JPEG_QUALITY,97])
        heldout.append(dict(path=path,video=video,phase=phase,role=role,time=time))

# Stage all bytes before publication, so a failed source decode cannot publish
# half a manifest. No old photographic references are removed.
for path,data in pending.items():
    target=assets/path
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(data)
sources=[]
for name in sorted({row['video'] for row in records}):
    path=Path(args.source_dir)/(name+'.mp4');digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    sources.append(dict(file=path.name,bytes=path.stat().st_size,sha256=digest.hexdigest()))
manifest=dict(version=1, description='Real varied-lighting footage, reviewed body/identity/context masks; disjoint replay timestamps',sources=sources,
    absent_video_categories=['loose ultrasonic front/back','loose tracker underside','loose caster underside','installed standoff top-screw-attached','missing-wheel assembly'],
    references=records,held_out=heldout)
(root/'tools/phase789_video_manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')

lines=['package dev.chyken.assembl_ar.camera;', 'import java.util.*;',
    'import dev.chyken.assembl_ar.vision.*;',
    '/** Reviewed real camera pixels and hardware masks. Generated by ingest_phase789_videos.py. */',
    'public final class Phase789VideoReferences {',
    ' public static final int DESCRIPTOR_VERSION=8;',
    ' private Phase789VideoReferences(){}',
    ' private static final Map<String,ComponentReferenceSpec> REFS=new LinkedHashMap<>();',
    ' private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();',
    ' private static final List<ComponentReferenceSpec> INDIVIDUAL=new ArrayList<>();', ' static {']
def jq(poly): return 'q('+','.join(str(v) for pt in poly for v in pt)+')'
def jl(regions): return 'Arrays.asList('+','.join(jq(r) for r in regions)+')'
for row in records:
    phase,role=row['phase'],row['role']
    installed=role in ('trackerwork','assembly','invalid')
    part='line_tracker_module' if phase==7 and role in ('trackers','trackerwork') else 'caster_wheel' if phase==7 else 'ultrasonic_distance_sensor' if phase==8 else 'metal_standoff' if role=='standoff' else 'robot_wheel'
    # Use exact seed IDs; no speculative alternate identities.
    if part=='metal_standoff': part='center_standoff'
    if part=='robot_wheel': part='drive_wheel'
    component='main_board' if installed and phase!=8 else part
    w,h=(100,120) if installed and phase==7 else (180,180) if installed and phase==9 else (45,20) if phase==8 else (14,50) if part=='line_tracker_module' else (48,24) if phase==7 else (5,40) if role=='standoff' else (65,65)
    rid=f'p{phase}scene_{role}_{row["path"]}'
    lines.append(f'  add("{row["path"]}",{phase},"{role}","{component}","{part}",{w},{h},{jq(row["body"])},{jl(row["regions"])},{jl(row["identity"])},{jl(row["context"])});')
lines += [' }',
    ' public static final class Entry {public final int phase;public final String role;public final List<VisionPoint> body;public final List<List<VisionPoint>> regions,identity,context;Entry(int p,String r,List<VisionPoint>b,List<List<VisionPoint>>f,List<List<VisionPoint>>i,List<List<VisionPoint>>c){phase=p;role=r;body=b;regions=f;identity=i;context=c;}}',
    ' private static void add(String path,int phase,String role,String component,String part,int w,int h,List<VisionPoint>b,List<List<VisionPoint>>f,List<List<VisionPoint>>i,List<List<VisionPoint>>c){',
    '  List<List<VisionPoint>> features=new ArrayList<>(f);if(phase==8){features.clear();features.addAll(i);features.addAll(c);}',
    '  ComponentReferenceSpec s=ComponentReferenceSpec.builder("p"+phase+"scene_"+role+"_"+path,component,"Reviewed Phase "+phase+" "+role,w,h).jpegAssetPath(path).assemblyStepNumber(phase).maximumImageDimension(1280).planarReferenceCornersNormalized(b.get(0),b.get(1),b.get(2),b.get(3)).featureRegionsNormalized(features).polarityAnchorsVerified(false).build();',
    '  REFS.put(path,s);ENTRIES.put(path,new Entry(phase,role,b,f,i,c));',
    '  if(!role.equals("trackerwork")&&!role.equals("assembly")&&!role.equals("invalid"))for(int n=0;n<f.size();n++){List<VisionPoint> r=f.get(n);INDIVIDUAL.add(ComponentReferenceSpec.builder("video789_"+phase+"_"+n+"_"+path,part,"Reviewed "+part,w,h).jpegAssetPath(path).maximumImageDimension(1280).planarReferenceCornersNormalized(r.get(0),r.get(1),r.get(2),r.get(3)).featureRegionsNormalized(Collections.singletonList(r)).polarityAnchorsVerified(false).build());}',
    ' }',
    ' public static ComponentReferenceSpec find(String path){return REFS.get(path);}',
    ' public static Entry annotation(String path){return ENTRIES.get(path);}',
    ' public static Collection<ComponentReferenceSpec> all(){return Collections.unmodifiableCollection(REFS.values());}',
    ' public static List<ComponentReferenceSpec> individual(){return Collections.unmodifiableList(INDIVIDUAL);}',
    ' private static List<VisionPoint> q(double...v){List<VisionPoint>p=new ArrayList<>();for(int n=0;n<v.length;n+=2)p.add(new VisionPoint(v[n],v[n+1]));return Collections.unmodifiableList(p);}',
    '}']
(root/'app/src/main/java/dev/chyken/assembl_ar/camera/Phase789VideoReferences.java').write_text('\n'.join(lines)+'\n')
sheet=Image.new('RGB',(512*3,320*((len(records)+2)//3)),'white');draw=ImageDraw.Draw(sheet)
for index,row in enumerate(records):
    image=Image.open(assets/row['path']).convert('RGB'); image.thumbnail((508,286))
    x=index%3*512;y=index//3*320+25;sheet.paste(image,(x,y))
    draw.text((x+3,y-22),f'{index} P{row["phase"]} {row["role"]} {row["video"]} {row["time"]}s',fill='black')
    for group,color in [(row['regions'],'lime'),(row['identity'],'cyan'),(row['context'],'orange')]:
        for poly in group:
            pts=[(x+px*image.width,y+py*image.height) for px,py in poly]
            draw.line(pts+[pts[0]],fill=color,width=2)
sheet.save(out/'reviewed-contact.jpg')
print(json.dumps(dict(references=len(records),held_out=len(heldout),asset_bytes=sum(map(len,pending.values())))))
