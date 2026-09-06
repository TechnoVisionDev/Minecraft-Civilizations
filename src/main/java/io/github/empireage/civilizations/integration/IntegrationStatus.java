package io.github.empireage.civilizations.integration;

import java.util.Objects;

/** Uniform status exposed to startup logs and `/civ admin inspect`. */
public record IntegrationStatus(String name, boolean available, boolean active, String version, String detail) {
    public IntegrationStatus {
        Objects.requireNonNull(name, "name");
        version = version == null ? "unknown" : version;
        detail = detail == null ? "" : detail;
    }

    public static IntegrationStatus missing(String name) {
        return new IntegrationStatus(name, false, false, "not installed", "Optional plugin is not installed or enabled.");
    }
}
