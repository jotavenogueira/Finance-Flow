package com.financeflow.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Tokens aleatórios de 32 bytes em base64url sem preenchimento (43 caracteres). */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean valid(String token) {
        if (token == null || token.length() != 43) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(token).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static String hash(String token) {
        return HexFormat.of().formatHex(sha256(token.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
