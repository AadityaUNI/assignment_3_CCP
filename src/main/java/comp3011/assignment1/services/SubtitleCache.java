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

/**
 * Remembers Whisper's answers so a repeat request never calls OpenAI again. That makes the second viewing of a video
 * (especially after seeking forward) instant, and lets subtitles that were seen before keep showing when the internet
 * is down.
 *
 * <p>
 * Shape: media key -> (chunk index -> subtitles). The media key is made by the player from the file's contents, so any
 * client playing the same file shares the same entries. Each media key also has an expiry time so the file on disk
 * can't grow forever: every use pushes it back, and a key nobody has used for {@link #TIME_TO_LIVE_SECONDS} is
 * dropped the next time the cache is loaded or saved.
 * </p>
 *
 * <p>
 * The cache is read from disk once when the server starts and written once when it closes. The server runs inside the
 * player, so closing the player writes it. A crash loses only what was added during that run.
 * </p>
 */
@Component
public class SubtitleCache {
    // 30 days since the key was last used.
    static final long TIME_TO_LIVE_SECONDS = 30L * 24 * 60 * 60;

    // Shape of the file: { "<media key>": { "expiry": <epoch seconds>, "segments": { "<chunk>": [ subtitles ] } } }
    // Plain records so Jackson can read and write them with no extra setup.
    record StoredMedia(long expiry, Map<Long, Subtitle[]> segments) {
    }

    // One media key's chunks plus when it expires. The map is concurrent because the player's 3 workers (and any other
    // clients) read and write at the same time; expiry is volatile because any of those threads may refresh it.
    private static final class MediaEntry {
        final ConcurrentHashMap<Long, SubtitleResponse> segments = new ConcurrentHashMap<>();
        volatile long expiryEpochSec;
    }

    private final ConcurrentHashMap<String, MediaEntry> media = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Path file;
    private final Clock clock;

    // Spring uses this one (@Autowired is needed because there are two constructors and it can't guess which).
    // The file location can be changed in application.properties.
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

    private long newExpiry() {
        return clock.instant().getEpochSecond() + TIME_TO_LIVE_SECONDS;
    }

    // Runs once after Spring creates the bean. Never throws: a missing or damaged file just means an empty cache,
    // because a broken cache must not stop the server (and so the player's subtitles) from starting.
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
        }
    }

    // Runs when Spring closes the server. Writes to a temporary file first and then renames it over the real one, so
    // a crash half way through leaves the old complete file instead of a broken one.
    @PreDestroy
    void save() {
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
