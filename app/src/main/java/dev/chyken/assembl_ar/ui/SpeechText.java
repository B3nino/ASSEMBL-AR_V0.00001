package dev.chyken.assembl_ar.ui;

/** Plain speech input; preserve the HUD's richer formatting independently. */
public final class SpeechText {
    private SpeechText(){}
    public static String clean(String text){
        if(text==null)return "";
        return text.replaceAll("\\[([^]]+)\\]\\([^)]*\\)","$1").replaceAll("(?m)^\\s*#{1,6}\\s*","")
                .replaceAll("[`*_•●▪→✓]","").replaceAll("(?m)^\\s*[-+]\\s+","")
                .replaceAll("[\\r\\n\\t]+"," ").replaceAll("\\s+"," ").trim();
    }
}
