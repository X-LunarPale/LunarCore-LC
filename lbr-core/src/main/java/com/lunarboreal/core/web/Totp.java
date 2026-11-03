package com.lunarboreal.core.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/** RFC 6238 SHA-1 TOTP, 30-second steps. */
final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    static String base32(byte[] bytes) {
        StringBuilder output = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 255);
            bits += 8;
            while (bits >= 5) { bits -= 5; output.append(ALPHABET.charAt((buffer >> bits) & 31)); }
        }
        if (bits > 0) output.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return output.toString();
    }

    static byte[] decode(String secret) {
        byte[] output = new byte[secret.length() * 5 / 8];
        int buffer = 0, bits = 0, index = 0;
        for (char c : secret.toUpperCase(Locale.ROOT).toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("Invalid TOTP secret");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) { bits -= 8; output[index++] = (byte) (buffer >> bits); }
        }
        return output;
    }

    static String code(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 15;
            int value = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                    | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            return String.format(Locale.ROOT, "%0" + digits + "d", value % (int) Math.pow(10, digits));
        } catch (GeneralSecurityException ex) { throw new IllegalStateException("TOTP unavailable", ex); }
    }

    static long matchingStep(String secret, String candidate, long nowMillis, long lastStep) {
        if (candidate == null || !candidate.matches("[0-9]{6}")) return -1;
        byte[] key = decode(secret);
        long current = Math.floorDiv(nowMillis, 30_000);
        for (long step = current - 1; step <= current + 1; step++) {
            if (step > lastStep && java.security.MessageDigest.isEqual(
                    code(key, step, 6).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    candidate.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return step;
        }
        return -1;
    }
}
