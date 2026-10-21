# Wiring schematic standard

These rules record the user's requested style for Phase 6 and future wiring overhauls.

- Use the Phase 4 circuit presentation as the baseline: component artwork, identifiable physical pins, and straight horizontal/vertical wire runs with sharp 90-degree elbows.
- Use CircuitWireRoute.elbow or CircuitWireRoute.perimeter. Do not introduce Bézier curves, diagonal freehand wires, or rounded cable-like routing.
- Give each net its own routing lane. Route opposite-side pins around the component perimeter rather than through the board image.
- Read endpoint positions from SchematicComponentCatalog and apply the same SchematicPlacement transform to artwork, wires and touch targets.
- Keep user-selected wire colors consistent across the picker, main schematic, legend and full-screen view. Retain visible outlines for black, white and yellow wires.
- Crossing wires are separate nets unless an explicit electrical junction is authored. Do not place connection dots at ordinary crossings; endpoint markers belong on pins.
- Preserve traveling highlights and wire inspection on the complete orthogonal polyline. Test hit targets on long rails and elbows, not only near sampled points.
- Keep the large schematic accessible immediately after Apply Colors; retain full-screen zoom/pan and the assigned-color legend.
- Do not ask users to scan an already identified and installed component again just to enter its wiring phase. Phase 6 begins with color selection.
- Renumber HUD, TTS, developer navigation and project details together. Version saved checkpoints and migrate the previous step positions and confirmations when a step is removed.

Implementation:
- app/src/main/java/dev/chyken/assembl_ar/model/CircuitWireRoute.java
- app/src/main/java/dev/chyken/assembl_ar/ui/DriverSchematicRenderer.java
- app/src/main/java/dev/chyken/assembl_ar/ui/MotorWiringArtworkRenderer.java
- app/src/main/java/dev/chyken/assembl_ar/ui/WiringWorkspace.java

