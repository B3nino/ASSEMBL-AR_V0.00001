"""Record measured tablet results and exact candidate identities after native runs."""
from pathlib import Path
import hashlib,json,re,statistics

ROOT=Path(__file__).resolve().parents[1]

def read_json(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def hash_lines(path):
    return {m.group(2):m.group(1) for m in re.finditer(r'^([a-f0-9]{64})\s+(\S+)',path.read_text(encoding='utf-8-sig'),re.M)}

def main():
    docs=ROOT/'docs';tmp=ROOT.parent/'tmp'
    groups=read_json(ROOT/'app/build/tmp/trained-model-native/results.json')
    sweep=read_json(docs/'TRAINED_MODEL_COMPATIBILITY_SWEEP_2026_10_27.json')
    rechecks=read_json(ROOT/'app/build/tmp/trained-model-rechecks/results.json')
    assert len(groups)==13 and all(row['passed'] for row in groups),groups
    assert len(sweep)==100 and len({row['case'] for row in sweep})==100
    assert len(rechecks)==5 and all(row['passed'] for row in rechecks),rechecks
    rechecked={row['case'] for row in rechecks if row['passed']}
    assert all(row['passed'] or row['case'] in rechecked for row in sweep)
    events=(tmp/'trained_assist_final_events.log').read_text(encoding='utf-8',errors='replace')
    timings={}
    for phase,baseline,assisted,inference in re.findall(r'varied phase=(\d+) baselineMs=(\[[^\]]+\]) assistedMs=(\[[^\]]+\]) inferenceMs=(\[[^\]]+\])',events):
        b,a,n=map(json.loads,[baseline,assisted,inference])
        timings[phase]={'baseline_verification_ms':b,'assisted_verification_ms':a,'separate_inference_ms':n,
                        'baseline_median_ms':statistics.median(b),'assisted_median_ms':statistics.median(a),
                        'verification_median_reduction_percent':100*(1-statistics.median(a)/statistics.median(b))}
    assert '5' in timings and '9' in timings
    line=[line for line in events.splitlines() if 'TRAINED_RECOVERY baseline=' in line][-1]
    baseline,assisted=line.split('baseline=',1)[1].split(' assisted=',1)
    def metrics(value):
        return {'size':value.split()[0],**{name:float(number) for name,number in re.findall(r'(\w+)=(-?[\d.]+)',value)}}
    camera={'baseline':metrics(baseline),'assisted':metrics(assisted),'assisted_duration_seconds':30,
            'scope':'Actual sensor and private preview stream; camera callback/tracker work plus trained inference. Not displayed UI FPS or recognition accuracy.'}
    camera['delivered_fps_retained_percent']=camera['assisted']['fps']/camera['baseline']['fps']*100
    before=hash_lines(tmp/'trained_assist_reconnected_before_hashes.txt')
    after=hash_lines(tmp/'trained_assist_reconnected_after_hashes.txt')
    assert len(before)==3 and before==after,(before,after)
    archive=ROOT/'app/build/tmp/tablet-audit/candidate-code.zip'
    # The broad sweep ran before the final readiness-check reorder. Final native
    # model/monitor/soak groups and Nano compatibility rechecks cover that guard.
    sweep_log=(tmp/'trained_assist_reconnected_candidate.log').read_text(encoding='utf-8-sig')
    sweep_archive_sha=re.findall(r'SHA256 ([a-f0-9]{64})',sweep_log)[-1]
    evidence={'device':{'model':'2603ARP14G','serial':'368e278b'},'date':'2026-10-27','timezone':'Asia/Manila',
              'final_candidate_sha256':digest(archive),'broad_sweep_candidate_sha256':sweep_archive_sha,
              'source_change_between_sweep_and_final_groups':'Skip the synchronized legacy Nano readiness query before the supported photo path; validated by monitor isolation and final Nano rechecks.',
              'model_groups':groups,'broad_sweep':sweep,'focused_rechecks':rechecks,
              'initial_sweep_passed':sum(row['passed'] for row in sweep),'covered_compatibility_groups':len(sweep),
              'single_clean_100_group_sweep':False,'verification_timings':timings,'camera':camera,
              'saved_data':{'before':before,'after':after,'unchanged':True},
              'limitations':['No independent new-room recognition accuracy study was performed.',
                             'Verification timings exclude inference and do not prove a universal end-to-end recognition speedup.',
                             'The first sweep had two failures; each passed two isolated reruns without relaxed limits.',
                             'Release assistance remains opt-in; learner APK was not replaced or cleared.']}
    for name,content in [('TRAINED_MODEL_NATIVE_FINAL_2026_10_27.json',groups),('TRAINED_MODEL_COMPATIBILITY_RECHECKS_2026_10_27.json',rechecks),
                         ('TRAINED_MODEL_DEVICE_VERIFICATION_2026_10_27.json',evidence),
                         ('TRAINED_MODEL_TIMINGS_2026_10_27.json',{'scope':'Final tablet retained-frame photo verification; separate inference times','phases':timings,'camera':camera,'limits':evidence['limitations']})]:
        (docs/name).write_text(json.dumps(content,indent=2),encoding='utf-8')
    print(json.dumps({'model_groups':len(groups),'compatibility_covered':len(sweep),'focused_rechecks':len(rechecks),
                      'data_unchanged':True,'camera':camera,'verification_timings':timings},indent=2))

if __name__=='__main__':main()
