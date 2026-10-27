package dev.chyken.assembl_ar.vision;

/** Reviewed identity hints only. Installation, face, pose and counts retain their verifiers. */
final class TrainedModelPolicy {
    static final long QUERY_INTERVAL_NS=400_000_000L;
    // Tablet recovery inference can take >2 seconds. A hint still has to pass
    // current-frame motion, scope and original geometric verification checks.
    static final long MAX_AGE_NS=3_000_000_000L;
    static final double NMS_IOU=.45, MAX_LUMA_CHANGE=12;
    // These scores propose bounded search windows only. The original geometric
    // verifier owns acceptance; .75 discarded useful recovery locations.
    static double confidence(String component){return .35;}
    static String component(String name){
        switch(name){
            case "main_board_front":case "main_board_back":return "main_board";
            case "gear_motor":return "gear_motor_6v";
            case "motor_driver":return "dual_motor_driver";
            case "arduino_nano":return "arduino_nano_microcontroller";
            case "line_tracker":return "line_tracker_module";
            case "extension_board":return "extension_circuit_board";
            case "oled_display":return "oled_096_i2c";
            case "battery_pack":return "battery_pack_7v_12v";
            default:return name;
        }
    }
    static boolean assisted(RecognitionRequest request){return modelPhase(request)>0;}
    /** The phase model follows the active checkpoint, including installed views.
     * Standalone component tests use the model that was trained for that component. */
    static int modelPhase(RecognitionRequest request){
        if(request==null||request.getExpectation()==null)return -1;
        int phase=request.getAssemblyStepNumber();
        if(phase==4||phase==6)return -1;
        if(phase==2&&request.getPhase2Stage()==5)return 2;
        if(phase==7&&request.getPhase7Stage()>=3)return 7;
        if(phase==9&&request.getPhase9Stage()>=3)return 9;
        if(phase==10&&request.getPhase10Stage()>0)return 10;
        if(phase==11&&request.getPhase11Stage()>0)return 11;
        switch(request.getExpectation().getComponentId()){
            case "main_board":return 1;
            case "motor_bracket":case "gear_motor_6v":return 2;
            case "dual_motor_driver":return 3;
            case "arduino_nano_microcontroller":return 5;
            case "caster_wheel":case "line_tracker_module":return 7;
            case "ultrasonic_distance_sensor":return 8;
            case "center_standoff":case "drive_wheel":return 9;
            case "extension_data_cable":case "extension_circuit_board":case "oled_protector":return 10;
            case "oled_096_i2c":case "battery_pack_7v_12v":return 11;
            default:return -1;
        }
    }
    static boolean proposes(RecognitionRequest request,String component){
        if(request.getPhase2Stage()==5)return "gear_motor_6v".equals(component)||"motor_bracket".equals(component);
        if(request.getPhase7Stage()==3)return "caster_wheel".equals(component);
        if(request.getPhase7Stage()==4)return "line_tracker_module".equals(component);
        if(request.getPhase9Stage()>=3)return "drive_wheel".equals(component)||"center_standoff".equals(component);
        if(request.getPhase10Stage()==4)return "extension_circuit_board".equals(component)||"oled_protector".equals(component);
        if(request.getPhase10Stage()==5)return "extension_circuit_board".equals(component);
        if(request.getPhase11Stage()==3)return "oled_096_i2c".equals(component);
        return request.getExpectation()!=null&&component.equals(request.getExpectation().getComponentId());
    }
    static boolean packaged(int phase){return phase>=1&&phase<=11&&phase!=4&&phase!=6;}
    static boolean fresh(long source,long current,boolean sameScope,int width,int height,int oldWidth,int oldHeight,double lumaChange){
        return sameScope&&current>=source&&current-source<=MAX_AGE_NS&&width==oldWidth&&height==oldHeight
                &&Double.isFinite(lumaChange)&&lumaChange<=MAX_LUMA_CHANGE;
    }
    private TrainedModelPolicy(){}
}
