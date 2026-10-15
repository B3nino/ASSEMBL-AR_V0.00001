package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Real geometry from the 3 October 2026 installed-bracket replay: the native board corners and
 * the bracket-profile candidate each slot check accepted, in 480×640 analysis pixels. Before
 * this change every quad below passed its slot gate.
 */
public final class BracketSlotSurroundingsTest {
    private static final List<VisionPoint> BOARD_133359 = quad(
            p(75.54, 206.28), p(387.24, 217.97), p(399.97, 581.96), p(78.48, 599.08));
    private static final List<VisionPoint> BOARD_133541 = quad(
            p(110.75, 136.78), p(361.15, 143.84), p(377.53, 459.35), p(74.13, 448.67));
    private static final List<VisionPoint> BOARD_133543 = quad(
            p(96.47, 133.70), p(380.00, 131.18), p(364.73, 447.12), p(117.22, 450.39));
    /** Board rotated 180° in the image: canonical corner 0 is at the image's bottom right. */
    private static final List<VisionPoint> BOARD_133552 = quad(
            p(459.76, 502.30), p(66.47, 497.05), p(125.80, 219.88), p(378.26, 226.53));
    private static final List<VisionPoint> BOARD_133630 = quad(
            p(390.15, 464.81), p(116.95, 471.83), p(115.35, 138.75), p(383.94, 135.94));
    private static final List<VisionPoint> BOARD_133517 = quad(
            p(414.06, 560.98), p(-14.08, 569.28), p(60.48, 204.43), p(348.69, 195.98));
    /** Board turned sideways in the image. */
    private static final List<VisionPoint> BOARD_133641 = quad(
            p(100.46, 445.74), p(191.79, 189.71), p(501.67, 159.09), p(499.61, 442.78));

    @Test
    public void emptySlotThreeNoLongerAcceptsTheMicrocontrollerSocketRow() {
        List<VisionPoint> socketRow = quad(
                p(311.40, 441.75), p(320.32, 586.18), p(278.41, 588.77), p(269.49, 444.34));
        assertEquals("socket row", feature(3, BOARD_133359, socketRow));
        assertFalse(slot(3).evaluateSingle(BOARD_133359, socketRow).isAccepted());
    }

    @Test
    public void aDarkPatchMergedWithTheSocketRowIsStillTheSocketRow() {
        // Rotated board; the patch centres on slot 3's lane but covers the socket row.
        List<VisionPoint> board = quad(
                p(422.39, 502.67), p(66.40, 484.01), p(126.73, 155.00), p(393.26, 142.48));
        List<VisionPoint> merged = quad(
                p(157.98, 144.05), p(227.50, 150.04), p(215.57, 288.36), p(146.05, 282.37));
        assertEquals("socket row", feature(3, board, merged));
        assertFalse(slot(3).evaluateSingle(board, merged).isAccepted());
    }

    @Test
    public void emptySlotFourNoLongerAcceptsTheBackgroundBeyondTheBoardEdge() {
        List<VisionPoint> upright = quad(
                p(372.15, 276.46), p(400.71, 278.20), p(390.50, 446.70), p(361.94, 444.97));
        assertEquals("board edge", feature(4, BOARD_133543, upright));
        assertFalse(slot(4).evaluateSingle(BOARD_133543, upright).isAccepted());

        List<VisionPoint> rotated = quad(
                p(97.55, 218.51), p(140.23, 229.74), p(107.82, 352.90), p(65.14, 341.67));
        assertEquals("board edge", feature(4, BOARD_133552, rotated));
        assertFalse(slot(4).evaluateSingle(BOARD_133552, rotated).isAccepted());
    }

    @Test
    public void installedBracketThreeIsNoLongerAmbiguousWithTheCableSlotOrSocketRow() {
        List<VisionPoint> cableSlot = quad(
                p(297.81, 325.85), p(299.32, 391.93), p(287.10, 392.20), p(285.59, 326.12));
        List<VisionPoint> bracket = quad(
                p(328.98, 303.32), p(330.85, 366.98), p(306.43, 367.69), p(304.56, 304.04));
        assertEquals("opening or shadow", feature(3, BOARD_133541, cableSlot));
        assertFalse(slot(3).evaluateSingle(BOARD_133541, cableSlot).isAccepted());
        assertAccepted(3, BOARD_133541, bracket);
    }

    @Test
    public void realBracketsInEverySlotStillPass() {
        assertAccepted(1, BOARD_133543, quad(
                p(113.00, 314.00), p(113.00, 381.00), p(82.00, 381.00), p(82.00, 314.00)));
        assertAccepted(1, BOARD_133517, quad(
                p(376.69, 197.91), p(409.24, 208.62), p(375.14, 312.31), p(342.58, 301.60)));
        // Bracket 2 standing beside the left cable slot is wide enough to be a plate.
        assertAccepted(2, BOARD_133541, quad(
                p(180.03, 310.89), p(182.24, 370.44), p(150.76, 371.60), p(148.56, 312.05)));
        assertAccepted(2, BOARD_133552, quad(
                p(328.27, 223.07), p(348.54, 225.92), p(337.71, 303.08), p(317.44, 300.24)));
        assertAccepted(3, BOARD_133543, quad(
                p(311.31, 312.70), p(332.66, 313.30), p(330.63, 386.35), p(309.28, 385.76)));
        assertAccepted(3, BOARD_133630, quad(
                p(179.00, 223.00), p(179.00, 296.00), p(155.00, 296.00), p(155.00, 223.00)));
        assertAccepted(4, BOARD_133641, quad(
                p(423.66, 144.76), p(429.09, 196.89), p(324.89, 207.74), p(319.46, 155.62)));
    }

    @Test
    public void detectionsThatCannotBePlacedOnTheBoardPlaneFailClosed() {
        List<VisionPoint> board = quad(p(100, 300), p(300, 300), p(320, 400), p(80, 400));
        // Far above this foreshortened board's horizon.
        List<VisionPoint> aboveHorizon = quad(p(190, -900), p(210, -900), p(210, -800), p(190, -800));
        assertEquals("off-plane", feature(1, board, aboveHorizon));
    }

    /** 6 October 2026 held-out photos: empty slots that reached confirmation before this rule set. */
    @Test
    public void emptySlotFourNoLongerAcceptsTheDcJackAndItsShadow() {
        List<VisionPoint> board = quad(
                p(78.00, 168.00), p(377.00, 177.00), p(376.00, 523.00), p(81.00, 534.00));
        List<VisionPoint> jack = quad(
                p(405.00, 525.00), p(335.00, 524.00), p(336.00, 376.00), p(406.00, 377.00));
        assertEquals("DC jack", feature(4, board, jack));
        assertFalse(slot(4).evaluateSingle(board, jack).isAccepted());
    }

    @Test
    public void aShadowBelowTheSeatingLineIsNotASeatedBracket() {
        // Board turned sideways; the shadow past its right edge starts below the seating line.
        List<VisionPoint> board = quad(
                p(184.00, 389.00), p(191.00, 99.00), p(538.00, 110.00), p(532.00, 396.00));
        List<VisionPoint> shadow = quad(
                p(442.00, 104.00), p(447.00, 68.00), p(543.00, 81.00), p(538.00, 117.00));
        assertEquals("not seated", feature(4, board, shadow));
        assertFalse(slot(4).evaluateSingle(board, shadow).isAccepted());
    }

    @Test
    public void emptyTabHolesAndShadowSliversAreTooThinToBeABracket() {
        List<VisionPoint> tabHoleBoard = quad(
                p(110.00, 192.00), p(396.00, 144.00), p(439.00, 551.00), p(93.00, 546.00));
        List<VisionPoint> tabHole = quad(
                p(171.00, 428.00), p(158.00, 428.00), p(160.00, 353.00), p(173.00, 353.00));
        assertEquals("opening or shadow", feature(2, tabHoleBoard, tabHole));
        assertFalse(slot(2).evaluateSingle(tabHoleBoard, tabHole).isAccepted());

        List<VisionPoint> sliverBoard = quad(
                p(134.00, 246.00), p(364.00, 241.00), p(415.00, 533.00), p(90.00, 536.00));
        List<VisionPoint> sliver = quad(
                p(339.00, 321.00), p(350.00, 320.00), p(360.00, 398.00), p(349.00, 399.00));
        assertEquals("opening or shadow", feature(4, sliverBoard, sliver));
        assertFalse(slot(4).evaluateSingle(sliverBoard, sliver).isAccepted());
    }

    @Test
    public void realBracketsInTheSixOctoberPhotosStillPass() {
        assertAccepted(1, quad(p(77.93, 168.32), p(377.24, 177.19), p(376.37, 522.85), p(81.45, 533.54)),
                quad(p(47.00, 366.00), p(88.00, 366.00), p(88.00, 450.00), p(47.00, 450.00)));
        assertAccepted(2, quad(p(88.87, 202.42), p(355.92, 211.48), p(350.01, 515.58), p(93.78, 526.31)),
                quad(p(141.00, 382.00), p(160.00, 382.00), p(160.00, 454.00), p(141.00, 454.00)));
        List<VisionPoint> board221055 = quad(
                p(127.45, 200.45), p(395.24, 208.22), p(400.63, 532.59), p(125.56, 528.42));
        assertAccepted(3, board221055,
                quad(p(335.95, 373.45), p(356.44, 372.79), p(358.90, 448.87), p(338.40, 449.54)));
        // Outer plate projected past the board edge.
        assertAccepted(4, board221055,
                quad(p(403.64, 366.18), p(425.82, 365.56), p(428.19, 450.88), p(406.01, 451.50)));
        // The slot 4 plate closest to the DC jack zone.
        assertAccepted(4, quad(p(116.73, 155.02), p(321.80, 209.92), p(342.14, 492.86), p(93.60, 523.87)),
                quad(p(302.66, 332.31), p(347.46, 328.72), p(356.29, 439.10), p(311.49, 442.68)));
    }

    private static void assertAccepted(int slot, List<VisionPoint> board, List<VisionPoint> quad) {
        assertNull("slot " + slot + " plate", feature(slot, board, quad));
        assertTrue("slot " + slot + " plate passes its gate",
                slot(slot).evaluateSingle(board, quad).isAccepted());
    }

    private static String feature(int slot, List<VisionPoint> board, List<VisionPoint> detected) {
        double boardWidth = (board.get(0).distanceTo(board.get(1))
                + board.get(3).distanceTo(board.get(2))) * 0.5;
        return BracketSlotSurroundings.featureAt(board, detected, boardWidth,
                ChassisRelativePlacementGate.motorBracketSlotX(slot));
    }

    private static ChassisRelativePlacementGate slot(int slot) {
        return ChassisRelativePlacementGate.forMotorBracketSlot(slot);
    }

    private static List<VisionPoint> quad(VisionPoint... corners) {
        return Arrays.asList(corners);
    }

    private static VisionPoint p(double x, double y) {
        return new VisionPoint(x, y);
    }
}
