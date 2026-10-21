package dev.chyken.assembl_ar.ui;

/** Trace masks authored against the user's original 1224 × 1147 logo, in image pixels. */
public final class LogoFormationSpec {
    public static final int WIDTH=1224,HEIGHT=1147;
    public static final class Stroke {
        public final float start,end,width;
        public final float[] points;
        Stroke(float start,float end,float width,float... points){this.start=start;this.end=end;this.width=width;this.points=points;}
        public float progress(float time){return interval(time,start,end);}
    }
    public static final Stroke[] STROKES={
        new Stroke(0,.22f,38,397,252,344,252,329,267,329,324),
        new Stroke(.04f,.26f,38,835,252,889,252,905,268,905,324),
        new Stroke(.08f,.30f,38,329,680,329,734,345,753,397,753),
        new Stroke(.12f,.34f,38,835,753,889,753,905,734,905,680),
        new Stroke(.16f,.47f,42,370,380,616,305,844,380,590,465,370,380),
        new Stroke(.29f,.57f,42,370,380,370,621,580,718,733,672),
        new Stroke(.37f,.57f,42,590,465,590,628),
        new Stroke(.40f,.60f,42,844,380,844,455),
        new Stroke(.46f,.64f,42,580,663,580,718),
        new Stroke(.46f,.76f,76,620,625,680,493,742,621),
        new Stroke(.58f,.78f,60,650,588,723,588),
        new Stroke(.53f,.81f,76,753,490,820,490,852,500,859,529,842,557,796,568,796,624),
        new Stroke(.64f,.84f,76,827,568,871,625)
    };
    private LogoFormationSpec(){}
    public static float interval(float progress,float start,float end){return Math.max(0,Math.min(1,(progress-start)/(end-start)));}
    public static float wordmark(float progress){return interval(progress,.70f,.93f);}
    public static float tagline(float progress){return interval(progress,.87f,1);}
}

