package com.stockflow.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EmailNormalizer {

    private static final int MAX_LENGTH = 320;
    private static final Pattern FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private EmailNormalizer() {
    }

    /** trim + minúsculas con Locale.ROOT. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String normalizedEmail) {
        return normalizedEmail.length() <= MAX_LENGTH && FORMAT.matcher(normalizedEmail).matches();
    }
}
