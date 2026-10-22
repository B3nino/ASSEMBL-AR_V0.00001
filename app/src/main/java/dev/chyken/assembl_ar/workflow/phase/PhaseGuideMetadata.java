package dev.chyken.assembl_ar.workflow.phase;

import java.util.*;

/** Current authored sub-steps, shared by project details and DEV navigation after each overhaul. */
public final class PhaseGuideMetadata {
    private static final String[][] STEPS={
        {"1.1 · Prepare and scan the main board"},
        {"2.1 · Four motor brackets","2.2 · Two TT gear motors","2.3 · Fasteners and tools","2.4 · Video-guided assembly","2.5 · Top, left and right hardware scans","2.6 · Joint stability"},
        {"3.1 · Motor driver front scan","3.2 · Screws and nuts","3.3 · Assembly demo","3.4 · Pre-screw placement","3.5 · Final assembly scan","3.6 · Joint stability"},
        {"4.1 · Select wire colors","4.2 · Component wiring schematic","4.3 · Wire pull test"},
        {"5.1 · Nano scan and View Info","5.2 · Installation demo","5.3 · Installed orientation scan","5.4 · Pin alignment and stability"},
        {"6.1 · Six jumper wire colors","6.2 · Power and ground check","6.3 · Four PWM control signals","6.4 · Final wiring checklist"},
        {"7.1a · Scan caster wheel","7.1b · Caster screws and nuts","7.2a · Scan two line trackers","7.2b · Tracker screws, spacers and nuts","7.3 · Caster demos, bottom scan and stability","7.4 · Tracker demos, bottom scan and stability"},
        java.util.Arrays.stream(Phase8SensorWiringFlow.Screen.values()).map(Phase8SensorWiringFlow.Screen::label).toArray(String[]::new)
    };
    private static final String[] SUMMARIES={
        "Prepare a clean workstation, identify the main board and confirm its front side.",
        "Scan four brackets and two motors, follow the demo, verify three views and check the joints.",
        "Scan the driver, gather short screws, check placement, fasten and verify the installation.",
        "Choose physical wire colors, follow the component diagram and pull-test all four connections.",
        "Identify the Nano, learn its orientation, watch installation and verify placement and all pins.",
        "Choose six wire colors, follow the actual pin layout and confirm power, signals and seated headers.",
        "Scan each component set, confirm its hardware on a dedicated 3D preparation screen, then follow the demos and verify both bottom-view installations.",
        "Scan and mount the sensor, verify its outward orientation, personalize ten signal and tracker wires, then confirm seating and routing."
    };
    private PhaseGuideMetadata(){}
    public static boolean overhauled(AssemblyPhase phase){return phase.getNumber()>=1&&phase.getNumber()<=STEPS.length;}
    public static List<String> subSteps(AssemblyPhase phase){
        return overhauled(phase)?Collections.unmodifiableList(Arrays.asList(STEPS[phase.getNumber()-1])):Collections.emptyList();
    }
    public static String summary(AssemblyPhase phase){return overhauled(phase)?SUMMARIES[phase.getNumber()-1]:phase.getSummary();}
}
