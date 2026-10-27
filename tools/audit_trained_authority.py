"""Audit the stricter identity threshold with brightness changes; no weights/labels changed.

Existing test captures are development evidence, not independent-session calibration.
Unmatched detections include annotation omissions and localization/class mistakes.
"""
from pathlib import Path
import json
import cv2
import numpy as np

root = Path(__file__).resolve().parents[1]
cv2.setNumThreads(2)
report = {'scope': __doc__, 'threshold': .85, 'class_margin': .15, 'phases': []}

def iou(a, b):
    intersection = max(0, min(a[2], b[2])-max(a[0], b[0]))*max(0, min(a[3], b[3])-max(a[1], b[1]))
    return intersection/max(1, (a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection)

for phase in [1, 2, 3, 5, 7, 8, 9, 10, 11]:
    dataset = root.parent/'training'/('phase12' if phase <= 2 else 'phases3_11')/'datasets'/f'phase{phase}'
    model = cv2.dnn.readNetFromONNX(str(root/f'app/src/main/assets/models/trained/phase{phase}.onnx'))
    stats = {name: {'truth': 0, 'matched_iou_025': 0, 'predictions': 0, 'unmatched': 0} for name in ['original', 'dimmer', 'brighter']}
    suspect = []
    for path in sorted((dataset/'images/test').glob('*')):
        original = cv2.imread(str(path))
        scale = min(1, 640/max(original.shape[:2]))
        original = cv2.resize(original, (max(2, round(original.shape[1]*scale)//2*2), max(2, round(original.shape[0]*scale)//2*2)), interpolation=cv2.INTER_AREA)
        height, width = original.shape[:2]
        truths = []
        for line in (dataset/'labels/test'/f'{path.stem}.txt').read_text().splitlines():
            label, cx, cy, w, h = map(float, line.split())
            truths.append((int(label), [(cx-w/2)*width, (cy-h/2)*height, (cx+w/2)*width, (cy+h/2)*height]))
        for name, alpha, beta in [('original', 1., 0), ('dimmer', .8, 0), ('brighter', 1.1, 8)]:
            image = np.clip(original.astype(np.float32)*alpha+beta, 0, 255).astype(np.uint8)
            factor = min(512/width, 512/height); rw, rh = round(width*factor), round(height*factor)
            left, top = (512-rw)//2, (512-rh)//2
            padded = np.full((512, 512, 3), 114, dtype=np.uint8)
            padded[top:top+rh, left:left+rw] = cv2.resize(image, (rw, rh))
            model.setInput(cv2.dnn.blobFromImage(padded, 1/255, (512,512), swapRB=True))
            output = model.forward()[0].T
            candidates = []
            for row in output:
                scores = row[4:]; label = int(scores.argmax()); score = float(scores[label])
                second = float(np.partition(scores, -2)[-2]) if len(scores) > 1 else 0
                if score < .85 or score-second < .15: continue
                cx, cy, w, h = row[:4]
                box = [max(0, np.floor((cx-w/2-left)*width/rw)), max(0, np.floor((cy-h/2-top)*height/rh)),
                       min(width, np.ceil((cx+w/2-left)*width/rw)), min(height, np.ceil((cy+h/2-top)*height/rh))]
                area = (box[2]-box[0])*(box[3]-box[1])/(width*height)
                if min(box[0], box[1]) <= 1 or box[2] >= width-1 or box[3] >= height-1 or min(box[2]-box[0], box[3]-box[1]) < 10 or not .001 <= area <= .80: continue
                candidates.append((label, box, score))
            kept = []
            for prediction in sorted(candidates, key=lambda x: -x[2])[:256]:
                if not any(prediction[0] == previous[0] and iou(prediction[1], previous[1]) > .45 for previous in kept): kept.append(prediction)
                if len(kept) >= 16: break
            matched = set()
            for label, box in truths:
                options = [(iou(box, item[1]), i) for i, item in enumerate(kept) if item[0] == label and i not in matched]
                if options and max(options)[0] >= .25: matched.add(max(options)[1])
            values = stats[name]; values['truth'] += len(truths); values['matched_iou_025'] += len(matched)
            values['predictions'] += len(kept); values['unmatched'] += len(kept)-len(matched)
            if len(kept) > len(matched): suspect.append({'image': path.name, 'variant': name, 'predictions': [{'label': p[0], 'score': p[2]} for i,p in enumerate(kept) if i not in matched]})
    item = {'phase': phase, 'variants': stats, 'unmatched_examples': suspect}
    report['phases'].append(item)
    print(json.dumps({'phase': phase, 'variants': stats}), flush=True)
(root/'docs/ONNX_PRIORITY_LIGHTING_AUDIT_2026_10_27.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
