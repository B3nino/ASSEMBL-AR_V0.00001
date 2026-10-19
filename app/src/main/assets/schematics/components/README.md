# Component artwork and connection anchors

These are the two original user-supplied PNGs, copied without pixel changes. Android loads them from assets on a background executor; no network or camera analysis is involved.

| Asset | Native size | Native orientation |
| --- | --- | --- |
| `tt_gear_motor.png` | 605 × 291 | Gray motor cap left, yellow gearbox right |
| `dual_motor_driver.png` | 765 × 1024 | Green screw blocks top, six small header pins bottom |

## Authoritative anchors

`data/SchematicComponentCatalog.java` stores all anchors in normalized coordinates relative to the **original image**, independent of screen size. Native pixel measurements:

- Motor copper tabs: upper `(27, 71)`, lower `(27, 220)`. The image does not identify electrical polarity.
- Driver openings, left to right in native orientation: MOTOR B left `(110, 57)`, MOTOR B right `(290, 57)`, MOTOR A left `(485, 57)`, MOTOR A right `(665, 57)`.
- Matching screw heads: same x coordinates, y `218`. A wire ends at an **opening**; its screw gets a separate tightening ring.
- Six small header pins, left to right: B-IA, B-IB, GND, VCC, A-IA, A-IB. Their centers are `(185 + 85 × index, 946)`. They are typed separately from motor outputs.

## Phase 4 view and routes

The driver is rotated 180° so green blocks face the user and the six small pins face away, matching `references/assembly-captures/main-board-driver-leads-20261005_222717.jpg`. Both motor pictures rotate 90° clockwise, cap at top and gearbox below. "Inner" means facing the board center; "outer" means facing the wheels.

| Pictured tab | Opening in the mounted view | Native anchor |
| --- | --- | --- |
| Left inner | Left pair, left opening | `a_right_opening` |
| Left outer | Left pair, right opening | `a_left_opening` |
| Right outer | Right pair, right opening | `b_left_opening` |
| Right inner | Right pair, left opening | `b_right_opening` |

These routes preserve the existing assembly reference's lead order. Tab names describe the picture; the artwork does not establish +/−, motor direction, or electrical continuity. Legacy positive/negative enum names remain internal serialization keys so existing saved color choices are preserved.

## Reusing this for later schematics

1. Add the original artwork here and register its dimensions and named anchors in `SchematicComponentCatalog`.
2. Classify each connector accurately (`MOTOR_TAB`, `WIRE_OPENING`, `SCREW_HEAD`, `CONTROL_HEADER`, `POWER_HEADER`, `GROUND_HEADER`). Keep the insertion opening and tightening screw separate.
3. Use `SchematicPlacement.fit` for a whole component, or `fitCrop` for an enlarged detail, with the required clockwise quarter turns.
4. Draw the image and map its pin anchors with that same placement. `MotorWiringArtworkRenderer.image` demonstrates the affine Canvas matrix. Do not maintain a separate hand-positioned set of touch points.
5. Connect named anchors, use the selected insulation colors, and provide positional text plus spoken instructions. Add crop/rotation tests when introducing another component orientation.

Phase 4 uses the full artwork and all four wires in large views. Compact previews enlarge the selected motor and driver screw-block detail. Four selectors expose all connections; extremely short previews offer expansion instead of shrinking the images into unreadable thumbnails.
