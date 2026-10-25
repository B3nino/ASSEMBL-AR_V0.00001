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
 * The NEOBOT guide grouped into 11 learner-visible phases.
 *
 * <p>All 18 manual sections and their 152 source instructions are preserved. Sections 4–5
 * form one motor-lead job; driver power and control share Phase 6; sections 9–10 share
 * the caster and line-tracker Phase 7. Section 11 is split between mounting
 * the distance sensor and wiring every sensor. Every camera target reuses an existing
 * recognition path, so a phase checks the same installed evidence the per-card guide did, once
 * the group is finished. Direct checks repeat every manual CHECK and section validation prompt
 * verbatim; authored lines consolidate fastening and seating facts the camera cannot see.</p>
 */
public final class AssemblyPhaseCatalog {
    /** Current learner roadmap; legacy manual IDs remain stable for saved progress. */
    public static final int ROADMAP_PHASE_COUNT=11;
    /** Bump whenever phase membership, targets or checklists change; shown in the build stamp. */
    public static final int REVISION = 7;

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
        if(BATTERY_FIT.equals(id))id=OLED_FIT;
        if(RIBBON_CABLE.equals(id)||UPPER_BOARD.equals(id))id=OLED_PROTECTOR_FIT;
        if(WHEEL_PAIR.equals(id))id=CENTER_STANDOFF;
        if(SENSOR_WIRING.equals(id))id=DISTANCE_SENSOR_MOUNT;
        if(LINE_TRACKERS.equals(id))id=CASTER_FIT;
        if(DRIVER_CONTROL.equals(id))id=DRIVER_WIRING; // Saved pre-overhaul sessions retain their destination.
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
                "Choose your wire colors, follow the custom schematic, then pull-test all four leads.")
                .whole(4).whole(5)
                .context(GEAR_MOTOR, 2, "Match the selected colors to each motor's positive and negative leads.")
                .context(MOTOR_DRIVER, 1, "Left and right motor screw-terminal pairs.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Loosen, then tighten all four terminal screws.")
                .check("All four motor-output terminal screws were loosened before inserting leads.")
                .check("The first motor's selected leads are in its illustrated terminal pair; "
                        + "the flipped right motor's leads are in the other pair in reversed order.")
                .check("All four terminal screws are tightened.")
                .dependsOn(MOTOR_ASSEMBLIES, MOTOR_DRIVER_MOUNT)
                .build(phases));

        phases.add(new PhaseBuilder(MICROCONTROLLER_FIT, "Install the Arduino Nano",
                "Identify the Nano, follow its installation demo, verify USB orientation and check all pins.")
                .whole(6)
                .part(MICROCONTROLLER, 1, "Both header rows, no bent or offset pins.")
                .context(MAIN_BOARD, 1, "Nano socket and FRONT marking.")
                .placement(6, "microcontroller", MICROCONTROLLER, MAIN_BOARD, "microcontroller",
                        Arrays.asList(1, 2, 3))
                .check("Both pin rows are aligned with the matching sockets and no pin is bent "
                        + "or offset.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(DRIVER_WIRING, "Connect the Nano and motor driver",
                "Select six wire colors, connect power and PWM signals, then check every header.")
                .whole(7).whole(8)
                .context(MICROCONTROLLER, 1, "Already identified and installed in Phase 5.")
                .checklistPart(JUMPER_WIRE, 6, "Two power and four PWM control leads.")
                .context(MOTOR_DRIVER, 1, "VCC, GND, A-IA, A-IB, B-IA and B-IB.")
                .pinCheck(7, 1, 2).pinCheck(8, 1, 2, 3, 4)
                .check("All six jumper housings are fully seated with no adjacent cross-connections.")
                .dependsOn(MOTOR_DRIVER_MOUNT, MICROCONTROLLER_FIT)
                .build(phases));

        phases.add(new PhaseBuilder(CASTER_FIT, "Install the caster and line trackers",
                "Scan the caster and two IR trackers, confirm hardware, follow both demos and verify the underside.")
                .whole(9).whole(10)
                .part(CASTER_WHEEL, 1, "Front-mounted with the ball facing down.")
                .checklistPart(SCREW_20MM, 2, "Left and right posts.")
                .checklistPart(NUT, 6, "Four caster nuts and two tracker nuts.")
                .context(MAIN_BOARD, 1, "Front caster mounting holes.")
                .tool(THREADLOCKER, 1, "Apply to both post assemblies.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Tighten the screws.")
                .placement(9, "caster", CASTER_WHEEL, MAIN_BOARD, "front caster",
                        Collections.singletonList(7))
                .check("Both 20 mm screw posts are locked to the board by tightened board-level "
                        + "nuts with threadlocker.")
                .check("The caster bracket sits over both posts and is held by two tightened "
                        + "retaining nuts with threadlocker.")
                .part(LINE_TRACKER, 2, "One left and one right, sensing faces down.")
                .checklistPart(PLASTIC_SPACER, 2, "One under each tracker; stacked nuts are allowed.",
                        NUT)
                .checklistPart(MOUNTING_SCREW, 2, "Through tracker, spacer and board.")
                .placement(10, "left_tracker", LINE_TRACKER, MAIN_BOARD, "left line tracker",
                        Arrays.asList(2, 3, 4, 6))
                .placement(10, "right_tracker", LINE_TRACKER, MAIN_BOARD, "right line tracker",
                        Arrays.asList(8, 9, 10, 12))
                .check("Each tracker has a plastic spacer (or stacked nuts) between it and the board.")
                .check("Each tracker's mounting screw passes through tracker, spacer and board, and "
                        + "its nut is tightened.")
                .dependsOn(BOARD_PREPARATION)
                .build(phases));

        phases.add(new PhaseBuilder(DISTANCE_SENSOR_MOUNT, "Install and wire the distance sensor",
                "Identify the sensor, verify outward installation, then wire signals, indicators and both trackers using your own colors.")
                .whole(11)
                .part(DISTANCE_SENSOR, 1, "Front socket; both transducers face outward.")
                .checklistPart(JUMPER_WIRE, 12, "Ten connections and two spares. Sensor VCC/GND connect through the PCB.")
                .context(LINE_TRACKER, 2, "Already mounted in Phase 7.")
                .context(LED, 1, "Integrated in the main board.")
                .context(SPEAKER, 1, "Integrated in the main board.")
                .context(MICROCONTROLLER, 1, "D2, D3, D4, A1, A2 and A3.")
                .context(MAIN_BOARD, 1, "Sensor socket, 5V and GND/signal rails.")
                .placement(11, "distance_sensor", DISTANCE_SENSOR, MAIN_BOARD, "front distance sensor", Arrays.asList(2, 3, 4))
                .pinCheck(11, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12)
                .check("Sensor eyes face outward and all four pins are fully seated.")
                .check("No extra wires are needed for sensor VCC/GND or LED/speaker grounds; the PCB connects them.")
                .check("All ten jumper housings are seated, with the tracker leads routed through the PCB holes.")
                .dependsOn(BOARD_PREPARATION, MICROCONTROLLER_FIT, CASTER_FIT)
                .build(phases));

        phases.add(new PhaseBuilder(CENTER_STANDOFF, "Install the standoff and wheels",
                "Identify the standoff and two wheels, follow both demos, verify installation and check the fasteners and axle grip.")
                .whole(12).whole(13)
                .part(STANDOFF, 1, "Center hole; remove and save its top screw for the extension board.")
                .part(WHEEL, 2, "One on each gear-motor shaft; either hub face is accepted for identification.")
                .checklistPart(NUT, 1, "Lock underneath the center standoff.")
                .context(MAIN_BOARD, 1, "Center mounting hole.")
                .context(GEAR_MOTOR, 2, "Align the flat shafts with the wheel hubs.")
                .tool(THREADLOCKER, 1, "Lock the lower fastener after tightening.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Tighten the standoff fastener; save its top screw.")
                .placement(12, "center_standoff", STANDOFF, MAIN_BOARD, "center standoff", Arrays.asList(2, 3))
                .placement(13, "left_wheel", WHEEL, MAIN_BOARD, "left wheel", Arrays.asList(1, 2))
                .placement(13, "right_wheel", WHEEL, MAIN_BOARD, "right wheel", Arrays.asList(3, 4))
                .check("The center standoff is locked tightly with its lower M3 nut.")
                .check("The top screw is removed and saved for the extension board.")
                .check("Both wheel hubs are fully seated on the motor shafts without slipping.")
                .dependsOn(BOARD_PREPARATION, MOTOR_ASSEMBLIES)
                .build(phases));

        phases.add(new PhaseBuilder(OLED_PROTECTOR_FIT, "Place and connect the extension board",
                "Scan the cable, board and protector; gather hardware, follow four demos, verify both assemblies and confirm secure connections.")
                .whole(14).whole(15).whole(16)
                .cameraPart(DATA_CABLE, 1, "Both keyed connectors; keep the red edge stripe oriented as shown.")
                .part(EXTENSION_BOARD, 1, "Front face up; upper board and OLED footprint.")
                .cameraPart(OLED_PROTECTOR, 1, "Front face up; align both mounting holes.")
                .checklistPart(SCREW_20MM, 2, "Fasten the OLED protector through the extension board.")
                .checklistPart(NUT, 2, "One underneath each protector fastener.")
                .context(MAIN_BOARD, 1, "Lower cable header and chassis.")
                .context(STANDOFF, 1, "Reuse the top screw saved in Phase 9.")
                .context(MOTOR_BRACKET, 4, "Support the extension board evenly.")
                .tool(THREADLOCKER, 1, "Prevent protector nuts loosening.")
                .tool(PHILLIPS_SCREWDRIVER, 1, "Tighten protector hardware and center screw.")
                .placement(14, "oled_protector", OLED_PROTECTOR, EXTENSION_BOARD, "OLED protector", Arrays.asList(2,3))
                .placement(16, "extension_board", EXTENSION_BOARD, MAIN_BOARD, "extension board", Arrays.asList(2,4,6))
                .check("Both protector screws and M3 nuts are tight and the protector does not jiggle.")
                .check("Both data cable connectors are fully seated in the orientation shown.")
                .check("The top board is firm and level on its supports with no pinched wires.")
                .dependsOn(MOTOR_ASSEMBLIES, CENTER_STANDOFF, DISTANCE_SENSOR_MOUNT)
                .build(phases));

        phases.add(new PhaseBuilder(OLED_FIT, "Install OLED display and battery",
                "Seat the OLED, secure the battery beside it on the upper Extension Board and complete the power check.")
                .whole(17).whole(18)
                .part(OLED_DISPLAY, 1, "Match all four pins to the protected socket.")
                .part(BATTERY_PACK, 1, "A compatible 7–12 V pack; keep the switch OFF while connecting.")
                .tool(DOUBLE_SIDED_TAPE, 1, "Or a securing strap / prepared holder.", BATTERY_HOLDER)
                .context(OLED_PROTECTOR, 1, "Installed OLED support.")
                .context(EXTENSION_BOARD, 1, "Mount the battery beside the OLED as shown in the demo.")
                .placement(17, "oled_display", OLED_DISPLAY, EXTENSION_BOARD, "OLED display", Arrays.asList(2,3,4))
                .pinCheck(17,1,2,3,4)
                .placement(18,"battery",BATTERY_PACK,EXTENSION_BOARD,"battery pack",Arrays.asList(3,4))
                .check("The OLED is straight and all four pins are seated inside the socket.")
                .check("The battery is secured clear of moving parts and the power plug is fully inserted.")
                .dependsOn(OLED_PROTECTOR_FIT).build(phases));
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

        PhaseBuilder cameraPart(String componentId,int quantity,String note){parts.add(new Part(componentId,quantity,PartRole.NEW_PART,InventoryEvidence.CAMERA_IDENTITY,Collections.emptyList(),note));return this;}

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
