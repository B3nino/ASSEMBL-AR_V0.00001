"""Compare recovery proposal thresholds on existing test photos using OpenCV CPU.

This measures bounding-box proposals, not app verification or independent-room accuracy.
No training images or model weights are modified.
"""
from pathlib import Path
import json
import cv2
import numpy as np

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets/models/trained'
cv2.setNumThreads(2)
report = {'runtime': cv2.__version__, 'scope': 'Existing held-out test photos, resized like the 640px camera replay. Proposal coverage only; no assembly verdict.', 'phases': []}

def iou(a, b):
    left, top = max(a[0], b[0]), max(a[1], b[1])
    right, bottom = min(a[2], b[2]), min(a[3], b[3])
    intersection = max(0, right-left) * max(0, bottom-top)
    return intersection / max(1e-9, (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - intersection)

for phase in [1, 2, 3, 5, 7, 8, 9, 10, 11]:
    dataset = root.parent / 'training' / ('phase12' if phase <= 2 else 'phases3_11') / 'datasets' / f'phase{phase}'
    model = cv2.dnn.readNetFromONNX(str(assets / f'phase{phase}.onnx'))
    metadata = json.loads((assets / f'phase{phase}.json').read_text())
    classes = metadata['classes']
    stats = {str(threshold): {'truth': 0, 'localized_at_iou_025': 0, 'proposals': 0, 'unmatched_proposals': 0} for threshold in [.35, .75]}
    files = sorted((dataset / 'images/test').glob('*'))
    for path in files:
        image = cv2.imread(str(path))
        height, width = image.shape[:2]
        scale = min(1., 640/max(height, width))
        image = cv2.resize(image, (max(2, round(width*scale)//2*2), max(2, round(height*scale)//2*2)), interpolation=cv2.INTER_AREA)
        height, width = image.shape[:2]
        ratio = min(512/width, 512/height)
        rw, rh = round(width*ratio), round(height*ratio)
        left, top = (512-rw)//2, (512-rh)//2
        resized = cv2.resize(image, (rw, rh), interpolation=cv2.INTER_LINEAR)
        padded = cv2.copyMakeBorder(resized, top, 512-rh-top, left, 512-rw-left, cv2.BORDER_CONSTANT, value=(114, 114, 114))
        model.setInput(cv2.dnn.blobFromImage(padded, 1/255, (512,512), swapRB=True))
        output = model.forward()[0].T
        label_path = dataset / 'labels/test' / (path.stem+'.txt')
        truths = []
        for line in label_path.read_text().splitlines():
            label, cx, cy, w, h = map(float, line.split())
            truths.append((int(label), [(cx-w/2)*width, (cy-h/2)*height, (cx+w/2)*width, (cy+h/2)*height]))
        for threshold, values in stats.items():
            candidates = []
            for row in output:
                label = int(row[4:].argmax())
                score = float(row[4+label])
                if score < float(threshold): continue
                cx, cy, w, h = row[:4]
                box = [max(0., (cx-w/2-left)*width/rw), max(0., (cy-h/2-top)*height/rh), min(width, (cx+w/2-left)*width/rw), min(height, (cy+h/2-top)*height/rh)]
                if box[2]-box[0] >= 3 and box[3]-box[1] >= 3: candidates.append((label, box, score))
            kept = []
            for item in sorted(candidates, key=lambda x: -x[2])[:256]:
                if not any(item[0] == prev[0] and iou(item[1],prev[1]) > .45 for prev in kept): kept.append(item)
                if len(kept) == 16: break
            # Each actual component request uses at most six matching boxes.
            kept = [item for label in range(len(classes)) for item in [x for x in kept if x[0] == label][:6]]
            matched = set()
            for label, box in truths:
                options = [(iou(box,item[1]), i) for i,item in enumerate(kept) if i not in matched and item[0] == label]
                if options and max(options)[0] >= .25: matched.add(max(options)[1])
            values['truth'] += len(truths)
            values['localized_at_iou_025'] += len(matched)
            values['proposals'] += len(kept)
            values['unmatched_proposals'] += len(kept)-len(matched)
    phase_report = {'phase': phase, 'test_images': len(files), 'classes': classes, 'thresholds': stats}
    report['phases'].append(phase_report)
    print(json.dumps(phase_report), flush=True)
output_path = root / 'docs/TRAINED_HINT_COVERAGE_2026_10_27.json'
output_path.write_text(json.dumps(report, indent=2), encoding='utf-8')
