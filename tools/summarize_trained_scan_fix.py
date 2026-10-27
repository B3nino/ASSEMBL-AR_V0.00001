"""Summarize exact-candidate scan verification, without modifying device state."""
from pathlib import Path
import hashlib
import json
import re
import statistics

root = Path(__file__).resolve().parents[1]
tmp = root.parent / 'tmp'
docs = root / 'docs'

def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def hashes(path):
    text = path.read_text(encoding='utf-8-sig')
    return {name: digest for digest, name in re.findall(r'^([0-9a-f]{64})\s+(\S+)\s*$', text, re.M)}

models = read_json(root / 'app/build/tmp/trained-model-native/results.json')
focused = read_json(root / 'app/build/tmp/trained-scan-continuity/final-results.json')
prior = read_json(docs / 'TRAINED_SCAN_FIX_CONTINUITY_PREFINAL_2026_10_27.json')
assert len(models) == 19 and all(row['passed'] for row in models), 'Final model checks incomplete'
assert len(focused) == 6 and all(row['passed'] for row in focused), 'Final focused checks incomplete'
compatibility = {row['case'] for row in prior if row['passed']} | {row['case'] for row in focused if row['passed']}
assert len(compatibility) == 20, 'A compatibility scenario still lacks a passing result'
asset_audit = read_json(docs / 'TRAINED_SCAN_FIX_ASSET_AUDIT_2026_10_27.json')
archive = root / 'app/build/tmp/tablet-audit/candidate-code.zip'
archive_sha = hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest()
events = (tmp / 'trained_scan_fix_native_events_final.log').read_text(encoding='utf-8-sig')
continuous = []
for pid, cycle, name, ms, heap in re.findall(r'TrainedAssistTest\(\s*(\d+)\): continuous cycle=(\d+) checkpoint=(\w+) elapsedMs=(\d+) nativeHeap=(\d+)', events):
    continuous.append({'pid': int(pid), 'cycle': int(cycle), 'checkpoint': name, 'elapsed_ms': int(ms), 'native_heap_bytes': int(heap)})
assert continuous, 'No continuous-session measurements'
continuous = [row for row in continuous if row['pid'] == continuous[-1]['pid']]
assert len(continuous) == 14, 'Incomplete continuous-session measurements'
timings = {}
for phase, baseline, assisted, inference, frames, rounds in re.findall(r'varied phase=(\d+) baselineMs=(\[.*?\]) assistedMs=(\[.*?\]) inferenceMs=(\[.*?\]) frames=(\d+) rounds=(\d+)', events):
    baseline, assisted, inference = map(json.loads, [baseline, assisted, inference])
    before, after = statistics.median(baseline), statistics.median(assisted)
    timings[phase] = {'baseline_verify_ms': baseline, 'assisted_verify_ms': assisted, 'inference_ms': inference,
                      'baseline_verify_median_ms': before, 'assisted_verify_median_ms': after,
                      'verify_median_reduction_percent': 100 * (before-after)/before, 'frames': int(frames), 'rounds': int(rounds)}
camera = re.findall(r'TRAINED_RECOVERY baseline=(.*?) assisted=(.*)', events)[-1]
def stream(value):
    values = re.search(r'(\d+)x(\d+) fps=([\d.]+) bufferPathP95Ms=([\d.]+) recoveryRequests=(\d+) lastInferenceMs=([\d.]+) nativeGrowthBytes=(\d+)', value)
    assert values, 'Missing camera stream values'
    w,h,fps,p95,queries,inference,growth = values.groups()
    return {'width': int(w), 'height': int(h), 'sensor_delivery_fps': float(fps), 'buffer_tracker_p95_ms': float(p95),
            'recovery_requests': int(queries), 'last_forward_ms': float(inference), 'native_growth_bytes': int(growth)}
before = hashes(tmp / 'trained_scan_fix_before_hashes.txt')
after_tests = hashes(tmp / 'trained_scan_fix_after_tests_hashes.txt')
after_install = hashes(tmp / 'trained_scan_fix_after_install_hashes.txt')
assert len(before) == 3 and before == after_tests == after_install, 'Learner progress/database hashes changed'
installed = (tmp / 'trained_scan_fix_installed_apk_hash.txt').read_text(encoding='utf-8-sig').strip().split()[0]
assert installed == asset_audit['apk_sha256'], 'Installed APK differs from audited candidate'
assert re.search(r'^Success\s*$', (tmp / 'trained_scan_fix_install.log').read_text(encoding='utf-8-sig'), re.M), 'Update not confirmed'
result = {'date': '2026-10-27', 'timezone': 'Asia/Manila', 'device': {'model': '2603ARP14G', 'serial': '368e278b'},
          'apk_sha256': installed, 'candidate_sha256': archive_sha, 'model_checks': models, 'focused_checks': focused,
          'compatibility_scenarios_with_passing_results': len(compatibility),
          'compatibility_note': '19 passed before the final performance/cold-expiry adjustment; one missing-file fixture was repaired. Six final rechecks cover Phase 2 and Nano, including that fixture. All original acceptance gates remain unchanged.',
          'continuous_session': continuous, 'continuous_cycle_end_growth_bytes': continuous[-1]['native_heap_bytes']-continuous[6]['native_heap_bytes'],
          'warm_verification_timings': timings, 'camera_soak': {'baseline': stream(camera[0]), 'assisted': stream(camera[1])},
          'progress_and_database_hashes': before, 'data_hashes_identical_after_tests_and_update': True,
          'updated_in_place': True, 'uninstalled_or_cleared_data': False,
          'limits': ['Retained-camera replay is not independent-room accuracy or a live physical 11-phase assembly session.',
                     'Verification timing excludes separate inference time; no universal end-to-end speedup is claimed.',
                     'Camera sensor delivery/buffer tracking measurements are not displayed UI frame rate or a long thermal study.']}
for filename, rows in [('TRAINED_SCAN_FIX_NATIVE_FINAL_2026_10_27.json', models), ('TRAINED_SCAN_FIX_CONTINUITY_FINAL_2026_10_27.json', focused), ('TRAINED_SCAN_FIX_DEVICE_VERIFICATION_2026_10_27.json', result)]:
    (docs / filename).write_text(json.dumps(rows, indent=2), encoding='utf-8')
print(json.dumps({'model_checks': len(models), 'focused_checks': len(focused), 'compatibility_covered': len(compatibility),
                  'continuous_cycle_end_growth_bytes': result['continuous_cycle_end_growth_bytes'], 'camera_soak': result['camera_soak'],
                  'timings': timings, 'updated_in_place': True, 'data_hashes_match': True}, indent=2))
