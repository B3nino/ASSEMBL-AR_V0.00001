package dev.chyken.assembl_ar.workflow;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;

import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

/**
 * Short visual instructions paired with the full authored action for speech and accessibility.
 * A demonstration never creates recognition evidence or marks an action complete.
 */
public final class GuidedDemonstrationCatalog {
    public enum Action { PLACE, INSERT, TIGHTEN, WIRE, CHECK, PREPARE }
    public enum Relationship { NONE, BRACKET_IN_BOARD_SLOT, MOTOR_BETWEEN_BRACKETS }

    private static final List<VisionPoint> UNIT_BOARD = Collections.unmodifiableList(Arrays.asList(
            new VisionPoint(0, 0), new VisionPoint(1, 0),
            new VisionPoint(1, 1), new VisionPoint(0, 1)));
    private static final Map<Integer, Entry> ENTRIES = entries();

    private GuidedDemonstrationCatalog() { }

    public static Demo create(AssemblyStep section, GuidedStep card,
                              @Nullable GuidedCheckpoint checkpoint) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(card, "card");
        AssemblyInstruction instruction = card.getPrimaryInstruction();
        Entry entry = PROJECT_ID.equals(section.getProjectId())
                ? ENTRIES.get(key(section.getStepNumber(), instruction.getOrder())) : null;
        // Future custom instructions keep their complete source text until they have authored
        // short copy. Never truncate a pin number, warning, or physical operation mid-sentence.
        if (entry == null) {
            entry = new Entry(instruction.getType() == InstructionType.CHECK ? Action.CHECK : Action.PREPARE,
                    checkpoint == null ? "" : value(checkpoint.getComponentId()),
                    "Follow this action", instruction.getText());
        }
        String partId = checkpoint != null && checkpoint.getComponentId() != null
                ? checkpoint.getComponentId() : entry.partId;

        Set<String> warnings = new LinkedHashSet<>();
        for (AssemblyInstruction context : card.getContext()) {
            if (context.getType() == InstructionType.WARNING) warnings.add(context.getText());
        }
        String warningText = String.join(" ", warnings);
        StringBuilder spoken = new StringBuilder(instruction.getText());
        // Keep the physical direction and exact wire mapping in the authored sentence. The
        // short caption is visual copy, not a replacement for this source instruction.
        for (String warning : warnings) {
            if (!instruction.getText().contains(warning)) spoken.append(" ").append(warning);
        }

        ChassisRelativePlacementGate gate = checkpoint == null ? null : checkpoint.getPlacementGate();
        String anchorId = checkpoint == null ? "" : value(checkpoint.getAnchorComponentId());
        if (gate == null && checkpoint == null && entry.action == Action.CHECK
                && PROJECT_ID.equals(section.getProjectId())
                && hasMatchingFinalPlacementIllustration(section.getStepNumber())) {
            gate = ChassisRelativePlacementGate.forStep(section);
            anchorId = section.getStepNumber() == 14 || section.getStepNumber() >= 17
                    ? EXTENSION_BOARD : MAIN_BOARD;
        }
        List<ChassisRelativePlacementGate.TargetProjection> targets = gate == null
                ? Collections.emptyList() : gate.projectTargets(UNIT_BOARD);
        String anchorLabel = targets.isEmpty() ? "" : EXTENSION_BOARD.equals(anchorId)
                ? "Extension board" : "Main board";
        Relationship relationship = Relationship.NONE;
        List<ChassisRelativePlacementGate.TargetProjection> contextTargets = new ArrayList<>();
        if (PROJECT_ID.equals(section.getProjectId()) && section.getStepNumber() == 2 && !targets.isEmpty()) {
            if (MOTOR_BRACKET.equals(partId)) relationship = Relationship.BRACKET_IN_BOARD_SLOT;
            else if (GEAR_MOTOR.equals(partId)) relationship = Relationship.MOTOR_BETWEEN_BRACKETS;
            if (relationship != Relationship.NONE) {
                for (int slot = 1; slot <= 4; slot++) {
                    contextTargets.addAll(ChassisRelativePlacementGate.forMotorBracketSlot(slot)
                            .projectTargets(UNIT_BOARD));
                }
            }
        }
        List<ChassisRelativePlacementGate.TargetProjection> animatedTargets = new ArrayList<>();
        if (entry.action != Action.CHECK) {
            animatedTargets.addAll(targets);
            // Both installed pieces remain validation targets, but only the new piece moves.
            if ((section.getStepNumber() == 2 && instruction.getOrder() == 17)
                    || (section.getStepNumber() == 10 && instruction.getOrder() == 12)
                    || (section.getStepNumber() == 13 && instruction.getOrder() >= 3)) {
                if (animatedTargets.size() > 1) animatedTargets.remove(0);
            }
        }
        return new Demo(card.getStableId(), entry.title, entry.caption, spoken.toString(),
                warningText, entry.action, partId, componentLabel(partId), anchorId,
                anchorLabel, targets.isEmpty() ? "Manual illustration" : "Board-relative overview",
                instruction.getReferenceImagePath(), card.getReferenceImagePaths(), targets,
                animatedTargets, relationship, contextTargets,
                connectionsFor(section, instruction.getOrder()));
    }

    /** Targets use the same unit-board coordinates and corner order as placement validation. */
    public static final class Demo {
        private final String stableId, shortTitle, caption, spokenInstruction, warningText;
        private final Action action;
        private final String partId, partLabel, anchorComponentId, anchorLabel, viewLabel;
        private final String referenceImagePath;
        private final List<String> referenceImagePaths;
        private final List<ChassisRelativePlacementGate.TargetProjection> targets;
        private final List<ChassisRelativePlacementGate.TargetProjection> animatedTargets;
        private final Relationship relationship;
        private final List<ChassisRelativePlacementGate.TargetProjection> contextTargets;
        private final List<PinConnection> pinConnections;

        private Demo(String stableId, String shortTitle, String caption, String spokenInstruction,
                     String warningText, Action action, String partId, String partLabel,
                     String anchorComponentId, String anchorLabel, String viewLabel,
                     String referenceImagePath, List<String> referenceImagePaths,
                     List<ChassisRelativePlacementGate.TargetProjection> targets,
                     List<ChassisRelativePlacementGate.TargetProjection> animatedTargets,
                     Relationship relationship,
                     List<ChassisRelativePlacementGate.TargetProjection> contextTargets,
                     List<PinConnection> pinConnections) {
            this.stableId = stableId;
            this.shortTitle = shortTitle;
            this.caption = caption;
            this.spokenInstruction = spokenInstruction;
            this.warningText = warningText;
            this.action = action;
            this.partId = partId;
            this.partLabel = partLabel;
            this.anchorComponentId = anchorComponentId;
            this.anchorLabel = anchorLabel;
            this.viewLabel = viewLabel;
            this.referenceImagePath = referenceImagePath;
            this.referenceImagePaths = Collections.unmodifiableList(new ArrayList<>(referenceImagePaths));
            this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
            this.animatedTargets = Collections.unmodifiableList(new ArrayList<>(animatedTargets));
            this.relationship = relationship;
            this.contextTargets = Collections.unmodifiableList(new ArrayList<>(contextTargets));
            this.pinConnections = Collections.unmodifiableList(new ArrayList<>(pinConnections));
        }

        public String getStableId() { return stableId; }
        public String getShortTitle() { return shortTitle; }
        public String getCaption() { return caption; }
        public String getSpokenInstruction() { return spokenInstruction; }
        public String getWarningText() { return warningText; }
        public Action getAction() { return action; }
        public String getPartId() { return partId; }
        public String getPartLabel() { return partLabel; }
        public String getAnchorComponentId() { return anchorComponentId; }
        public String getAnchorLabel() { return anchorLabel; }
        public String getViewLabel() { return viewLabel; }
        public String getReferenceImagePath() { return referenceImagePath; }
        public List<String> getReferenceImagePaths() { return referenceImagePaths; }
        public List<ChassisRelativePlacementGate.TargetProjection> getTargets() { return targets; }
        public List<ChassisRelativePlacementGate.TargetProjection> getAnimatedTargets() { return animatedTargets; }
        public Relationship getRelationship() { return relationship; }
        /** Context is instructional support geometry; it never adds placement evidence. */
        public List<ChassisRelativePlacementGate.TargetProjection> getContextTargets() { return contextTargets; }
        public String getAttachmentCaption() {
            if (relationship == Relationship.BRACKET_IN_BOARD_SLOT) return "Bracket tab → board slot";
            if (relationship == Relationship.MOTOR_BETWEEN_BRACKETS) return "Motor → between bracket pair";
            return partLabel;
        }
        public List<PinConnection> getPinConnections() { return pinConnections; }
    }

    private static List<PinConnection> connectionsFor(AssemblyStep section, int order) {
        if (!PROJECT_ID.equals(section.getProjectId())) return Collections.emptyList();
        int[] required = new int[0];
        switch (section.getStepNumber()) {
            case 5:
                if (order == 3 || order == 4) required = new int[]{1};
                else if (order == 6 || order == 7) required = new int[]{2};
                else if (order == 8) required = new int[]{1, 2};
                break;
            case 7:
                if (order == 2) required = new int[]{1};
                else if (order == 3) required = new int[]{2};
                else if (order == 5) required = new int[]{1, 2};
                break;
            case 8:
                if (order >= 3 && order <= 6) required = new int[]{order - 2};
                else if (order == 7) required = new int[]{1, 2, 3, 4};
                break;
            case 11:
                if (order >= 5 && order <= 8) required = new int[]{order - 4};
                else if (order >= 9 && order <= 14) required = new int[]{order - 2};
                // Embedded distance-sensor supply paths (5,6) are not extra jumper instructions.
                else if (order == 15) required = new int[]{1, 2, 3, 4, 7, 8, 9, 10, 11, 12};
                break;
            case 17:
                if (order == 3 || order == 4) required = new int[]{1, 2, 3, 4};
                break;
            default:
                // Ribbon actions concern a keyed connector, not nine separately routed wires.
                // Their source images preserve the red stripe and physical connector direction.
                break;
        }
        List<PinConnection> result = new ArrayList<>();
        for (int desired : required) {
            for (PinConnection connection : section.getPinConnections()) {
                if (connection.getOrder() == desired) result.add(connection);
            }
        }
        return result;
    }

    private static boolean hasMatchingFinalPlacementIllustration(int section) {
        // The section 6 final teaches chassis directions, while section 11 checks the whole
        // underside. A microcontroller/sensor rectangle would illustrate a different action.
        return section == 2 || section == 9 || section == 10 || section == 13
                || section == 16 || section == 17 || section == 18;
    }

    private static String componentLabel(String id) {
        for (Component component : NeobotSeedData.getComponents()) {
            if (component.getId().equals(id)) return component.getName();
        }
        return "";
    }

    private static String value(@Nullable String value) { return value == null ? "" : value; }
    private static int key(int section, int order) { return section * 100 + order; }

    private static final class Entry {
        final Action action;
        final String partId, title, caption;
        Entry(Action action, String partId, String title, String caption) {
            this.action = action;
            this.partId = partId;
            this.title = title;
            this.caption = caption;
        }
    }

    private static void add(Map<Integer, Entry> entries, int section, int order, Action action,
                            String partId, String title, String caption) {
        if (entries.put(key(section, order), new Entry(action, partId, title, caption)) != null) {
            throw new IllegalStateException("Duplicate demonstration " + section + ":" + order);
        }
    }

    private static Map<Integer, Entry> entries() {
        Map<Integer, Entry> e = new LinkedHashMap<>();
        add(e, 1, 1, Action.PREPARE, MAIN_BOARD, "Set down the main board", "Component side up.");
        add(e, 2, 1, Action.INSERT, MOTOR_BRACKET, "Insert bracket 1 · left outer", "Tab into the main-board slot; bracket stays upright.");
        add(e, 2, 2, Action.INSERT, MOTOR_BRACKET, "Insert bracket 2 · left inner", "Pair with bracket 1 to hold the left motor.");
        add(e, 2, 3, Action.INSERT, MOTOR_BRACKET, "Insert bracket 3 · right inner", "Tab into the mirrored main-board slot.");
        add(e, 2, 4, Action.INSERT, MOTOR_BRACKET, "Insert bracket 4 · right outer", "Pair with bracket 3 to hold the right motor.");
        add(e, 2, 5, Action.PLACE, GEAR_MOTOR, "Place the left motor", "Between brackets 1 + 2; wires toward board center.");
        add(e, 2, 6, Action.PLACE, GEAR_MOTOR, "Turn the first motor inward", "Terminals and wires face the board center.");
        add(e, 2, 7, Action.PREPARE, GEAR_MOTOR, "Hold the first motor flat", "Keep downward thumb pressure.");
        add(e, 2, 8, Action.INSERT, SCREW_30MM, "Insert the lower screw", "Hold the motor flat; use a 30 mm screw.");
        add(e, 2, 9, Action.TIGHTEN, NUT, "Tighten the lower nut", "Fit it on the opposite side of the screw.");
        add(e, 2, 10, Action.INSERT, SCREW_30MM, "Insert the upper screw", "Place the second 30 mm screw above the first.");
        add(e, 2, 11, Action.TIGHTEN, NUT, "Tighten the upper nut", "Fit it on the opposite side of the screw.");
        add(e, 2, 12, Action.CHECK, GEAR_MOTOR, "Check the first motor", "It must lie flat and feel rigid.");
        add(e, 2, 13, Action.TIGHTEN, SCREW_30MM, "Finish tightening motor 1", "Adjust if needed; add one turn to both screws.");
        add(e, 2, 14, Action.PREPARE, THREADLOCKER, "Lock the first motor's nuts", "Apply threadlocker to both nuts.");
        add(e, 2, 17, Action.PLACE, GEAR_MOTOR, "Place the right motor", "Between brackets 3 + 4; wires toward board center.");
        add(e, 2, 18, Action.PLACE, GEAR_MOTOR, "Turn the second motor inward", "Terminals and wires face the board center.");
        add(e, 2, 19, Action.PREPARE, GEAR_MOTOR, "Hold the second motor flat", "Keep downward thumb pressure.");
        add(e, 2, 20, Action.INSERT, SCREW_30MM, "Insert motor 2's lower screw", "Hold the motor flat; use a 30 mm screw.");
        add(e, 2, 21, Action.TIGHTEN, NUT, "Tighten motor 2's lower nut", "Fit it on the opposite side of the screw.");
        add(e, 2, 22, Action.INSERT, SCREW_30MM, "Insert motor 2's upper screw", "Place the second 30 mm screw above the first.");
        add(e, 2, 23, Action.TIGHTEN, NUT, "Tighten motor 2's upper nut", "Fit it on the opposite side of the screw.");
        add(e, 2, 24, Action.CHECK, GEAR_MOTOR, "Check the second motor", "It must lie flat and feel rigid.");
        add(e, 2, 25, Action.TIGHTEN, SCREW_30MM, "Finish tightening motor 2", "Adjust if needed; add one turn to both screws.");
        add(e, 2, 26, Action.PREPARE, THREADLOCKER, "Lock the second motor's nuts", "Apply threadlocker to both nuts.");
        add(e, 2, 27, Action.CHECK, GEAR_MOTOR, "Check both motors", "Flat, rigid and mirrored across the board.");

        add(e, 3, 1, Action.PLACE, MOTOR_DRIVER, "Align the motor driver", "Match the driver to both guide holes.");
        add(e, 3, 2, Action.INSERT, SCREW_SHORT, "Insert both short screws", "Pass through the driver and main-board holes.");
        add(e, 3, 3, Action.TIGHTEN, NUT, "Tighten both driver nuts", "Fit the nuts on top of the motor driver.");
        add(e, 3, 4, Action.PREPARE, THREADLOCKER, "Lock the driver nuts", "Apply threadlocker to both nuts.");
        add(e, 4, 1, Action.PREPARE, MOTOR_DRIVER, "Open the motor terminals", "Loosen all four output screws.");

        add(e, 5, 3, Action.WIRE, GEAR_MOTOR, "Wire the first motor", "Use its illustrated red-and-black terminal pair.");
        add(e, 5, 4, Action.TIGHTEN, MOTOR_DRIVER, "Clamp the first motor's leads", "Tighten both terminal screws.");
        add(e, 5, 6, Action.WIRE, GEAR_MOTOR, "Wire the second motor", "Reverse the illustrated order for the flipped motor.");
        add(e, 5, 7, Action.TIGHTEN, MOTOR_DRIVER, "Clamp the second motor's leads", "Tighten both terminal screws.");
        add(e, 5, 8, Action.CHECK, GEAR_MOTOR, "Tug-check all four leads", "Each wire must remain firmly clamped.");

        add(e, 6, 1, Action.CHECK, MICROCONTROLLER, "Orient the microcontroller", "USB faces the rear power-jack end.");
        add(e, 6, 2, Action.PLACE, MICROCONTROLLER, "Align both pin rows", "Match every pin to its main-board socket.");
        add(e, 6, 3, Action.INSERT, MICROCONTROLLER, "Seat the microcontroller", "Press straight down without bending pins.");
        add(e, 6, 4, Action.CHECK, MICROCONTROLLER, "Check every pin", "Fully inserted, with neither row offset.");
        add(e, 6, 5, Action.CHECK, MAIN_BOARD, "Find the chassis front", "FRONT is the PERSONAL ROBOT KIT edge.");

        add(e, 7, 2, Action.WIRE, JUMPER_WIRE, "Connect VCC to VIN", "Motor-driver VCC → microcontroller VIN.");
        add(e, 7, 3, Action.WIRE, JUMPER_WIRE, "Connect ground to ground", "Motor-driver GND → microcontroller GND.");
        add(e, 7, 5, Action.CHECK, MOTOR_DRIVER, "Check the power wiring", "VCC → VIN and GND → GND before power.");
        add(e, 8, 3, Action.WIRE, JUMPER_WIRE, "Connect A-1A to pin 5", "Motor-driver A-1A → microcontroller D5.");
        add(e, 8, 4, Action.WIRE, JUMPER_WIRE, "Connect A-1B to pin 9", "Motor-driver A-1B → microcontroller D9.");
        add(e, 8, 5, Action.WIRE, JUMPER_WIRE, "Connect B-1A to pin 6", "Motor-driver B-1A → microcontroller D6.");
        add(e, 8, 6, Action.WIRE, JUMPER_WIRE, "Connect B-1B to pin 10", "Motor-driver B-1B → microcontroller D10.");
        add(e, 8, 7, Action.CHECK, MOTOR_DRIVER, "Check all four signals", "Match every driver lead to the wiring diagram.");

        add(e, 9, 1, Action.INSERT, SCREW_20MM, "Insert caster post 1", "Use a 20 mm screw in the first front hole.");
        add(e, 9, 2, Action.INSERT, SCREW_20MM, "Insert caster post 2", "Use a 20 mm screw in the mirrored hole.");
        add(e, 9, 3, Action.INSERT, NUT, "Fit the first lower nut", "Lock the first screw from underneath.");
        add(e, 9, 4, Action.INSERT, NUT, "Fit the second lower nut", "Lock the second screw at board level.");
        add(e, 9, 5, Action.TIGHTEN, NUT, "Tighten both post nuts", "Secure both screws to the board.");
        add(e, 9, 6, Action.PREPARE, THREADLOCKER, "Lock the post nuts", "Apply threadlocker to both board-level nuts.");
        add(e, 9, 7, Action.PLACE, CASTER_WHEEL, "Fit the caster bracket", "Lower it over both protruding posts.");
        add(e, 9, 8, Action.INSERT, NUT, "Fit caster nut 1", "Thread it onto the first post.");
        add(e, 9, 9, Action.INSERT, NUT, "Fit caster nut 2", "Thread it onto the mirrored post.");
        add(e, 9, 10, Action.TIGHTEN, NUT, "Tighten both caster nuts", "Secure the caster on its two posts.");
        add(e, 9, 11, Action.PREPARE, THREADLOCKER, "Lock the caster nuts", "Apply threadlocker to both retaining nuts.");
        add(e, 9, 12, Action.CHECK, CASTER_WHEEL, "Check the caster", "Ball down; a stable third support point.");

        add(e, 10, 2, Action.PREPARE, LINE_TRACKER, "Choose the first tracker hole", "Use the illustrated mounting position.");
        add(e, 10, 3, Action.PLACE, LINE_TRACKER, "Turn tracker 1 face down", "The sensing face points toward the floor.");
        add(e, 10, 4, Action.PLACE, PLASTIC_SPACER, "Add tracker 1's spacer", "Keep a spacer between the tracker and board.");
        add(e, 10, 6, Action.INSERT, LINE_TRACKER, "Insert tracker 1's screw", "Pass through the tracker, spacer and board.");
        add(e, 10, 7, Action.TIGHTEN, NUT, "Secure the first tracker", "Fit its nut and tighten the fastener.");
        add(e, 10, 8, Action.PREPARE, LINE_TRACKER, "Choose the opposite hole", "Use the matching position for tracker 2.");
        add(e, 10, 9, Action.PLACE, LINE_TRACKER, "Turn tracker 2 face down", "The sensing face points toward the floor.");
        add(e, 10, 10, Action.PLACE, PLASTIC_SPACER, "Add tracker 2's spacer", "Keep a spacer between the tracker and board.");
        add(e, 10, 12, Action.INSERT, LINE_TRACKER, "Insert tracker 2's screw", "Pass through the tracker, spacer and board.");
        add(e, 10, 13, Action.TIGHTEN, NUT, "Secure the second tracker", "Fit its nut and tighten the fastener.");
        add(e, 10, 14, Action.CHECK, LINE_TRACKER, "Check both line trackers", "Secure, symmetric and facing the floor.");

        add(e, 11, 2, Action.PLACE, DISTANCE_SENSOR, "Face the distance sensor forward", "Both circular transducers face the front.");
        add(e, 11, 3, Action.PLACE, DISTANCE_SENSOR, "Align the four sensor pins", "Match them to the front header.");
        add(e, 11, 4, Action.INSERT, DISTANCE_SENSOR, "Seat the distance sensor", "Press evenly into the header.");
        add(e, 11, 5, Action.WIRE, JUMPER_WIRE, "Connect the LED signal", "LED signal → A1.");
        add(e, 11, 6, Action.WIRE, JUMPER_WIRE, "Connect the speaker signal", "Speaker signal → D3.");
        add(e, 11, 7, Action.WIRE, JUMPER_WIRE, "Connect sensor Trigger", "Distance-sensor Trigger → D2.");
        add(e, 11, 8, Action.WIRE, JUMPER_WIRE, "Connect sensor Echo", "Distance-sensor Echo → D4.");
        add(e, 11, 9, Action.WIRE, JUMPER_WIRE, "Power the left tracker", "Left V+ → any available 5V pin.");
        add(e, 11, 10, Action.WIRE, JUMPER_WIRE, "Ground the left tracker", "Left GND → any available GND pin.");
        add(e, 11, 11, Action.WIRE, JUMPER_WIRE, "Connect the left signal", "Left tracker S → A2.");
        add(e, 11, 12, Action.WIRE, JUMPER_WIRE, "Power the right tracker", "Right V+ → any available 5V pin.");
        add(e, 11, 13, Action.WIRE, JUMPER_WIRE, "Ground the right tracker", "Right GND → any available GND pin.");
        add(e, 11, 14, Action.WIRE, JUMPER_WIRE, "Connect the right signal", "Right tracker S → A3.");
        add(e, 11, 15, Action.CHECK, DISTANCE_SENSOR, "Check the signal wiring", "Compare sensor, LED and speaker connections.");
        add(e, 11, 16, Action.CHECK, MAIN_BOARD, "Check the underside", "Trackers face down; the caster stays clear.");

        add(e, 12, 2, Action.PREPARE, MAIN_BOARD, "Find the center hole", "Locate the main board's standoff hole.");
        add(e, 12, 3, Action.INSERT, STANDOFF, "Insert the center standoff", "Pass through the hole from above.");
        add(e, 12, 4, Action.TIGHTEN, NUT, "Secure the standoff below", "Fit and tighten the nut underneath.");
        add(e, 12, 5, Action.PREPARE, THREADLOCKER, "Lock the standoff nut", "Apply threadlocker to the retaining nut.");
        add(e, 12, 6, Action.PREPARE, STANDOFF, "Keep the upper screw", "Leave it on the standoff for the upper board.");
        add(e, 13, 1, Action.PLACE, WHEEL, "Align the first wheel", "Match its hub to the motor shaft.");
        add(e, 13, 2, Action.INSERT, WHEEL, "Seat the first wheel", "Press it fully onto the shaft.");
        add(e, 13, 3, Action.PLACE, WHEEL, "Align the second wheel", "Match its hub to the opposite shaft.");
        add(e, 13, 4, Action.INSERT, WHEEL, "Seat the second wheel", "Press it fully onto the opposite shaft.");
        add(e, 13, 5, Action.CHECK, WHEEL, "Check both wheels", "Equally seated, free to turn and clear of the board.");

        add(e, 14, 2, Action.PLACE, OLED_PROTECTOR, "Position the OLED protector", "Place it over the extension-board footprint.");
        add(e, 14, 3, Action.PLACE, OLED_PROTECTOR, "Align the protector holes", "Match both attachment points.");
        add(e, 14, 4, Action.INSERT, MOUNTING_SCREW, "Insert protector fastener 1", "Use the first illustrated attachment point.");
        add(e, 14, 5, Action.INSERT, MOUNTING_SCREW, "Insert protector fastener 2", "Use the second illustrated attachment point.");
        add(e, 14, 6, Action.INSERT, NUT, "Fit protector nut 1", "Place the nut on the reverse side.");
        add(e, 14, 7, Action.INSERT, NUT, "Fit protector nut 2", "Place the second nut on the reverse side.");
        add(e, 14, 8, Action.TIGHTEN, MOUNTING_SCREW, "Tighten the protector", "Secure both fasteners.");
        add(e, 14, 9, Action.PREPARE, THREADLOCKER, "Lock the protector nuts", "Apply threadlocker to both nuts.");

        add(e, 15, 2, Action.PLACE, DATA_CABLE, "Orient the first connector", "Follow the main-board header illustration.");
        add(e, 15, 3, Action.INSERT, DATA_CABLE, "Seat the main-board connector", "Press fully and evenly into the header.");
        add(e, 15, 4, Action.WIRE, DATA_CABLE, "Route the ribbon cable", "Lead toward the extension board without twisting.");
        add(e, 15, 5, Action.PLACE, DATA_CABLE, "Orient the second connector", "Keep the red stripe as shown on page 25.");
        add(e, 15, 6, Action.INSERT, DATA_CABLE, "Seat the upper connector", "Press fully and evenly into the upper header.");
        add(e, 15, 7, Action.CHECK, DATA_CABLE, "Check both cable ends", "Fully seated connectors; no twists.");

        add(e, 16, 2, Action.PLACE, EXTENSION_BOARD, "Orient the extension board", "Match the direction shown on page 26.");
        add(e, 16, 3, Action.WIRE, DATA_CABLE, "Route the wiring inside", "Use the open space between the boards.");
        add(e, 16, 4, Action.PLACE, EXTENSION_BOARD, "Align the center hole", "Line up the upper board with the standoff.");
        add(e, 16, 5, Action.CHECK, EXTENSION_BOARD, "Check the wire clearance", "Keep every wire clear before lowering.");
        add(e, 16, 6, Action.PLACE, EXTENSION_BOARD, "Lower the extension board", "Rest it on all four bracket supports.");
        add(e, 16, 8, Action.INSERT, MOUNTING_SCREW, "Insert the retained screw", "Pass through the upper board into the standoff.");
        add(e, 16, 9, Action.TIGHTEN, MOUNTING_SCREW, "Tighten the center screw", "Secure the extension board.");
        add(e, 16, 10, Action.CHECK, EXTENSION_BOARD, "Check all four supports", "No pinched wires and no board flex.");

        add(e, 17, 2, Action.PLACE, OLED_DISPLAY, "Orient the OLED", "Face outward inside the protector.");
        add(e, 17, 3, Action.PLACE, OLED_DISPLAY, "Align the four OLED pins", "Match the I2C socket before pressing.");
        add(e, 17, 4, Action.INSERT, OLED_DISPLAY, "Seat the OLED", "Press straight into the socket.");
        add(e, 17, 5, Action.CHECK, OLED_DISPLAY, "Check the protected display", "It must sit inside the protector without binding.");
        add(e, 18, 1, Action.PREPARE, BATTERY_PACK, "Choose the battery mount", "Use double-sided tape or a prepared holder.");
        add(e, 18, 2, Action.PREPARE, BATTERY_PACK, "Prepare the battery mount", "Apply tape or seat the battery in its holder.");
        add(e, 18, 3, Action.PLACE, BATTERY_PACK, "Position the battery", "Use the illustrated extension-board area.");
        add(e, 18, 4, Action.PREPARE, BATTERY_PACK, "Secure the battery", "Keep it from moving into nearby components.");
        add(e, 18, 6, Action.CHECK, BATTERY_PACK, "Check voltage and clearance", "7–12 V; keep wheels, OLED, buttons and cable clear.");
        return Collections.unmodifiableMap(e);
    }
}
