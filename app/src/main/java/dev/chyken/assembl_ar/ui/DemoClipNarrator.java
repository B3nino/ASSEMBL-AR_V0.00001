package dev.chyken.assembl_ar.ui;

/** Main-thread speech policy for a demo playlist, independent of video and TTS engines. */
public final class DemoClipNarrator {
    public interface Voice {
        void speak(String text,String key);
        void stop();
    }
    private final String[] lines;
    private final Voice voice;
    private int clip=-1;
    private boolean playing;
    private String pendingKey;
    private long sequence;

    public DemoClipNarrator(String[] lines,Voice voice){
        this.lines=lines.clone();this.voice=voice;
    }
    /** Seeks replay the relevant instruction; ordinary position updates never repeat speech. */
    public void onPlayback(int clip,boolean playing,boolean seek){
        boolean changed=this.clip!=clip||this.playing!=playing||seek;
        this.clip=clip;this.playing=playing&&clip>=0&&clip<lines.length;
        if(!changed)return;
        if(this.playing)speak();else stopSpeech();
    }
    /** Unmute/engine readiness can arrive after the video has already started. */
    public void onVoiceAvailable(){if(playing)speak();}
    private void speak(){pendingKey="phase2:demo:"+clip+":"+(++sequence);voice.speak(lines[clip],pendingKey);}
    public boolean isPending(){return pendingKey!=null;}
    public boolean onSpeechFinished(String key){
        if(pendingKey==null||!pendingKey.equals(key))return false;
        pendingKey=null;return true;
    }
    private void stopSpeech(){pendingKey=null;voice.stop();}
    public void stop(){playing=false;clip=-1;stopSpeech();}
}
