package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.domain.Claim;
import org.bukkit.Material;

import java.time.Instant;
import java.util.UUID;

/** O(1), cache-backed war authorization used by protection events. */
@FunctionalInterface
public interface WarAccessPort {
    boolean authorized(UUID actor, Long actorCivilizationId, Claim territory, Action action,
                       Material material, Instant now);

    /** Administrative variant which keeps the active-campaign/opponent checks but skips only roster membership. */
    default boolean authorizedIgnoringRoster(UUID actor, Long actorCivilizationId, Claim territory, Action action,
                                             Material material, Instant now) {
        return authorized(actor, actorCivilizationId, territory, action, material, now);
    }

    /** Checks the actual target block after actor/campaign authorization succeeds. */
    default boolean targetProtected(Claim territory, Action action, Material actionMaterial,
                                    Material targetMaterial, int blockX, int blockY, int blockZ) {
        return false;
    }

    enum Action {
        BREAK,
        PLACE,
        INTERACT,
        EXPLOSION,
        PVP
    }

    WarAccessPort DENY_ALL = (actor, actorCivilizationId, territory, action, material, now) -> false;
}
