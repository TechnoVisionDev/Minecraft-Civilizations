package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarState;
import org.bukkit.Material;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Default cache-only implementation of wartime land authorization. */
public final class SnapshotWarAccess implements WarAccessPort {
    private final StateCache cache;
    private final Settings.War settings;

    public SnapshotWarAccess(StateCache cache, Settings.War settings) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public boolean authorized(UUID actor, Long actorCivilizationId, Claim territory, Action action,
                              Material material, Instant now) {
        return authorized(actor, actorCivilizationId, territory, action, material, now, false);
    }

    @Override
    public boolean authorizedIgnoringRoster(UUID actor, Long actorCivilizationId, Claim territory, Action action,
                                            Material material, Instant now) {
        return authorized(actor, actorCivilizationId, territory, action, material, now, true);
    }

    private boolean authorized(UUID actor, Long actorCivilizationId, Claim territory, Action action,
                               Material material, Instant now, boolean rosterBypass) {
        if (!cache.ready() || actor == null || actorCivilizationId == null || territory == null
            || actorCivilizationId == territory.civilizationId()) return false;
        War war = cache.snapshot().warFor(actorCivilizationId);
        if (war == null || war.effectiveState(now) != WarState.ACTIVE
            || !Objects.equals(war.opponent(actorCivilizationId), territory.civilizationId())
            || !rosterBypass && !war.rostered(actorCivilizationId, actor)) return false;
        if (territory.plotType() == PlotType.CAPITAL && !settings.allowCapitalCombat()) return false;
        if (material != null && settings.alwaysProtectedMaterials().contains(material)) return false;
        return switch (action) {
            case PVP, BREAK, INTERACT -> true;
            case PLACE -> material != null && settings.siegePlaceMaterials().contains(material);
            case EXPLOSION -> material == Material.TNT
                && cache.snapshot().technologies(actorCivilizationId).contains("redstone_engineering");
        };
    }

    @Override
    public boolean targetProtected(Claim territory, Action action, Material actionMaterial,
                                   Material targetMaterial, int blockX, int blockY, int blockZ) {
        if (territory == null || targetMaterial == null) return false;
        if (settings.alwaysProtectedMaterials().contains(targetMaterial)) return true;
        if (action == Action.EXPLOSION && actionMaterial == Material.TNT
            && !settings.tntBreakableMaterials().contains(targetMaterial)) return true;
        if (territory.plotType() != PlotType.CAPITAL) return false;
        Civilization civilization = cache.snapshot().civilization(territory.civilizationId());
        if (civilization == null || civilization.home() == null
            || !civilization.home().worldId().equals(territory.key().worldId())) return false;
        int centerX = (int) Math.floor(civilization.home().x());
        int centerY = (int) Math.floor(civilization.home().y());
        int centerZ = (int) Math.floor(civilization.home().z());
        return Math.abs(blockX - centerX) <= settings.capitalMonumentRadius()
            && Math.abs(blockZ - centerZ) <= settings.capitalMonumentRadius()
            && blockY >= centerY - 1 && blockY <= centerY + settings.capitalMonumentHeight();
    }
}
