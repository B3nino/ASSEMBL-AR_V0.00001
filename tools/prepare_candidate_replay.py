"""Extract built app code/resources and test fixtures for isolated native replay.

Build first, update the separate test APK, push this archive to the target app's
external-files directory, and use CandidateCodeReplayInstrumentedTest. Reviewed
video assets travel with the candidate; legacy assets come from the installed app.
"""
from pathlib import Path
import hashlib
import json
import re
import zipfile

root = Path(__file__).resolve().parents[1]
source = root / 'app/build/intermediates/apk/debug/app-arm64-v8a-debug.apk'
target = root / 'app/build/tmp/tablet-audit/candidate-code.zip'
target.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(source) as apk, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as output:
    # Carry the small tablet-authored exemplars for isolated asset replay too.
    # Existing large reference/video assets still come from the installed app.
    names = sorted(n for n in apk.namelist() if re.fullmatch(r'classes[0-9]*\.dex', n) or n in ('resources.arsc','AndroidManifest.xml') or n.startswith('res/') or n.startswith('assets/references/Phase3/STEP3_5ASSEMBLYSCAN/TABLET_DRIVER_PLACEMENT') or n.startswith('assets/references/Phase3/STEP3_1MOTORDRIVER_INVALID/') or '/var_light/VIDEO_' in n or '/var_light/VIDEO35_' in n or '/var_light/VIDEO789_' in n or '/var_light/VIDEO1011_' in n)
    if not names:
        raise SystemExit('No DEX in the built APK')
    for name in names:
        output.writestr(name, apk.read(name))
    # The installed generic runner can replay the latest fixtures too. This avoids
    # repeated USB installer prompts for every test-only code change.
    tests = root / 'app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk'
    with zipfile.ZipFile(tests) as fixtures:
        for index, name in enumerate(sorted(n for n in fixtures.namelist() if re.fullmatch(r'classes[0-9]*\.dex', n))):
            output.writestr(f'classes{100 + index}.dex', fixtures.read(name))
    # Test-only held-out pixels never enter the production reference registry/APK.
    manifest=root/'tools/phase789_video_manifest.json'
    if manifest.is_file():
        replay=root/'app/build/tmp/phase789-raw'
        paths=[replay/row['path'] for row in json.loads(manifest.read_text())['held_out']]
        if all(path.is_file() for path in paths):
            for path in paths:output.write(path,'assets/replay/phase789/'+path.name)
        else:
            print('Phase 7-9 raw replay files absent; native fixtures require separately pushed frames or re-ingestion.')
    manifest1011=root/'tools/phase1011_video_manifest.json'
    if manifest1011.is_file():
        replay=root/'app/build/tmp/phase1011-raw'
        paths=[replay/row['path'] for row in json.loads(manifest1011.read_text())['held_out']]
        if all(path.is_file() for path in paths):
            for path in paths:output.write(path,'assets/replay/phase1011/'+path.name)
        else:print('Phase 10–11 raw replay files absent; regenerate them from the recordings.')
print(target)
print(f'{target.stat().st_size} bytes; SHA256 {hashlib.sha256(target.read_bytes()).hexdigest()}')
