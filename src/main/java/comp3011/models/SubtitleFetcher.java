/*
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.models;

import comp3011.assignment1.models.SubtitleResponse;
import comp3011.frameprocessors.Subtitle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import tools.jackson.databind.json.JsonMapper;

import static comp3011.models.MultiThreadedVideoPlayerModel.NO_SEEK_REQUEST;

class SubtitleFetcher {
    // Subtitle Worker Settings
    private final long segmentLength = 20_000_000L; // microseconds (20 s)
    private static final double MIN_SUBTITLE_SECONDS = 1.0; // the brief asks for subtitles to stay up at least 1 s
    // show subtitles slightly early: Whisper's start times often run late and readers need a moment
    private static final double SUBTITLE_LEAD_SECONDS = 0;
    private final int subThreads = 3;
    private final ConcurrentHashMap<Long, Subtitle[]> subtitleCache = new ConcurrentHashMap<>();
    // Chunks still to fetch, sorted so ceiling() finds the next one; a worker claims one by winning remove().
    private final ConcurrentSkipListSet<Long> pendingChunks = new ConcurrentSkipListSet<>();
    // Not final: stopSubtitleThreads() replaces it with a fresh pool. Only touched on the FX thread.
    private ExecutorService subWorkers = Executors.newFixedThreadPool(subThreads);

    // Audio thread decodes each chunk to 16 kHz mono 16-bit PCM for Whisper; arrays are never changed once stored.
    private static final int SUBTITLE_AUDIO_SAMPLE_RATE = 16_000;
    private static final long AUDIO_IDLE_SLEEP_MS = 200; // how often the audio decoder rechecks when nothing is ahead
    private static final long SUBTITLE_IDLE_SLEEP_MS = 200; // same for subtitle workers waiting for a chunk or its audio
    private static final long SUBTITLE_RETRY_MIN_MS = 1_000; // first wait after a failed request
    private static final long SUBTITLE_RETRY_MAX_MS = 5_000; // the wait doubles after each failure up to this
    private final ConcurrentHashMap<Long, byte[]> audioChunks = new ConcurrentHashMap<>();
    // Chunk indexes whose audio hasn't been decoded yet. Same grid as pendingChunks.
    private final ConcurrentSkipListSet<Long> pendingAudioChunks = new ConcurrentSkipListSet<>();
    // Started and stopped by the FX thread, like decodeThread. Never touches the playback grabber.
    private Thread audioDecodeThread;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    // JsonMapper is thread-safe once built, so every subtitle worker shares this one.
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final URI subtitleURI = URI.create("http://localhost:8080/api/v1/subtitles");
    // workers take the next uncaptioned chunk, send it to the API and cache the result

    // Server cache key for this video: null = not ready yet, "" = failed (send without a key).
    private volatile String mediaKey;

    private final Supplier<File> videoFile;
    private final LongSupplier videoDurationUs;
    private final LongSupplier currentTimestampUs;

    SubtitleFetcher(Supplier<File> videoFile, LongSupplier videoDurationUs, LongSupplier currentTimestampUs) {
        this.videoFile = videoFile;
        this.videoDurationUs = videoDurationUs;
        this.currentTimestampUs = currentTimestampUs;
    }

    // Fingerprinting reads up to 3 MB (slow off USB), so it gets its own thread; workers wait for it.
    void startMediaKeyThread(File file) {
        mediaKey = null;
        Thread thread = new Thread(() -> {
            String key;
            try {
                key = MediaFingerprint.compute(file);
            } catch (Exception e) {
                key = ""; // e.g. the file disappeared: subtitles still work, they just aren't cached
            }
            if (videoFile.get() == file) { // ignore the answer if another file was opened meanwhile
                mediaKey = key;
            }
        }, "media-fingerprint");
        thread.setDaemon(true);
        thread.start();
    }

    // Queues every chunk not done yet (none if the length is unknown); only called while the threads are stopped.
    void populatePendingChunks() {
        pendingChunks.clear();
        pendingAudioChunks.clear();
        if (videoDurationUs.getAsLong() == NO_SEEK_REQUEST) {
            return;
        }

        long chunkCount = (videoDurationUs.getAsLong() + segmentLength - 1) / segmentLength; // round up: the last partial chunk counts
        for (long chunk = 0; chunk < chunkCount; chunk++) {
            if (!subtitleCache.containsKey(chunk)) {
                pendingChunks.add(chunk);
                // Only decode audio for chunks that still need subtitles; cached ones had their audio dropped.
                if (!audioChunks.containsKey(chunk)) {
                    pendingAudioChunks.add(chunk);
                }
            }
        }
    }

    void startAudioDecodeThread() {
        audioDecodeThread = new Thread(this::runAudioDecodeLoop, "subtitle-audio-decoder");
        audioDecodeThread.setDaemon(true);
        audioDecodeThread.setPriority(Thread.NORM_PRIORITY - 1); // playback comes first
        audioDecodeThread.start();
    }

    // Interrupt and join, like stopDecodeThread(). Quick: the loop checks for interrupt after every audio frame.
    void stopAudioDecodeThread() {
        if (audioDecodeThread == null) {
            return;
        }
        audioDecodeThread.interrupt();
        try {
            audioDecodeThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        audioDecodeThread = null;
    }

    // Always decodes the next pending chunk from the playhead on (so seeks jump ahead); sleeps if none are ahead.
    private void runAudioDecodeLoop() {
        try (FFmpegFrameGrabber audioGrabber = new FFmpegFrameGrabber(videoFile.get())) {
            // Output format must be set before start(): FFmpeg sets up its resampler there.
            audioGrabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            audioGrabber.setSampleRate(SUBTITLE_AUDIO_SAMPLE_RATE);
            audioGrabber.setAudioChannels(1);
            audioGrabber.start();
            if (!audioGrabber.hasAudio()) {
                return;
            }

            while (!Thread.currentThread().isInterrupted() && !pendingAudioChunks.isEmpty()) {
                long currentChunk = currentTimestampUs.getAsLong() / segmentLength;
                Long next = pendingAudioChunks.ceiling(currentChunk);
                if (next == null) {
                    Thread.sleep(AUDIO_IDLE_SLEEP_MS); // throws if interrupted, which ends the loop
                    continue;
                }
                if (pendingAudioChunks.remove(next)) { // only one claimer can win a chunk
                    audioChunks.put(next, decodeAudioChunk(audioGrabber, next));
                }
            }
        } catch (InterruptedException e) {
            // Stopped by stopAudioDecodeThread(). The half-done chunk is not stored, so the next populate re-adds it.
        } catch (Exception e) {
            // e.g. the media file was removed. Playback handles that itself; here we just stop decoding.
            System.err.println("Subtitle audio decoding stopped: " + e);
        }
    }

    // One chunk's audio as 16-bit LE PCM: seek to its start, stop at the next chunk (written with help from Claude)
    private byte[] decodeAudioChunk(FFmpegFrameGrabber audioGrabber, long chunk) throws Exception {
        long chunkStartUs = chunk * segmentLength;
        long chunkEndUs = chunkStartUs + segmentLength;
        audioGrabber.setTimestamp(chunkStartUs);

        int expectedBytes = (int) (segmentLength / 1_000_000L) * SUBTITLE_AUDIO_SAMPLE_RATE * 2; // 2 bytes per sample
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(expectedBytes);
        Frame frame;
        while ((frame = audioGrabber.grabSamples()) != null) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (frame.timestamp >= chunkEndUs) {
                return pcm.toByteArray(); // reached the next chunk, so this one is complete
            }
            if (frame.timestamp < chunkStartUs || frame.samples == null) {
                continue;
            }

            ShortBuffer samples = (ShortBuffer) frame.samples[0];
            ByteBuffer bytes = ByteBuffer.allocate(samples.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asShortBuffer().put(samples.duplicate()); // duplicate(): don't move the grabber's buffer position
            pcm.write(bytes.array(), 0, bytes.capacity());
        }
        // null also means the file became unreadable (USB pulled); only the last chunk may end early
        boolean lastChunk = chunkEndUs >= videoDurationUs.getAsLong();
        if (!lastChunk || !videoFile.get().exists()) {
            throw new IOException("Media file could not be read during chunk " + chunk);
        }
        return pcm.toByteArray();
    }

    void startSubtitlesThreads() {
        for (int i = 0; i < subThreads; i++) {
            subWorkers.submit(this::subtitleLoop);
        }
    }

    // Runs until interrupted, even when nothing is pending, since failed chunks come back to be retried.
    private void subtitleLoop() {
        long retryDelayMs = SUBTITLE_RETRY_MIN_MS; // this worker's own backoff; reset to the minimum after a success
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // take the next one
                long currentChunk = currentTimestampUs.getAsLong() / segmentLength;
                Long next = pendingChunks.ceiling(currentChunk);
                // needs the media key and decoded audio; only the worker whose remove() wins takes the chunk
                if (mediaKey != null && next != null && audioChunks.containsKey(next) && pendingChunks.remove(next)) {
                    boolean succeeded = false;
                    try {
                        succeeded = processChunk(next);
                    } catch (Exception e) {
                        System.out.println(e.toString());
                    }
                    if (succeeded) {
                        retryDelayMs = SUBTITLE_RETRY_MIN_MS;
                    } else {
                        // failed (chunk already re-queued): back off 1, 2, 4, then 5 s max so recovery stays quick
                        Thread.sleep(retryDelayMs);
                        retryDelayMs = Math.min(retryDelayMs * 2, SUBTITLE_RETRY_MAX_MS);
                    }
                } else {
                    Thread.sleep(SUBTITLE_IDLE_SLEEP_MS); // nothing ready yet: wait instead of spinning
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // stopping: just leave
        }
    }

    // Sends one chunk to our API: true if cached, false if it was re-queued for a retry.
    private boolean processChunk(Long next) {
        // next is the starting of the segment
        byte[] segment = audioChunks.get(next);
        byte[] wavBytes = convertWav(segment);
        HttpRequest.Builder builder = HttpRequest.newBuilder(subtitleURI)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofByteArray(wavBytes));
        // lets the server answer from its cache; chunk length is in the key since it changes what a chunk is
        String key = mediaKey;
        if (key != null && !key.isEmpty()) {
            builder.header("X-Media-Key", key + "-" + segmentLength / 1_000_000L);
            builder.header("X-Chunk-Index", Long.toString(next));
        }
        HttpRequest request = builder.build();

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                // a malformed body throws into the catch below, so the chunk is retried
                Subtitle[] parsed = JSON.readValue(response.body(), SubtitleResponse.class).subs();
                // times come back relative to the chunk, so shift them to whole-file time (chunk 2: +40 s)
                double chunkStartSec = next * segmentLength / 1_000_000.0;
                Subtitle[] shifted = new Subtitle[parsed.length];
                for (int i = 0; i < parsed.length; i++) {
                    shifted[i] = new Subtitle(parsed[i].text(), parsed[i].start() + chunkStartSec,
                            parsed[i].end() + chunkStartSec);
                }
                subtitleCache.put(next, shifted);
                audioChunks.remove(next); // subtitles are cached, so the raw audio (about 640 KB per chunk) isn't needed
                return true;
            }
            // Any non-200 (503 no internet, 502 key problem, 422 bad audio, ...) is retried later with a backoff.
            pendingChunks.add(next);
            return false;
        } catch (InterruptedException e) {
            // send() clears the interrupt flag when it throws this, so set it again or subtitleLoop would never stop
            pendingChunks.add(next);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            pendingChunks.add(next); // server not running, timeout, or an unreadable reply
            return false;
        }
    }

    // Whisper needs a real file, so prepend a 44-byte WAV header to the raw PCM (layout with help from Claude).
    private byte[] convertWav(byte[] segment) {
        final int channels = 1;
        final int bitsPerSample = 16;
        final int blockAlign = channels * bitsPerSample / 8;            // bytes per sample frame (2)
        final int byteRate = SUBTITLE_AUDIO_SAMPLE_RATE * blockAlign;   // bytes per second of audio (32,000)

        ByteBuffer wav = ByteBuffer.allocate(44 + segment.length).order(ByteOrder.LITTLE_ENDIAN); // WAV is little-endian

        // RIFF chunk: says "this is a WAVE file" and how big the rest of it is.
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(36 + segment.length);          // file size minus the 8 bytes of "RIFF" + this field
        wav.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // fmt chunk: how to read the samples.
        wav.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(16);                           // size of this fmt chunk for plain PCM
        wav.putShort((short) 1);                  // audio format 1 = uncompressed PCM
        wav.putShort((short) channels);
        wav.putInt(SUBTITLE_AUDIO_SAMPLE_RATE);
        wav.putInt(byteRate);
        wav.putShort((short) blockAlign);
        wav.putShort((short) bitsPerSample);

        // data chunk: the samples themselves, copied unchanged.
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(segment.length);
        wav.put(segment);

        return wav.array();
    }

    // Interrupts the workers, waits at most 500 ms (FX thread), then makes a fresh pool for the next start.
    void stopSubtitleThreads() {
        ExecutorService oldWorkers = subWorkers;
        oldWorkers.shutdownNow(); // interrupts running loops and drops tasks that haven't started
        try {
            if (!oldWorkers.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                System.err.println("Subtitle workers still finishing after stop; continuing without them.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        subWorkers = Executors.newFixedThreadPool(subThreads);
    }

    // Subtitle showing at this media time, or null (also null while its chunk hasn't arrived).
    Subtitle findSubtitleAt(long timestampUs) {
        Subtitle[] chunk = subtitleCache.get(timestampUs / segmentLength);
        if (chunk == null) {
            return null;
        }
        double seconds = timestampUs / 1_000_000.0; // Subtitle times are in seconds
        for (int i = 0; i < chunk.length; i++) {
            Subtitle subtitle = chunk[i];
            double shownUntil = subtitle.end();
            double minimumUntil = subtitle.start() + MIN_SUBTITLE_SECONDS;
            if (shownUntil < minimumUntil) {
                // Too short to read: hold it for the minimum time, but never into the next subtitle's start.
                double limit = i + 1 < chunk.length ? Math.min(minimumUntil, chunk[i + 1].start()) : minimumUntil;
                shownUntil = Math.max(shownUntil, limit);
            }
            // first match wins, so the lead never cuts the previous subtitle short
            if (seconds >= subtitle.start() - SUBTITLE_LEAD_SECONDS && seconds <= shownUntil) {
                return subtitle;
            }
        }
        return null;
    }
}
