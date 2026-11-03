/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.frameprocessors;

import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;

/**
 * Renders speech-to-text subtitles onto each frame. Selected with -S / --subtitle-frames, and applied in the effect
 * chain like any other frame processor.
 */
public class FrameSubtitler extends FrameProcessor {

    public FrameSubtitler() {
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameSubtitler requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameSubtitler requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 1) {
            throw new Exception("FrameSubtitler requires at least one image channel");
        }

        Subtitle subs = info.subs();
        // paste these onto the current frame

    }
}