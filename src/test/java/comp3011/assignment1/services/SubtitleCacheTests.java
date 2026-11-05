package comp3011.assignment1.services;

import comp3011.assignment1.models.SubtitleResponse;
import comp3011.frameprocessors.Subtitle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for SubtitleCache: save/load round trip, expiry, and bad files. Tests built by AI with my directions. */
class SubtitleCacheTests {
    @TempDir
    Path folder;

    private static SubtitleResponse response(String text) {
        return new SubtitleResponse(new Subtitle[]{new Subtitle(text, 1.5, 3.0)});
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void savedEntriesComeBackInAFreshCache() {
        Path file = folder.resolve("cache.json");
        Clock now = clockAt(Instant.parse("2026-01-01T00:00:00Z"));

        SubtitleCache first = new SubtitleCache(file, now);
        first.put("abc-123", 2, response("hello there"));
        first.put("abc-123", 5, new SubtitleResponse(new Subtitle[0])); // a silent chunk is cached too
        first.save();

        SubtitleCache second = new SubtitleCache(file, now);
        second.load();
        assertEquals("hello there", second.get("abc-123", 2).orElseThrow().subs()[0].text());
        assertEquals(1.5, second.get("abc-123", 2).orElseThrow().subs()[0].start());
        assertEquals(0, second.get("abc-123", 5).orElseThrow().subs().length);
        assertTrue(second.get("abc-123", 3).isEmpty(), "a chunk that was never stored is a miss");
        assertTrue(second.get("other-key", 2).isEmpty());
    }

    @Test
    void expiredMediaIsNotLoadedAndIsGoneFromTheNextSave() {
        Path file = folder.resolve("cache.json");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        SubtitleCache first = new SubtitleCache(file, clockAt(start));
        first.put("old-1", 0, response("old"));
        first.save();

        Instant muchLater = start.plus(Duration.ofSeconds(SubtitleCache.TIME_TO_LIVE_SECONDS + 1));
        SubtitleCache second = new SubtitleCache(file, clockAt(muchLater));
        second.load();
        assertTrue(second.get("old-1", 0).isEmpty());

        second.save();
        SubtitleCache third = new SubtitleCache(file, clockAt(start));
        third.load(); // even with the clock back at the start, the expired entry was dropped from the file
        assertTrue(third.get("old-1", 0).isEmpty());
    }

    @Test
    void useExtendsTheExpiry() {
        Path file = folder.resolve("cache.json");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        long ttl = SubtitleCache.TIME_TO_LIVE_SECONDS;

        SubtitleCache first = new SubtitleCache(file, clockAt(start));
        first.put("key-1", 0, response("kept"));

        // Used again just before it would have expired, then saved and reloaded after the original expiry.
        SubtitleCache used = new SubtitleCache(file, clockAt(start.plusSeconds(ttl - 10)));
        used.put("key-1", 0, response("kept"));
        used.save();

        SubtitleCache reloaded = new SubtitleCache(file, clockAt(start.plusSeconds(ttl + 100)));
        reloaded.load();
        assertTrue(reloaded.get("key-1", 0).isPresent());
    }

    @Test
    void missingOrDamagedFileGivesAnEmptyCacheInsteadOfAnError() throws Exception {
        Clock now = clockAt(Instant.parse("2026-01-01T00:00:00Z"));

        SubtitleCache missing = new SubtitleCache(folder.resolve("nothing-here.json"), now);
        assertDoesNotThrow(missing::load);
        assertTrue(missing.get("k", 0).isEmpty());

        Path broken = folder.resolve("broken.json");
        Files.writeString(broken, "{ this is not json");
        SubtitleCache damaged = new SubtitleCache(broken, now);
        assertDoesNotThrow(damaged::load);
        assertTrue(damaged.get("k", 0).isEmpty());
    }

    @Test
    void saveCreatesTheFolderIfNeeded() {
        Path file = folder.resolve("a").resolve("b").resolve("cache.json");
        SubtitleCache cache = new SubtitleCache(file, clockAt(Instant.parse("2026-01-01T00:00:00Z")));
        cache.put("k-1", 0, response("x"));
        cache.save();
        assertTrue(Files.exists(file));
    }
}
