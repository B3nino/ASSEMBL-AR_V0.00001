# Component model assets

The active neural detectors are the nine full-component models under trained/.
Phase 5 uses trained/phase5.onnx to locate the complete Arduino Nano board.
The former ATmega chip detector, arduinonano.onnx and roi_locator.json were removed.

See trained/README.md and docs/TRAINED_MODEL_ASSISTANCE.md for input contracts,
component routing, verifier boundaries and validation limitations.
The OBJ/MTL and recognition-template assets in this directory remain separate
from the trained neural detectors and support the existing rendering/edge paths.
