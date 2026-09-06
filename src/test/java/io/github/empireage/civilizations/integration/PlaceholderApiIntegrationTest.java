package io.github.empireage.civilizations.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderApiIntegrationTest {
    @Test
    void generatedExpansionLoadsWithoutPapiCompileDependency() throws Exception {
        String name = "io.github.empireage.civilizations.integration.generated.TestExpansion";
        byte[] bytes = PlaceholderApiIntegration.bridgeBytes(name, "missing-test-token", "EmpireAge", "1.0-test");

        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        Class<?> generated = new TestLoader(getClass().getClassLoader()).define(name, bytes);
        PlaceholderExpansion expansion = (PlaceholderExpansion) generated.getDeclaredConstructor().newInstance();

        assertEquals("civ", expansion.getIdentifier());
        assertEquals("EmpireAge", expansion.getAuthor());
        assertEquals("1.0-test", expansion.getVersion());
        assertTrue(expansion.persist());
        assertNull(expansion.onRequest(null, "name"));
    }

    private static final class TestLoader extends ClassLoader {
        private TestLoader(ClassLoader parent) { super(parent); }
        private Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
