# Experimental ATmega chip locator

`arduinonano.onnx` was supplied by the project owner on 26 October 2026 and is copied into this app's assets. The application never opens the original Downloads file.

- SHA256: `ddc02a2b627e7781bf12235b46da23827c85f855072afa773fe57cd2b091fbe5`
- Embedded description: Ultralytics YOLO11n trained on `/content/Arduino-Nano-1/data.yaml`.
- Embedded class: `0: Mega328p`. The owner confirmed that training boxes cover the ATmega chip, **not the complete Nano PCB**.
- Input: float32 RGB `[1,3,640,640]`, image scale `1/255`, aspect-preserving letterbox with value 114.
- Output: float32 `[1,5,8400]`, channel-first center-X, center-Y, width, height and one class confidence; no separate objectness column.
- Embedded producer: PyTorch 2.11.0; ONNX opset 12; no quantization declared.
- Embedded license metadata: AGPL-3.0, https://ultralytics.com/license . The supplied model's training-data provenance and validation set were not provided.

`roi_locator.json` maps the chip class to a **search proposal** for the Nano. A chip detection expands the crop searched by the existing ORB body matcher. It never supplies a whole-board outline, orientation classification, ORB percentage, or installation pass on its own. Those still require photo/RANSAC and independent mounting-context evidence. An unrelated ATmega chip therefore cannot complete a Nano assembly simply because this model recognizes it.

The network is loaded on the existing background bank loader when a Nano target is selected, not for every startup or phase. Inference runs on the detector worker at most once a second while acquiring/lost, and stops while a verified or photo-localized wrong-orientation target is tracked. Unsupported output/import disables the locator while the existing vision pipeline continues. No new Gradle dependency or second camera session is used.

Initial native tablet replay localized the mounted chip at 86.08% confidence. It missed the sampled loose-front/reversed views, and returned no chip on the sampled missing-Nano or blank views. These are examples from existing recordings, **not an independent accuracy evaluation**. CPU inference measured about 400 ms warm in isolation and slower under the combined replay workload, so this model is not a 100 ms detector on this tablet.

To inspect metadata without an inference runtime:

```powershell
python -B tools/inspect_onnx_model.py app/src/main/assets/models/arduinonano.onnx
```

For on-device comparison, open **Benchmark & logging → Experimental Nano chip detector (ONNX)** in the tester or assembly diagnostic controls. The switch persists and starts enabled for this supplied-model trial. Turning it off stops future model queries while ORB/geometry and outlines continue. An already loaded network keeps its process-owned weights, but performs no background inference. Enabling it loads the model on the existing reference worker if needed. No progress is cleared.

To omit activation for a whole build, set `enabled` to `false` in `roi_locator.json`. Rebuild/update without uninstalling or clearing app data.
