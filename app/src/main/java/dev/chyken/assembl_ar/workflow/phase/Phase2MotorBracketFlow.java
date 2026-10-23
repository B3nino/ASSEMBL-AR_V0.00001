package dev.chyken.assembl_ar.workflow.phase;

/** Physical evidence policy shared by the matcher and phase gate. T_motor is per motor ROI. */
public final class Phase2MotorBracketFlow {
    public static final int T_MOTOR=6, T_BRACKET=4;
    private Phase2MotorBracketFlow() { }
    public static boolean acceptsTop(int leftMotor,int rightMotor,int leftBracket,int rightBracket) {
        return leftMotor>=T_MOTOR&&rightMotor>=T_MOTOR&&leftBracket>=T_BRACKET&&rightBracket>=T_BRACKET;
    }
}
