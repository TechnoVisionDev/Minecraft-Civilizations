package io.github.empireage.civilizations.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameNormalizerTest {
    @Test
    void canonicalizesCompatibilityCharactersAndWhitespace() {
        assertEquals("roman empire", NameNormalizer.normalize("  Roman   Empire  "));
        assertEquals("abc", NameNormalizer.normalize("ＡＢＣ"));
    }

    @Test
    void rejectsFormattingAndControlCodes() {
        assertFalse(NameNormalizer.validName("§6Empire"));
        assertFalse(NameNormalizer.validName("Bad\nEmpire"));
        assertFalse(NameNormalizer.validName("&6Empire"));
    }

    @Test
    void acceptsSpecifiedPunctuationAndTagRange() {
        assertTrue(NameNormalizer.validName("King's-Realm"));
        assertTrue(NameNormalizer.validTag("K1NG"));
        assertFalse(NameNormalizer.validTag("TOOLONG"));
    }
}
