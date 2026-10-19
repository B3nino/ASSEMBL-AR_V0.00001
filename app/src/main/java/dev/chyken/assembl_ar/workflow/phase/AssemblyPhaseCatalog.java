package dev.chyken.assembl_ar.workflow.phase;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.chyken.assembl_ar.camera.IndividualPartTestCatalog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.workflow.SectionEvidencePolicy;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.InventoryEvidence;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.Part;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.PartRole;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.SourceRange;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.TargetKind;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.VisualTarget;

import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

/**
 * The NEOBOT guide grouped into 18 learner-visible phases.
 *
 * <p>All 18 manual sections and their 152 source instructions are preserved. Sections 4–5
 * form one motor-lead job; driver power and control have independent phases. Section 11 is split between mounting
 * the distance sensor and wiring every sensor. Every camera target reuses an existing
 * recognition path, so a phase checks the same installed evidence the per-card guide did, once
 * the group is finished. Direct checks repeat every manual CHECK and section validation prompt
 * verbatim; authored lines consolidate fastening and seating facts the camera cannot see.</p>
 */
public final class AssemblyPhaseCatalog {
    /** Planned roadmap. Phase 3–11 membership awaits the user's revised grouping. */
    public static final int ROADMAP_PHASE_COUNT=11;
    /** Bump whenever phase membership, targets or checklists change; shown in the build stamp. */
    public static final int REVISION = 2;

    public static final String BOARD_PREPARATION = "board_preparation";
    public static final String MOTOR_ASSEMBLIES = "motor_assemblies";
    public static final String MOTOR_DRIVER_MOUNT = "motor_driver_mount";
    public static final String MOTOR_LEADS = "motor_leads";
    public static final String MICROCONTROLLER_FIT = "microcontroller";
    public static final String DRIVER_WIRING = "driver_power_control";
    public static final String DRIVER_CONTROL = "driver_control_signals";
    public static final String CASTER_FIT = "caster";
    public static final String LINE_TRACKERS = "line_trackers";
    public static final String DISTANCE_SENSOR_MOUNT = "distance_sensor_mount";
    public static final String SENSOR_WIRING = "sensor_wiring";
    public static final String CENTER_STANDOFF = "center_standoff";
    public static final String WHEEL_PAIR = "wheel_pair";
    public static final String OLED_PROTECTOR_FIT = "oled_protector";
    public static final String RIBBON_CABLE = "ribbon_cable";
    public static final String UPPER_BOARD = "upper_board";
    public static final String OLED_FIT = "oled_display";
    public static final String BATTERY_FIT = "battery";

    private static final List<AssemblyPhase> PHASES = build();

    private AssemblyPhaseCatalog() { }

    public static List<AssemblyPhase> phases() { return PHASES; }

    @Nullable
    public static AssemblyPhase byId(String id) {
        for (AssemblyPhase phase : PHASES) {
            if (phase.getId().equals(id)) return phase;
        }
        return null;
    }

    /** The phase that owns a source instruction; every NEOBOT instruction has exactly one. */
    @Nullable
    public static AssemblyPhase phaseFor(int section, int order) {
        for (AssemblyPhase phase : PHASES) {
            if (phase.contains(section, order)) return phase;
        }
        return null;
    }

    /** Phases that include any instruction of this manual section, in guide order. */
    public static List<AssemblyPhase> phasesForSection(int section) {
        List<AssemblyPhase> result = new ArrayList<>();
        for (AssemblyPhase phase : PHASES) {
            if (phase.getSections().contains(section)) result.add(phase);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Manual sections this phase finishes: its sections whose final source instruction belongs to
     * it. Split sections are saved only once their last phase is complete.
     */
    public static List<Integer> sectionsCompletedBy(AssemblyPhase phase) {
        List<Integer> result = new ArrayList<>();
        for (int section : phase.getSections()) {
            List<AssemblyPhase> owners = phasesForSection(section);
            if (owners.get(owners.size() - 1) == phase) result.add(section);
        }
        return Collections.unmodifiableList(result);
    }

    /** Later phases that physically build on this phase, directly or transitively. */
    public static List<AssemblyPhase> dependentsOf(String phaseId) {
        Set<String> affected = new LinkedHashSet<>(Collections.singleton(phaseId));
        List<AssemblyPhase> result = new ArrayList<>();
        for (AssemblyPhase phase : PHASES) {
            for (String dependency : phase.getDependsOn()) {
                if (affected.contains(dependency)) {
                    affected.add(phase.getId());
                    result.add(phase);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static AssemblyStep section(int number) {
        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            if (step.getStepNumber() == number) return step;
        }
        throw new IllegalArgumentException("Unknown NEOBOT section " + number);
    }

    /** Source instructions of the phase in manual order, including notes and warnings. */
    public static List<AssemblyInstruction> instructionsOf(AssemblyPhase phase) {
        List<AssemblyInstruction> result = new ArrayList<>();
        for (SourceRange range : phase.getSources()) {
            for (AssemblyInstruction instruction : section(range.getSection()).getInstructions()) {
                if (range.contains(instruction.getOrder())) result.add(instruction);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Uses the same loose-part policy as the per-card introduction scans. */
    static boolean hasLooseCameraReference(String componentId) {
        return IndividualPartTestCatalog.getStandaloneComponentIds().contains(componentId)
                || WHEEL.equals(componentId) || BATTERY_PACK.equals(componentId);
    }

    private static List<AssemblyPhase> build() {
        List<AssemblyPhase> phases = new ArrayList<>();
        phases.add(new PhaseBuilder(BOARD_PREPARATION, "Prepare the main board",
                "Place the main board component-side up on a clean, flat, non-metal surface.")
                .whole(1)
                .part(MAIN_BOARD, 1, "Component side up; the anchor for every later check.")
                .completesOnInventory()
                .build(phases));

        PhaseBuilder motors = new PhaseBuilder(MOTOR_ASSEMBLIES, "Install the motor assemblies",
                "Four bracket halves clamp two motors flat to the board with 30 mm screws and nuts.")
                .whole(2)
                .countedPart(MOTOR_BRACKET, 4, "Two halves clamp each motor.")
                .part(GEAR_MOTOR, 2, "Terminals and wires face inward; the right motor is flipped.")
                .checklistPart(SCREW_30MM, 4, "Two per motor.")
                .checklistPart(NUT, 4, "One per 30 mm screw.")
                .context(MAIN_BOARD, 1, "Prepared in the previous phase.")
                .tool(THREADLOCKER, 1, "Apply after tightening.", NAIL_POLISH)
                .tool(PHILLIPS_SCREWDRIVER, 1, "Tighten the screws.")
                .motorSlot("left_motor", "left motor", Arrays.asList(5, 6, 7))
                .motorSlot("right_motor", "right motor", Arrays.asList(17, 18, 19));
        String[] slotLabels = {"bracket 1 · left outer slot", "bracket 2 · left inner slot",
                "bracket 3 · right inner slot", "bracket 4 · right outer slot"};
        String[] slotTargets = {"left_outer_bracket", "left_inner_bracket",
                "right_inner_bracket", "right_outer_bracket"};
        for (int slot = 1; slot <= 4; slot++) {
            motors.bracketSlot(slot, slotTargets[slot - 1], slotLabels[slot - 1]);
        }
        phases.add(motors
                .check("All four bracket tabs are fully inserted in their guide slots and every "
                        + "bracket half stands upright.")
                .check("Both motors: output shafts face outward and terminals/wires face inward, "
                        + "toward the board center.")
                .check("Each motor is held by two 30 mm screws with a nut on the opposite side of "
                        + "each screw; all four are tightened by one additional turn.")
                .check("Threadlocker (or nail polish) is applied to all four motor nuts.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(MOTOR_DRIVER_MOUNT, "Mount the motor driver",
                "Align the driver with its guide holes and fasten it with two short screws.")
                .whole(3)
                .part(MOTOR_DRIVER, 1, "Align to both board guide holes.")
                .checklistPart(SCREW_SHORT, 2, "Through the driver and main-board guide holes.")
                .checklistPart(NUT, 2, "Placed on top of the driver.")
                .context(MAIN_BOARD, 1, "Prepared board.")
                .tool(THREADLOCKER, 1, "Lock both fasteners.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Tighten the screws.")
                .placement(3, "motor_driver", MOTOR_DRIVER, MAIN_BOARD, "motor driver",
                        Collections.singletonList(1))
                .check("Both short screws pass through the driver and main-board guide holes, "
                        + "with both nuts on top of the driver and tightened.")
                .check("Threadlocker is applied to both motor-driver nuts.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(MOTOR_LEADS, "Connect the motor leads",
                "Open all four terminals, then clamp each motor's red and black leads.")
                .whole(4).whole(5)
                .context(GEAR_MOTOR, 2, "Use each motor's attached red and black leads.")
                .context(MOTOR_DRIVER, 1, "Left and right motor screw-terminal pairs.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Loosen, then tighten all four terminal screws.")
                .check("All four motor-output terminal screws were loosened before inserting leads.")
                .check("The first motor's red and black leads are in its illustrated terminal pair; "
                        + "the flipped right motor's leads are in the other pair in reversed order.")
                .check("All four terminal screws are tightened.")
                .dependsOn(MOTOR_ASSEMBLIES, MOTOR_DRIVER_MOUNT)
                .build(phases));

        phases.add(new PhaseBuilder(MICROCONTROLLER_FIT, "Fit the microcontroller",
                "Seat the Nano board straight into both socket rows with its USB toward the rear.")
                .whole(6)
                .part(MICROCONTROLLER, 1, "Both header rows, no bent or offset pins.")
                .context(MAIN_BOARD, 1, "Nano socket and FRONT marking.")
                .placement(6, "microcontroller", MICROCONTROLLER, MAIN_BOARD, "microcontroller",
                        Arrays.asList(1, 2, 3))
                .check("Both pin rows are aligned with the matching sockets and no pin is bent "
                        + "or offset.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(DRIVER_WIRING, "Wire driver power",
                "Connect the two driver power leads to VIN and GND.")
                .whole(7)
                .checklistPart(JUMPER_WIRE, 2, "Two power leads.")
                .context(MOTOR_DRIVER, 1, "VCC, GND and the four signal inputs.")
                .context(MICROCONTROLLER, 1, "VIN, GND, D5, D9, D6 and D10.")
                .pinCheck(7, 1, 2)
                .dependsOn(MOTOR_DRIVER_MOUNT, MICROCONTROLLER_FIT)
                .build(phases));

        phases.add(new PhaseBuilder(DRIVER_CONTROL, "Wire driver control signals",
                "Connect the four PWM control leads to D5, D9, D6 and D10.")
                .whole(8)
                .checklistPart(JUMPER_WIRE, 4, "Four control signal leads.")
                .context(MOTOR_DRIVER, 1, "Four signal inputs.")
                .context(MICROCONTROLLER, 1, "D5, D9, D6 and D10.")
                .pinCheck(8, 1, 2, 3, 4)
                .dependsOn(DRIVER_WIRING)
                .build(phases));

        phases.add(new PhaseBuilder(CASTER_FIT, "Fit the caster",
                "Build two 20 mm screw posts, then lock the caster bracket onto them.")
                .whole(9)
                .part(CASTER_WHEEL, 1, "Front-mounted with the ball facing down.")
                .checklistPart(SCREW_20MM, 2, "Left and right posts.")
                .checklistPart(NUT, 4, "Two lock the posts, two retain the caster.")
                .context(MAIN_BOARD, 1, "Front caster mounting holes.")
                .tool(THREADLOCKER, 1, "Apply to both post assemblies.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Tighten the screws.")
                .placement(9, "caster", CASTER_WHEEL, MAIN_BOARD, "front caster",
                        Collections.singletonList(7))
                .check("Both 20 mm screw posts are locked to the board by tightened board-level "
                        + "nuts with threadlocker.")
                .check("The caster bracket sits over both posts and is held by two tightened "
                        + "retaining nuts with threadlocker.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(LINE_TRACKERS, "Fit the line-tracker pair",
                "Mount one downward-facing tracker on each side, spaced off the board.")
                .whole(10)
                .part(LINE_TRACKER, 2, "One left and one right, sensing faces down.")
                .checklistPart(PLASTIC_SPACER, 2, "One under each tracker; stacked nuts are allowed.",
                        NUT)
                .checklistPart(MOUNTING_SCREW, 2, "Through tracker, spacer and board.")
                .checklistPart(NUT, 2, "Lock the mounting screws.")
                .context(MAIN_BOARD, 1, "Side mounting holes.")
                .placement(10, "left_tracker", LINE_TRACKER, MAIN_BOARD, "left line tracker",
                        Arrays.asList(2, 3, 4, 6))
                .placement(10, "right_tracker", LINE_TRACKER, MAIN_BOARD, "right line tracker",
                        Arrays.asList(8, 9, 10, 12))
                .check("Each tracker has a plastic spacer (or stacked nuts) between it and the board.")
                .check("Each tracker's mounting screw passes through tracker, spacer and board, and "
                        + "its nut is tightened.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(DISTANCE_SENSOR_MOUNT, "Fit the distance sensor",
                "Face both transducers forward and press all four pins into the front header.")
                .orders(11, 2, 3, 4)
                .part(DISTANCE_SENSOR, 1, "Front header, transducers facing forward.")
                .context(MAIN_BOARD, 1, "Front four-pin header.")
                .placement(11, "distance_sensor", DISTANCE_SENSOR, MAIN_BOARD,
                        "front distance sensor", Arrays.asList(2, 3, 4))
                .check("Both circular transducers face forward.")
                .check("All four sensor pins are aligned with the front header and the sensor is "
                        + "pressed evenly until fully seated.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(SENSOR_WIRING, "Wire the sensors and outputs",
                "Ten jumper leads for the LED, speaker, distance sensor and both line trackers.")
                .orders(11, 1, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
                .checklistPart(JUMPER_WIRE, 10, "One for each exposed mapping.")
                .context(DISTANCE_SENSOR, 1, "Trigger and Echo pins.")
                .context(LINE_TRACKER, 2, "Already mounted left and right.")
                .context(LED, 1, "Integrated in the main board.")
                .context(SPEAKER, 1, "Integrated in the main board.")
                .context(MICROCONTROLLER, 1, "D2, D3, D4, A1, A2, A3, 5V and GND.")
                .pinCheck(11, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12)
                .check("No extra wire was added for distance-sensor VCC/GND or LED/speaker ground; "
                        + "the main-board PCB already connects them.")
                .dependsOn(MICROCONTROLLER_FIT, LINE_TRACKERS, DISTANCE_SENSOR_MOUNT)
                .build(phases));

        phases.add(new PhaseBuilder(CENTER_STANDOFF, "Fit the center standoff",
                "Lock the standoff in the center hole and keep its upper screw for later.")
                .whole(12)
                .checklistPart(STANDOFF, 1, "Center hole; keep its upper screw.")
                .checklistPart(NUT, 1, "Secured under the robot.")
                .context(MAIN_BOARD, 1, "Center mounting hole.")
                .tool(THREADLOCKER, 1, "Lock the lower fastener.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Tighten while keeping the upper screw.")
                .placement(12, "center_standoff", STANDOFF, MAIN_BOARD, "center standoff",
                        Arrays.asList(2, 3))
                .check("The standoff passes through the center hole from the top, its nut is "
                        + "tightened underneath and threadlocker is applied.")
                .check("The upper screw is kept on the standoff for the extension board.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(WHEEL_PAIR, "Fit the wheel pair",
                "Press one wheel fully onto each motor shaft.")
                .whole(13)
                .part(WHEEL, 2, "One on each gear-motor shaft.")
                .context(GEAR_MOTOR, 2, "Motor shafts must be unobstructed.")
                .placement(13, "left_wheel", WHEEL, MAIN_BOARD, "left wheel", Arrays.asList(1, 2))
                .placement(13, "right_wheel", WHEEL, MAIN_BOARD, "right wheel", Arrays.asList(3, 4))
                .check("Each wheel hub is pressed fully onto its motor shaft.")
                .dependsOn(MOTOR_ASSEMBLIES)
                .build(phases));

        phases.add(new PhaseBuilder(OLED_PROTECTOR_FIT, "Fit the OLED protector",
                "Fasten the protector over the OLED footprint on the loose extension board.")
                .whole(14)
                .checklistPart(OLED_PROTECTOR, 1, "Over the extension-board OLED footprint.")
                .part(EXTENSION_BOARD, 1, "The 'other PCB' in the guide; the work surface here.")
                .checklistPart(MOUNTING_SCREW, 2, "The two attachment points on page 23; the guide "
                        + "does not state their size.")
                .checklistPart(NUT, 2, "One on the reverse side of each fastener.")
                .tool(THREADLOCKER, 1, "Prevent loosening.")
                .placement(14, "oled_protector", OLED_PROTECTOR, EXTENSION_BOARD, "OLED protector",
                        Arrays.asList(2, 3))
                .check("Both protector fasteners pass through the protector and board, with nuts on "
                        + "the reverse side, tightened.")
                .check("Threadlocker is applied to both OLED-protector nuts.")
                .build(phases));

        phases.add(new PhaseBuilder(RIBBON_CABLE, "Connect the ribbon cable",
                "Seat both keyed connectors, keeping the red stripe direction and no twist.")
                .whole(15)
                .checklistPart(DATA_CABLE, 1, "Both keyed connectors; red edge stripe for direction.")
                .context(MAIN_BOARD, 1, "Lower connector.")
                .context(EXTENSION_BOARD, 1, "Upper connector.")
                .check("The main-board connector is fully and evenly seated in its header.")
                .check("The extension-board connector is fully seated with the red-edge stripe in "
                        + "the direction shown on page 25.")
                .dependsOn(OLED_PROTECTOR_FIT, SENSOR_WIRING)
                .build(phases));

        phases.add(new PhaseBuilder(UPPER_BOARD, "Fit the upper board",
                "Route the wiring inside, rest the extension board on the brackets and fasten it.")
                .whole(16)
                .context(EXTENSION_BOARD, 1, "The prepared upper cover.")
                .context(STANDOFF, 1, "Use the screw kept in the standoff phase.")
                .context(MOTOR_BRACKET, 4, "Their upper edges support the board.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Tighten the center screw.")
                .placement(16, "extension_board", EXTENSION_BOARD, MAIN_BOARD, "extension board",
                        Arrays.asList(2, 4, 6))
                .check("The retained center screw passes through the extension board into the "
                        + "standoff and is tightened.")
                .dependsOn(MOTOR_ASSEMBLIES, CENTER_STANDOFF, RIBBON_CABLE)
                .build(phases));

        phases.add(new PhaseBuilder(OLED_FIT, "Fit the OLED display",
                "Seat the OLED straight into its protected four-pin socket.")
                .whole(17)
                .part(OLED_DISPLAY, 1, "Match GND, VCC, SCL and SDA to the socket.")
                .context(OLED_PROTECTOR, 1, "Installed on the extension board.")
                .context(EXTENSION_BOARD, 1, "I2C/OLED footprint.")
                .placement(17, "oled_display", OLED_DISPLAY, EXTENSION_BOARD, "OLED display",
                        Arrays.asList(2, 3, 4))
                .pinCheck(17, 1, 2, 3, 4)
                .check("The OLED is pressed straight in and all four pins are fully seated.")
                .dependsOn(OLED_PROTECTOR_FIT, UPPER_BOARD)
                .build(phases));

        phases.add(new PhaseBuilder(BATTERY_FIT, "Fit the battery",
                "Secure a compatible 7–12 V pack on the extension board, clear of moving parts.")
                .whole(18)
                .part(BATTERY_PACK, 1, "A compatible 7–12 V pack.")
                .tool(DOUBLE_SIDED_TAPE, 1, "Or a separately made 3D-printed holder.",
                        BATTERY_HOLDER)
                .context(EXTENSION_BOARD, 1, "Battery mounting surface.")
                .placement(18, "battery", BATTERY_PACK, EXTENSION_BOARD, "battery pack",
                        Arrays.asList(3, 4))
                .check("The battery is held by double-sided tape or a prepared holder and cannot "
                        + "move into nearby components.")
                .dependsOn(UPPER_BOARD)
                .build(phases));
        return Collections.unmodifiableList(phases);
    }

    private static final class PhaseBuilder {
        private final String id;
        private final String title;
        private final String summary;
        private final List<SourceRange> sources = new ArrayList<>();
        private final List<Part> parts = new ArrayList<>();
        private final List<VisualTarget> targets = new ArrayList<>();
        private final List<String> checks = new ArrayList<>();
        private final List<String> dependsOn = new ArrayList<>();
        private boolean completesOnInventory;

        PhaseBuilder(String id, String title, String summary) {
            this.id = id;
            this.title = title;
            this.summary = summary;
        }

        PhaseBuilder whole(int section) {
            sources.add(new SourceRange(section, Collections.emptySet(), true));
            return this;
        }

        PhaseBuilder orders(int section, Integer... orders) {
            sources.add(new SourceRange(section, new LinkedHashSet<>(Arrays.asList(orders)), false));
            return this;
        }

        /** A new part identified by the camera, or listed when no loose reference exists. */
        PhaseBuilder part(String componentId, int quantity, String note) {
            InventoryEvidence evidence = hasLooseCameraReference(componentId)
                    ? InventoryEvidence.CAMERA_IDENTITY : InventoryEvidence.CHECKLIST;
            parts.add(new Part(componentId, quantity, PartRole.NEW_PART, evidence,
                    Collections.emptyList(), note));
            return this;
        }

        PhaseBuilder countedPart(String componentId, int quantity, String note) {
            parts.add(new Part(componentId, quantity, PartRole.NEW_PART,
                    InventoryEvidence.CAMERA_COUNT, Collections.emptyList(), note));
            return this;
        }

        PhaseBuilder checklistPart(String componentId, int quantity, String note,
                                   String... alternatives) {
            parts.add(new Part(componentId, quantity, PartRole.NEW_PART, InventoryEvidence.CHECKLIST,
                    Arrays.asList(alternatives), note));
            return this;
        }

        PhaseBuilder context(String componentId, int quantity, String note) {
            parts.add(new Part(componentId, quantity, PartRole.INSTALLED_CONTEXT,
                    InventoryEvidence.CHECKLIST, Collections.emptyList(), note));
            return this;
        }

        PhaseBuilder tool(String componentId, int quantity, String note, String... alternatives) {
            parts.add(new Part(componentId, quantity, PartRole.TOOL_OR_CONSUMABLE,
                    InventoryEvidence.CHECKLIST, Arrays.asList(alternatives), note));
            return this;
        }

        PhaseBuilder motorSlot(String targetId, String label, List<Integer> corrections) {
            targets.add(new VisualTarget(targetId, TargetKind.MOTOR_SLOT, "motor_pair", 2,
                    GEAR_MOTOR, MAIN_BOARD, label, 0, targetId, corrections));
            return this;
        }

        PhaseBuilder bracketSlot(int slot, String placementTargetId, String label) {
            targets.add(new VisualTarget("bracket_slot_" + slot, TargetKind.BRACKET_SLOT,
                    "bracket_slot_" + slot, 2, MOTOR_BRACKET, MAIN_BOARD, label, slot,
                    placementTargetId, Collections.singletonList(slot)));
            return this;
        }

        PhaseBuilder placement(int section, String targetId, String componentId, String anchorId,
                               String label, List<Integer> corrections) {
            targets.add(new VisualTarget(targetId, TargetKind.SECTION_PLACEMENT,
                    "section_" + section, section, componentId, anchorId, label, 0, targetId,
                    corrections));
            return this;
        }

        PhaseBuilder check(String text) {
            checks.add(text);
            return this;
        }

        /** Restates the exact authored pin mappings as one direct check each. */
        PhaseBuilder pinCheck(int section, int... pinOrders) {
            AssemblyStep step = section(section);
            for (int order : pinOrders) {
                PinConnection pin = null;
                for (PinConnection candidate : step.getPinConnections()) {
                    if (candidate.getOrder() == order) pin = candidate;
                }
                Objects.requireNonNull(pin, "Unknown pin " + section + ":" + order);
                checks.add(componentName(pin.getSourceComponentId()) + " " + pin.getSourcePin()
                        + " → " + componentName(pin.getTargetComponentId()) + " "
                        + pin.getTargetPin() + " is connected.");
            }
            return this;
        }

        PhaseBuilder dependsOn(String... phaseIds) {
            dependsOn.addAll(Arrays.asList(phaseIds));
            return this;
        }

        PhaseBuilder completesOnInventory() {
            completesOnInventory = true;
            return this;
        }

        AssemblyPhase build(List<AssemblyPhase> earlier) {
            for (String dependency : dependsOn) {
                boolean known = false;
                for (AssemblyPhase phase : earlier) known |= phase.getId().equals(dependency);
                if (!known) throw new IllegalStateException(id + " depends on a later phase " + dependency);
            }
            List<String> directChecks = new ArrayList<>(checks);
            if (!completesOnInventory) {
                // Every manual CHECK and each finished section's validation prompt stays verbatim.
                for (SourceRange range : sources) {
                    AssemblyStep step = section(range.getSection());
                    for (AssemblyInstruction instruction : step.getInstructions()) {
                        if (instruction.getType() == InstructionType.CHECK
                                && range.contains(instruction.getOrder())) {
                            directChecks.add(instruction.getText());
                        }
                    }
                    if (range.isWholeSection() || range.contains(lastOrder(step))) {
                        directChecks.add(step.getValidationPrompt());
                    }
                }
            }
            return new AssemblyPhase(id, earlier.size() + 1, title, summary, sources, parts,
                    targets, dedupe(directChecks), dependsOn, completesOnInventory);
        }
    }

    private static int lastOrder(AssemblyStep step) {
        List<AssemblyInstruction> instructions = step.getInstructions();
        return instructions.get(instructions.size() - 1).getOrder();
    }

    private static List<String> dedupe(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    /** The component's display name, or its ID when it is not in the NEOBOT catalog. */
    public static String componentName(String componentId) {
        for (dev.chyken.assembl_ar.model.Component component : NeobotSeedData.getComponents()) {
            if (component.getId().equals(componentId)) return component.getName();
        }
        return componentId;
    }

    /** Sections whose final camera check was physical-only in the per-card guide. */
    static boolean isPhysicalOnlySection(int section) {
        return SectionEvidencePolicy.isPhysicalFinal(section);
    }
}
