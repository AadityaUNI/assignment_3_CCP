package comp3011.assignment1.services;

import com.openai.models.audio.transcriptions.*;
import comp3011.assignment1.exception.MultipleShutdownException;
import comp3011.assignment1.models.*;
import comp3011.frameprocessors.Subtitle;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

import com.openai.client.OpenAIClient;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.AudioResponseFormat;
import org.springframework.web.client.RestClient;

@Service
public class SpeechService {
    private static final int PUNCTUATOR_THRESHOLD = 3;
    // Assignment rule: a segment is treated as silence/noise when BOTH conditions hold.
    private static final double NO_SPEECH_PROB_LIMIT = 0.6;
    private static final double AVG_LOG_PROB_LIMIT = -1.0;
    private static final double MIN_SEG_DURATION = 1;
    private static final double MAX_SEG_DURATION = 7;
    private static final double TOLERANCE = 0.0009;
    private final OpenAIClient gpt;
    private final Clock clock;
    private final String startUTC;
    private final long startMillis;
    private AtomicLong totalInput = new AtomicLong(0);
    private AtomicLong totalOutput = new AtomicLong(0);
    AtomicBoolean requestingShutdown = new AtomicBoolean(false);
    // Total Whisper audio seconds billed so far. DoubleAdder is the JDK's thread-safe
    // double accumulator; many request threads can add to it at once without locking.
    private final DoubleAdder subtitleUsage = new DoubleAdder();

    private final SubtitleCache subtitleCache;

    SpeechService(OpenAIClient gptC, SubtitleCache subtitleCache) {
        gpt = gptC;
        this.subtitleCache = subtitleCache;
        clock = Clock.systemUTC();
        startUTC = clock.instant().toString();
        startMillis = clock.millis();
    }

    public double getSubtitleUsage() {
        return subtitleUsage.sum();
    }

    public String textFromSpeechHelper(byte[] audioBytes) throws IOException {
        try {
            Path path = Files.createTempFile("audioFile", ".webm");
            Files.write(path, audioBytes);
            TranscriptionCreateParams createParams = TranscriptionCreateParams.builder()
                    .file(path)
                    .model(AudioModel.WHISPER_1)
                    .build();

            Transcription transcription =
                    gpt.audio().transcriptions().create(createParams).asTranscription();

            // add tokens if attached to response
            transcription.usage().ifPresent(usage -> {
                usage.tokens().ifPresent(tokens -> {
                    totalInput.addAndGet(tokens.inputTokens());
                    totalOutput.addAndGet(tokens.outputTokens());
                });
            });

            return transcription.text();

        } catch (IOException e) {
            System.out.println("Unable to create temp file");
            throw new IOException("An unexpected server error occurred while processing speech.");        }
    }

    public UptimeResponse uptimeHelper() {
        return new UptimeResponse(startUTC, clock.instant().toString(), (double) (clock.millis() - startMillis) /1000);
    }

    public GlobalStatsResponse statsHelper() {
        return new GlobalStatsResponse(totalInput.get(), totalOutput.get());
    }

    public ResponseEntity<ShutdownResponse> shutdownHelper() {
        // returns true if value was changed, meaning we need to skip this for the true (first one)
        // and run for the rest
        if (!requestingShutdown.compareAndSet(false, true)) {
            throw new MultipleShutdownException();
        }

        RestClient shutdownClient = RestClient.create();
        shutdownClient.post().uri("http://localhost:8080/actuator/shutdown").retrieve().toBodilessEntity();
        String message = "Graceful shutdown requested.";
        return new ResponseEntity<ShutdownResponse>(new ShutdownResponse(message), HttpStatus.ACCEPTED);
    }

    // The media key comes from the player's file fingerprint ("<size>-<sha256 hex>-<chunk seconds>"), so only digits,
    // lowercase hex and dashes are accepted. Anything else is treated as "no key" and just skips the cache.
    private static final java.util.regex.Pattern MEDIA_KEY = java.util.regex.Pattern.compile("[0-9a-f-]{1,200}");

    // mediaKey and chunkIndex say which part of which video this audio is. When both are given and the answer is already
    // cached, it is returned without calling OpenAI (so it also works with no internet). Either one missing means
    // "don't use the cache", exactly as before the cache existed.
    public SubtitleResponse subtitleHelper(byte[] audioBytes, String mediaKey, Long chunkIndex) throws IOException {
        boolean cacheable = chunkIndex != null && mediaKey != null && MEDIA_KEY.matcher(mediaKey).matches();
        if (cacheable) {
            var cached = subtitleCache.get(mediaKey, chunkIndex);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        SubtitleResponse fresh = transcribeSubtitles(audioBytes);
        if (cacheable) {
            subtitleCache.put(mediaKey, chunkIndex, fresh); // only reached when OpenAI answered; errors throw above
        }
        return fresh;
    }

    private SubtitleResponse transcribeSubtitles(byte[] audioBytes) throws IOException {
        Path copy = null;
        try {
            Path path = Files.createTempFile("audioFile", ".wav");
            copy = path;
            Files.write(path, audioBytes);
            // Sent to https://api.openai.com/v1/audio/transcriptions. verbose_json is the only format that returns
            // timings; asking for both granularities gives per-segment timings and confidence values, plus per-word
            // timings. added parameter syntax with AI
            TranscriptionCreateParams createParams = TranscriptionCreateParams.builder()
                    .file(path)
                    .model(AudioModel.WHISPER_1)
                    .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                    .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.WORD)
                    .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.SEGMENT)
                    .build();

            // With verbose_json the response is the verbose variant, so asTranscription() would throw here.
            TranscriptionVerbose transcription =
                    gpt.audio().transcriptions().create(createParams).asVerbose();

            double seconds = transcription.usage().map(usage -> usage.seconds()).orElse(transcription.duration());
            subtitleUsage.add(seconds);

            // need to return a list of {string, start, end}
            var segments = transcription.segments();
            var words = transcription.words();
            if (segments.isPresent() && words.isPresent()) {
                return new SubtitleResponse(processSegments(segments.get(), words.get()));
            }
        } catch (IOException e) {
            System.out.println("Unable to create temp file");
            throw new IOException("An unexpected server error occurred while processing speech.");
        } finally {
            if (copy != null) {
                Files.deleteIfExists(copy);
            }
        }
        // Whisper found no segments/words (e.g. a silent chunk): an empty list tells the player "nothing to show here"
        // so it caches that and moves on, instead of getting an empty body and retrying forever.
        return new SubtitleResponse(new Subtitle[0]);
    }

    void finishSegment(List<Subtitle> processed, StringBuilder currText, double start, double end) {
        if (currText.length() == 0) {
            return; // nothing buffered, so no empty subtitle
        }
        processed.add(new Subtitle(currText.toString(), start, end));
        // Clear in place: assigning a new StringBuilder here would only change this method's local copy
        // of the reference, and the caller would keep the old text and add it to the next subtitle too.
        currText.setLength(0);
    }

    Subtitle[] processSegments(List<TranscriptionSegment> segments, List<TranscriptionWord> words) {
        // create arrays of subtitles, each having a start and end index and text,
        // init last start = 0, start by getting each segment, if segment threshold falls below silent, ignore this segment and all words in it
        // we build our own segments as follows:
        // keep a string curr text, start timestamp (last start)
        // segments appear as we add to the processed, so each segment's word's start is the same as the segment start
        // break on word if it ends with punctuation, and we have word count >= 4 but the end should be atleast one second after this word end
        // breaking: reset timestamp to the end of this word (looked through with word array)
        // break BEFORE word if char size after word is >= 84
        double lastSegStart = -1;
        double lastWordEnd = -1;
        int wordIndex = 0;
        int wordCount = 0; // words in the buffered subtitle; carries across segments like currText does
        StringBuilder currText = new StringBuilder();
        List<Subtitle> processed = new ArrayList<>();

        for (TranscriptionSegment seg : segments) {
            double segStart = seg.start(); double segEnd = seg.end();
            // process this duration
            String[] parts = seg.text().trim().split("\\s+");

            // Whisper is unsure there is speech here (and unsure of what it heard): drop the whole segment.
            // The words[] array is flat across all segments, so we must still step wordIndex past this
            // segment's words or every later segment would read the wrong word timings.
            if (seg.noSpeechProb() > NO_SPEECH_PROB_LIMIT && seg.avgLogprob() < AVG_LOG_PROB_LIMIT) {
                wordIndex += parts.length;
                continue;
            }

            for (String w : parts) {
                if (wordIndex >= words.size()) break; // incorrect index
                double wEnd = words.get(wordIndex).end(); double wStart = words.get(wordIndex).start();
                if (!inRange(wStart, wEnd, segStart - TOLERANCE, segEnd + TOLERANCE)) {
                    break; // next segment's words, caused by unequal length arrays
                }
                wordIndex++; // always process the current word.
                // word limit exceeded or word end doesn't fit the last segment duration, END PREV SEGMENT
                if (currText.toString().strip().length() + w.length() >= 84 || !inRange(wStart, wEnd, lastSegStart, lastSegStart + MAX_SEG_DURATION)) {
                    // end before this word
                    finishSegment(processed, currText, lastSegStart, lastWordEnd);
                    wordCount = 0; lastSegStart = -1; lastWordEnd = -1;
                }
                if (lastSegStart == -1) {
                    // not started the current segment
                    lastSegStart = wStart;
                }
                // can append word to current text
                currText.append(w).append(' ');
                wordCount++;
                lastWordEnd = wEnd;
                // check if current word is a punctuation end
                if (w.matches(".*[.,!?;:]") && wordCount >= PUNCTUATOR_THRESHOLD && wEnd >= lastSegStart + MIN_SEG_DURATION)  {
                    // ends with a punctuator, end current segment
                    finishSegment(processed, currText, lastSegStart, wEnd);
                    lastSegStart = -1; lastWordEnd = -1;
                    wordCount = 0;
                }
            }
            // currText might not be empty, passes into the next segment.
        }
        if (lastSegStart != -1) {
            finishSegment(processed, currText, lastSegStart, lastWordEnd);
        }
        return processed.toArray(new Subtitle[0]);
    }
    boolean inRange(double ws, double we, double a, double b) {
        return ws >= a && we <= b;
    }
}
