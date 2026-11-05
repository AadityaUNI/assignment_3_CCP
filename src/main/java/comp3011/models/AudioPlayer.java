/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.models;

import java.nio.Buffer;
import java.nio.ShortBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.bytedeco.javacv.Frame;

/** Plays decoded audio through Java Sound, converting 16-bit samples to interleaved little-endian PCM. */
public class AudioPlayer implements AutoCloseable {
    private static final long WRITE_TRACE_THRESHOLD_NS = 2_000_000L;
    private static final int AUDIO_WARMUP_BYTES = 4096;

    private SourceDataLine line; // Java Sound's audio-output interface - for us a destination, not a source.
    private byte[] outputBuffer = new byte[0];

    public void open(int sampleRate, int channels) throws LineUnavailableException {
        if (sampleRate <= 0 || channels <= 0) {
            return;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();
        warmUpLine();
    }

    public byte[] copySamples(Frame frame) {
        if (frame.samples == null || frame.samples.length == 0) {
            return new byte[0];
        }

        int byteCount = copySamplesToOutputBuffer(frame);
        if (byteCount <= 0) {
            return new byte[0];
        }

        byte[] samples = new byte[byteCount];
        System.arraycopy(outputBuffer, 0, samples, 0, byteCount);
        return samples;
    }

    public int write(byte[] samples, int offset, int length) {
        if (line == null || samples.length == 0 || length <= 0) {
            return 0;
        }

        // only write what fits so line.write never blocks; the caller keeps the rest for later
        int availableBeforeWrite = line.available();
        int byteCount = Math.min(length, availableBeforeWrite);
        if (byteCount <= 0) {
            return 0;
        }

        long startNs = System.nanoTime();
        int written = line.write(samples, offset, byteCount); // Will block if we get it wrong.
        long elapsedNs = System.nanoTime() - startNs;
        if (elapsedNs >= WRITE_TRACE_THRESHOLD_NS) {
            // Hopefully you'll never see this.
            System.out.printf(
                    "WARNING: Audio write blocked for %dus writing %d bytes, line available before write: %d bytes%n",
                    elapsedNs / 1000,
                    written,
                    availableBeforeWrite);
        }
        return written;
    }

    public void flush() {
        if (line != null) {
            line.flush();
        }
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.flush();
            line.close();
            line = null;
        }
    }

    // Plays and flushes some silence so the sound card's wake-up delay is over before real audio arrives.
    private void warmUpLine() {
        byte[] silence = new byte[Math.min(AUDIO_WARMUP_BYTES, line.getBufferSize())]; // All zeros.
        line.write(silence, 0, silence.length); // May play, but harmless.
        line.flush(); // Get rid of the rest straight away, hopefully warm up is done.
    }

    // Handles interleaved audio (normal) and planar audio (one buffer per channel, needs interleaving).
    private int copySamplesToOutputBuffer(Frame frame) {
        Buffer[] samples = frame.samples;
        int channels = frame.audioChannels;

        if (channels > 1 && samples.length == channels && samples[0] instanceof ShortBuffer) {
            ShortBuffer[] planarSamples = new ShortBuffer[channels];
            int sampleCount = Integer.MAX_VALUE;

            for (int channel = 0; channel < channels; channel++) {
                if (!(samples[channel] instanceof ShortBuffer channelSamples)) {
                    return 0;
                }
                planarSamples[channel] = channelSamples.duplicate();
                sampleCount = Math.min(sampleCount, planarSamples[channel].remaining());
            }

            ensureOutputBufferCapacity(sampleCount * channels * Short.BYTES);
            return copyPlanarSamples(planarSamples, sampleCount);
        }

        if (samples[0] instanceof ShortBuffer packedSamples) {
            ShortBuffer source = packedSamples.duplicate();
            ensureOutputBufferCapacity(source.remaining() * Short.BYTES);
            return copyPackedSamples(source);
        }

        return 0;
    }

    private int copyPackedSamples(ShortBuffer source) {
        int outputIndex = 0;
        while (source.hasRemaining()) {
            short sample = source.get();
            outputBuffer[outputIndex++] = (byte) (sample & 0xff);
            outputBuffer[outputIndex++] = (byte) ((sample >> 8) & 0xff);
        }
        return outputIndex;
    }

    private int copyPlanarSamples(ShortBuffer[] planarSamples, int sampleCount) {
        int outputIndex = 0;
        for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
            for (ShortBuffer channelSamples : planarSamples) {
                short sample = channelSamples.get();
                outputBuffer[outputIndex++] = (byte) (sample & 0xff);
                outputBuffer[outputIndex++] = (byte) ((sample >> 8) & 0xff);
            }
        }
        return outputIndex;
    }

    private void ensureOutputBufferCapacity(int byteCount) {
        if (outputBuffer.length < byteCount) {
            outputBuffer = new byte[byteCount];
        }
    }

}
