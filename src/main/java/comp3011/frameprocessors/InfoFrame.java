/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Aaditya Singh, A1946894
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.frameprocessors;

/** Per-frame info for effects. A class, not a record, so FrameSubtitler can set the overlay; one worker per frame. */
public final class InfoFrame {
    private final int frameNumber;
    private final long timestampUs;
    private final Subtitle subs; // the subtitle due at this frame's time, or null
    private SubtitleOverlay subtitleOverlay; // set by FrameSubtitler, null when nothing is being said

    public InfoFrame(int frameNumber, long timestampUs, Subtitle subs) {
        this.frameNumber = frameNumber;
        this.timestampUs = timestampUs;
        this.subs = subs;
    }

    public InfoFrame(int frameNumber, long timestampUs) {
        this(frameNumber, timestampUs, null); // backwards compatible
    }

    public int frameNumber() {
        return frameNumber;
    }

    public long timestampUs() {
        return timestampUs;
    }

    public Subtitle subs() {
        return subs;
    }

    public SubtitleOverlay subtitleOverlay() {
        return subtitleOverlay;
    }

    public void setSubtitleOverlay(SubtitleOverlay subtitleOverlay) {
        this.subtitleOverlay = subtitleOverlay;
    }
}
