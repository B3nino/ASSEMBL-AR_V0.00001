package dev.chyken.assembl_ar.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SpeechTextTest {
    @Test public void hudFormattingBecomesNaturalSingleLineSpeech(){
        assertEquals("Gather hardware. Locate two 10mm screws. Tap I Have These.",SpeechText.clean("## Gather **hardware**.\n• Locate two `10mm` screws.\n- Tap [I Have These](action://confirm)."));
    }
    @Test public void nullAndWhitespaceAreSafe(){assertEquals("",SpeechText.clean(null));assertEquals("First align pins.",SpeechText.clean(" First\r\n\talign   pins. "));}
}
