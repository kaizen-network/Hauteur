package io.github.maxlananas.hauteur.window;

/**
 * Pure coordinate math for the per-player height window, shared by the packet pipeline and mirrored
 * method-for-method by dev/verify/oracle.py.
 *
 * <p>Definitions:</p>
 * <ul>
 *   <li>Real space - Y coordinates as used by the Java server.</li>
 *   <li>Client space - what the Bedrock client believes; {@code clientY = realY - offset}.</li>
 *   <li>The offset is always a non-negative multiple of 16 so Java sections map onto exactly one
 *       Bedrock subchunk slot each; block X/Z and every relative quantity (deltas, motion, velocity,
 *       cursors, offsets) are never translated.</li>
 * </ul>
 *
 * <p>The client window is the Bedrock session's negotiated dimension: extended-height clients accept
 * [-512, 512), vanilla-era clients [0, 320), legacy [0, 256). Content that fits inside that window
 * needs no translation at all, which makes the extension a zero-work pass-through on ordinary servers.</p>
 */
public final class WindowRules {
    public static final int SECTION = 16;

    private WindowRules() {
    }

    /**
     * Highest placeable real block Y given the window policy: top client block + the cap offset,
     * never above the world's own last block.
     */
    public static int reachableBlockTop(int javaMaxY, int clientMinY, int clientHeight, int maxOffset) {
        return Math.min(javaMaxY - 1, clientMinY + clientHeight - 1 + Math.max(0, maxOffset));
    }

    public static int floorToSection(int y) {
        return Math.floorDiv(y, SECTION) * SECTION;
    }

    /**
     * Whether the real dimension [javaMinY, javaMaxY) exceeds the client window
     * [clientMinY, clientMinY + clientHeight) at either end. False means every coordinate is
     * already valid for the client and the offset must stay 0.
     */
    public static boolean windowingNeeded(int javaMinY, int javaMaxY, int clientMinY, int clientHeight) {
        return javaMinY < clientMinY || javaMaxY - 1 > clientMinY + clientHeight - 1;
    }

    /**
     * Largest offset that still keeps the top buildable layer (javaMaxY - 1) inside the window.
     * Offsets above this value would sink the world's roof below the client floor without making
     * any new area reachable, so they are never chosen.
     */
    public static int maxUsefulOffset(int javaMaxY, int clientMinY, int clientHeight) {
        return floorToSection(javaMaxY - clientMinY - clientHeight);
    }

    /**
     * Lowest (most negative) offset that still keeps the world's floor (javaMinY) inside the window, or 0 when the
     * floor already fits. A negative offset shows real Y below the client floor: {@code clientY = realY - offset}.
     * Lower offsets would lift the floor above the client floor without making any new area reachable.
     */
    public static int minUsefulOffset(int javaMinY, int clientMinY) {
        return Math.min(0, floorToSection(javaMinY - clientMinY));
    }

    /**
     * Offset that centers the window around {@code realY}: the window's client-space center is
     * {@code clientMinY + clientHeight / 2}, so an offset of {@code realY - center} puts the player
     * in the middle of the window and defers the next switch as long as possible. This centering is
     * also what makes switches naturally rare (once per ~450 climbed blocks at full window height)
     * without any hysteresis bookkeeping.
     */
    public static int targetOffset(double realY, int clientMinY, int clientHeight, int maxOffset) {
        return targetOffset(realY, clientMinY, clientHeight, 0, maxOffset);
    }

    /** As {@link #targetOffset(double, int, int, int)}, clamped to {@code [minOffset, maxOffset]}. */
    public static int targetOffset(double realY, int clientMinY, int clientHeight, int minOffset, int maxOffset) {
        if ((maxOffset <= 0 && minOffset >= 0) || !Double.isFinite(realY)) {
            return 0; // no usable position: keep the current frame's neutral choice
        }
        double centered = realY - (clientMinY + clientHeight / 2.0);
        // Snap and clamp in double precision, then narrow. An int product overflowed for |realY| above about
        // 2e9, which sent a far-away position to offset 0 while 1e10 went to the maximum.
        double snapped = Math.floor(centered / SECTION + 0.5) * SECTION;
        return (int) Math.max(Math.min(0, minOffset), Math.min(Math.max(0, maxOffset), snapped));
    }

    /**
     * A switch is requested when the player's feet approach the top or bottom edge of the window
     * (within {@code margin}) AND a different offset would actually help.
     */
    public static boolean shouldSwitch(double realY, int currentOffset, int clientMinY, int clientHeight,
                                       int margin, int maxOffset) {
        return shouldSwitch(realY, currentOffset, clientMinY, clientHeight, margin, 0, maxOffset);
    }

    /** As {@link #shouldSwitch(double, int, int, int, int, int)}, for an offset range {@code [minOffset, maxOffset]}. */
    public static boolean shouldSwitch(double realY, int currentOffset, int clientMinY, int clientHeight,
                                       int margin, int minOffset, int maxOffset) {
        // After a switch the client sits within 8 blocks of the window centre (targetOffset snaps to 16). A margin
        // of H/2 - 8 or more puts that band inside the trigger zone, so every section boundary re-triggered a switch
        // (measured: about 55 switches per 1000 blocks walked for H=256 and margin 128). The margin is therefore capped
        // so the band always stays clear of both edges. The default (64) is below the cap for every window height.
        int effectiveMargin = Math.max(0, Math.min(margin, clientHeight / 2 - SECTION));
        double clientY = realY - currentOffset;
        boolean nearTop = clientY >= clientMinY + clientHeight - effectiveMargin;
        boolean nearBottom = currentOffset > Math.min(0, minOffset) && clientY <= clientMinY + effectiveMargin;
        if (!nearTop && !nearBottom) {
            return false;
        }
        return targetOffset(realY, clientMinY, clientHeight, minOffset, maxOffset) != currentOffset;
    }

}
