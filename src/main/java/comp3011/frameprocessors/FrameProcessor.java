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

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;

/** Base for all effects: initialised once per video, then process() per frame. Must be deterministic per frame. */
public abstract class FrameProcessor {
    protected InfoVideo videoInfo;

    public FrameProcessor() {
    }

    public void initialise(InfoVideo videoInfo) throws Exception {
        this.videoInfo = videoInfo;
    }

    // A seed unique to this media and effect position: looks random but stays deterministic.
    protected int saltedMediaSeed(String mediaName, int instanceIndex) {
        return hashMediaName(mediaName) + instanceIndex;
    }

    private int hashMediaName(String mediaName) {
        CRC32 crc = new CRC32();
        byte[] bytes = mediaName.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    public abstract void process(Frame frame, InfoFrame info) throws Exception;
}
