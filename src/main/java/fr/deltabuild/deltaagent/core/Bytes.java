package fr.deltabuild.deltaagent.core;

import java.nio.charset.StandardCharsets;

/**
 * Byte searches, used to look at class files and world files without parsing them.
 */
public final class Bytes {

    public static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    public static boolean contains(byte[] data, byte[] pattern) {
        return indexOf(data, pattern) >= 0;
    }

    public static int indexOf(byte[] data, byte[] pattern) {
        return indexOf(data, data.length, pattern);
    }

    /** Searches the first {@code length} bytes of {@code data} only. */
    public static int indexOf(byte[] data, int length, byte[] pattern) {
        outer:
        for (int i = 0; i <= length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private Bytes() {
    }
}
