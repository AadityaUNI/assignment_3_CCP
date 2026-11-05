/*
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.models;

/** Load-test hook told the nanoTime timings of every frame shown; runs on the FX thread, so keep it quick. */
@FunctionalInterface
public interface FrameTimingListener {
    /** The default: playback is not being measured. */
    FrameTimingListener NONE = (frameNumber, targetTimeNs, preparedAtNs, shownAtNs) -> { };

    // headroom = target - prepared, lateness = shown - target
    void frameShown(int frameNumber, long targetTimeNs, long preparedAtNs, long shownAtNs);
}
