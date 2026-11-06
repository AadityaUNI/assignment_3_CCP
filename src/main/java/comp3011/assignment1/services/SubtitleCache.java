/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 *
 * AI acknowledgement: the structure of this class (nested map, load on start, atomic save on close) was designed with
 * help from Claude (Anthropic).
 */
package comp3011.assignment1.services;

import comp3011.assignment1.models.SubtitleResponse;
import comp3011.frameprocessors.Subtitle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Media key -> chunk -> subtitles, saved to disk so repeat plays (even offline) skip OpenAI; unused keys expire. */
@Component
public class SubtitleCache {
    // 30 days since the key was last used.
    static final long TIME_TO_LIVE_SECONDS = 30L * 24 * 60 * 60;
    // Also saved this often while running, so a crash or a killed player loses at most ~30 s of new subtitles.
    static final long SAVE_INTERVAL_MILLIS = 30_000;

    // On disk: { "<media key>": { "expiry": <epoch s>, "segments": { "<chunk>": [ subtitles ] } } }
    record StoredMedia(long expiry, Map<Long, Subtitle[]> segments) {
    }

    // One video's chunks; concurrent/volatile because several request threads use it at once.
    private static final class MediaEntry {
        final ConcurrentHashMap<Long, SubtitleResponse> segments = new ConcurrentHashMap<>();
        volatile long expiryEpochSec;
    }

    private final ConcurrentHashMap<String, MediaEntry> media = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Path file;
    private final Clock clock;
    private final AtomicLong lastSaveMillis = new AtomicLong(); // 0, so the first check after start saves

    // Spring's constructor (@Autowired picks it out of the two); file path is configurable.
    @Autowired
    public SubtitleCache(@Value("${subtitle.cache.file:cache/subtitle-cache.json}") String filePath) {
        this(Path.of(filePath), Clock.systemUTC());
    }

    // Tests pass their own file and clock so they don't touch the real cache and can test expiry.
    SubtitleCache(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    public Optional<SubtitleResponse> get(String mediaKey, long chunk) {
        MediaEntry entry = media.get(mediaKey);
        if (entry == null) {
            return Optional.empty();
        }
        SubtitleResponse hit = entry.segments.get(chunk);
        if (hit != null) {
            entry.expiryEpochSec = newExpiry(); // used again, so keep it longer
        }
        return Optional.ofNullable(hit);
    }

    public void put(String mediaKey, long chunk, SubtitleResponse response) {
        MediaEntry entry = media.computeIfAbsent(mediaKey, _ -> new MediaEntry());
        entry.segments.put(chunk, response);
        entry.expiryEpochSec = newExpiry();
    }

    // Checked on every request: saves if 30 s have passed; compareAndSet lets only one thread do it.
    public void saveIfDue() {
        long now = clock.millis();
        long last = lastSaveMillis.get();
        if (now - last >= SAVE_INTERVAL_MILLIS && lastSaveMillis.compareAndSet(last, now)) {
            save();
        }
    }

    private long newExpiry() {
        return clock.instant().getEpochSecond() + TIME_TO_LIVE_SECONDS;
    }

    // Never throws: a missing or broken file just means starting with an empty cache.
    @PostConstruct
    void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Map<String, StoredMedia> stored = json.readValue(file, new TypeReference<Map<String, StoredMedia>>() {
            });
            long now = clock.instant().getEpochSecond();
            for (Map.Entry<String, StoredMedia> item : stored.entrySet()) {
                StoredMedia storedMedia = item.getValue();
                if (storedMedia.expiry() < now) {
                    continue; // expired: leave it out, so it is gone from the file at the next save
                }
                MediaEntry entry = new MediaEntry();
                entry.expiryEpochSec = storedMedia.expiry();
                storedMedia.segments().forEach((chunk, subs) -> entry.segments.put(chunk, new SubtitleResponse(subs)));
                media.put(item.getKey(), entry);
            }
        } catch (Exception e) {
            System.err.println("Subtitle cache could not be read (" + e.getMessage() + "). Starting empty.");
            media.clear();
            keepUnreadableFile();
        }
    }

    // Moves the bad file aside, so the next save (an empty cache) can't overwrite and lose it.
    private void keepUnreadableFile() {
        Path backup = file.resolveSibling(file.getFileName() + ".unreadable");
        try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("Unreadable subtitle cache kept as " + backup.getFileName() + ".");
        } catch (IOException e) {
            System.err.println("Unreadable subtitle cache could not be moved aside (" + e.getMessage() + ").");
        }
    }

    // Write a temp file then rename it over the real one, so a crash mid-save can't corrupt it.
    // refined by claude: synchronized: a periodic save and the shutdown save would otherwise write the same .tmp file at once.
    @PreDestroy
    synchronized void save() {
        long now = clock.instant().getEpochSecond();
        Map<String, StoredMedia> snapshot = new HashMap<>();
        media.forEach((key, entry) -> {
            if (entry.expiryEpochSec < now) {
                return; // expired
            }
            Map<Long, Subtitle[]> segments = new HashMap<>();
            entry.segments.forEach((chunk, response) -> segments.put(chunk, response.subs()));
            snapshot.put(key, new StoredMedia(entry.expiryEpochSec, segments));
        });

        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            json.writeValue(temporary, snapshot);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            System.err.println("Subtitle cache could not be saved (" + e.getMessage() + ").");
        }
    }
}
