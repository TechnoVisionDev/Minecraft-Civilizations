package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.domain.ChunkKey;

import java.util.UUID;

/**
 * Synchronous, preflight hook for region plugins/world borders. It is called by
 * the command layer on the server thread before database work starts.
 */
@FunctionalInterface
public interface ClaimEnvironmentPort {
    Check check(UUID actor, ChunkKey chunk, String worldName, String biomeKey);

    record Check(boolean allowed, String reason) {
        public static Check allow() { return new Check(true, ""); }
        public static Check deny(String reason) { return new Check(false, reason); }
    }

    ClaimEnvironmentPort ALLOW_ALL = (actor, chunk, worldName, biomeKey) -> Check.allow();
}
