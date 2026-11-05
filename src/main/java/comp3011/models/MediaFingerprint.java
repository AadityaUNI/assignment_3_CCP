/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 *
 * AI acknowledgement: the implementation was done with the help of claude
 */
package comp3011.models;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Quick content key: hashes the size plus 1 MB from the start, middle and end (small files in full). */
public final class MediaFingerprint {
    static final int SAMPLE_BYTES = 1024 * 1024;

    private MediaFingerprint() {
    }

    // Returns "<size>-<sha256 hex>". Reads the disk, so call it from a background thread, never the FX thread.
    public static String compute(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            // Size goes in first as fixed-width bytes, so "files of different length" can never hash alike.
            sha256.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());

            if (size <= 3L * SAMPLE_BYTES) {
                update(sha256, input, 0, (int) size);
            } else {
                update(sha256, input, 0, SAMPLE_BYTES);
                update(sha256, input, (size - SAMPLE_BYTES) / 2, SAMPLE_BYTES);
                update(sha256, input, size - SAMPLE_BYTES, SAMPLE_BYTES);
            }
            return size + "-" + HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java install", e);
        }
    }

    private static void update(MessageDigest digest, RandomAccessFile input, long position, int length)
            throws IOException {
        byte[] buffer = new byte[length];
        input.seek(position);
        input.readFully(buffer);
        digest.update(buffer);
    }
}
