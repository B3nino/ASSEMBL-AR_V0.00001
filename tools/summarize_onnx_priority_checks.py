"""Bind final native checks, model bytes, installation and preserved data to one APK."""
from pathlib import Path
import hashlib
import json
import re

root = Path(__file__).resolve().parents[1]
tmp, docs = root.parent/'tmp', root/'docs'

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def hashes(path):
    return {name: sha for sha, name in re.findall(r'^([0-9a-f]{64})\s+(\S+)\s*$', path.read_text(encoding='utf-8-sig'), re.M)}

model_checks = read(root/'app/build/tmp/onnx-priority/results.json')
focused = read(root/'app/build/tmp/onnx-priority/compatibility-final.json')
compatibility = read(docs/'ONNX_PRIORITY_COMPATIBILITY_PREFINAL_2026_10_28.json')
assert len(model_checks) == 19 and all(r['passed'] for r in model_checks)
assert len(focused) == 5 and all(r['passed'] for r in focused)
assert len(compatibility) == 12 and all(r['passed'] for r in compatibility)
asset_audit = read(docs/'ONNX_PRIORITY_ASSET_AUDIT_2026_10_27.json')
apk = root/asset_audit['apk']
assert hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest() == asset_audit['apk_sha256']
assert 'OK (780 tests)' in (tmp/'onnx_priority_host.log').read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in (tmp/'onnx_priority_release.log').read_text(encoding='utf-8-sig')

events = (tmp/'onnx_priority_native_events_final.log').read_text(encoding='utf-8-sig')
continuous = []
for pid, cycle, checkpoint, ms, heap in re.findall(r' (\d+)\s+\d+ I TrainedAssistTest: continuous cycle=(\d+) checkpoint=(\w+) elapsedMs=(\d+) nativeHeap=(\d+)', events):
    continuous.append({'pid': int(pid), 'cycle': int(cycle), 'checkpoint': checkpoint, 'elapsed_ms': int(ms), 'native_heap_bytes': int(heap)})
assert continuous
continuous = [r for r in continuous if r['pid'] == continuous[-1]['pid']]
assert len(continuous) == 14
camera = re.findall(r'TRAINED_RECOVERY baseline=(.*?) assisted=(.*)', events)[-1]

def stream(value):
    w,h,fps,p95,requests,inference,growth = re.search(r'(\d+)x(\d+) fps=([\d.]+) bufferPathP95Ms=([\d.]+) recoveryRequests=(\d+) lastInferenceMs=([\d.]+) nativeGrowthBytes=(\d+)',value).groups()
    return {'width': int(w), 'height': int(h), 'sensor_delivery_fps': float(fps), 'buffer_tracker_p95_ms': float(p95),
            'queries': int(requests), 'last_forward_ms': float(inference), 'native_growth_bytes': int(growth)}

elapsed = int(re.findall(r'shared_analyzer_identity_without_matching_references elapsedMs=(\d+)', events)[-1])
before = hashes(tmp/'onnx_priority_before_hashes.txt')
after_tests = hashes(tmp/'onnx_priority_after_tests_hashes.txt')
after_install = hashes(tmp/'onnx_priority_after_install_hashes.txt')
assert len(before) == 3 and before == after_tests == after_install
installed = (tmp/'onnx_priority_installed_apk_hash.txt').read_text(encoding='utf-8-sig').split()[0]
assert installed == asset_audit['apk_sha256']
assert re.search(r'^Success\s*$', (tmp/'onnx_priority_install.log').read_text(encoding='utf-8-sig'), re.M)
candidate = root/'app/build/tmp/tablet-audit/candidate-code.zip'
result = {'date': '2026-10-28', 'timezone': 'Asia/Manila', 'device': {'model': '2603ARP14G', 'serial': '368e278b'},
          'apk_sha256': installed, 'candidate_sha256': hashlib.file_digest(candidate.open('rb'), 'sha256').hexdigest(),
          'host_tests_passed': 780, 'native_model_checks': model_checks, 'final_compatibility_rechecks': focused,
          'compatibility_sweep': compatibility,
          'compatibility_scope': '12 checks passed before removal of the exclusive model wait and additional class guards. Five affected pipeline/lifecycle checks were rerun on the final candidate; underlying photo matchers are unchanged.',
          'continuous_session': continuous, 'cycle_end_native_growth_bytes': continuous[-1]['native_heap_bytes']-continuous[6]['native_heap_bytes'],
          'shared_analyzer_identity_without_matching_photo_elapsed_ms': elapsed,
          'camera_soak': {'baseline': stream(camera[0]), 'assisted': stream(camera[1])},
          'progress_and_database_hashes': before, 'data_unchanged_after_tests_and_install': True,
          'updated_in_place': True, 'model_weights_changed': False,
          'limits': ['Retained-image/YUV replay is not an independent-room physical 11-phase assembly session.',
                     'No universal recognition-speed or accuracy guarantee; several experimental models still have low coverage.',
                     'Camera sensor delivery/buffer tracking is not displayed UI FPS or a sustained thermal study.',
                     'Confusable component classes retain independent geometry/photo checks.']}
for name, value in [('ONNX_PRIORITY_NATIVE_FINAL_2026_10_28.json', model_checks),
                    ('ONNX_PRIORITY_COMPATIBILITY_FINAL_2026_10_28.json', focused),
                    ('ONNX_PRIORITY_DEVICE_VERIFICATION_2026_10_28.json', result)]:
    (docs/name).write_text(json.dumps(value,indent=2),encoding='utf-8')
print(json.dumps({'installed': True, 'data_hashes_match': True, 'model_checks': len(model_checks),
                  'focused_checks': len(focused), 'continuous': continuous,
                  'shared_identity_ms': elapsed, 'camera_soak': result['camera_soak']},indent=2))
