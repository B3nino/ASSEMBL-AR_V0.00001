package dev.chyken.assembl_ar.data;

import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyProject;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.ComponentCategory;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.LearningContentType;
import dev.chyken.assembl_ar.model.LearningModule;
import dev.chyken.assembl_ar.model.LearningStep;
import dev.chyken.assembl_ar.model.ManualReference;
import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.model.MotorWireColorConfig;
import dev.chyken.assembl_ar.model.RequiredComponent;
import dev.chyken.assembl_ar.model.SeedIntegrityReport;
import dev.chyken.assembl_ar.model.ValidationType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical, JVM-testable seed for the manuscript's single delimited prototype kit.
 * No Android APIs are used here so seed integrity can be tested as a plain unit test.
 */
public final class NeobotSeedData {
    /** Runtime Phase 4 routes described by physical openings in the mounted-view artwork. */
    public static List<PinConnection> getPhase4PinConnections(MotorWireColorConfig colors){
        List<PinConnection> pins=new ArrayList<>();
        for(MotorWireColorConfig.Terminal terminal:MotorWireColorConfig.Terminal.values()){
            pins.add(new PinConnection(terminal.ordinal()+1,GEAR_MOTOR,terminal.label,MOTOR_DRIVER,
                    terminal.output,colors.swatch(terminal).hex(),"Motor output",
                    "View the driver with green screw blocks toward you and the six small header pins away. Insert the wire into the pictured opening and tighten the matching screw. Motor tabs are unmarked; the picture does not establish positive/negative polarity."));
        }
        return Collections.unmodifiableList(pins);
    }
    /** Six user-colored routes. Driver I/1 spelling aliases share one physical header anchor. */
    public static List<PinConnection> getPhase6PinConnections(dev.chyken.assembl_ar.model.DriverWireColorConfig colors){
        List<PinConnection> pins=new ArrayList<>();
        for(dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire wire:dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire.values())
            pins.add(new PinConnection(wire.ordinal()+1,MOTOR_DRIVER,wire.driverPin,MICROCONTROLLER,wire.nanoPin,colors.swatch(wire).hex(),wire.power()?"Power":"PWM control","Match the highlighted physical pins. A-1A/A-1B and B-1A/B-1B are printed A-IA/A-IB and B-IA/B-IB in the supplied driver artwork."));
        return Collections.unmodifiableList(pins);
    }
    public static List<PinConnection> getPhase8PinConnections(dev.chyken.assembl_ar.model.ComponentWiringColorConfig colors){
        List<PinConnection> result=new ArrayList<>();for(dev.chyken.assembl_ar.model.ComponentWiringColorConfig.Wire w:dev.chyken.assembl_ar.model.ComponentWiringColorConfig.Wire.values()){
            String source=w.section==1?DISTANCE_SENSOR:w==dev.chyken.assembl_ar.model.ComponentWiringColorConfig.Wire.LED?LED:w==dev.chyken.assembl_ar.model.ComponentWiringColorConfig.Wire.SPEAKER?SPEAKER:LINE_TRACKER;
            String target=w.target.startsWith("5V")||w.target.startsWith("GND rail")?MAIN_BOARD:MICROCONTROLLER;
            result.add(new PinConnection(w.ordinal()+1,source,w.source,target,w.target,colors.swatch(w).hex(),"Sensor wiring","Physical left/right are defined from above. Sensor VCC/GND already connect through PCB."));
        }return Collections.unmodifiableList(result);
    }
    public static final String PROJECT_ID = "neobot_personal_robot_kit_2026";
    public static final String ASSEMBLY_DOCUMENT_ID = "neobot_assembly_wiring_2026";
    public static final String LEARNING_DOCUMENT_ID = "neobot_personal_robot_manual_2026";

    public static final String MAIN_BOARD = "main_board";
    public static final String MOTOR_BRACKET = "motor_bracket";
    public static final String GEAR_MOTOR = "gear_motor_6v";
    public static final String MOTOR_DRIVER = "dual_motor_driver";
    public static final String MICROCONTROLLER = "arduino_nano_microcontroller";
    public static final String CASTER_WHEEL = "caster_wheel";
    public static final String LINE_TRACKER = "line_tracker_module";
    public static final String DISTANCE_SENSOR = "ultrasonic_distance_sensor";
    public static final String LED = "onboard_led";
    public static final String SPEAKER = "onboard_speaker";
    public static final String STANDOFF = "center_standoff";
    public static final String WHEEL = "drive_wheel";
    public static final String OLED_PROTECTOR = "oled_protector";
    public static final String DATA_CABLE = "extension_data_cable";
    public static final String EXTENSION_BOARD = "extension_circuit_board";
    public static final String OLED_DISPLAY = "oled_096_i2c";
    public static final String BATTERY_PACK = "battery_pack_7v_12v";
    public static final String JUMPER_WIRE = "jumper_wire";
    public static final String MOMENTARY_BUTTON = "momentary_button";
    public static final String IR_RECEIVER = "ir_receiver_38khz";
    public static final String IR_TRANSMITTER = "ir_remote_transmitter";
    public static final String CR2025_BATTERY = "cr2025_battery";
    public static final String SCREW_30MM = "screw_30mm";
    public static final String SCREW_SHORT = "short_screw";
    public static final String SCREW_20MM = "screw_20mm";
    public static final String MOUNTING_SCREW = "mounting_screw";
    public static final String NUT = "hex_nut";
    public static final String PLASTIC_SPACER = "plastic_spacer";
    public static final String THREADLOCKER = "threadlocker";
    public static final String NAIL_POLISH = "nail_polish_optional";
    public static final String DOUBLE_SIDED_TAPE = "double_sided_tape";
    public static final String BATTERY_HOLDER = "battery_holder_3d_printed_optional";
    public static final String PHILLIPS_SCREWDRIVER = "phillips_screwdriver";

    private static final List<Component> COMPONENTS = buildComponents();
    private static final List<AssemblyStep> ASSEMBLY_STEPS = buildAssemblySteps();
    private static final AssemblyProject PROJECT = new AssemblyProject(
            PROJECT_ID,
            "NEOBOT Personal Robot Kit Assembly",
            "NEOBOT Personal Robot Kit",
            "Build the NEOBOT robot with component identification, demonstrations and physical checks. Follow the current phase guide "
                    + "across assembly and wiring before exploring the "
                    + "learning activities.",
            assemblyRef(1, 30),
            ASSEMBLY_STEPS);
    private static final List<LearningModule> LEARNING_MODULES = buildLearningModules();

    static {
        SeedIntegrityReport report = validate();
        if (!report.isValid()) {
            throw new IllegalStateException("Invalid NEOBOT seed: " + report.getErrors());
        }
    }

    private NeobotSeedData() {
    }

    public static AssemblyProject getProject() {
        return PROJECT;
    }

    public static List<Component> getComponents() {
        return COMPONENTS;
    }

    public static List<AssemblyStep> getAssemblySteps() {
        return ASSEMBLY_STEPS;
    }

    public static List<LearningModule> getLearningModules() {
        return LEARNING_MODULES;
    }

    private static List<Component> buildComponents() {
        List<Component> result = new ArrayList<>();
        result.add(component(MAIN_BOARD, "Personal Robot Kit main board", ComponentCategory.STRUCTURAL, 1,
                "V2.0 two-level robot chassis PCB with component footprints, headers, motor positions, "
                        + "power input, integrated LED and speaker connections.",
                "Acts as the chassis, electrical backplane, and spatial coordinate-grid anchor.",
                "Base for every assembly step; keep it on a clean, flat, non-metal surface.",
                "High-texture board is the primary homography and solvePnP anchor; front is the edge marked "
                        + "PERSONAL ROBOT KIT opposite the power jack/USB end.", 2, 30));
        result.add(component(MOTOR_BRACKET, "Gear-motor bracket", ComponentCategory.STRUCTURAL, 4,
                "Black bracket that passes through the main-board guide slots and clamps a gear motor.",
                "Holds each motor flat and stabilizes the extension board after it is fitted.",
                "Two brackets per motor in step 2; bracket tops also support the upper PCB in step 16.",
                "Use silhouette/edge detection because the dark, low-texture and possibly 3D-printed surface "
                        + "can provide few ORB features.", 3, 8));
        result.add(component(GEAR_MOTOR, "6 V DC gear motor", ComponentCategory.ACTUATOR, 2,
                "Yellow reduction gear motor with attached red and black terminal wires and white output shaft.",
                "Drives the left or right wheel.",
                "Mounted with its terminals and wires facing inward; the right motor is flipped, so its output "
                        + "wire order is reversed at the motor driver.",
                "Register left/right/flipped views and use the yellow gearbox, metal can, shaft, and wire exit as "
                        + "orientation features.", 4, 15));
        result.add(component(MOTOR_DRIVER, "Dual DC motor driver module", ComponentCategory.ELECTRONIC, 1,
                "Blue dual H-bridge board with four green motor screw terminals, VCC/GND power pins, "
                        + "and A-1A/A-1B/B-1A/B-1B signal pins.",
                "Controls motor direction and PWM current from the microcontroller.",
                "Align to the main-board guide holes, secure with two short screws, then wire power, outputs, "
                        + "and four PWM control signals.",
                "Terminal block and mounting-hole arrangement define its front/back orientation.", 8, 15));
        result.add(component(MICROCONTROLLER, "Arduino Nano-compatible microcontroller board",
                ComponentCategory.ELECTRONIC, 1,
                "Nano-form-factor board whose headers expose digital, PWM, analog, VIN, 5V and GND pins.",
                "Runs the robot sketches and supplies or receives all control signals.",
                "Fully seat both pin rows in the main-board socket with the USB connector toward the robot rear.",
                "Use the USB connector, reset switch and header pin rows to reject a reversed orientation.", 11, 20));
        result.add(component(CASTER_WHEEL, "Ball caster wheel", ComponentCategory.STRUCTURAL, 1,
                "Front-mounted white bracket with a metal ball and two mounting arms.",
                "Provides the third support point that balances the two-wheel robot.",
                "Fit over the two 20 mm screws under the front of the main board and lock with nuts/threadlocker.",
                "Metal highlights can wash out ORB; supplement with circular/ball and mounting-hole geometry.", 15, 17));
        result.add(component(LINE_TRACKER, "Infrared line tracker module", ComponentCategory.SENSOR, 2,
                "Three-pin downward-facing reflectance sensor module with V+, GND and S pins.",
                "Reports floor markings so the robot can determine its location.",
                "Mount one on each underside edge with a spacer; signals go to A2 (left) and A3 (right).",
                "Register component face, underside emitter/receiver pair and trimmer orientation.", 17, 20));
        result.add(component(DISTANCE_SENSOR, "Ultrasonic distance sensor", ComponentCategory.SENSOR, 1,
                "Front-facing ultrasonic module with VCC, Trigger, Echo and GND pins.",
                "Measures distance to obstacles.",
                "Install at the robot front; Trigger connects to D2 and Echo to D4 while VCC/GND are already "
                        + "connected through the PCB footprint.",
                "The two circular transducers are reliable Hough-circle fallback features.", 18, 20));
        result.add(component(LED, "On-board LED", ComponentCategory.ELECTRONIC, 1,
                "Red LED in the main-board LED footprint; its ground is already connected by the PCB.",
                "Provides visual output.", "Signal maps to A1; GND is embedded in the PCB.",
                "Recognize together with the main-board anchor; do not treat the LED alone as a robust ORB target.",
                2, 20));
        result.add(component(SPEAKER, "On-board speaker/buzzer", ComponentCategory.ELECTRONIC, 1,
                "Round black sounder in the main-board speaker footprint; ground is already connected by the PCB.",
                "Provides audible feedback and the volume demonstration used by the button activity.",
                "Signal maps to D3; GND is embedded in the PCB.",
                "Circular outline is suitable for shape fallback and its polarity marking helps orientation.", 2, 20));
        result.add(component(STANDOFF, "Center circuit-board standoff", ComponentCategory.STRUCTURAL, 1,
                "Single center spacer with lower nut and removable upper screw.",
                "Holds the two PCBs apart while leaving wiring quickly accessible.",
                "Secure in the center hole with an M3 nut; remove and save the top screw for the extension board.",
                "Metallic part should use contour/shape plus synthetic-lighting references.", 21, 26));
        result.add(component(WHEEL, "Drive wheel", ComponentCategory.STRUCTURAL, 2,
                "Black rubber tire with white hub sized for the gear-motor output shaft.",
                "Transfers motor rotation to the work surface.",
                "Press one wheel onto each motor shaft; the manual allows later modification.",
                "Circular hub and tread provide strong geometry and texture.", 1, 30));
        result.add(component(OLED_PROTECTOR, "OLED protector", ComponentCategory.STRUCTURAL, 1,
                "Protective frame mounted on the extension board around the OLED location.",
                "Helps safeguard the OLED from accidental bumps and falls.",
                "Install on the other PCB and use threadlocker before the upper PCB is mounted.",
                "Register top/side profiles; it may be dark and low texture.", 23, 28));
        result.add(component(DATA_CABLE, "Extension ribbon data cable", ComponentCategory.WIRING, 1,
                "Polarized ribbon cable between the main board and extension circuit board.",
                "Extends pins 7, 8, 11, 12, A0, A4, A5, 5V and GND to the extension.",
                "Connect one keyed end to each PCB before mounting the extension board.",
                "Use the red edge stripe and connector key for polarity/orientation validation.", 24, 30));
        result.add(component(EXTENSION_BOARD, "Extension circuit board", ComponentCategory.STRUCTURAL, 1,
                "Upper PCB carrying five buttons, OLED area, battery area and additional component footprints.",
                "Serves as the robot cover and user-interface expansion board.",
                "Connect the ribbon cable, position over the lower PCB and secure to the center standoff.",
                "Board silkscreen and hole pattern provide the secondary spatial anchor.", 1, 30));
        result.add(component(OLED_DISPLAY, "0.96-inch I2C OLED display", ComponentCategory.ELECTRONIC, 1,
                "SSD1306-compatible 128x64 OLED that operates at 3.3V to 5V and exposes GND, VCC, SCL and SDA.",
                "Displays text, numbers, graphics, sensor readings and user instructions.",
                "Carefully press into the protected extension-board OLED socket; A4/A5 are reserved for I2C.",
                "Register display face, four-pin header, and blue PCB; avoid depending on changing screen pixels.",
                6, 13));
        result.add(component(BATTERY_PACK, "7 V to 12 V battery pack", ComponentCategory.POWER, 1,
                "Robot supply pack; the guide photographs a 9 V pack and permits larger-capacity 7-12 V packs.",
                "Powers the assembled robot.",
                "Fix to the extension board with double-sided tape or a 3D-printed holder.",
                "Registration must tolerate different allowed pack shapes and focus on secure placement.", 29, 30));
        result.add(component(JUMPER_WIRE, "Jumper wire", ComponentCategory.WIRING, 16,
                "The completed manual wiring uses at least 16 individual female jumper leads: two power, "
                        + "four motor-control, and ten sensor/output leads. The manuals do not state the "
                        + "packaged set's total lead count.",
                "Carries power, ground, PWM and sensor signals between modules and microcontroller pins.",
                "Used in steps 7, 8 and 11; trace both endpoints rather than identifying by color alone.",
                "Spatial validation should track colored wire paths and endpoint glow rings.", 13, 20));
        result.add(component(MOMENTARY_BUTTON, "Momentary push button", ComponentCategory.ELECTRONIC, 5,
                "Normally open four-terminal button; each terminal pair is internally connected.",
                "Provides user input by connecting its assigned microcontroller pin to GND, producing LOW.",
                "Integrated into the extension board at pins 7, 8, 11, 12 and A0/D14.",
                "Recognize in the extension-board context; five identical instances are distinguished by position.",
                1, 5));
        result.add(component(IR_RECEIVER, "38 kHz infrared receiver module", ComponentCategory.SENSOR, 1,
                "Two-hole module with GND, VCC and S (signal) pins.",
                "Receives 38 kHz infrared pulse trains encoded as 32-bit values.",
                "Mount with screws; connect VCC to 5V, GND to GND and S to pin 7.",
                "Pin order and receiver-window direction are critical; wrong power wiring can permanently damage it.",
                14, 19));
        result.add(component(IR_TRANSMITTER, "Infrared remote-control transmitter", ComponentCategory.ELECTRONIC, 1,
                "Handheld IR remote whose direction keys send distinct 32-bit codes.",
                "Wirelessly commands forward, reverse, left and right movement.",
                "Fit a CR2025 cell and point the transmitter toward the receiver.",
                "Direction-pad layout is distinctive; live code remains the authoritative input.", 14, 25));
        result.add(component(CR2025_BATTERY, "CR2025 coin-cell battery", ComponentCategory.POWER, 1,
                "Coin cell required by the infrared transmitter.", "Powers the handheld transmitter.",
                "Install in the transmitter in the polarity/orientation shown on manual page 15.",
                "Metallic disc is not a useful stand-alone ORB target; use shape and transmitter context.", 15, 15));

        result.add(simpleComponent(SCREW_30MM, "30 mm screw", ComponentCategory.FASTENER, 4,
                "Long motor-bracket fastener.", "Clamps both gear motors.", 5, 8));
        result.add(simpleComponent(SCREW_SHORT, "Short screw", ComponentCategory.FASTENER, 2,
                "Short motor-driver fastener.", "Secures the motor driver with nuts on top.", 8, 8));
        result.add(simpleComponent(SCREW_20MM, "20 mm screw", ComponentCategory.FASTENER, 2,
                "Caster mounting screw.", "Forms the two front caster mounting posts.", 15, 16));
        result.add(simpleComponent(MOUNTING_SCREW, "Component mounting screw", ComponentCategory.FASTENER, 8,
                "General small screw used for sensor, protector, receiver or board fixtures where the guide does "
                        + "not state a length.", "Secures modules without inventing an unsupported screw length.", 14, 28));
        result.add(simpleComponent(NUT, "Hex nut", ComponentCategory.FASTENER, 16,
                "Matching metal nut for the supplied screws and standoff.", "Locks mechanical assemblies.", 5, 23));
        result.add(simpleComponent(PLASTIC_SPACER, "Plastic spacer", ComponentCategory.STRUCTURAL, 2,
                "Spacer placed between each line tracker and main board; nuts may be substituted.",
                "Sets a stable downward-facing sensor gap.", 17, 17));
        result.add(simpleComponent(THREADLOCKER, "Threadlocker", ComponentCategory.CONSUMABLE, 1,
                "Fastener-locking compound.", "Prevents screws and nuts loosening from robot vibration.", 7, 23));
        result.add(simpleComponent(NAIL_POLISH, "Nail polish (optional substitute)", ComponentCategory.OPTIONAL, 0,
                "Do-it-yourself substitute for threadlocker.", "Prevents motor fasteners loosening when threadlocker "
                        + "is unavailable.", 7, 7));
        result.add(simpleComponent(DOUBLE_SIDED_TAPE, "Double-sided tape", ComponentCategory.CONSUMABLE, 1,
                "Adhesive mounting tape.", "Attaches the battery pack to the extension board.", 29, 29));
        result.add(simpleComponent(BATTERY_HOLDER, "3D-printed battery holder (optional)", ComponentCategory.OPTIONAL, 0,
                "Optional reusable alternative to double-sided tape.", "Retains the battery pack.", 29, 29));
        result.add(simpleComponent(PHILLIPS_SCREWDRIVER, "Phillips screwdriver", ComponentCategory.TOOL, 1,
                "Driver used on the kit's Phillips-head screws and motor-driver terminal screws.",
                "Tightens mechanical fasteners and screw terminals.", 5, 26));
        return Collections.unmodifiableList(result);
    }

    private static Component component(
            String id,
            String name,
            ComponentCategory category,
            int quantity,
            String description,
            String purpose,
            String usage,
            String recognitionNotes,
            int pageStart,
            int pageEnd) {
        boolean learningManualComponent = id.equals(OLED_DISPLAY)
                || id.equals(MOMENTARY_BUTTON)
                || id.equals(IR_RECEIVER)
                || id.equals(IR_TRANSMITTER)
                || id.equals(CR2025_BATTERY);
        return new Component(id, name, category, quantity, description, purpose, usage, recognitionNotes,
                learningManualComponent ? learningRef(pageStart, pageEnd) : assemblyRef(pageStart, pageEnd),
                recognitionAssets(id));
    }

    private static Component simpleComponent(
            String id,
            String name,
            ComponentCategory category,
            int quantity,
            String description,
            String purpose,
            int pageStart,
            int pageEnd) {
        return new Component(id, name, category, quantity, description, purpose,
                "See the ordered step requirements and instructions.",
                "Small, shiny or low-texture part: combine synthetic ORB views with edge/circle fallback.",
                assemblyRef(pageStart, pageEnd), recognitionAssets(id));
    }

    /**
     * Component recognition uses cropped RGB images extracted directly from the source PDFs.
     * Manual page renders remain exclusively in {@link ManualReference} for the help UI. A blank
     * list is intentional when the manuals never show the physical item itself; an administrator
     * can register a measured multi-angle replacement without teaching ORB an unrelated scene.
     */
    private static List<String> recognitionAssets(String componentId) {
        switch (componentId) {
            case MAIN_BOARD:
                // Keep both PCB colour revisions under the same assembly-interchangeable
                // component id. Opposite-face references are mirrored by VisionReferenceFactory,
                // so they can identify the chassis without satisfying the step-1 top-face check.
                return assetPaths(
                        "references/assembly/image-001.png",
                        "references/assembly/image-034.png",
                        "references/variants/main-board-white-top.jpg",
                        "references/variants/main-board-white-underside.jpg");
            case MOTOR_BRACKET:
                return sourceAssets("assembly", 3);
            case GEAR_MOTOR:
                return sourceAssets("assembly", 5, 51);
            case MOTOR_DRIVER:
                // Keep one canonical installed orientation; image-017 rotates the loose module and
                // cannot share the same authored terminal/polarity axes.
                return sourceAssets("assembly", 13);
            case MICROCONTROLLER:
                return sourceAssets("assembly", 24);
            case CASTER_WHEEL:
                return sourceAssets("assembly", 36);
            case LINE_TRACKER:
                return sourceAssets("assembly", 45);
            case DISTANCE_SENSOR:
                return sourceAssets("assembly", 43, 67);
            case LED:
                return sourceAssets("learning", 27);
            case SPEAKER:
                return sourceAssets("assembly", 9);
            case STANDOFF:
                return sourceAssets("assembly", 47);
            case WHEEL:
                return sourceAssets("assembly", 49);
            case OLED_PROTECTOR:
                return sourceAssets("assembly", 61);
            case DATA_CABLE:
                // image-057 is the close registered connector view with a visible red edge stripe.
                return sourceAssets("assembly", 57);
            case EXTENSION_BOARD:
                return sourceAssets("assembly", 63);
            case OLED_DISPLAY:
                return sourceAssets("learning", 19, 39);
            case BATTERY_PACK:
                return sourceAssets("assembly", 65);
            case JUMPER_WIRE:
                return sourceAssets("assembly", 30, 32, 55);
            case MOMENTARY_BUTTON:
                return sourceAssets("learning", 2, 9);
            case IR_RECEIVER:
                return sourceAssets("learning", 42, 44);
            case IR_TRANSMITTER:
                return sourceAssets("learning", 62);
            case CR2025_BATTERY:
                return sourceAssets("learning", 48);
            case SCREW_30MM:
                return sourceAssets("assembly", 7);
            case SCREW_SHORT:
                return sourceAssets("assembly", 15);
            case SCREW_20MM:
                return sourceAssets("assembly", 40);
            case MOUNTING_SCREW:
                return sourceAssets("assembly", 53);
            case NUT:
                return sourceAssets("assembly", 11);
            case PLASTIC_SPACER:
                return sourceAssets("assembly", 38);
            case PHILLIPS_SCREWDRIVER:
                return sourceAssets("assembly", 8);
            case THREADLOCKER:
            case NAIL_POLISH:
            case DOUBLE_SIDED_TAPE:
            case BATTERY_HOLDER:
                return Collections.emptyList();
            default:
                throw new IllegalArgumentException("No recognition policy for component " + componentId);
        }
    }

    private static List<String> sourceAssets(String document, int... imageIndexes) {
        if (imageIndexes.length > 4) {
            throw new IllegalArgumentException("At most four bundled references are supported");
        }
        List<String> paths = new ArrayList<>();
        for (int imageIndex : imageIndexes) {
            paths.add("references/" + document + "/image-"
                    + String.format(Locale.US, "%03d", imageIndex) + ".png");
        }
        return Collections.unmodifiableList(paths);
    }

    private static List<String> assetPaths(String... paths) {
        if (paths.length > 4) {
            throw new IllegalArgumentException("At most four bundled references are supported");
        }
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(paths)));
    }

    private static List<AssemblyStep> buildAssemblySteps() {
        List<AssemblyStep> result = new ArrayList<>();

        result.add(step(1, "Prepare the main board",
                "Set up the main board as the clean spatial anchor for assembly.",
                ValidationType.MANUAL_CONFIRMATION,
                "Show the main board component-side up to begin assembly.", 2, 2,
                list(
                        ins(1, InstructionType.ACTION,
                                "Place the main board component-side up on the work surface.", 2,
                                "s02-00-main-board-gray.webp"),
                        ins(2, InstructionType.NOTE,
                                "Use a flat, clean, non-metal work surface while assembling.", 2,
                                "s02-00-main-board-gray.webp")),
                list(req(MAIN_BOARD, 1, "Use as the chassis and world-coordinate anchor.")),
                Collections.emptyList(),
                overlays(OverlayType.SPATIAL_HIGHLIGHT, OverlayType.ORIENTATION_MARKER)));

        result.add(step(2, "Install the gear motors",
                "Clamp both motors flat to the board with their terminals and wires facing inward.",
                ValidationType.WIGGLE_TEST,
                "Gently wiggle both motor assemblies. Confirm neither is flimsy and both lie flat before continuing.",
                3, 8,
                list(
                        ins(1, InstructionType.ACTION,
                                "Insert the first bracket half into one guide slot at the first motor position.", 3,
                                "motor-bracket-half-gray.webp"),
                        ins(2, InstructionType.ACTION,
                                "Insert the matching bracket half into the paired slot at the first motor position.", 3,
                                "motor-bracket-half-gray.webp"),
                        ins(3, InstructionType.ACTION,
                                "Insert the third bracket half into one guide slot at the opposite motor position.", 3,
                                "motor-bracket-half-gray.webp"),
                        ins(4, InstructionType.ACTION,
                                "Insert the fourth bracket half into the remaining paired slot.", 3,
                                "s02-01-brackets-mounted-gray.webp"),
                        ins(5, InstructionType.ACTION,
                                "Place the first gear motor between the first pair of brackets.", 4,
                                "gear-motor-gray.webp"),
                        ins(6, InstructionType.ACTION,
                                "Turn the first motor so its terminals and wires face inward, toward the center "
                                        + "of the main board.", 4,
                                "s02-02-first-motor-positioned-gray.webp"),
                        ins(7, InstructionType.ACTION,
                                "Hold the first motor flat against the main board with downward thumb pressure.", 5,
                                "s02-02-first-motor-positioned-gray.webp"),
                        ins(8, InstructionType.ACTION,
                                "While holding the first motor flat, insert the lower 30 mm screw through the "
                                        + "bracket and motor assembly.", 5,
                                "s02-03-first-motor-one-screw-gray.webp"),
                        ins(9, InstructionType.ACTION,
                                "Fit the nut on the opposite side of the lower screw and tighten it.", 5,
                                "s02-03-first-motor-one-screw-gray.webp"),
                        ins(10, InstructionType.ACTION,
                                "Insert the upper 30 mm screw above the first screw.", 6,
                                "s02-04-first-motor-two-screws-gray.webp"),
                        ins(11, InstructionType.ACTION,
                                "Fit the nut on the opposite side of the upper screw and tighten it.", 6,
                                "s02-04-first-motor-two-screws-gray.webp"),
                        ins(12, InstructionType.CHECK,
                                "Check that the first motor lies flat on the main board and its bracket assembly "
                                        + "is not flimsy.", 6,
                                "s02-04-first-motor-two-screws-gray.webp"),
                        ins(13, InstructionType.ACTION,
                                "Adjust the first motor if needed, then tighten both screws by one additional turn.", 6,
                                "s02-04-first-motor-two-screws-gray.webp"),
                        ins(14, InstructionType.ACTION,
                                "Apply threadlocker to both nuts on the first motor assembly.", 7,
                                "s02-04-first-motor-two-screws-gray.webp"),
                        ins(15, InstructionType.NOTE,
                                "Nail polish is an acceptable do-it-yourself substitute.", 7),
                        ins(16, InstructionType.NOTE,
                                "Page 8 instructs you to repeat the complete installation on the opposite side. "
                                        + "The following cards expand that repeat explicitly.", 8),
                        ins(17, InstructionType.ACTION,
                                "Place the second gear motor between the opposite pair of brackets.", 4,
                                "gear-motor-gray.webp"),
                        ins(18, InstructionType.ACTION,
                                "Turn the second motor so its terminals and wires face inward, toward the center "
                                        + "of the main board.", 4,
                                "s02-05-both-motors-gray.webp"),
                        ins(19, InstructionType.ACTION,
                                "Hold the second motor flat against the main board with downward thumb pressure.", 5,
                                "s02-05-both-motors-gray.webp"),
                        ins(20, InstructionType.ACTION,
                                "While holding the second motor flat, insert its lower 30 mm screw through the "
                                        + "bracket and motor assembly.", 5,
                                "s02-05-both-motors-gray.webp"),
                        ins(21, InstructionType.ACTION,
                                "Fit the nut on the opposite side of the second motor's lower screw and tighten it.", 5,
                                "s02-05-both-motors-gray.webp"),
                        ins(22, InstructionType.ACTION,
                                "Insert the second motor's upper 30 mm screw above the first screw.", 6,
                                "s02-05-both-motors-gray.webp"),
                        ins(23, InstructionType.ACTION,
                                "Fit the nut on the opposite side of the second motor's upper screw and tighten it.", 6,
                                "s02-05-both-motors-gray.webp"),
                        ins(24, InstructionType.CHECK,
                                "Check that the second motor lies flat on the main board and its bracket assembly "
                                        + "is not flimsy.", 6,
                                "s02-05-both-motors-gray.webp"),
                        ins(25, InstructionType.ACTION,
                                "Adjust the second motor if needed, then tighten both screws by one additional turn.", 6,
                                "s02-05-both-motors-gray.webp"),
                        ins(26, InstructionType.ACTION,
                                "Apply threadlocker to both nuts on the second motor assembly.", 7,
                                "s02-05-both-motors-gray.webp"),
                        ins(27, InstructionType.CHECK,
                                "Compare both completed motor assemblies with page 8: both motors must be flat, "
                                        + "rigid and mirrored across the main board.", 8,
                                "s02-05-both-motors-gray.webp")),
                list(
                        req(MAIN_BOARD, 1, "Prepared in step 1."),
                        req(GEAR_MOTOR, 2, "Terminals and wires face inward; right motor is flipped."),
                        req(MOTOR_BRACKET, 4, "Use two to clamp each motor."),
                        req(SCREW_30MM, 4, "Two per motor."),
                        req(NUT, 4, "One per 30 mm screw."),
                        req(THREADLOCKER, 1, "Apply after tightening."),
                        req(PHILLIPS_SCREWDRIVER, 1, "Tighten screws.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.DYNAMIC_ARROW,
                        OverlayType.ORIENTATION_MARKER, OverlayType.SPATIAL_HIGHLIGHT)));

        result.add(step(3, "Install the motor driver",
                "Align and secure the motor driver in its guide holes.",
                ValidationType.WIGGLE_TEST,
                "Gently wiggle the motor driver and confirm it is correctly positioned and secure.", 8, 8,
                list(
                        ins(1, InstructionType.ACTION,
                                "Ensure the motor driver is correctly positioned by aligning it with the guide holes.", 8,
                                "motor-driver-gray.webp"),
                        ins(2, InstructionType.ACTION,
                                "Insert the two short screws through the motor-driver and main-board guide holes.", 8,
                                "s03-00-motor-driver-installed-gray.webp"),
                        ins(3, InstructionType.ACTION,
                                "Place both nuts on top of the motor driver and tighten them.", 8,
                                "s03-00-motor-driver-installed-gray.webp"),
                        ins(4, InstructionType.ACTION,
                                "Apply threadlocker to both motor-driver nuts.", 8,
                                "s03-00-motor-driver-installed-gray.webp")),
                list(
                        req(MOTOR_DRIVER, 1, "Align to both board guide holes."),
                        req(SCREW_SHORT, 2, "Insert through driver/main-board guide holes."),
                        req(NUT, 2, "Place on top."),
                        req(THREADLOCKER, 1, "Lock both fasteners."),
                        req(PHILLIPS_SCREWDRIVER, 1, "Tighten screws.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.TARGET_GLOW_RING,
                        OverlayType.ORIENTATION_MARKER)));

        result.add(step(4, "Connect the motors",
                "Open every motor-output screw terminal before inserting wires.",
                ValidationType.MANUAL_CONFIRMATION,
                "Confirm all four green motor-driver screw terminals are loosened enough to accept a stripped wire.",
                9, 9,
                list(ins(1, InstructionType.ACTION,
                        "Loosen all four motor-output terminal screws with a Phillips screwdriver.", 9)),
                list(
                        req(MOTOR_DRIVER, 1, "Loosen all four output terminal screws."),
                        req(PHILLIPS_SCREWDRIVER, 1, "Use a Phillips tip.")),
                Collections.emptyList(),
                overlays(OverlayType.TARGET_GLOW_RING, OverlayType.DYNAMIC_ARROW)));

        result.add(step(5, "Insert the wires from the motor to the screw terminals as illustrated",
                "Insert and clamp both motors' red/black leads in the illustrated order.",
                ValidationType.CONTINUITY_TEST,
                "Tug each lead gently, then confirm continuity from each motor lead to its assigned driver terminal.",
                10, 11,
                list(
                        ins(1, InstructionType.NOTE,
                                "If a lead will not enter a loosened terminal, gently widen the terminal opening "
                                        + "with a suitable tool.", 10),
                        ins(2, InstructionType.NOTE,
                                "Small pliers or tweezers can help you handle the motor leads.", 10),
                        ins(3, InstructionType.ACTION,
                                "Insert the first motor's red and black leads into its illustrated terminal pair.", 10),
                        ins(4, InstructionType.ACTION,
                                "Tighten both terminal screws for the first motor's leads.", 10),
                        ins(5, InstructionType.WARNING,
                                "The right motor is flipped; reverse its wiring connection as illustrated.", 10),
                        ins(6, InstructionType.ACTION,
                                "Insert the second motor's red and black leads into the other terminal pair in the "
                                        + "reversed order shown.", 10),
                        ins(7, InstructionType.ACTION,
                                "Tighten both terminal screws for the second motor's leads.", 10),
                        ins(8, InstructionType.CHECK,
                                "Gently tug all four motor leads and confirm each remains firmly clamped in its "
                                        + "screw terminal.", 11)),
                list(
                        req(GEAR_MOTOR, 2, "Use each motor's attached red and black leads."),
                        req(MOTOR_DRIVER, 1, "Use the illustrated left and right motor screw-terminal pairs."),
                        req(PHILLIPS_SCREWDRIVER, 1, "Tighten all four terminal screws.")),
                list(
                        pin(1, GEAR_MOTOR, "LEFT motor's two leads", MOTOR_DRIVER,
                                "first/left motor screw-terminal pair", "red and black; order shown on page 10",
                                "First/left motor output",
                                "The guide illustrates, but does not name, the two individual output terminals."),
                        pin(2, GEAR_MOTOR, "RIGHT motor's two leads", MOTOR_DRIVER,
                                "second/right motor screw-terminal pair",
                                "red and black; reverse the left motor's illustrated order",
                                "Second/right motor output",
                                "The right motor is physically flipped, so reverse its two-wire connection. The "
                                        + "guide does not name the two individual output terminals.")),
                overlays(OverlayType.POLARITY_ARROW, OverlayType.TARGET_GLOW_RING,
                        OverlayType.SPATIAL_HIGHLIGHT)));

        result.add(step(6, "Install the microcontroller board to the mainboard",
                "Fully seat the Nano-form-factor board in the main-board socket with the documented orientation.",
                ValidationType.WIGGLE_TEST,
                "Gently wiggle the microcontroller and visually check both header rows; every pin must be fully and "
                        + "correctly inserted.", 11, 12,
                list(
                        ins(1, InstructionType.CHECK,
                                "Orient the microcontroller as illustrated, with its USB connector toward the "
                                        + "rear/power-jack end of the robot.", 11),
                        ins(2, InstructionType.ACTION,
                                "Align both microcontroller pin rows with the matching main-board sockets.", 11),
                        ins(3, InstructionType.ACTION,
                                "Press the microcontroller straight down into both sockets without bending or "
                                        + "offsetting any pins.", 11),
                        ins(4, InstructionType.CHECK,
                                "Confirm every microcontroller pin is fully inserted into the correct socket.", 11),
                        ins(5, InstructionType.CHECK,
                                "Use page 12 to establish the chassis directions: FRONT is the PERSONAL ROBOT KIT "
                                        + "edge, and left/right are defined while facing that front.", 12)),
                list(
                        req(MICROCONTROLLER, 1, "Align both full header rows without bent or offset pins."),
                        req(MAIN_BOARD, 1, "Use the Nano socket and FRONT marking.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.PIN_ALIGNMENT,
                        OverlayType.ORIENTATION_MARKER)));

        result.add(step(7, "Connect the motor driver to a power source",
                "Map motor-driver VCC/GND to microcontroller VIN/GND.",
                ValidationType.CONTINUITY_TEST,
                "With external power disconnected, confirm continuity VCC-to-VIN and GND-to-GND and confirm there "
                        + "is no short between power and ground.", 13, 13,
                list(
                        ins(1, InstructionType.NOTE,
                                "The VIN and GND pin on the microcontroller board is directly connected to the "
                                        + "terminals where power is connected to.", 13),
                        ins(2, InstructionType.ACTION,
                                "Connect motor-driver VCC to microcontroller VIN.", 13),
                        ins(3, InstructionType.ACTION,
                                "Connect motor-driver GND to microcontroller GND.", 13),
                        ins(4, InstructionType.WARNING,
                                "Wiring mistakes may lead to malfunction or damage to the equipment.", 13),
                        ins(5, InstructionType.CHECK,
                                "Verify both power connections are correct before applying power: VCC to VIN and "
                                        + "GND to GND.", 13)),
                list(
                        req(MOTOR_DRIVER, 1, "Use its VCC and GND pins."),
                        req(MICROCONTROLLER, 1, "Use VIN and GND."),
                        req(JUMPER_WIRE, 2, "One positive and one ground connection.")),
                list(
                        pin(1, MOTOR_DRIVER, "VCC", MICROCONTROLLER, "VIN", "red", "Motor supply positive",
                                "VIN is directly connected to the board's DC power positive terminal."),
                        pin(2, MOTOR_DRIVER, "GND", MICROCONTROLLER, "GND", "black", "Common ground",
                                "GND is directly connected to the board's DC power negative terminal.")),
                overlays(OverlayType.POLARITY_ARROW, OverlayType.TARGET_GLOW_RING,
                        OverlayType.SPATIAL_HIGHLIGHT)));

        result.add(step(8, "Connect the signal pins to the microcontroller board",
                "Wire the four driver inputs to the exact left/right PWM pins.",
                ValidationType.CONTINUITY_TEST,
                "Confirm all four endpoint pairs with a continuity check before proceeding: A-1A/D5, A-1B/D9, "
                        + "B-1A/D6 and B-1B/D10.", 13, 15,
                list(
                        ins(1, InstructionType.NOTE,
                                "The motor driver's signal pins and the microcontroller board's PWM pins are "
                                        + "specifically designed to control the amount of current flowing into each "
                                        + "motor terminal.", 13),
                        ins(2, InstructionType.NOTE,
                                "Pins 5 and 9 control the first (left) motor; pins 6 and 10 control the second "
                                        + "(right) motor.", 14),
                        ins(3, InstructionType.ACTION, "Connect A-1A to pin 5.", 14),
                        ins(4, InstructionType.ACTION, "Connect A-1B to pin 9.", 14),
                        ins(5, InstructionType.ACTION, "Connect B-1A to pin 6.", 14),
                        ins(6, InstructionType.ACTION, "Connect B-1B to pin 10.", 14),
                        ins(7, InstructionType.CHECK,
                                "Make sure all connections are correct before proceeding to the next section.", 15)),
                list(
                        req(MOTOR_DRIVER, 1, "Use all four signal inputs."),
                        req(MICROCONTROLLER, 1, "Use PWM-capable D5, D9, D6 and D10."),
                        req(JUMPER_WIRE, 4, "One for each exact mapping.")),
                list(
                        pin(1, MOTOR_DRIVER, "A-1A", MICROCONTROLLER, "D5 / PWM", "purple",
                                "Left motor A PWM", "Exact authored mapping."),
                        pin(2, MOTOR_DRIVER, "A-1B", MICROCONTROLLER, "D9 / PWM", "purple",
                                "Left motor B PWM", "Exact authored mapping."),
                        pin(3, MOTOR_DRIVER, "B-1A", MICROCONTROLLER, "D6 / PWM", "green",
                                "Right motor A PWM", "Exact authored mapping."),
                        pin(4, MOTOR_DRIVER, "B-1B", MICROCONTROLLER, "D10 / PWM", "green",
                                "Right motor B PWM", "Exact authored mapping.")),
                overlays(OverlayType.PIN_ALIGNMENT, OverlayType.TARGET_GLOW_RING,
                        OverlayType.SPATIAL_HIGHLIGHT)));

        result.add(step(9, "Add the caster wheel",
                "Build the two mounting posts, then fit and lock the front caster.",
                ValidationType.WIGGLE_TEST,
                "Wiggle the caster and roll its ball. Confirm the mount is locked, the ball moves freely, and the "
                        + "robot rests on three support points.", 15, 17,
                list(
                        ins(1, InstructionType.ACTION,
                                "Insert the first 20 mm screw through one front caster mounting hole.", 15),
                        ins(2, InstructionType.ACTION,
                                "Insert the second 20 mm screw through the mirrored front mounting hole.", 15),
                        ins(3, InstructionType.ACTION,
                                "From underneath, fit a nut that locks the first screw to the main board.", 16),
                        ins(4, InstructionType.ACTION,
                                "Fit a board-level nut to the second screw in the same way.", 16),
                        ins(5, InstructionType.ACTION,
                                "Tighten both board-level nuts.", 16),
                        ins(6, InstructionType.ACTION,
                                "Apply threadlocker to both board-level nuts.", 16),
                        ins(7, InstructionType.ACTION,
                                "Place the caster bracket over the two protruding screw posts.", 16),
                        ins(8, InstructionType.ACTION,
                                "Fit the first caster-retaining nut onto one screw post.", 16),
                        ins(9, InstructionType.ACTION,
                                "Fit the second caster-retaining nut onto the mirrored screw post.", 16),
                        ins(10, InstructionType.ACTION,
                                "Tighten both caster-retaining nuts.", 16),
                        ins(11, InstructionType.ACTION,
                                "Apply threadlocker to both caster-retaining nuts.", 16),
                        ins(12, InstructionType.CHECK,
                                "Compare the finished caster with page 17: the ball faces down and gives the robot "
                                        + "a stable third support point.", 17)),
                list(
                        req(CASTER_WHEEL, 1, "Front-mounted with ball facing down."),
                        req(SCREW_20MM, 2, "Create left and right posts."),
                        req(NUT, 4, "Lock posts and caster arms."),
                        req(THREADLOCKER, 1, "Apply to both post assemblies."),
                        req(PHILLIPS_SCREWDRIVER, 1, "Tighten screws.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.TARGET_GLOW_RING,
                        OverlayType.DYNAMIC_ARROW)));

        result.add(step(10, "Install the line trackers",
                "Mount a downward-facing line tracker at each side with a spacer.",
                ValidationType.WIGGLE_TEST,
                "Gently wiggle both trackers and confirm each is secure, spaced off the board and facing down.",
                17, 17,
                list(
                        ins(1, InstructionType.NOTE,
                                "The line trackers will be used by the robot to get information about its location "
                                        + "using the markings underneath it.", 17),
                        ins(2, InstructionType.ACTION,
                                "Choose the illustrated mounting hole for the first line tracker.", 17),
                        ins(3, InstructionType.ACTION,
                                "Orient the first line tracker with its sensing face down toward the floor.", 17),
                        ins(4, InstructionType.ACTION,
                                "Place a plastic spacer between the first line tracker and the main board.", 17),
                        ins(5, InstructionType.NOTE,
                                "You may use stacked nuts instead of a plastic spacer.", 17),
                        ins(6, InstructionType.ACTION,
                                "Insert the first tracker's mounting screw through the tracker, spacer and board.", 17),
                        ins(7, InstructionType.ACTION,
                                "Fit the first tracker's nut and tighten the mounting fastener.", 17),
                        ins(8, InstructionType.ACTION,
                                "Choose the matching hole on the opposite side for the second line tracker.", 17),
                        ins(9, InstructionType.ACTION,
                                "Orient the second line tracker with its sensing face down toward the floor.", 17),
                        ins(10, InstructionType.ACTION,
                                "Place a plastic spacer between the second line tracker and the main board.", 17),
                        ins(11, InstructionType.NOTE,
                                "You may use stacked nuts instead of a plastic spacer.", 17),
                        ins(12, InstructionType.ACTION,
                                "Insert the second tracker's mounting screw through the tracker, spacer and board.", 17),
                        ins(13, InstructionType.ACTION,
                                "Fit the second tracker's nut and tighten the mounting fastener.", 17),
                        ins(14, InstructionType.CHECK,
                                "Confirm both trackers are secure, symmetric and facing the floor.", 17),
                        ins(15, InstructionType.NOTE,
                                "You may relocate the line trackers later to suit your purpose.", 17)),
                list(
                        req(LINE_TRACKER, 2, "One left and one right, both facing down."),
                        req(PLASTIC_SPACER, 2, "One between each tracker and main board; nuts are allowed substitutes."),
                        req(MOUNTING_SCREW, 2, "Secure the modules through side mounting holes."),
                        req(NUT, 2, "Lock the mounting screws.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.ORIENTATION_MARKER,
                        OverlayType.SPATIAL_HIGHLIGHT)));

        result.add(step(11, "Install the distance sensor and follow the wiring diagram",
                "Install the front sensor and complete the exact LED, speaker, distance and line-tracker mappings.",
                ValidationType.CONTINUITY_TEST,
                "Check every mapped signal and supply path: LED/A1, speaker/D3, trigger/D2, echo/D4, both tracker "
                        + "5V/GND rails, left S/A2 and right S/A3.", 18, 20,
                list(
                        ins(1, InstructionType.NOTE,
                                "Do not add redundant power wires: distance-sensor VCC/GND and LED/speaker GND "
                                        + "are already connected through the main-board PCB.", 18),
                        ins(2, InstructionType.ACTION,
                                "Orient the distance sensor with both circular transducers facing forward.", 18),
                        ins(3, InstructionType.ACTION,
                                "Align all four distance-sensor pins with the front header.", 18),
                        ins(4, InstructionType.ACTION,
                                "Press the distance sensor evenly into the header until it is fully seated.", 18),
                        ins(5, InstructionType.ACTION, "Connect the onboard LED signal to A1.", 18),
                        ins(6, InstructionType.ACTION, "Connect the onboard speaker signal to D3.", 18),
                        ins(7, InstructionType.ACTION,
                                "Connect the Distance Sensor Trigger pin to D2.", 18),
                        ins(8, InstructionType.ACTION,
                                "Connect the Distance Sensor Echo pin to D4.", 18),
                        ins(9, InstructionType.ACTION,
                                "Connect the left line tracker's V+ pin to any available 5V pin.", 18),
                        ins(10, InstructionType.ACTION,
                                "Connect the left line tracker's GND pin to any available GND pin.", 18),
                        ins(11, InstructionType.ACTION,
                                "Connect the left line tracker's S pin to A2.", 19),
                        ins(12, InstructionType.ACTION,
                                "Connect the right line tracker's V+ pin to any available 5V pin.", 18),
                        ins(13, InstructionType.ACTION,
                                "Connect the right line tracker's GND pin to any available GND pin.", 18),
                        ins(14, InstructionType.ACTION,
                                "Connect the right line tracker's S pin to A3.", 19),
                        ins(15, InstructionType.CHECK,
                                "Verify every sensor, LED and speaker connection against the wiring diagram.", 19),
                        ins(16, InstructionType.CHECK,
                                "Use page 20 to confirm the completed underside: both trackers face down and the "
                                        + "caster remains centered and unobstructed.", 20)),
                list(
                        req(DISTANCE_SENSOR, 1, "Install at the front with transducers facing forward."),
                        req(LINE_TRACKER, 2, "Already mounted left/right in step 10."),
                        req(LED, 1, "Integrated in main board."),
                        req(SPEAKER, 1, "Integrated in main board."),
                        req(MICROCONTROLLER, 1, "Use D2, D3, D4, A1, A2, A3, 5V and GND."),
                        req(JUMPER_WIRE, 10, "Use sufficient leads for all exposed mappings.")),
                list(
                        pin(1, LED, "signal/anode", MICROCONTROLLER, "A1 / D15", "gray", "LED output",
                                "LED ground is already connected to the PCB."),
                        pin(2, SPEAKER, "signal/+", MICROCONTROLLER, "D3", "gray", "Speaker PWM output",
                                "Speaker ground is already connected to the PCB."),
                        pin(3, DISTANCE_SENSOR, "Trigger", MICROCONTROLLER, "D2", "blue", "Ultrasonic trigger",
                                "Exact authored mapping."),
                        pin(4, DISTANCE_SENSOR, "Echo", MICROCONTROLLER, "D4", "purple", "Ultrasonic echo",
                                "Exact authored mapping."),
                        pin(5, DISTANCE_SENSOR, "VCC", MAIN_BOARD, "embedded 5V", "red", "5V power",
                                "Already connected through PCB footprint."),
                        pin(6, DISTANCE_SENSOR, "GND", MAIN_BOARD, "embedded GND", "black", "Ground",
                                "Already connected through PCB footprint."),
                        pin(7, LINE_TRACKER, "LEFT V+", MICROCONTROLLER, "any 5V", "red", "5V power",
                                "Either available 5V pin is allowed."),
                        pin(8, LINE_TRACKER, "LEFT GND", MICROCONTROLLER, "any GND", "black", "Ground",
                                "Either available GND pin is allowed."),
                        pin(9, LINE_TRACKER, "LEFT S", MICROCONTROLLER, "A2 / D16", "yellow",
                                "Left line signal", "Exact authored side mapping."),
                        pin(10, LINE_TRACKER, "RIGHT V+", MICROCONTROLLER, "any 5V", "red", "5V power",
                                "Either available 5V pin is allowed."),
                        pin(11, LINE_TRACKER, "RIGHT GND", MICROCONTROLLER, "any GND", "black", "Ground",
                                "Either available GND pin is allowed."),
                        pin(12, LINE_TRACKER, "RIGHT S", MICROCONTROLLER, "A3 / D17", "green",
                                "Right line signal", "Exact authored side mapping.")),
                overlays(OverlayType.PIN_ALIGNMENT, OverlayType.TARGET_GLOW_RING,
                        OverlayType.SPATIAL_HIGHLIGHT, OverlayType.ORIENTATION_MARKER)));

        result.add(step(12, "Install the standoff",
                "Fit the center standoff and remove and save its upper screw for the extension board.",
                ValidationType.WIGGLE_TEST,
                "Confirm the standoff is locked upright with its lower nut tight and the top screw removed and saved.",
                21, 21,
                list(
                        ins(1, InstructionType.NOTE,
                                "The standoff will hold the two circuit boards together. You should be able to "
                                        + "quickly access the wires and other components since there is only one screw.", 21),
                        ins(2, InstructionType.ACTION,
                                "Locate the center mounting hole in the main board.", 21),
                        ins(3, InstructionType.ACTION,
                                "Insert the standoff through the center hole from the top of the board.", 21),
                        ins(4, InstructionType.ACTION,
                                "Fit the retaining nut underneath the robot and tighten it.", 21),
                        ins(5, InstructionType.ACTION,
                                "Apply threadlocker to the standoff's retaining nut.", 21),
                        ins(6, InstructionType.ACTION,
                                "Remove the upper screw from the standoff and save it for the extension board.", 21),
                        ins(7, InstructionType.NOTE,
                                "You may use additional standoffs (not included) if you need to.", 21)),
                list(
                        req(STANDOFF, 1, "Use the center hole."),
                        req(NUT, 1, "Secure under the robot."),
                        req(THREADLOCKER, 1, "Lock lower fastener."),
                        req(PHILLIPS_SCREWDRIVER, 1, "Tighten the lower fastener; remove and save the upper screw.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.TARGET_GLOW_RING,
                        OverlayType.DYNAMIC_ARROW)));

        result.add(step(13, "Install the wheels",
                "Press one wheel onto each gear-motor shaft.",
                ValidationType.WIGGLE_TEST,
                "Rotate and gently tug both wheels. Confirm each is seated, turns with its motor shaft and does not "
                        + "rub the board.", 22, 22,
                list(
                        ins(1, InstructionType.ACTION,
                                "Align the first wheel hub with one gear-motor output shaft.", 22),
                        ins(2, InstructionType.ACTION,
                                "Press the first wheel fully onto the motor shaft.", 22),
                        ins(3, InstructionType.ACTION,
                                "Align the second wheel hub with the opposite motor shaft.", 22),
                        ins(4, InstructionType.ACTION,
                                "Press the second wheel fully onto the motor shaft.", 22),
                        ins(5, InstructionType.CHECK,
                                "Confirm both wheels are equally seated and rotate freely without rubbing the board.", 22),
                        ins(6, InstructionType.NOTE, "You may modify the wheels later when needed.", 22)),
                list(
                        req(WHEEL, 2, "One on each gear-motor shaft."),
                        req(GEAR_MOTOR, 2, "Motor shafts must be unobstructed.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.DYNAMIC_ARROW)));

        result.add(step(14, "Install the OLED protector",
                "Fit the OLED protector to the extension board using two 20mm screws and M3 nuts.",
                ValidationType.WIGGLE_TEST,
                "Gently wiggle the protector and confirm it is firmly mounted over the OLED footprint.", 23, 23,
                list(
                        ins(1, InstructionType.NOTE,
                                "The protector will help safeguard the OLED display from accidental bumps and falls.", 23),
                        ins(2, InstructionType.ACTION,
                                "Position the OLED protector over its footprint on the extension circuit board.", 23),
                        ins(3, InstructionType.ACTION,
                                "Align both protector mounting holes with the two holes shown on the board.", 23),
                        ins(4, InstructionType.ACTION,
                                "Insert the first 20mm screw through the protector and board.", 23),
                        ins(5, InstructionType.ACTION,
                                "Insert the second 20mm screw through the protector and board.", 23),
                        ins(6, InstructionType.ACTION,
                                "Fit the first fastener's nut on the reverse side of the board.", 23),
                        ins(7, InstructionType.ACTION,
                                "Fit the second fastener's nut on the reverse side of the board.", 23),
                        ins(8, InstructionType.ACTION,
                                "Tighten both OLED-protector fasteners.", 23),
                        ins(9, InstructionType.ACTION,
                                "Apply threadlocker to both OLED-protector nuts.", 23)),
                list(
                        req(OLED_PROTECTOR, 1, "Mount over the extension-board OLED footprint."),
                        req(EXTENSION_BOARD, 1, "The 'other PCB' in the guide."),
                        req(SCREW_20MM, 2, "Two 20mm screws through the protector and extension board."),
                        req(NUT, 2, "Fit one on the reverse side of each protector fastener."),
                        req(THREADLOCKER, 1, "Prevent loosening.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.TARGET_GLOW_RING,
                        OverlayType.SPATIAL_HIGHLIGHT)));

        result.add(step(15, "Install the data cable",
                "Connect the ribbon cable between the main and extension circuit boards.",
                ValidationType.CONTINUITY_TEST,
                "Confirm both connectors are fully seated and verify the extended pin paths for 7, 8, 11, 12, "
                        + "A0, A4, A5, 5V and GND.", 24, 25,
                list(
                        ins(1, InstructionType.NOTE,
                                "The data cable will extend pins 7, 8, 11, 12, A0, A4, A5, 5V and GND from the "
                                        + "mainboard to the extension.", 24),
                        ins(2, InstructionType.ACTION,
                                "Orient one ribbon-cable connector over the main-board header as pictured.", 24),
                        ins(3, InstructionType.ACTION,
                                "Press the first connector fully and evenly into the main-board header.", 24),
                        ins(4, InstructionType.ACTION,
                                "Route the ribbon toward the extension board without twisting it.", 24),
                        ins(5, InstructionType.ACTION,
                                "Orient the other connector over the extension-board header, preserving the red-edge "
                                        + "stripe direction shown on page 25.", 25),
                        ins(6, InstructionType.ACTION,
                                "Press the second connector fully and evenly into the extension-board header.", 25),
                        ins(7, InstructionType.CHECK,
                                "Check that both ribbon-cable connectors are fully seated and the cable is not twisted.", 25)),
                list(
                        req(DATA_CABLE, 1, "Use both keyed connectors and the red edge stripe for orientation."),
                        req(MAIN_BOARD, 1, "Lower connector."),
                        req(EXTENSION_BOARD, 1, "Upper connector.")),
                list(
                        pin(1, MAIN_BOARD, "D7", EXTENSION_BOARD, "button 1 / D7", "ribbon", "Digital 7", "Extended."),
                        pin(2, MAIN_BOARD, "D8", EXTENSION_BOARD, "button 2 / D8", "ribbon", "Digital 8", "Extended."),
                        pin(3, MAIN_BOARD, "D11", EXTENSION_BOARD, "button 3 / D11", "ribbon", "Digital 11", "Extended."),
                        pin(4, MAIN_BOARD, "D12", EXTENSION_BOARD, "button 4 / D12", "ribbon", "Digital 12", "Extended."),
                        pin(5, MAIN_BOARD, "A0 / D14", EXTENSION_BOARD, "button 5 / A0", "ribbon", "Analog 0 / Digital 14", "Extended."),
                        pin(6, MAIN_BOARD, "A4 / SDA", EXTENSION_BOARD, "OLED SDA / A4", "ribbon", "I2C data", "Reserved while I2C is in use."),
                        pin(7, MAIN_BOARD, "A5 / SCL", EXTENSION_BOARD, "OLED SCL / A5", "ribbon", "I2C clock", "Reserved while I2C is in use."),
                        pin(8, MAIN_BOARD, "5V", EXTENSION_BOARD, "5V", "ribbon", "5V power", "Extended."),
                        pin(9, MAIN_BOARD, "GND", EXTENSION_BOARD, "GND", "ribbon", "Common ground", "Extended.")),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.PIN_ALIGNMENT,
                        OverlayType.ORIENTATION_MARKER, OverlayType.POLARITY_ARROW,
                        OverlayType.TARGET_GLOW_RING)));

        result.add(step(16, "Install the extension circuit board",
                "Position the upper board on the motor-bracket supports and center standoff.",
                ValidationType.WIGGLE_TEST,
                "Wiggle the extension board gently. Confirm the center screw is secure, the brackets support it, "
                        + "and no wire is pinched.", 26, 26,
                list(
                        ins(1, InstructionType.NOTE,
                                "The extension circuit board also serves as the cover where the OLED display, "
                                        + "battery and additional components may be placed.", 26),
                        ins(2, InstructionType.ACTION,
                                "Orient the extension circuit board as shown on page 26.", 26),
                        ins(3, InstructionType.ACTION,
                                "Route the ribbon and other wiring into the open space between the two boards.", 26),
                        ins(4, InstructionType.ACTION,
                                "Align the extension board's center hole with the standoff.", 26),
                        ins(5, InstructionType.CHECK,
                                "Before lowering the board, make sure no wire will be pinched.", 26),
                        ins(6, InstructionType.ACTION,
                                "Lower the extension board onto all four motor-bracket supports.", 26),
                        ins(7, InstructionType.NOTE,
                                "The four motor brackets stabilize the extension board by supporting both sides.", 26),
                        ins(8, InstructionType.ACTION,
                                "Insert the top screw saved from the center standoff through the extension board.", 26),
                        ins(9, InstructionType.ACTION,
                                "Tighten the center screw to secure the extension board.", 26),
                        ins(10, InstructionType.CHECK,
                                "Confirm the board rests on all four bracket supports without pinched wires or flex.", 26)),
                list(
                        req(EXTENSION_BOARD, 1, "Position as the upper cover."),
                        req(STANDOFF, 1, "Use the screw retained in step 12."),
                        req(MOTOR_BRACKET, 4, "Their upper edges stabilize the board."),
                        req(PHILLIPS_SCREWDRIVER, 1, "Tighten center screw.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.ORIENTATION_MARKER,
                        OverlayType.SPATIAL_HIGHLIGHT)));

        result.add(step(17, "Install the OLED display",
                "Carefully seat the OLED in its protected extension-board header.",
                ValidationType.CONTINUITY_TEST,
                "With power off, confirm the module is seated and its GND, VCC, SCL and SDA paths reach the "
                        + "extension-board header without shorts.", 27, 28,
                list(
                        ins(1, InstructionType.WARNING,
                                "To prevent accidental damage, keep the OLED display in the box until needed.", 27),
                        ins(2, InstructionType.ACTION,
                                "Orient the OLED face outward within the installed protector.", 27),
                        ins(3, InstructionType.ACTION,
                                "Align all four OLED pins with the matching I2C socket.", 27),
                        ins(4, InstructionType.ACTION,
                                "Press the OLED straight into the socket until it is fully seated.", 27),
                        ins(5, InstructionType.CHECK,
                                "Use page 28 to confirm the OLED sits inside the protector without binding.", 28)),
                list(
                        req(OLED_DISPLAY, 1, "Align its GND/VCC/SCL/SDA header correctly."),
                        req(OLED_PROTECTOR, 1, "Installed in step 14."),
                        req(EXTENSION_BOARD, 1, "Use the I2C/OLED footprint.")),
                list(
                        pin(1, OLED_DISPLAY, "GND", EXTENSION_BOARD, "GND", "module header", "Ground", "Do not reverse."),
                        pin(2, OLED_DISPLAY, "VCC", EXTENSION_BOARD, "5V", "module header", "3.3V-5V supply", "Do not reverse."),
                        pin(3, OLED_DISPLAY, "SCL", EXTENSION_BOARD, "A5 / SCL", "module header", "I2C clock", "A5 cannot be used for another purpose while I2C is active."),
                        pin(4, OLED_DISPLAY, "SDA", EXTENSION_BOARD, "A4 / SDA", "module header", "I2C data", "A4 cannot be used for another purpose while I2C is active.")),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.PIN_ALIGNMENT,
                        OverlayType.ORIENTATION_MARKER, OverlayType.TARGET_GLOW_RING)));

        result.add(step(18, "Install the battery",
                "Secure a compatible 7-12 V battery pack beside the OLED on the upper extension board.",
                ValidationType.WIGGLE_TEST,
                "Gently press and wiggle the unpowered battery pack. Confirm its mount is secure and it cannot "
                        + "obstruct the wheels, OLED, buttons, or ribbon cable.", 29, 30,
                list(
                        ins(1, InstructionType.ACTION,
                                "Choose a battery-retention method: securing tape, a strap or a separately made "
                                        + "3D-printed holder.", 29),
                        ins(2, InstructionType.ACTION,
                                "Apply the tape to the battery, or seat the battery in its prepared holder.", 29),
                        ins(3, InstructionType.ACTION,
                                "Position the battery on the upper extension board beside the OLED as shown.", 29),
                        ins(4, InstructionType.ACTION,
                                "Press or secure the battery so it cannot move into nearby components.", 29),
                        ins(5, InstructionType.NOTE,
                                "The guide accepts compatible larger-capacity battery packs from 7 V to 12 V.", 30),
                        ins(6, InstructionType.CHECK,
                                "Confirm the pack is rated 7-12 V and does not obstruct the wheels, OLED, buttons, "
                                        + "or ribbon cable.", 30)),
                list(
                        req(BATTERY_PACK, 1, "Use a compatible 7-12 V pack."),
                        req(DOUBLE_SIDED_TAPE, 1, "Manual mounting option; a 3D-printed holder is an alternative."),
                        req(EXTENSION_BOARD, 1, "Battery mounting surface.")),
                Collections.emptyList(),
                overlays(OverlayType.GHOST_COMPONENT, OverlayType.ORIENTATION_MARKER,
                        OverlayType.SPATIAL_HIGHLIGHT)));

        return Collections.unmodifiableList(result);
    }

    private static AssemblyStep step(
            int number,
            String title,
            String summary,
            ValidationType validationType,
            String validationPrompt,
            int pageStart,
            int pageEnd,
            List<AssemblyInstruction> instructions,
            List<RequiredComponent> requirements,
            List<PinConnection> connections,
            List<OverlayType> overlays) {
        return new AssemblyStep(
                String.format(Locale.US, "neobot_step_%02d", number),
                PROJECT_ID, number, title, summary,
                validationType, validationPrompt, assemblyRef(pageStart, pageEnd),
                instructions, requirements, connections, overlays);
    }

    private static AssemblyInstruction ins(int order, InstructionType type, String text, int page) {
        return new AssemblyInstruction(order, type, text, page, assemblyAsset(page));
    }

    /** Uses a reviewed gray-background photograph while retaining the source manual page. */
    private static AssemblyInstruction ins(
            int order,
            InstructionType type,
            String text,
            int page,
            String instructionalFilename) {
        return new AssemblyInstruction(
                order, type, text, page, instructionalAsset(instructionalFilename));
    }

    private static RequiredComponent req(String componentId, int quantity, String notes) {
        return new RequiredComponent(componentId, quantity, notes);
    }

    private static PinConnection pin(
            int order,
            String sourceComponentId,
            String sourcePin,
            String targetComponentId,
            String targetPin,
            String wireColor,
            String signal,
            String notes) {
        return new PinConnection(order, sourceComponentId, sourcePin, targetComponentId, targetPin,
                wireColor, signal, notes);
    }

    private static List<LearningModule> buildLearningModules() {
        List<LearningModule> result = new ArrayList<>();
        result.add(buildButtonsTheoryModule());
        result.add(buildButtonCodingModule());
        result.add(buildOledTheoryModule());
        result.add(buildOledCodingModule());
        result.add(buildInfraredTheoryModule());
        result.add(buildIrCodeCaptureModule());
        result.add(buildIrControllerModule());
        return Collections.unmodifiableList(result);
    }

    private static LearningModule buildButtonsTheoryModule() {
        String moduleId = "learning_buttons_theory";
        return learningModule(moduleId, 1, "Using Buttons",
                "How the extension board's five momentary buttons produce active-LOW input.", "", 1, 1,
                list(
                        learn(moduleId, 1, "Five user-input buttons", LearningContentType.THEORY,
                                "A button is the most basic way to interact with a machine. The robot's extension "
                                        + "circuit board provides five momentary buttons for user interaction. The "
                                        + "buttons are connected by default through the data cable to main-board pins "
                                        + "7, 8, 11, 12 and A0/14. A4, A5, RST, 5V and GND are also extended to the board.",
                                "", "", "The extension data cable and microcontroller must be installed.", 1, 1),
                        learn(moduleId, 2, "Momentary-button terminals", LearningContentType.THEORY,
                                "Momentary buttons have two pairs of terminals, with each pair interconnected. "
                                        + "Typically one set is wired to GND and the other to a digital pin. By "
                                        + "default the two sides are disconnected.",
                                "", "", "", 1, 1),
                        learn(moduleId, 3, "Active-LOW behavior", LearningContentType.THEORY,
                                "Pressing the button connects its pin to GND, which the device registers as a LOW "
                                        + "reading.",
                                "", "", "Use INPUT_PULLUP in the coding activity so each unpressed pin defaults HIGH.",
                                1, 1)));
    }

    private static LearningModule buildButtonCodingModule() {
        String moduleId = "learning_button_interaction";
        String declarations = "const byte b1 = 7;\n"
                + "const byte b2 = 8;\n"
                + "const byte b3 = 11;\n"
                + "const byte b4 = 12;\n"
                + "const byte b5 = 14; // pin A0";
        String setup = "pinMode(b1, INPUT_PULLUP);\n"
                + "pinMode(b2, INPUT_PULLUP);\n"
                + "pinMode(b3, INPUT_PULLUP);\n"
                + "pinMode(b4, INPUT_PULLUP);\n"
                + "pinMode(b5, INPUT_PULLUP);";
        String loop = "if (digitalRead(b1) == LOW) {\n"
                + "  speakerOff();\n"
                + "}\n\n"
                + "if (digitalRead(b2) == LOW) {\n"
                + "  speakerOn(64);\n"
                + "}\n\n"
                + "if (digitalRead(b3) == LOW) {\n"
                + "  speakerOn(128);\n"
                + "}\n\n"
                + "if (digitalRead(b4) == LOW) {\n"
                + "  speakerOn(192);\n"
                + "}\n\n"
                + "if (digitalRead(b5) == LOW) {\n"
                + "  speakerOn(255);\n"
                + "}\n\n"
                + "delay(100); // wait for 100ms";
        return learningModule(moduleId, 2, "Coding: Button Interaction",
                "Write a sketch that captures button presses: button 1 sound off; buttons 2-5 sound on at "
                        + "25%, 50%, 75% and 100%.", "learning_buttons_theory", 2, 5,
                list(
                        learn(moduleId, 1, "Copy the template and use it in the new sketch",
                                LearningContentType.CODING,
                                "Make a new sketch. Copy the template and use it instead of the default blank sketch.",
                                "", "", "Requires template.ino from the preceding robot coding modules.", 2, 2),
                        learn(moduleId, 2, "Make the necessary declarations and set up",
                                LearningContentType.CODING,
                                "Add the five button declarations and set each button pin's mode in setup(). "
                                        + "INPUT_PULLUP sets a pin HIGH by default; pressing the button connects it "
                                        + "to GND and forces it LOW.",
                                declarations + "\n\n// inside setup()\n" + setup,
                                "Do not add the declaration/setup statements shown in this activity back to the "
                                        + "shared template.",
                                "The template supplies speakerOn(), speakerOff() and the existing robot declarations.",
                                3, 4),
                        learn(moduleId, 3, "Wait for button presses", LearningContentType.CODING,
                                "Use conditional statements to choose a task for each active-LOW press. delay() gives "
                                        + "the button time to return to a stable state after being pressed.",
                                loop, "", "Run this logic inside loop().", 4, 5),
                        learn(moduleId, 4, "Test the buttons", LearningContentType.TESTING,
                                "Upload the sketch and test. Press buttons from 5 down to 1; the sound volume should "
                                        + "decrease until button 1 turns it off. Save the sketch as Buttons.ino. The "
                                        + "next activity uses this sketch.",
                                "", "This sketch should not be integrated into the template; keep it as a separate "
                                        + "reference for incorporating buttons into future code.",
                                "Requires the assembled extension board, speaker and uploaded sketch.", 5, 5)));
    }

    private static LearningModule buildOledTheoryModule() {
        String moduleId = "learning_oled_theory_install";
        return learningModule(moduleId, 3, "Using the OLED Screen",
                "OLED/I2C fundamentals and installation of the SSD1306Ascii library.", "learning_button_interaction",
                6, 7,
                list(
                        learn(moduleId, 1, "OLED display", LearningContentType.THEORY,
                                "OLED means Organic Light-Emitting Diode, a display technology used in televisions, "
                                        + "smartphones and wearable devices. This kit uses a 0.96-inch I2C OLED that "
                                        + "displays text, numbers and graphics, runs on 3.3V to 5V, and has a 128x64 "
                                        + "dot-matrix resolution.",
                                "", "", "", 6, 6),
                        learn(moduleId, 2, "Four I2C connections", LearningContentType.THEORY,
                                "The display requires GND, VCC, SCL and SDA. Its A4/A5 connections are embedded in "
                                        + "the circuit board.",
                                "", "A4 and A5 cannot be used for other purposes whenever an I2C device is in use.",
                                "OLED and extension data cable must be installed with correct polarity.", 6, 6),
                        learn(moduleId, 3, "Communication protocols and libraries", LearningContentType.THEORY,
                                "I2C means Inter-integrated Circuit and is pronounced 'eye-squared-see'. It formats "
                                        + "and processes data for communication between electronic devices. The "
                                        + "microcontroller also supports UART and SPI, but their details are outside "
                                        + "this guide. A library is a collection of pre-written code that makes complex "
                                        + "sensors/components easier to use.",
                                "", "", "The activity uses a library instead of implementing I2C directly.", 6, 7),
                        learn(moduleId, 4, "Install SSD1306Ascii", LearningContentType.INSTALLATION,
                                "Open Tools > Manage Libraries. Search for 'oled', select Display as the topic, "
                                        + "find SSD1306Ascii by Bill Greiman, select the latest version and install it.",
                                "", "", "Internet/library-manager access is needed only during development; app content "
                                        + "and assembly operation remain offline.", 7, 7)));
    }

    private static LearningModule buildOledCodingModule() {
        String moduleId = "learning_oled_visual_feedback";
        String declarations = "#include \"SSD1306Ascii.h\"\n"
                + "#include \"SSD1306AsciiAvrI2c.h\"\n\n"
                + "// 0X3C+SA0 - 0x3C or 0x3D\n"
                + "#define I2C_ADDRESS 0X3C\n\n"
                + "// Define proper RST_PIN if required.\n"
                + "#define RST_PIN -1\n\n"
                + "SSD1306AsciiAvrI2c oled;";
        String oledSetup = "#if RST_PIN >= 0\n"
                + "  oled.begin(&Adafruit128x64, I2C_ADDRESS, RST_PIN);\n"
                + "#else // RST_PIN >= 0\n"
                + "  oled.begin(&Adafruit128x64, I2C_ADDRESS);\n"
                + "#endif // RST_PIN >= 0\n"
                + "oled.setFont(Adafruit5x7);";
        String instructions = "oled.clear();\n"
                + "oled.println(\"PRESS THE BUTTON\");\n"
                + "oled.println();\n"
                + "oled.println(\"1. NO SOUND\");\n"
                + "oled.println(\"2. 25% SOUND LEVEL\");\n"
                + "oled.println(\"3. 50% SOUND LEVEL\");\n"
                + "oled.println(\"4. 75% SOUND LEVEL\");\n"
                + "oled.println(\"5. 100% SOUND LEVEL\");";
        return learningModule(moduleId, 4, "Coding: Visual Feedback Using the OLED Display",
                "Use the OLED to provide visual feedback by extending the saved Buttons.ino sketch.",
                "learning_oled_theory_install", 8, 13,
                list(
                        learn(moduleId, 1, "Get an existing sketch that may require visual feedback",
                                LearningContentType.CODING,
                                "Use the previous sketch saved as Buttons.ino.", "", "",
                                "Buttons.ino from the button-interaction activity is required.", 8, 8),
                        learn(moduleId, 2, "Try an example sketch from the library",
                                LearningContentType.TESTING,
                                "Open File > Examples > SSD1306Ascii > AvrI2C128x64. This example matches the "
                                        + "SSD1306 display chip, I2C interface and 128x64 resolution. Install the OLED "
                                        + "and upload the example. Important functions: oled.begin() initializes; "
                                        + "oled.clear() resets the display; oled.setFont() selects a font; oled.print() "
                                        + "prints without moving to the next line; oled.println() prints and moves to "
                                        + "the next line; oled.set1X() selects normal text; oled.set2X() selects double text.",
                                "#define I2C_ADDRESS 0X3C",
                                "If nothing appears, change 0x3C to 0x3D and upload again. If it still fails, try a "
                                        + "different OLED display to confirm.",
                                "SSD1306Ascii must be installed and the physical OLED connected.", 9, 10),
                        learn(moduleId, 3, "Integrate the example into Buttons.ino",
                                LearningContentType.CODING,
                                "Copy the example sketch and integrate it with Buttons.ino. Copy the declaration-area "
                                        + "and setup() lines as shown.",
                                declarations + "\n\n// inside setup()\n" + oledSetup,
                                "", "Preserve the existing button, speaker and robot setup code.", 10, 11),
                        learn(moduleId, 4, "Use the OLED to improve the sketch",
                                LearningContentType.CODING,
                                "Print instructions that tell the human how to interact with the robot. Place the "
                                        + "statements inside setup() or wherever needed, upload and test. Save the file. "
                                        + "OLED output can also show real-time sensor readings without Serial Monitor, "
                                        + "or relay messages needing text and numbers.",
                                instructions,
                                "The sketch will not work properly if it runs without the OLED display installed.",
                                "Uses the code integrated in step 3 and the five-button behavior.", 12, 13)));
    }

    private static LearningModule buildInfraredTheoryModule() {
        String moduleId = "learning_ir_theory_wiring";
        return learningModule(moduleId, 5, "Wireless Remote Control (Infrared)",
                "Infrared signaling, receiver safety, wiring and transmitter battery installation.", "", 14, 15,
                list(
                        learn(moduleId, 1, "Infrared signaling", LearningContentType.THEORY,
                                "An infrared remote control transmits pulses of infrared light toward a receiver. "
                                        + "The pulses are at 38 kHz (38,000 events per second) to avoid interference "
                                        + "from ambient light such as sunlight and light bulbs. The transmitter must "
                                        + "face the receiver.",
                                "", "", "Maintain line of sight between transmitter and receiver.", 14, 14),
                        learn(moduleId, 2, "32-bit codes", LearningContentType.THEORY,
                                "The receiver interprets the light pulses as a 32-bit binary code, from 0 through "
                                        + "4,294,967,295 in decimal.",
                                "", "", "Use an unsigned long in the Arduino sketch.", 14, 14),
                        learn(moduleId, 3, "Receiver module", LearningContentType.THEORY,
                                "The receiver module has two mounting holes and three pins: GND, VCC and S (signal). "
                                        + "Any wire color may be used.",
                                "", "Incorrect microcontroller connections will permanently damage the IR receiver.",
                                "Identify all three receiver labels before attaching wires.", 14, 14),
                        learn(moduleId, 4, "Mount and wire the receiver", LearningContentType.INSTALLATION,
                                "Use screws to mount the IR receiver where needed. Connect receiver VCC to 5V, GND "
                                        + "to GND, and S to pin 7.",
                                "VCC -> 5V\nGND -> GND\nS -> D7",
                                "Pin 7 cannot be used for another purpose while the IR receiver is connected. "
                                        + "Double-check the wiring before proceeding.",
                                "The assembled robot, microcontroller, jumper wires and receiver are required.", 15, 15),
                        learn(moduleId, 5, "Install the transmitter battery", LearningContentType.INSTALLATION,
                                "Install the CR2025 battery in the transmitter in the orientation shown.",
                                "", "Observe coin-cell polarity.", "Requires the IR transmitter and CR2025 cell.", 15, 15)));
    }

    private static LearningModule buildIrCodeCaptureModule() {
        String moduleId = "learning_ir_code_capture";
        String setupCode = "#include <IRremote.h>  // insert library\n\n"
                + "const byte irPin = 7;  // IR pin connection\n\n"
                + "IRrecv irrecv(irPin);   // create an object, irrecv\n"
                + "decode_results results; // create an object, results\n\n"
                + "void setup() {\n"
                + "  Serial.begin(9600);\n"
                + "  irrecv.enableIRIn();  // start receiving IR signals\n"
                + "}";
        String loopCode = "void loop() {\n"
                + "  // check if there is a decoded message\n"
                + "  if (irrecv.decode(&results) == true) {\n"
                + "    Serial.println(results.value);  // print code\n"
                + "    irrecv.resume();                 // resume receiving\n"
                + "  }\n"
                + "}";
        return learningModule(moduleId, 6, "Coding: Remote Control Codes",
                "Capture the specific decimal code sent by each remote-control button.",
                "learning_ir_theory_wiring", 16, 19,
                list(
                        learn(moduleId, 1, "Install the IR Remote library", LearningContentType.INSTALLATION,
                                "Open a blank sketch and Tools > Manage Libraries. Search for 'ir remote' using "
                                        + "Communication as the topic. Find IRremote by shirriff, z3t0, ArminJo and "
                                        + "others. Select version 2.6.0 and click INSTALL; this version is easier to use.",
                                "", "Do not update the library after installing version 2.6.0.",
                                "The manual's code uses the pre-3.x IRremote API and specifically requires 2.6.0.", 16, 16),
                        learn(moduleId, 2, "Perform set up", LearningContentType.CODING,
                                "Insert the statements needed for the IR receiver. #include adds the library content. "
                                        + "Pin 7 receives the signal; irrecv starts IR communications and results "
                                        + "stores received IR codes.",
                                setupCode, "", "Receiver S must already be connected to pin 7.", 17, 17),
                        learn(moduleId, 3, "Check for IR messages", LearningContentType.CODING,
                                "Repeatedly check for received messages. irrecv.decode(&results) is true or false. "
                                        + "When true, print results.value to Serial Monitor. Receiving pauses when a "
                                        + "message is received; irrecv.resume() resumes new messages.",
                                loopCode, "Always call irrecv.resume() after handling a decoded message.",
                                "Serial Monitor uses 9600 baud to match Serial.begin(9600).", 18, 18),
                        learn(moduleId, 4, "Check the transmitter code", LearningContentType.TESTING,
                                "Upload and open Serial Monitor. Point the transmitter at the receiver, press its "
                                        + "buttons and note the distinct code displayed for each. Holding a button for "
                                        + "several seconds produces 4294967295, which indicates a repeated press and can "
                                        + "also mean the received code is unsupported.",
                                "// observed example\n16718055\n4294967295",
                                "Do not treat repeat code 4294967295 as a new direction command.",
                                "The transmitter must face the powered receiver.", 19, 19),
                        learn(moduleId, 5, "Record direction-button codes", LearningContentType.TESTING,
                                "Record the code for each direction button; those buttons control the robot in the "
                                        + "next activity. Save the sketch as IRcodes.ino.",
                                "forward: 16718055\nreverse: 16730805\nleft: 16716015\nright: 16734885",
                                "Codes are the guide's transmitter values; capture again if using a different remote.",
                                "IRcodes.ino becomes the input to the controller activity.", 19, 19)));
    }

    private static LearningModule buildIrControllerModule() {
        String moduleId = "learning_ir_remote_controller";
        String irDeclarations = "#include <IRremote.h>  // insert library\n\n"
                + "const byte irPin = 7;  // IR pin connection\n\n"
                + "IRrecv irrecv(irPin);   // create an object, irrecv\n"
                + "decode_results results; // create an object, results";
        String receive = "// check if there is a decoded message\n"
                + "if (irrecv.decode(&results) == true) {\n"
                + "  if (results.value != 4294967295) {\n"
                + "    code = results.value;\n"
                + "  }\n\n"
                + "  controlRobot(); // will the robot move?\n"
                + "  irrecv.resume(); // resume receiving\n"
                + "}";
        String controller = "void controlRobot() {\n"
                + "  // forward\n"
                + "  if (code == 16718055) {\n"
                + "    forward(182);\n"
                + "  }\n\n"
                + "  // reverse\n"
                + "  if (code == 16730805) {\n"
                + "    reverse(182);\n"
                + "  }\n\n"
                + "  // left\n"
                + "  if (code == 16716015) {\n"
                + "    left(182);\n"
                + "  }\n\n"
                + "  // right\n"
                + "  if (code == 16734885) {\n"
                + "    right(182);\n"
                + "  }\n\n"
                + "  delay(200); // give the motors time to run\n"
                + "  stop();\n"
                + "}";
        return learningModule(moduleId, 7, "Coding: Infrared Remote Controller",
                "Control the assembled robot wirelessly using the four infrared direction codes.",
                "learning_ir_code_capture", 20, 25,
                list(
                        learn(moduleId, 1, "Copy the template", LearningContentType.CODING,
                                "Open a new sketch and replace its contents with template.ino.",
                                "", "", "Requires the robot template with forward(), reverse(), left(), right() and stop().",
                                20, 20),
                        learn(moduleId, 2, "Add IRcodes.ino", LearningContentType.CODING,
                                "At the very beginning of the template insert the declaration lines from IRcodes.ino. "
                                        + "In setup(), also add irrecv.enableIRIn() to start receiving IR signals.",
                                irDeclarations + "\n\n// inside setup()\nirrecv.enableIRIn();",
                                "", "Requires the saved IRcodes.ino and IRremote 2.6.0.", 21, 21),
                        learn(moduleId, 3, "Create a variable for the received code", LearningContentType.CODING,
                                "Declare a variable that can store any value from 0 through 4,294,967,295.",
                                "long unsigned code; // to store 0 to 4294967295",
                                "A signed 32-bit long cannot represent the full documented range.",
                                "Declare it with the other global variables.", 22, 22),
                        learn(moduleId, 4, "Record incoming IR messages", LearningContentType.CODING,
                                "Check received messages and update code only when results.value is not 4294967295. "
                                        + "When the repeat value arrives, preserve the previous code. Call controlRobot() "
                                        + "for every received message, then resume the receiver.",
                                receive,
                                "Filter 4294967295 so a long press repeats the last valid direction instead of storing "
                                        + "the repeat sentinel.",
                                "Place inside loop().", 22, 23),
                        learn(moduleId, 5, "Make the robot move", LearningContentType.CODING,
                                "Insert controlRobot() after loop(). Test forward and reverse with 6V sent to the "
                                        + "motors, then add left and right. Use the exact codes and PWM value 182. Add "
                                        + "a 200 ms delay before forcing the robot to stop.",
                                controller,
                                "It is important that the robot is forced to stop; it should move only while a "
                                        + "direction button is pressed.",
                                "Exact mappings: 16718055 forward, 16730805 reverse, 16716015 left, 16734885 right.",
                                23, 25),
                        learn(moduleId, 6, "Control the robot", LearningContentType.TESTING,
                                "Upload and test the robot. Use the controller to make it move, modify the sketch as "
                                        + "needed, and perform further testing.",
                                "", "Test in a clear area and be ready to disconnect power.",
                                "The fully assembled robot, receiver, transmitter and motor wiring are required.", 25, 25),
                        learn(moduleId, 7, "Save the sketch", LearningContentType.CODING,
                                "Save the sketch as IR Remote.ino.", "", "",
                                "Complete control testing first.", 25, 25)));
    }

    private static LearningModule learningModule(
            String id,
            int order,
            String title,
            String overview,
            String prerequisite,
            int pageStart,
            int pageEnd,
            List<LearningStep> steps) {
        return new LearningModule(id, order, title, overview, prerequisite,
                learningRef(pageStart, pageEnd), steps);
    }

    private static LearningStep learn(
            String moduleId,
            int order,
            String title,
            LearningContentType type,
            String content,
            String code,
            String warning,
            String dependency,
            int pageStart,
            int pageEnd) {
        return new LearningStep(moduleId + "_" + String.format(Locale.US, "%02d", order),
                moduleId, order, title,
                type, content, code, warning, dependency, learningRef(pageStart, pageEnd));
    }

    public static SeedIntegrityReport validate() {
        List<String> errors = new ArrayList<>();
        if (PROJECT_ID.trim().isEmpty()) {
            errors.add("Project ID is empty");
        }
        if (ASSEMBLY_STEPS.size() != 18) {
            errors.add("Expected 18 assembly steps, found " + ASSEMBLY_STEPS.size());
        }

        Set<String> componentIds = new HashSet<>();
        Set<String> recognitionPaths = new HashSet<>();
        Set<String> intentionallyUnbundled = new HashSet<>(Arrays.asList(
                THREADLOCKER, NAIL_POLISH, DOUBLE_SIDED_TAPE, BATTERY_HOLDER));
        for (Component component : COMPONENTS) {
            if (!componentIds.add(component.getId())) {
                errors.add("Duplicate component ID: " + component.getId());
            }
            if (component.getManualReference().getAssetPaths().isEmpty()) {
                errors.add("Component has no manual asset: " + component.getId());
            }
            for (String manualPath : component.getManualReference().getAssetPaths()) {
                if (!manualPath.startsWith("manuals/") || !manualPath.endsWith(".jpg")) {
                    errors.add("Invalid manual-page asset for " + component.getId() + ": " + manualPath);
                }
            }
            List<String> references = component.getReferenceImagePaths();
            if (references.size() > 4) {
                errors.add("Component has more than four bundled references: " + component.getId());
            }
            if (references.isEmpty() && !intentionallyUnbundled.contains(component.getId())) {
                errors.add("Component unexpectedly has no bundled source reference: " + component.getId());
            }
            for (String referencePath : references) {
                boolean extractedSource = ((referencePath.startsWith("references/assembly/")
                        || referencePath.startsWith("references/learning/"))
                        && referencePath.endsWith(".png"))
                        || (referencePath.startsWith("references/variants/")
                        && (referencePath.endsWith(".png") || referencePath.endsWith(".jpg")));
                if (!extractedSource) {
                    errors.add("Recognition target is not a bundled physical image for "
                            + component.getId() + ": " + referencePath);
                }
                if (!recognitionPaths.add(referencePath)) {
                    errors.add("Recognition source is reused by multiple catalog entries: "
                            + referencePath);
                }
            }
            if (JUMPER_WIRE.equals(component.getId())
                    && (component.getKitQuantity() != 16
                    || !component.getDescription().contains("at least 16")
                    || !component.getDescription().contains("do not state the packaged set's total lead count"))) {
                errors.add("Jumper-wire quantity must be the derived 16-lead minimum, not a pack claim");
            }
        }

        Set<String> stepIds = new HashSet<>();
        for (int index = 0; index < ASSEMBLY_STEPS.size(); index++) {
            AssemblyStep step = ASSEMBLY_STEPS.get(index);
            int expectedNumber = index + 1;
            if (step.getStepNumber() != expectedNumber) {
                errors.add("Expected step number " + expectedNumber + " at index " + index
                        + ", found " + step.getStepNumber());
            }
            if (!stepIds.add(step.getId())) {
                errors.add("Duplicate assembly step ID: " + step.getId());
            }
            if (step.getInstructions().isEmpty()) {
                errors.add("Step has no instructions: " + step.getId());
            }
            if (step.getOverlayTypes().isEmpty()) {
                errors.add("Step has no AR overlay type: " + step.getId());
            }
            int instructionOrder = 1;
            for (AssemblyInstruction instruction : step.getInstructions()) {
                if (instruction.getOrder() != instructionOrder++) {
                    errors.add("Non-consecutive instruction order in " + step.getId());
                    break;
                }
                String referencePath = instruction.getReferenceImagePath();
                boolean manualPage = referencePath.matches(
                        "manuals/assembly/page-[0-9]{2}\\.jpg");
                boolean instructionalImage = referencePath.matches(
                        "references/instructional/gray/[a-z0-9-]+-gray\\.webp");
                if (!manualPage && !instructionalImage) {
                    errors.add("Invalid guided-card image in " + step.getId() + ": "
                            + referencePath);
                }
            }
            for (RequiredComponent requirement : step.getRequiredComponents()) {
                if (!componentIds.contains(requirement.getComponentId())) {
                    errors.add("Unknown required component " + requirement.getComponentId()
                            + " in " + step.getId());
                }
            }
            for (PinConnection connection : step.getPinConnections()) {
                if (!componentIds.contains(connection.getSourceComponentId())) {
                    errors.add("Unknown connection source " + connection.getSourceComponentId()
                            + " in " + step.getId());
                }
                if (!componentIds.contains(connection.getTargetComponentId())) {
                    errors.add("Unknown connection target " + connection.getTargetComponentId()
                            + " in " + step.getId());
                }
            }
        }

        assertConnection(errors, 8, "A-1A", "D5 / PWM");
        assertConnection(errors, 8, "A-1B", "D9 / PWM");
        assertConnection(errors, 8, "B-1A", "D6 / PWM");
        assertConnection(errors, 8, "B-1B", "D10 / PWM");
        assertConnection(errors, 11, "Trigger", "D2");
        assertConnection(errors, 11, "Echo", "D4");
        assertConnection(errors, 11, "LEFT S", "A2 / D16");
        assertConnection(errors, 11, "RIGHT S", "A3 / D17");

        if (LEARNING_MODULES.size() != 7) {
            errors.add("Expected 7 learning modules, found " + LEARNING_MODULES.size());
        }
        Map<String, Integer> expectedLearningStepCounts = new HashMap<>();
        expectedLearningStepCounts.put("learning_button_interaction", 4);
        expectedLearningStepCounts.put("learning_oled_visual_feedback", 4);
        expectedLearningStepCounts.put("learning_ir_code_capture", 5);
        expectedLearningStepCounts.put("learning_ir_remote_controller", 7);
        Set<String> learningIds = new HashSet<>();
        for (int index = 0; index < LEARNING_MODULES.size(); index++) {
            LearningModule module = LEARNING_MODULES.get(index);
            if (module.getOrder() != index + 1) {
                errors.add("Non-consecutive learning-module order at " + module.getId());
            }
            if (!learningIds.add(module.getId())) {
                errors.add("Duplicate learning module ID: " + module.getId());
            }
            Integer expectedCount = expectedLearningStepCounts.get(module.getId());
            if (expectedCount != null && module.getSteps().size() != expectedCount) {
                errors.add(module.getId() + " expected " + expectedCount + " steps, found "
                        + module.getSteps().size());
            }
            for (int stepIndex = 0; stepIndex < module.getSteps().size(); stepIndex++) {
                LearningStep learningStep = module.getSteps().get(stepIndex);
                if (learningStep.getOrder() != stepIndex + 1) {
                    errors.add("Non-consecutive learning-step order in " + module.getId());
                    break;
                }
            }
        }
        return new SeedIntegrityReport(errors);
    }

    private static void assertConnection(
            List<String> errors,
            int stepNumber,
            String sourcePin,
            String targetPin) {
        AssemblyStep step = ASSEMBLY_STEPS.get(stepNumber - 1);
        for (PinConnection connection : step.getPinConnections()) {
            if (sourcePin.equals(connection.getSourcePin())
                    && targetPin.equals(connection.getTargetPin())) {
                return;
            }
        }
        errors.add("Missing exact step " + stepNumber + " pin map: " + sourcePin + " -> " + targetPin);
    }

    private static ManualReference assemblyRef(int pageStart, int pageEnd) {
        return new ManualReference(ASSEMBLY_DOCUMENT_ID,
                "Personal Robot Kit Manual Assembly and Wiring (2026)",
                pageStart, pageEnd, assemblyAssets(pageStart, pageEnd));
    }

    private static ManualReference learningRef(int pageStart, int pageEnd) {
        return new ManualReference(LEARNING_DOCUMENT_ID,
                "Personal Robot Kit Manual 2026",
                pageStart, pageEnd, learningAssets(pageStart, pageEnd));
    }

    private static List<String> assemblyAssets(int pageStart, int pageEnd) {
        return pageAssets("manuals/assembly/page-", pageStart, pageEnd);
    }

    private static List<String> learningAssets(int pageStart, int pageEnd) {
        return pageAssets("manuals/learning/page-", pageStart, pageEnd);
    }

    private static List<String> pageAssets(String prefix, int pageStart, int pageEnd) {
        List<String> paths = new ArrayList<>();
        for (int page = pageStart; page <= pageEnd; page++) {
            paths.add(prefix + String.format(Locale.US, "%02d", page) + ".jpg");
        }
        return Collections.unmodifiableList(paths);
    }

    private static String assemblyAsset(int page) {
        return "manuals/assembly/page-" + String.format(Locale.US, "%02d", page) + ".jpg";
    }

    private static String instructionalAsset(String filename) {
        if (filename == null || filename.trim().isEmpty()
                || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("Instructional asset filename must be a basename");
        }
        return "references/instructional/gray/" + filename.trim();
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private static <T> List<T> list(T... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    private static List<OverlayType> overlays(OverlayType... values) {
        return list(values);
    }
}
