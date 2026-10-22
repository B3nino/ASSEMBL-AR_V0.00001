package dev.chyken.assembl_ar.learning;

import java.util.*;

/** Offline practice blocks. Pin assignments match the authored Phase 6 and 8 diagrams. */
public final class NeobotProgram {
    public enum Kind {FORWARD, BACKWARD, LEFT, RIGHT, STOP, WAIT, LED_ON, LED_OFF, BEEP, READ_DISTANCE, READ_TRACKERS}
    public static final class Block {
        public final Kind kind;public final int pwm,durationMs;
        public Block(Kind kind,int pwm,int durationMs){this.kind=Objects.requireNonNull(kind);if(pwm<0||pwm>255||durationMs<50||durationMs>30000)throw new IllegalArgumentException("PWM 0–255; duration 50–30000 ms");this.pwm=pwm;this.durationMs=durationMs;}
        public String label(){return kind.name().replace('_',' ')+" · "+durationMs+" ms"+(kind.ordinal()<4?" · PWM "+pwm:"");}
    }
    public static List<Block> starter(){return new ArrayList<>(Arrays.asList(new Block(Kind.FORWARD,160,1000),new Block(Kind.STOP,0,500)));}
    public static String encode(List<Block> blocks){StringBuilder s=new StringBuilder("1");for(Block b:blocks)s.append(';').append(b.kind).append(',').append(b.pwm).append(',').append(b.durationMs);return s.toString();}
    public static List<Block> decode(String text){try{String[] p=text.split(";",-1);if(!p[0].equals("1")||p.length>101)throw new IllegalArgumentException();List<Block> result=new ArrayList<>();for(int i=1;i<p.length;i++){String[] q=p[i].split(",");if(q.length!=3)throw new IllegalArgumentException();result.add(new Block(Kind.valueOf(q[0]),Integer.parseInt(q[1]),Integer.parseInt(q[2])));}return result;}catch(RuntimeException invalid){return starter();}}
    public static String cpp(List<Block> blocks){StringBuilder out=new StringBuilder("// ASSEMBL-AR Neobot practice sketch\n// Review wiring and use the Arduino IDE to compile/upload.\nconst int A1A=5, A1B=9, B1A=6, B1B=10;\nconst int TRIG=2, ECHO=4, LED_PIN=A1, SPEAKER=3;\nconst int TRACK_LEFT=A2, TRACK_RIGHT=A3;\n\nvoid drive(int left, int right) {\n  analogWrite(A1A, left > 0 ? left : 0);\n  analogWrite(A1B, left < 0 ? -left : 0);\n  analogWrite(B1A, right > 0 ? right : 0);\n  analogWrite(B1B, right < 0 ? -right : 0);\n}\nlong distanceCm() {\n  digitalWrite(TRIG, LOW); delayMicroseconds(2);\n  digitalWrite(TRIG, HIGH); delayMicroseconds(10); digitalWrite(TRIG, LOW);\n  unsigned long us=pulseIn(ECHO, HIGH, 30000UL);\n  return us == 0 ? -1 : us / 58;\n}\nvoid setup() {\n  for (int pin : {A1A,A1B,B1A,B1B,TRIG,LED_PIN,SPEAKER}) pinMode(pin, OUTPUT);\n  pinMode(ECHO, INPUT); pinMode(TRACK_LEFT, INPUT); pinMode(TRACK_RIGHT, INPUT);\n  Serial.begin(9600); drive(0,0);\n}\nvoid loop() {\n");
        // Avoid initializer_list dependencies in older Arduino AVR toolchains.
        int start=out.indexOf("  for (int pin");int end=out.indexOf("\n",start);out.replace(start,end,"  pinMode(A1A,OUTPUT); pinMode(A1B,OUTPUT); pinMode(B1A,OUTPUT); pinMode(B1B,OUTPUT);\n  pinMode(TRIG,OUTPUT); pinMode(LED_PIN,OUTPUT); pinMode(SPEAKER,OUTPUT);");
        for(Block b:blocks){out.append("  // ").append(b.label()).append('\n');switch(b.kind){
            case FORWARD:out.append("  drive("+b.pwm+","+b.pwm+");\n");break;
            case BACKWARD:out.append("  drive(-"+b.pwm+",-"+b.pwm+");\n");break;
            case LEFT:out.append("  drive(-"+b.pwm+","+b.pwm+");\n");break;
            case RIGHT:out.append("  drive("+b.pwm+",-"+b.pwm+");\n");break;
            case STOP:out.append("  drive(0,0);\n");break;
            case LED_ON:out.append("  digitalWrite(LED_PIN,HIGH);\n");break;
            case LED_OFF:out.append("  digitalWrite(LED_PIN,LOW);\n");break;
            case BEEP:out.append("  tone(SPEAKER,880,"+b.durationMs+");\n");break;
            case READ_DISTANCE:out.append("  Serial.println(distanceCm());\n");break;
            case READ_TRACKERS:out.append("  Serial.print(analogRead(TRACK_LEFT)); Serial.print(','); Serial.println(analogRead(TRACK_RIGHT));\n");break;
            default:break;
        }out.append("  delay(").append(b.durationMs).append(");\n");}
        return out.append("  drive(0,0); noTone(SPEAKER);\n  while (true) delay(1000); // Run once after reset\n}\n").toString();
    }
}
