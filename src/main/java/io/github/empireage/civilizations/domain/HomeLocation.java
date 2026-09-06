package io.github.empireage.civilizations.domain;

import java.util.UUID;

public record HomeLocation(UUID worldId, double x, double y, double z, float yaw, float pitch) {}
