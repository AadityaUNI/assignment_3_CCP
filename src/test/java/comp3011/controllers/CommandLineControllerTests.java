/*
 * Tests for the command line parsing in comp3011.controllers.CommandLineController.
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CommandLineControllerTests {

    @TempDir
    Path tempDir;

    // The parser only checks the file exists, so an empty placeholder keeps the tests independent of a real movie.mp4.
    private String video() throws IOException {
        Path movie = tempDir.resolve("movie.mp4");
        if (!Files.exists(movie)) {
            Files.createFile(movie);
        }
        return movie.toString();
    }

    private static List<String> processorNames(CommandLineController clc) {
        return clc.getProcessors().stream().map(p -> p.getClass().getSimpleName()).toList();
    }

    @Test
    void generalStackedParse() throws IOException {
        // single chained argument, order preserved
        CommandLineController chained = new CommandLineController(new String[]{"-wsd", video()});
        assertNull(chained.getErrorMessage());
        assertEquals(List.of("FrameBlackAndWhiter", "FrameScratcher", "FrameDuster"), processorNames(chained));

        // chain mixed with long-form effect, separate short option and other flags
        CommandLineController mixed = new CommandLineController(
                new String[]{"-nyp", "--jitter-frames", "-a", "-x", "-2", "-v", video()});
        assertNull(mixed.getErrorMessage());
        assertEquals(0, mixed.getExitCode());
        assertEquals(List.of("FrameNumberer", "FrameYellower", "FramePepperer", "FrameJitterer", "FrameVignetter"),
                processorNames(mixed));
        assertEquals(true, mixed.isAudioRequested());
        assertEquals(true, mixed.isMaximiseRequested());
        assertEquals(2, mixed.getDisplayId());
        assertNotNull(mixed.getVideoFile());
    }

    @Test
    void repeatedEffectsParse() throws IOException {
        // the same effect can be given more than once, and each use gets its own processor
        CommandLineController repeated = new CommandLineController(new String[]{"-sns", video()});
        assertNull(repeated.getErrorMessage());
        assertEquals(List.of("FrameScratcher", "FrameNumberer", "FrameScratcher"), processorNames(repeated));
        assertNotSame(repeated.getProcessors().get(0), repeated.getProcessors().get(2));
    }

    @Test
    void threadingModeParse() throws IOException {
        // multi-threaded is the default
        CommandLineController defaultMode = new CommandLineController(new String[]{"-s", video()});
        assertNull(defaultMode.getErrorMessage());
        assertEquals(false, defaultMode.isSingleThreadingRequested());
        assertEquals(List.of("FrameScratcher"), processorNames(defaultMode));

        // --single selects the original player and is not mistaken for an effect chain
        CommandLineController single = new CommandLineController(new String[]{"--single", "-s", video()});
        assertNull(single.getErrorMessage());
        assertEquals(true, single.isSingleThreadingRequested());
        assertEquals(List.of("FrameScratcher"), processorNames(single));
    }

    @Test
    void helpWithoutFile() {
        // help on its own needs no video file
        CommandLineController helpOnly = new CommandLineController(new String[]{"--help"});
        assertNull(helpOnly.getErrorMessage());
        assertEquals(0, helpOnly.getExitCode());

        // effects with no video file are an error even when help is requested
        CommandLineController effectsAndHelp = new CommandLineController(new String[]{"--number-frames", "-h"});
        assertEquals("No video file specified.", effectsAndHelp.getErrorMessage());
        assertEquals(1, effectsAndHelp.getExitCode());
    }

    @Test
    void invalidParse() throws IOException {
        // singular argument stacked in with effects is not a valid chain
        CommandLineController singularMixed = new CommandLineController(new String[]{"-wax", video()});
        assertEquals("Unknown option: -wax", singularMixed.getErrorMessage());
        assertEquals(1, singularMixed.getExitCode());
        assertEquals(false, singularMixed.shouldLaunchApplication());
        assertEquals(true, singularMixed.getProcessors().isEmpty());

        // unknown effect letter inside an otherwise valid chain
        CommandLineController badEffect = new CommandLineController(new String[]{"-wsz", video()});
        assertEquals("Unknown option: -wsz", badEffect.getErrorMessage());
        assertEquals(1, badEffect.getExitCode());
        assertEquals(true, badEffect.getProcessors().isEmpty());
    }

    @Test
    void subtitleFramesParse() throws IOException {
        // short form on its own turns subtitles on
        CommandLineController shortForm = new CommandLineController(new String[]{"-S", video()});
        assertNull(shortForm.getErrorMessage());
        assertEquals(List.of("FrameSubtitler"), processorNames(shortForm));
        assertEquals(true, shortForm.isSubtitlesRequested());

        // long form behaves the same
        CommandLineController longForm = new CommandLineController(new String[]{"--subtitle-frames", video()});
        assertNull(longForm.getErrorMessage());
        assertEquals(List.of("FrameSubtitler"), processorNames(longForm));
        assertEquals(true, longForm.isSubtitlesRequested());

        // S can be stacked in a chain with other effects and keeps its place in the order
        CommandLineController chained = new CommandLineController(new String[]{"-wSv", "-a", video()});
        assertNull(chained.getErrorMessage());
        assertEquals(List.of("FrameBlackAndWhiter", "FrameSubtitler", "FrameVignetter"), processorNames(chained));
        assertEquals(true, chained.isSubtitlesRequested());
        assertEquals(true, chained.isAudioRequested());
    }

    @Test
    void subtitleLetterIsCaseSensitive() throws IOException {
        // lowercase -s is still scratch, so subtitles must start switched off
        CommandLineController scratch = new CommandLineController(new String[]{"-s", video()});
        assertNull(scratch.getErrorMessage());
        assertEquals(List.of("FrameScratcher"), processorNames(scratch));
        assertEquals(false, scratch.isSubtitlesRequested());

        // both letters in one chain give one of each, in order
        CommandLineController both = new CommandLineController(new String[]{"-sS", video()});
        assertNull(both.getErrorMessage());
        assertEquals(List.of("FrameScratcher", "FrameSubtitler"), processorNames(both));
        assertEquals(true, both.isSubtitlesRequested());
    }

}
