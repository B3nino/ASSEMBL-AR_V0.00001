package dev.chyken.assembl_ar.workflow.phase;

import java.util.*;

/** Current authored sub-steps, shared by project details and DEV navigation after each overhaul. */
public final class PhaseGuideMetadata {
    private static final String[][] STEPS={
        {"1.1 · Prepare and scan the main board"},
        {"2.1 · Four motor brackets","2.2 · Two TT gear motors","2.3 · Fasteners and tools","2.4 · Video-guided assembly","2.5 · Top, left and right hardware scans","2.6 · Joint stability"},
        {"3.1 · Motor driver front scan","3.2 · Screws and nuts","3.3 · Assembly demo","3.4 · Pre-screw placement","3.5 · Final assembly scan","3.6 · Joint stability"},
        {"4.1 · Select wire colors","4.2 · Component wiring schematic","4.3 · Wire pull test"},
        {"5.1 · Nano scan and View Info","5.2 · Installation demo","5.3 · Installed orientation scan","5.4 · Pin alignment and stability"}
    };
    private static final String[] SUMMARIES={
        "Prepare a clean workstation, identify the main board and confirm its front side.",
        "Scan four brackets and two motors, follow the demo, verify three views and check the joints.",
        "Scan the driver, gather short screws, check placement, fasten and verify the installation.",
        "Choose physical wire colors, follow the component diagram and pull-test all four connections.",
        "Identify the Nano, learn its orientation, watch installation and verify placement and all pins."
    };
    private PhaseGuideMetadata(){}
    public static boolean overhauled(AssemblyPhase phase){return phase.getNumber()>=1&&phase.getNumber()<=STEPS.length;}
    public static List<String> subSteps(AssemblyPhase phase){
        return overhauled(phase)?Collections.unmodifiableList(Arrays.asList(STEPS[phase.getNumber()-1])):Collections.emptyList();
    }
    public static String summary(AssemblyPhase phase){return overhauled(phase)?SUMMARIES[phase.getNumber()-1]:phase.getSummary();}
}
