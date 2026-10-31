package dev.mobilehorizon.compat.api;

import java.nio.charset.StandardCharsets;

/**
 * Small line-protocol codec shared by BridgeMain and tests.
 * Text fields are lowercase hexadecimal UTF-8, so every message is one safe line.
 */
final class ProtocolCodec {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ProtocolCodec() {
    }

    static String encode(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            output[i * 2] = HEX[b >>> 4];
            output[i * 2 + 1] = HEX[b & 0x0f];
        }
        return new String(output);
    }

    static String decode(String value) {
        if (value == null || (value.length() & 1) != 0) {
            throw new IllegalArgumentException("invalid hex field");
        }
        byte[] output = new byte[value.length() / 2];
        for (int i = 0; i < output.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid hex field");
            output[i] = (byte) ((hi << 4) | lo);
        }
        return new String(output, StandardCharsets.UTF_8);
    }

    static void requireSingleLine(String value, String label, int maxChars) {
        if (value == null || value.length() > maxChars) {
            throw new IllegalArgumentException(label + " is missing or too large");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(label + " contains a protocol delimiter");
        }
    }
}
