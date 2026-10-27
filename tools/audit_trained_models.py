"""Verify packaged training contracts and actual APK bytes, without downloads."""
from pathlib import Path
import argparse,hashlib,json,zipfile

ROOT=Path(__file__).resolve().parents[1]
EXPECTED={1:['main_board_front','main_board_back'],2:['motor_bracket','gear_motor'],3:['motor_driver'],
          5:['arduino_nano'],7:['caster_wheel','line_tracker'],8:['ultrasonic_distance_sensor'],
          9:['center_standoff','drive_wheel'],10:['extension_data_cable','extension_board','oled_protector'],
          11:['oled_display','battery_pack']}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path);parser.add_argument('--onnx',action='store_true');args=parser.parse_args()
    assets=ROOT/'app/src/main/assets/models/trained'
    apk=ROOT/'app/build/intermediates/apk/debug/app-arm64-v8a-debug.apk'
    models=[]
    with zipfile.ZipFile(apk) as archive:
        assert 'assets/models/arduinonano.onnx' not in archive.namelist(),'Retired chip model still packaged'
        assert 'assets/models/roi_locator.json' not in archive.namelist(),'Retired chip manifest still packaged'
        dex=[archive.read(name) for name in archive.namelist() if name.startswith('classes') and name.endswith('.dex')]
        assert any(b'routing=full-components-v2' in data for data in dex),'APK predates corrected model routing'
        assert not any(b'Ldev/chyken/assembl_ar/vision/OpenCvDnnRoiLocator;' in data for data in dex),'Retired detector class still packaged'
        for phase,names in EXPECTED.items():
            model=assets/f'phase{phase}.onnx';contract=assets/f'phase{phase}.json';data=json.loads(contract.read_text(encoding='utf-8'))
            digest=hashlib.sha256(model.read_bytes()).hexdigest()
            assert digest==data['sha256'],f'phase{phase}: checksum'
            assert data['classes']=={str(i):name for i,name in enumerate(names)},f'phase{phase}: class mapping'
            assert data['input']['shape']==[1,3,512,512] and data['input']['color']=='RGB' and data['input']['dtype']=='float32'
            assert data['output']['shape']==[1,4+len(names),5376]
            assert data['opset']==12 and data['precision']=='float32' and data['dynamic'] is False and data['embedded_nms'] is False
            for p in [model,contract]:
                assert archive.read('assets/models/trained/'+p.name)==p.read_bytes(),f'APK contains stale {p.name}'
            if args.onnx:
                import onnx
                graph=onnx.load(str(model));onnx.checker.check_model(graph)
                def shape(value):return [d.dim_value for d in value.type.tensor_type.shape.dim]
                assert shape(graph.graph.input[0])==data['input']['shape']
                assert shape(graph.graph.output[0])==data['output']['shape']
            models.append({'phase':phase,'sha256':digest,'bytes':model.stat().st_size,'classes':names,'apk_bytes_match':True})
        assert not any(f'assets/models/trained/phase{phase}.onnx' in archive.namelist() for phase in [4,6])
    with apk.open('rb') as stream:apk_digest=hashlib.file_digest(stream,'sha256').hexdigest()
    result={'models':models,'total_model_bytes':sum(m['bytes'] for m in models),'onnx_checker':args.onnx,
            'full_component_routing_marker':True,'retired_chip_removed':True,
            'apk':str(apk.relative_to(ROOT)),'apk_bytes':apk.stat().st_size,'apk_sha256':apk_digest}
    if args.output:args.output.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
