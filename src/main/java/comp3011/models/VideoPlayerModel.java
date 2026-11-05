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
package comp3011.models;

import java.io.File;

/** MVC model interface: multi-threaded by default, the single-threaded original with --single. */
public interface VideoPlayerModel {
    void play(File file);

    void startOver();

    void backFiveSeconds();

    void forwardFiveSeconds();

    void togglePause();

    void toggleAudioOutput();

    boolean isAudioOutputEnabled();

    void stopPlayback();

    void shutdown();

    boolean toggleSubtitles();

    // Measurement only: lets the load test time every frame shown. Pass FrameTimingListener.NONE to stop.
    void setFrameTimingListener(FrameTimingListener listener);
}
