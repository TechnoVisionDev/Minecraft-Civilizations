package io.github.empireage.civilizations.util;

import java.text.Normalizer;
import java.util.Locale;

public final class NameNormalizer {
    private static final String NAME_PATTERN = "[\\p{L}\\p{N} '\\-]{3,24}";
    private static final String TAG_PATTERN = "[A-Za-z0-9]{2,5}";

    private NameNormalizer() {}

    public static boolean validName(String value) {
        if (value == null || !value.matches(NAME_PATTERN) || value.contains("§") || value.contains("&")) return false;
        return value.codePoints().noneMatch(Character::isISOControl);
    }

    public static boolean validTag(String value) {
        return value != null && value.matches(TAG_PATTERN);
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
