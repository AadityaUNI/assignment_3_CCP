package comp3011.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for MediaFingerprint. Tests built by AI with my directions. */
class MediaFingerprintTests {
    @TempDir
    Path folder;

    private File fileWith(String name, byte[] bytes) throws Exception {
        Path path = folder.resolve(name);
        Files.write(path, bytes);
        return path.toFile();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new Random(42).nextBytes(bytes); // fixed seed: every call gives the same bytes
        return bytes;
    }

    @Test
    void sameContentGivesSameKeyEvenWithADifferentFileName() throws Exception {
        byte[] bytes = randomBytes(5 * MediaFingerprint.SAMPLE_BYTES);
        assertEquals(MediaFingerprint.compute(fileWith("a.mp4", bytes)),
                MediaFingerprint.compute(fileWith("copy-of-a.mp4", bytes)));
    }

    @Test
    void keyLooksLikeSizeDashHex() throws Exception {
        String key = MediaFingerprint.compute(fileWith("a.mp4", randomBytes(1000)));
        assertTrue(key.matches("1000-[0-9a-f]{64}"), key); // also what the server's key check accepts
    }

    @Test
    void changingAByteInAnySampledPlaceChangesTheKey() throws Exception {
        int size = 5 * MediaFingerprint.SAMPLE_BYTES;
        String original = MediaFingerprint.compute(fileWith("a.mp4", randomBytes(size)));

        int[] sampledPositions = {10, (size - MediaFingerprint.SAMPLE_BYTES) / 2 + 10, size - 10};
        for (int position : sampledPositions) {
            byte[] changed = randomBytes(size);
            changed[position]++;
            assertNotEquals(original, MediaFingerprint.compute(fileWith("b.mp4", changed)), "position " + position);
        }
    }

    @Test
    void smallFilesAreHashedInFull() throws Exception {
        byte[] bytes = randomBytes(2 * MediaFingerprint.SAMPLE_BYTES);
        byte[] changed = bytes.clone();
        changed[MediaFingerprint.SAMPLE_BYTES + 5]++; // a byte that a 3-sample hash would skip, but a full hash sees
        assertNotEquals(MediaFingerprint.compute(fileWith("a.mp4", bytes)),
                MediaFingerprint.compute(fileWith("b.mp4", changed)));
    }

    @Test
    void differentSizesGiveDifferentKeys() throws Exception {
        assertNotEquals(MediaFingerprint.compute(fileWith("a.mp4", randomBytes(100))),
                MediaFingerprint.compute(fileWith("b.mp4", randomBytes(101))));
    }
}
