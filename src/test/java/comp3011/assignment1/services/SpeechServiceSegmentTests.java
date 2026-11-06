package comp3011.assignment1.services;

import com.openai.client.OpenAIClient;
import com.openai.models.audio.transcriptions.TranscriptionSegment;
import com.openai.models.audio.transcriptions.TranscriptionWord;
import comp3011.frameprocessors.Subtitle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Small tests for SpeechService.processSegments using hand-built segments, tests built by AI with my directions. */
class SpeechServiceSegmentTests {

    // The client is never used by processSegments, so a mock is enough to build the service.
    private final SpeechService service = new SpeechService(mock(OpenAIClient.class), mock(SubtitleCache.class));

    private static TranscriptionSegment seg(String text, double start, double end, double noSpeech, double logProb) {
        return TranscriptionSegment.builder()
                .id(0).seek(0).temperature(0f).compressionRatio(1f).tokens(List.of())
                .text(text).start(start).end(end).noSpeechProb((float) noSpeech).avgLogprob((float) logProb)
                .build();
    }

    private static TranscriptionWord word(String w, double start, double end) {
        return TranscriptionWord.builder().word(w).start(start).end(end).build();
    }

    @Test
    void silentSegmentIsDroppedAndLaterWordsStayAligned() {
        // Segment 1 fails BOTH thresholds (noSpeech > 0.6 and logProb < -1.0), so it must be ignored.
        var segments = List.of(
                seg("hello there", 0, 1, 0.9, -2.0),
                seg("good morning.", 5, 6, 0.01, -0.2));
        // words[] is flat across segments; the silent segment's 2 words must be stepped over.
        var words = List.of(
                word("hello", 0, 0.5), word("there", 0.5, 1),
                word("good", 5, 5.5), word("morning.", 5.5, 6));

        Subtitle[] subs = service.processSegments(segments, words);

        assertTrue(subs.length >= 1);
        for (Subtitle s : subs) {
            assertFalse(s.text().contains("hello"), "silent segment text leaked into subtitles");
        }
        // Timings must come from the second segment's words, proving wordIndex was advanced correctly.
        for (Subtitle s : subs) {
            System.out.println(s.text());
            System.out.println(s.start());
            System.out.println(s.end());
        }
        assertEquals(5.0, subs[0].start(), 0.001);
        assertEquals(6.0, subs[subs.length - 1].end(), 0.001);
    }

    @Test
    void onlyOneThresholdFailingKeepsTheSegment() {
        // High noSpeechProb alone is not enough to drop it (logProb is fine), per the "both" rule.
        var segments = List.of(seg("still here", 0, 1, 0.9, -0.3));
        var words = List.of(word("still", 0, 0.5), word("here", 0.5, 1));

        Subtitle[] subs = service.processSegments(segments, words);

        assertTrue(subs.length >= 1);
        assertTrue(subs[0].text().contains("still"));
    }

    // ---- Break-rule tests: PUNCTUATOR_THRESHOLD = 3 words, MIN 1 s, MAX 7 s, 84 characters ----

    /** One word per token of text, each lasting `step` seconds, back to back from time 0. */
    private static List<TranscriptionWord> evenWords(String text, double step) {
        String[] tokens = text.split(" ");
        var list = new java.util.ArrayList<TranscriptionWord>();
        for (int i = 0; i < tokens.length; i++) {
            list.add(word(tokens[i], i * step, (i + 1) * step));
        }
        return list;
    }

    private static TranscriptionSegment clean(String text, double start, double end) {
        return seg(text, start, end, 0.01, -0.2); // passes the silence check
    }

    @Test
    void punctuationBreaksOnlyOnceThreeWordsAreBuffered() {
        // "there." is the 2nd word (below the threshold of 3) so no break; "friend," is the 4th, so break there.
        String text = "Hi there. my friend, how are you?";
        Subtitle[] subs = service.processSegments(List.of(clean(text, 0, 3.5)), evenWords(text, 0.5));

        assertEquals(2, subs.length);
        assertEquals("Hi there. my friend,", subs[0].text().trim());
        assertEquals(0.0, subs[0].start(), 0.001);
        assertEquals(2.0, subs[0].end(), 0.001);
        assertEquals("how are you?", subs[1].text().trim());
        assertEquals(2.0, subs[1].start(), 0.001);
        assertEquals(3.5, subs[1].end(), 0.001);
    }

    @Test
    void punctuationDoesNotBreakBeforeMinimumDuration() {
        // Enough words and punctuation, but the subtitle has only been on screen 0.3 s (< 1 s), so keep going.
        String text = "a b c. d e f.";
        Subtitle[] subs = service.processSegments(List.of(clean(text, 0, 0.6)), evenWords(text, 0.1));

        assertEquals(1, subs.length);
        assertEquals("a b c. d e f.", subs[0].text().trim());
    }

    @Test
    void wordCountCarriesAcrossSegments() {
        // split mid-sentence across segments: 4 words in total, so one subtitle spans both
        var segments = List.of(clean("Hello there", 0, 1), clean("my friend.", 1, 2));
        Subtitle[] subs = service.processSegments(segments, evenWords("Hello there my friend.", 0.5));

        assertEquals(1, subs.length);
        assertEquals("Hello there my friend.", subs[0].text().trim());
        assertEquals(0.0, subs[0].start(), 0.001);
        assertEquals(2.0, subs[0].end(), 0.001);
    }

    @Test
    void subtitleIsSplitWhenItWouldRunPastMaxDuration() {
        // No punctuation or pauses: "four" ends at 7.5 s, past the 7 s window from "one", so it starts a new one.
        var words = List.of(word("one", 0, 2), word("two", 2, 4), word("three", 4, 6.5), word("four", 6.5, 7.5));
        Subtitle[] subs = service.processSegments(List.of(clean("one two three four", 0, 7.5)), words);

        assertEquals(2, subs.length);
        assertEquals("one two three", subs[0].text().trim());
        assertEquals(6.5, subs[0].end(), 0.001);
        assertEquals("four", subs[1].text().trim());
        assertEquals(6.5, subs[1].start(), 0.001);
    }

    @Test
    void subtitleIsSplitBeforeTheWordThatReachesEightyFourCharacters() {
        // 12 x 8-letter words: the 10th would reach 84 chars (81 + 8), so 9 words then 3
        String text = String.join(" ", java.util.Collections.nCopies(12, "abcdefgh"));
        Subtitle[] subs = service.processSegments(List.of(clean(text, 0, 1.2)), evenWords(text, 0.1));

        assertEquals(2, subs.length);
        assertEquals(9, subs[0].text().trim().split(" ").length);
        assertEquals(3, subs[1].text().trim().split(" ").length);
        assertTrue(subs[0].text().trim().length() < 84);
    }

    // ---- Pause rule: PAUSE_THRESHOLD = 0.9 s ----
    // Gaps sit just either side of it (1.0 s splits, 0.8 s doesn't), so changing the threshold breaks a test.

    @Test
    void pauseStartsANewSubtitleEvenWithoutEnoughWordsForPunctuation() {
        // "Hi." is below the 3-word punctuation threshold, so only the 1.0 s pause can split it from "Who's there?".
        var words = List.of(word("Hi.", 0, 0.4), word("Who's", 1.4, 1.7), word("there?", 1.7, 2.2));
        Subtitle[] subs = service.processSegments(List.of(clean("Hi. Who's there?", 0, 2.2)), words);

        assertEquals(2, subs.length);
        assertEquals("Hi.", subs[0].text().trim());
        assertEquals(0.0, subs[0].start(), 0.001);
        assertEquals(0.4, subs[0].end(), 0.001);
        assertEquals("Who's there?", subs[1].text().trim());
        assertEquals(1.4, subs[1].start(), 0.001);
        assertEquals(2.2, subs[1].end(), 0.001);
    }

    @Test
    void pauseStartsANewSubtitleWithoutPunctuation() {
        // No punctuation and well inside 7 s, so only the 1.0 s pause after "we" can split it.
        var words = List.of(word("so", 0, 0.3), word("then", 0.3, 0.6), word("we", 0.6, 1.0),
                word("went", 2.0, 2.3), word("home", 2.3, 2.8));
        Subtitle[] subs = service.processSegments(List.of(clean("so then we went home", 0, 2.8)), words);

        assertEquals(2, subs.length);
        assertEquals("so then we", subs[0].text().trim());
        assertEquals(1.0, subs[0].end(), 0.001);
        assertEquals("went home", subs[1].text().trim());
        assertEquals(2.0, subs[1].start(), 0.001);
    }

    @Test
    void pauseAcrossASegmentBoundaryAlsoSplits() {
        // Whisper often ends a segment at a pause, so the text mustn't carry into the next one (1.0 s gap).
        var segments = List.of(clean("I know", 0, 0.8), clean("you do", 1.8, 2.4));
        var words = List.of(word("I", 0, 0.3), word("know", 0.3, 0.8), word("you", 1.8, 2.1), word("do", 2.1, 2.4));
        Subtitle[] subs = service.processSegments(segments, words);

        assertEquals(2, subs.length);
        assertEquals("I know", subs[0].text().trim());
        assertEquals("you do", subs[1].text().trim());
        assertEquals(1.8, subs[1].start(), 0.001);
    }

    @Test
    void gapJustUnderThePauseThresholdDoesNotSplit() {
        // 0.8 s between every word is under 0.9 s, so it stays one subtitle.
        var words = List.of(word("this", 0, 0.3), word("is", 1.1, 1.3), word("one", 2.1, 2.4),
                word("line", 3.2, 3.5));
        Subtitle[] subs = service.processSegments(List.of(clean("this is one line", 0, 3.5)), words);

        assertEquals(1, subs.length);
        assertEquals("this is one line", subs[0].text().trim());
    }
}
