package dev.chyken.assembl_ar.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.Layout;
import android.text.StaticLayout;
import android.util.AttributeSet;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.model.MotorWireColorConfig;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate.TargetProjection;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog.Action;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog.Demo;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog.Relationship;

/** Instruction-only diagram. Its authored coordinates are never mapped onto the camera image. */
public final class AssemblyDemoView extends View {
    private static final long DURATION_MILLIS = 5400;
    private static final int BACKGROUND = Color.rgb(241, 246, 237);
    private static final int INK = Color.rgb(40, 52, 35);
    private static final int MUTED = Color.rgb(81, 98, 72);
    private static final int GREEN = Color.rgb(63, 125, 20);
    private static final int BOARD = Color.rgb(220, 230, 210);
    private static final int GOLD = Color.rgb(241, 174, 53);
    private static final ExecutorService IMAGE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(4 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF scene = new RectF();
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    @Nullable private Demo demo;
    @Nullable private Bitmap reference;
    private int imageGeneration;
    private float progress;
    private boolean explicitlyPaused;
    @Nullable private MotorWireColorConfig motorWireColors;
    private List<PinConnection> motorConnections=java.util.Collections.emptyList();
    @Nullable private java.util.function.Consumer<Terminal> wireSelected;
    private Terminal selectedWire=Terminal.LEFT_POSITIVE;
    @Nullable private MotorWiringArtworkRenderer wiringArtwork;
    private boolean wireTouch,expandedSchematic;
    @Nullable private dev.chyken.assembl_ar.model.DriverWireColorConfig driverWireColors;
    @Nullable private DriverSchematicRenderer driverArtwork;
    private dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire selectedDriverWire=dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire.VCC;
    @Nullable private java.util.function.Consumer<dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire> driverWireSelected;
    private int driverFocus;
    public void setExpandedSchematic(boolean value){expandedSchematic=value;invalidate();}
    public void setDriverWireFocus(int focus){driverFocus=focus;invalidate();}
    public void setDriverWireColors(@NonNull dev.chyken.assembl_ar.model.DriverWireColorConfig colors){
        boolean entering=driverWireColors==null;driverWireColors=colors;motorWireColors=null;demo=null;reference=null;imageGeneration++;
        if(driverArtwork==null)driverArtwork=new DriverSchematicRenderer(this);
        StringBuilder text=new StringBuilder("Driver to Arduino Nano. USB connector at bottom. ");
        for(dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire wire:dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire.values())text.append(wire.spoken()).append(", ").append(colors.swatch(wire).label).append(" wire. ");
        setContentDescription(text);if(entering){animator.cancel();progress=0;animator.setRepeatCount(ValueAnimator.INFINITE);}startOrResume();invalidate();
    }
    public void setSelectedDriverWire(dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire value){selectedDriverWire=value;invalidate();}
    public void setOnDriverWireSelected(@Nullable java.util.function.Consumer<dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire> listener){driverWireSelected=listener;setClickable(listener!=null||hasOnClickListeners());}
    public List<PinConnection> getDriverWireConnections(){return dev.chyken.assembl_ar.data.NeobotSeedData.getPhase6PinConnections(driverWireColors==null?dev.chyken.assembl_ar.model.DriverWireColorConfig.defaults():driverWireColors);}
    public boolean isDriverArtworkReady(){return driverArtwork!=null&&driverArtwork.ready();}

    public AssemblyDemoView(@NonNull Context context) { this(context, null); }

    public AssemblyDemoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AssemblyDemoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        animator.setDuration(DURATION_MILLIS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    /** Replaces the instruction and starts its demonstration from the beginning when visible. */
    public void setDemo(@Nullable Demo value) {
        animator.cancel();
        driverWireColors=null;driverWireSelected=null;motorWireColors=null;motorConnections=java.util.Collections.emptyList();wireSelected=null;setClickable(false);animator.setRepeatCount(0);
        demo = value;
        progress = 0f;
        reference = null;
        imageGeneration++;
        if (value == null) {
            setContentDescription(null);
        } else {
            StringBuilder description = new StringBuilder("Instruction demonstration. ")
                    .append(value.getSpokenInstruction());
            if (!value.getTargets().isEmpty()) {
                description.append(" Schematic in reference orientation. ");
                for (TargetProjection target : value.getTargets()) {
                    description.append(target.getLabel()).append(". ");
                }
                if (value.getRelationship() != Relationship.NONE) {
                    description.append(value.getAttachmentCaption()).append(". ");
                }
            }
            for (PinConnection pin : value.getPinConnections()) {
                description.append(' ').append(componentLabel(pin.getSourceComponentId()))
                        .append(' ').append(pin.getSourcePin()).append(" to ")
                        .append(componentLabel(pin.getTargetComponentId())).append(' ')
                        .append(pin.getTargetPin()).append('.');
            }
            setContentDescription(description);
            if (!value.getTargets().isEmpty() || !hasReadablePinDiagram()) {
                loadReference(value.getReferenceImagePath(), imageGeneration);
            }
            startOrResume();
        }
        invalidate();
    }

    /** Replays this instruction, without changing any assembly completion or recognition state. */
    public void replay() {
        animator.cancel();
        progress = 0f;
        explicitlyPaused = false;
        startOrResume();
        invalidate();
    }

    public void pause() {
        explicitlyPaused = true;
        animator.pause();
    }

    public void resume() {
        explicitlyPaused = false;
        startOrResume();
    }

    private boolean reduceMotion() {
        AccessibilityManager manager = (AccessibilityManager) getContext()
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        return !ValueAnimator.areAnimatorsEnabled()
                || manager != null && manager.isTouchExplorationEnabled();
    }

    private void startOrResume() {
        if (demo == null && motorWireColors==null && driverWireColors==null || explicitlyPaused) return;
        if (reduceMotion()) {
            animator.cancel();
            progress = 1f;
            invalidate();
            return;
        }
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) return;
        if (animator.isPaused()) animator.resume();
        else if (!animator.isStarted() && (progress < 1f || motorWireColors!=null || driverWireColors!=null)) animator.start();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startOrResume();
    }

    @Override protected void onDetachedFromWindow() {
        // A paused ValueAnimator can remain registered with AnimationHandler and retain
        // this view/Activity. Detached screens must release that registration completely.
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (animator == null) return; // View construction can invoke this before field initialization.
        if (isShown()) startOrResume();
        else animator.pause();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (animator == null) return;
        if (visibility == VISIBLE) startOrResume();
        else animator.pause();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(Math.round(dp(320)), widthMeasureSpec),
                resolveSize(Math.round(dp(236)), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        fill(BACKGROUND);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(14), dp(14), paint);
        if(driverWireColors!=null){driverArtwork.draw(canvas,getWidth(),getHeight(),driverWireColors,selectedDriverWire,driverFocus,progress,!reduceMotion());return;}
        if(motorWireColors!=null){drawMotorWiring(canvas);return;}
        if (demo == null || getWidth() < dp(80) || getHeight() < dp(90)) return;
        float inset = dp(12);
        text(canvas, demo.getRelationship() != Relationship.NONE ? "BOARD SLOT DETAIL · FRONT ↑"
                        : !demo.getTargets().isEmpty() ? "PLACEMENT SCHEMATIC"
                        : hasReadablePinDiagram() ? "PIN CONNECTION SCHEMATIC"
                        : demo.getAction() == Action.TIGHTEN ? "FASTENER SCHEMATIC · SEE MANUAL"
                        : "MANUAL ILLUSTRATION", inset, dp(20), getWidth() - 2 * inset,
                10, MUTED, true);
        scene.set(inset, dp(34), getWidth() - inset, getHeight() - dp(47));
        int save = canvas.save();
        canvas.clipRect(scene);
        if (!demo.getTargets().isEmpty()) drawPlacement(canvas, demo.getTargets());
        else if (hasReadablePinDiagram()) drawPins(canvas, demo.getPinConnections());
        else if (demo.getAction() == Action.TIGHTEN) drawFastener(canvas);
        else drawReference(canvas);
        canvas.restoreToCount(save);
        drawPhase(canvas);
    }

    /** Independent custom schematic; never projected onto the live camera or played as a video. */
    public void setMotorWireColors(@NonNull MotorWireColorConfig colors){
        boolean entering=motorWireColors==null;driverWireColors=null;motorWireColors=colors;motorConnections=NeobotSeedData.getPhase4PinConnections(colors);demo=null;reference=null;imageGeneration++;
        StringBuilder description=new StringBuilder("Motor wiring using component illustrations. Green screw blocks toward you; six small header pins away. Inner motor tabs face the board center. Motor tabs have no marked polarity. ");
        for(Terminal t:Terminal.values())description.append(t.spoken()).append(", ").append(colors.swatch(t).label).append(" wire to ").append(t.output).append(". ");
        if(wiringArtwork==null)wiringArtwork=new MotorWiringArtworkRenderer(this);
        setContentDescription(description);if(entering){animator.cancel();progress=0;animator.setRepeatCount(ValueAnimator.INFINITE);}startOrResume();invalidate();
    }
    public void setSelectedMotorWire(Terminal terminal){selectedWire=terminal;invalidate();}
    public void setOnMotorWireSelected(@Nullable java.util.function.Consumer<Terminal> listener){wireSelected=listener;setClickable(listener!=null);}
    public List<PinConnection> getMotorWireConnections(){return motorConnections;}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(driverWireColors!=null&&driverWireSelected!=null){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){wireTouch=driverArtwork.hit(event.getX(),event.getY())!=null;return wireTouch;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP&&wireTouch){wireTouch=false;dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire wire=driverArtwork.hit(event.getX(),event.getY());if(wire!=null){selectedDriverWire=wire;performClick();driverWireSelected.accept(wire);invalidate();}return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)wireTouch=false;return true;
        }
        if(motorWireColors==null||wireSelected==null)return super.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){wireTouch=hitWire(event.getX(),event.getY())!=null;return wireTouch;}
        if(event.getActionMasked()==MotionEvent.ACTION_UP&&wireTouch){wireTouch=false;Terminal terminal=hitWire(event.getX(),event.getY());if(terminal!=null){selectedWire=terminal;performClick();wireSelected.accept(terminal);invalidate();}return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)wireTouch=false;return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private Terminal hitWire(float x,float y){return wiringArtwork==null?null:wiringArtwork.hit(x,y);}
    /** Artwork and pin hit targets share the same fitted/cropped/rotated transform. */
    private void drawMotorWiring(Canvas canvas){
        if(wiringArtwork!=null)wiringArtwork.draw(canvas,getWidth(),getHeight(),motorWireColors,
                selectedWire,wireSelected!=null,progress,!reduceMotion(),expandedSchematic);
    }
    public boolean isMotorWiringArtworkReady(){return wiringArtwork!=null&&wiringArtwork.isReady();}

    private void drawPlacement(Canvas canvas, List<TargetProjection> targets) {
        // Reserve space around the unit square: authored outboard targets must not be clamped.
        float side = Math.min(scene.width() / 1.65f, (scene.height() - dp(18)) / 1.16f);
        float boardWidth = side * .84f;
        float boardCenterX = scene.left + scene.width() * .68f;
        RectF board = new RectF(boardCenterX - boardWidth / 2,
                scene.top + dp(17), boardCenterX + boardWidth / 2,
                scene.top + dp(17) + side);
        boolean detail = demo.getRelationship() != Relationship.NONE;
        if (detail) {
            // On a compact camera overlay, enlarge the slot row rather than shrinking all
            // four supports into an unreadable whole-board thumbnail. This is a cropped
            // instruction diagram in canonical orientation, never a camera-space projection.
            boardWidth = scene.width() * .61f;
            side = boardWidth / .84f;
            float slotRow = scene.top + (scene.height() - dp(18)) * .77f;
            board.set(boardCenterX - boardWidth / 2, slotRow - side * .63f,
                    boardCenterX + boardWidth / 2, slotRow + side * .37f);
        }
        // Keep the real source illustration beside the abstract target so beginners can
        // recognize the component and relate the diagram to the physical assembly.
        RectF fullScene = new RectF(scene);
        scene.right = scene.left + scene.width() * .36f;
        scene.bottom -= dp(20);
        drawReference(canvas);
        scene.set(fullScene);
        fill(BOARD);
        canvas.drawRoundRect(board, dp(7), dp(7), paint);
        stroke(Color.rgb(149, 168, 136), 1);
        canvas.drawRoundRect(board, dp(7), dp(7), paint);
        textCentered(canvas, demo.getAnchorLabel(), boardCenterX, scene.top + dp(10),
                scene.width() * .57f, 10, MUTED, true);
        if (!detail) {
            textCentered(canvas, "FRONT ↑", boardCenterX, board.top + dp(11),
                    board.width(), 8, MUTED, true);
        }
        drawSupportContext(canvas, board);
        float motion = clamp((progress - .18f) / .55f);
        float travel = detail ? Math.min(side * .23f, (scene.height() - dp(18)) * .32f)
                : side * .23f;
        for (int index = 0; index < targets.size(); index++) {
            TargetProjection target = targets.get(index);
            boolean moving = isAnimatedTarget(target);
            float targetX = board.left + (float) target.getCenter().getX() * board.width();
            float targetY = board.top + (float) target.getCenter().getY() * board.height();
            if (demo.getRelationship() == Relationship.MOTOR_BETWEEN_BRACKETS) {
                // The recognizer's coarse footprint and the mounting relationship serve
                // different purposes. The schematic body belongs between the support faces.
                targetX = motorCenterX(target, board);
            }
            float direction = target.getCenter().getX() < .5 ? -1f : 1f;
            // A bracket's tab drops into a board slot. A motor drops between fixed supports.
            boolean mechanicalRelationship = demo.getRelationship() != Relationship.NONE;
            float offsetX = moving && !mechanicalRelationship ? direction * side * .35f * (1f - motion) : 0f;
            float partTravel = travel;
            if (detail) {
                float topExtent = demo.getRelationship() == Relationship.BRACKET_IN_BOARD_SLOT
                        ? bracketHeight(board) : motorHalfHeight(board);
                partTravel = Math.min(travel, Math.max(0, targetY - scene.top - topExtent - dp(2)));
            }
            float offsetY = moving ? -partTravel * (1f - motion) : 0f;
            if (!detail) {
                buildTargetPath(target, board, 0, 0);
                stroke(GREEN, 1.5f);
                paint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
                canvas.drawPath(path, paint);
                paint.setPathEffect(null);
            }
            if (moving && motion < .92f) {
                drawArrow(canvas, targetX + (mechanicalRelationship ? 0 : direction * side * .32f),
                        targetY - partTravel,
                        targetX, targetY, GREEN);
            }
            int color = moving && progress < .74f ? GOLD : Color.rgb(138, 183, 103);
            if (demo.getRelationship() == Relationship.BRACKET_IN_BOARD_SLOT) {
                drawBracket(canvas, targetX + offsetX, targetY + offsetY, board, color);
            } else if (demo.getRelationship() == Relationship.MOTOR_BETWEEN_BRACKETS) {
                drawMotor(canvas, target, board, offsetX, offsetY, color);
            } else {
                buildTargetPath(target, board, offsetX, offsetY);
                fill(color);
                canvas.drawPath(path, paint);
                stroke(moving && progress < .74f ? Color.rgb(136, 93, 16) : GREEN, 1.5f);
                canvas.drawPath(path, paint);
            }
            fill(INK);
            boolean bracket = demo.getRelationship() == Relationship.BRACKET_IN_BOARD_SLOT;
            float badgeX = targetX + (bracket ? 0 : offsetX);
            float badgeY = bracket ? targetY + dp(10) : targetY + offsetY;
            canvas.drawCircle(badgeX, badgeY, dp(bracket ? 6 : 7), paint);
            int label = bracket ? bracketNumber(target) : index + 1;
            textCentered(canvas, String.valueOf(label), badgeX,
                    badgeY + dp(3), dp(13), 8, Color.WHITE, true);
        }
        // The part name is outside the board so a small target never hides the instruction.
        text(canvas, demo.getAttachmentCaption(), scene.left, scene.bottom - dp(2), scene.width(),
                11, INK, true);
    }

    private boolean isAnimatedTarget(TargetProjection target) {
        for (TargetProjection candidate : demo.getAnimatedTargets()) {
            if (candidate.getId().equals(target.getId())) return true;
        }
        return false;
    }

    private int bracketNumber(TargetProjection target) {
        List<TargetProjection> slots = demo.getContextTargets();
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index).getId().equals(target.getId())) return index + 1;
        }
        return 1;
    }

    private void drawSupportContext(Canvas canvas, RectF board) {
        List<TargetProjection> slots = demo.getContextTargets();
        int currentBracket = demo.getRelationship() == Relationship.BRACKET_IN_BOARD_SLOT
                && !demo.getTargets().isEmpty() ? bracketNumber(demo.getTargets().get(0)) : 5;
        for (int index = 0; index < slots.size(); index++) {
            TargetProjection slot = slots.get(index);
            float x = board.left + (float) slot.getCenter().getX() * board.width();
            float y = board.top + (float) slot.getCenter().getY() * board.height();
            // Every board slot remains visible, including when no motor has been installed yet.
            fill(Color.rgb(103, 119, 92));
            canvas.drawRoundRect(x - board.width() * .024f, y - dp(2),
                    x + board.width() * .024f, y + dp(2), dp(1), dp(1), paint);
            if (index + 1 < currentBracket) {
                drawBracket(canvas, x, y, board, Color.rgb(164, 184, 149));
            }
            textCentered(canvas, String.valueOf(index + 1), x, y + dp(13),
                    board.width() * .15f, 8, MUTED, true);
        }
    }

    /** Upright support with a narrow tab entering the board slot, drawn as a schematic. */
    private void drawBracket(Canvas canvas, float x, float slotY, RectF board, int color) {
        float halfWidth = board.width() * .035f;
        float height = bracketHeight(board);
        // Match the photographed black T-shaped bracket and its three long guide openings.
        path.reset();
        path.moveTo(x - halfWidth, slotY - height);
        path.lineTo(x + halfWidth, slotY - height);
        path.lineTo(x + halfWidth, slotY - height * .19f);
        path.lineTo(x + halfWidth * 1.55f, slotY - height * .19f);
        path.lineTo(x + halfWidth * 1.55f, slotY);
        path.lineTo(x + halfWidth * .45f, slotY);
        path.lineTo(x + halfWidth * .45f, slotY + dp(3));
        path.lineTo(x - halfWidth * .45f, slotY + dp(3));
        path.lineTo(x - halfWidth * .45f, slotY);
        path.lineTo(x - halfWidth * 1.55f, slotY);
        path.lineTo(x - halfWidth * 1.55f, slotY - height * .19f);
        path.lineTo(x - halfWidth, slotY - height * .19f);
        path.close();
        fill(Color.rgb(43, 49, 43));
        canvas.drawPath(path, paint);
        stroke(color == GOLD ? GOLD : GREEN, color == GOLD ? 1.5f : 1);
        canvas.drawPath(path, paint);
        fill(BOARD);
        float holeHalfWidth = Math.max(dp(1), halfWidth * .18f);
        float leftHole = x - halfWidth * .32f, rightHole = x + halfWidth * .42f;
        canvas.drawRoundRect(leftHole - holeHalfWidth, slotY - height * .86f,
                leftHole + holeHalfWidth, slotY - height * .61f, dp(1), dp(1), paint);
        canvas.drawRoundRect(leftHole - holeHalfWidth, slotY - height * .48f,
                leftHole + holeHalfWidth, slotY - height * .24f, dp(1), dp(1), paint);
        canvas.drawRoundRect(rightHole - holeHalfWidth, slotY - height * .69f,
                rightHole + holeHalfWidth, slotY - height * .39f, dp(1), dp(1), paint);
    }

    private float bracketHeight(RectF board) { return Math.min(dp(28), board.height() * .15f); }

    private float motorHalfHeight(RectF board) {
        return Math.min(dp(25), Math.min(board.height() * .15f, (scene.height() - dp(18)) * .30f));
    }

    /** Motor body, outward shaft and inward leads make the mounting relationship recognizable. */
    private void drawMotor(Canvas canvas, TargetProjection target, RectF board,
                           float offsetX, float offsetY, int color) {
        float x = motorCenterX(target, board) + offsetX;
        float y = board.top + (float) target.getCenter().getY() * board.height() + offsetY;
        float halfWidth = board.width() * .05f, halfHeight = motorHalfHeight(board);
        boolean left = target.getCenter().getX() < .5;
        fill(color);
        canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight,
                dp(4), dp(4), paint);
        stroke(GREEN, 1.2f);
        canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight,
                dp(4), dp(4), paint);
        fill(Color.rgb(127, 140, 119));
        canvas.drawRoundRect(x - halfWidth * .8f, y - halfHeight,
                x + halfWidth * .8f, y - halfHeight * .25f, dp(2), dp(2), paint);
        float direction = left ? -1f : 1f;
        stroke(INK, 3);
        canvas.drawLine(x + direction * halfWidth, y + halfHeight * .5f,
                x + direction * halfWidth * 1.8f, y + halfHeight * .5f, paint);
        // Red and black leads bend toward the center of the board on both mirrored motors.
        stroke(Color.rgb(174, 59, 45), 1.5f);
        canvas.drawLine(x - direction * halfWidth, y - halfHeight * .7f,
                x - direction * halfWidth * 1.9f, y - halfHeight * .85f, paint);
        stroke(INK, 1.5f);
        canvas.drawLine(x - direction * halfWidth, y - halfHeight * .4f,
                x - direction * halfWidth * 1.9f, y - halfHeight * .55f, paint);
    }

    private float motorCenterX(TargetProjection target, RectF board) {
        List<TargetProjection> supports = demo.getContextTargets();
        if (supports.size() == 4) {
            int first = target.getCenter().getX() < .5 ? 0 : 2;
            double middle = (supports.get(first).getCenter().getX()
                    + supports.get(first + 1).getCenter().getX()) / 2;
            return board.left + (float) middle * board.width();
        }
        return board.left + (float) target.getCenter().getX() * board.width();
    }

    private void buildTargetPath(TargetProjection target, RectF board, float offsetX, float offsetY) {
        path.reset();
        List<VisionPoint> corners = target.getCorners();
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint point = corners.get(index);
            float x = board.left + (float) point.getX() * board.width() + offsetX;
            float y = board.top + (float) point.getY() * board.height() + offsetY;
            if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
    }

    private void drawPins(Canvas canvas, List<PinConnection> pins) {
        float boxWidth = scene.width() * .37f;
        float leftEnd = scene.left + boxWidth;
        float rightStart = scene.right - boxWidth;
        text(canvas, componentLabel(pins.get(0).getSourceComponentId()), scene.left + dp(5),
                scene.top + dp(12), boxWidth - dp(10), 9, MUTED, true);
        text(canvas, componentLabel(pins.get(0).getTargetComponentId()), rightStart + dp(5),
                scene.top + dp(12), boxWidth - dp(10), 9, MUTED, true);
        float rowsTop = scene.top + dp(18);
        float rowHeight = (scene.bottom - rowsTop) / pins.size();
        for (int index = 0; index < pins.size(); index++) {
            PinConnection pin = pins.get(index);
            float center = rowsTop + rowHeight * (index + .5f);
            float boxHeight = Math.min(dp(72), rowHeight - dp(5));
            float top = center - boxHeight / 2;
            fill(Color.WHITE);
            canvas.drawRoundRect(scene.left, top, leftEnd, top + boxHeight, dp(6), dp(6), paint);
            canvas.drawRoundRect(rightStart, top, scene.right, top + boxHeight, dp(6), dp(6), paint);
            pinText(canvas, pin.getSourcePin(), scene.left + dp(5), center,
                    boxWidth - dp(10), boxHeight);
            pinText(canvas, pin.getTargetPin(), rightStart + dp(5), center,
                    boxWidth - dp(10), boxHeight);
            drawArrow(canvas, leftEnd + dp(4), center, rightStart - dp(4), center, GREEN);
            float location = clamp((progress - .1f) / .7f);
            fill(GOLD);
            canvas.drawCircle(leftEnd + dp(4) + (rightStart - leftEnd - dp(8)) * location,
                    center, dp(3), paint);
        }
    }

    private void pinText(Canvas canvas, String value, float left, float centerY,
                         float width, float height) {
        textPaint.setColor(INK);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                12, getResources().getDisplayMetrics()));
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), textPaint,
                        Math.max(1, (int) width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(Math.max(1, (int) (height / textPaint.getFontSpacing())))
                .setEllipsize(TextUtils.TruncateAt.END).build();
        int save = canvas.save();
        canvas.translate(left, centerY - layout.getHeight() / 2f);
        layout.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void drawReference(Canvas canvas) {
        fill(Color.WHITE);
        canvas.drawRoundRect(scene, dp(8), dp(8), paint);
        if (reference == null) {
            textCentered(canvas, demo.getPartLabel(), scene.centerX(), scene.centerY(),
                    scene.width() - dp(20), 14, INK, true);
            textCentered(canvas, "Follow the current instruction", scene.centerX(),
                    scene.centerY() + dp(23), scene.width() - dp(20), 11, MUTED, false);
            return;
        }
        float scale = Math.min((scene.width() - dp(8)) / reference.getWidth(),
                (scene.height() - dp(8)) / reference.getHeight());
        float width = reference.getWidth() * scale, height = reference.getHeight() * scale;
        RectF bounds = new RectF(scene.centerX() - width / 2, scene.centerY() - height / 2,
                scene.centerX() + width / 2, scene.centerY() + height / 2);
        fill(Color.WHITE);
        canvas.drawBitmap(reference, null, bounds, paint);
        // No coordinate-level arrow is drawn on an unannotated manual image.
    }

    private void drawFastener(Canvas canvas) {
        RectF fullScene = new RectF(scene);
        scene.left = fullScene.left + fullScene.width() * .48f;
        drawReference(canvas);
        scene.set(fullScene);
        float centerX = scene.left + scene.width() * .22f;
        float top = scene.top + scene.height() * .20f;
        float boardY = scene.top + scene.height() * .45f;
        float studBottom = scene.top + scene.height() * .75f;
        fill(Color.rgb(121, 133, 116));
        canvas.drawRoundRect(centerX - dp(6), top, centerX + dp(6), studBottom, dp(2), dp(2), paint);
        for (float y = boardY; y < studBottom; y += dp(5)) {
            stroke(INK, 1);
            canvas.drawLine(centerX - dp(6), y + dp(3), centerX + dp(6), y, paint);
        }
        fill(BOARD);
        canvas.drawRoundRect(centerX - dp(30), boardY - dp(4), centerX + dp(30), boardY + dp(4),
                dp(2), dp(2), paint);
        fill(INK);
        canvas.drawRoundRect(centerX - dp(13), top - dp(4), centerX + dp(13), top + dp(5),
                dp(3), dp(3), paint);
        float motion = clamp((progress - .18f) / .56f);
        float nutY = boardY + dp(12) + dp(17) * (1f - motion);
        fill(GOLD);
        canvas.drawRoundRect(centerX - dp(13), nutY - dp(6), centerX + dp(13), nutY + dp(6),
                dp(3), dp(3), paint);
        if (motion < .95f) {
            drawArrow(canvas, centerX + dp(24), nutY + dp(5), centerX + dp(24), boardY + dp(7), GREEN);
        }
        textCentered(canvas, "Fasten securely", centerX, scene.bottom - dp(2),
                scene.width() * .45f, 10, INK, true);
    }

    private boolean hasReadablePinDiagram() {
        // Whole-section summaries can contain ten routes. Preserve their source illustration
        // instead of shrinking endpoint labels beyond readability in this compact viewport.
        return demo != null && !demo.getPinConnections().isEmpty() && demo.getPinConnections().size() <= 4;
    }

    private void drawPhase(Canvas canvas) {
        String phase;
        if (!demo.getTargets().isEmpty()) {
            phase = demo.getAnimatedTargets().isEmpty() ? "Check the fitted parts directly"
                    : progress < .18f ? "1 · Find the highlighted target"
                    : progress < .74f ? "2 · Move the part to its target"
                    : "3 · Check its fit directly";
        } else if (hasReadablePinDiagram()) {
            phase = progress < .25f ? "1 · Find the named pins"
                    : progress < .74f ? "2 · Connect the matching endpoints"
                    : "3 · Check both pin labels";
        } else {
            phase = demo.getAction() == Action.CHECK ? "Inspect the pictured assembly"
                    : demo.getAction() == Action.TIGHTEN ? "Fasten as shown in the manual"
                    : "Follow the pictured action";
        }
        text(canvas, phase, dp(12), getHeight() - dp(23), getWidth() - dp(24), 12, INK, true);
        float width = (getWidth() - dp(30)) / 3;
        for (int index = 0; index < 3; index++) {
            fill(progress >= index / 3f ? GREEN : Color.rgb(201, 215, 190));
            float left = dp(12) + index * (width + dp(3));
            canvas.drawRoundRect(left, getHeight() - dp(12), left + width,
                    getHeight() - dp(8), dp(2), dp(2), paint);
        }
    }

    private void drawArrow(Canvas canvas, float fromX, float fromY, float toX, float toY, int color) {
        stroke(color, 2);
        canvas.drawLine(fromX, fromY, toX, toY, paint);
        double angle = Math.atan2(toY - fromY, toX - fromX);
        float length = dp(6);
        path.reset();
        path.moveTo(toX - length * (float) Math.cos(angle - .6),
                toY - length * (float) Math.sin(angle - .6));
        path.lineTo(toX, toY);
        path.lineTo(toX - length * (float) Math.cos(angle + .6),
                toY - length * (float) Math.sin(angle + .6));
        canvas.drawPath(path, paint);
    }

    private void text(Canvas canvas, String value, float x, float baseline, float width,
                      float sizeSp, int color, boolean bold) {
        textPaint.setColor(color);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                sizeSp, getResources().getDisplayMetrics()));
        textPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        CharSequence fitted = TextUtils.ellipsize(value == null ? "" : value, textPaint,
                Math.max(0, width), TextUtils.TruncateAt.END);
        canvas.drawText(fitted.toString(), x, baseline, textPaint);
    }

    private void textCentered(Canvas canvas, String value, float centerX, float baseline, float width,
                              float sizeSp, int color, boolean bold) {
        textPaint.setTextAlign(Paint.Align.CENTER);
        text(canvas, value, centerX, baseline, width, sizeSp, color, bold);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setPathEffect(null);
    }

    private void stroke(int color, float widthDp) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(widthDp));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    private static String componentLabel(String id) {
        String label = id.replace('_', ' ').replace('-', ' ');
        return label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private void loadReference(@Nullable String path, int generation) {
        if (path == null || path.isEmpty()) return;
        Bitmap cached = IMAGES.get(path);
        if (cached != null) {
            reference = cached;
            return;
        }
        Context context = getContext().getApplicationContext();
        WeakReference<AssemblyDemoView> weakView = new WeakReference<>(this);
        IMAGE_EXECUTOR.execute(() -> {
            Bitmap bitmap = decodeBounded(context, path);
            if (bitmap == null) return;
            IMAGES.put(path, bitmap);
            AssemblyDemoView view = weakView.get();
            if (view != null) view.post(() -> {
                if (view.imageGeneration == generation) {
                    view.reference = bitmap;
                    view.invalidate();
                }
            });
        });
    }

    @Nullable private static Bitmap decodeBounded(Context context, String path) {
        String normalized = path.startsWith("assets/") ? path.substring(7) : path;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = context.getAssets().open(normalized)) {
            BitmapFactory.decodeStream(input, null, options);
        } catch (IOException ignored) {
            return null;
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 800) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        try (InputStream input = context.getAssets().open(normalized)) {
            return BitmapFactory.decodeStream(input, null, options);
        } catch (IOException ignored) {
            return null;
        }
    }
}
