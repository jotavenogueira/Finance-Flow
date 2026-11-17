package com.financeflow.usecase;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

final class Text {

    // Endereço simples (addr-spec): sem nome de exibição, comentários ou espaços.
    private static final String ATOM = "[\\p{L}\\p{N}\\p{M}!#$%&'*+/=?^_`{|}~-]+";
    private static final Pattern EMAIL =
            Pattern.compile(ATOM + "(\\." + ATOM + ")*@" + ATOM + "(\\." + ATOM + ")*");

    private Text() {
    }

    static String trim(String value) {
        return value == null ? "" : value.strip();
    }

    static int characters(String value) {
        return value.codePointCount(0, value.length());
    }

    static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    static Optional<String> normalizeEmail(String input) {
        String email = trim(input).toLowerCase(java.util.Locale.ROOT);
        if (bytes(email) < 3 || bytes(email) > 254 || !EMAIL.matcher(email).matches()) {
            return Optional.empty();
        }
        return Optional.of(email);
    }
}
