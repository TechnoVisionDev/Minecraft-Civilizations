package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.util.Connectivity;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Pure territory/capacity rules, shared by commands, war resolution, and tests. */
public final class TerritoryRules {
    private TerritoryRules() {}

    public static int claimCapacity(StateSnapshot snapshot, long civilizationId, Settings.Claims settings,
                                    TechnologyCatalog technologies) {
        var civilization = snapshot.civilization(civilizationId);
        if (civilization == null) return 0;
        return claimCapacity(snapshot.members(civilizationId), snapshot.technologies(civilizationId),
            civilization.adminClaimBonus(), settings, technologies);
    }

    public static int claimCapacity(Collection<Member> members, Set<String> unlocked, int administratorBonus,
                                    Settings.Claims settings, TechnologyCatalog technologies) {
        long established = members.stream().filter(Member::established)
            .filter(member -> member.role() != Role.LEADER).count();
        long technologyBonus = modifier(unlocked, "claim-capacity", technologies);
        long calculated = (long) settings.baseCapacity()
            + established * settings.establishedMemberBonus()
            + technologyBonus
            + administratorBonus;
        return (int) Math.max(0, Math.min(settings.absoluteCap(), calculated));
    }

    public static int plotCapacity(Set<String> unlocked, Settings.Plots settings,
                                   TechnologyCatalog technologies) {
        long calculated = (long) settings.baseLimit() + modifier(unlocked, "plot-capacity", technologies);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, calculated));
    }

    public static boolean conquestTransferKeepsBothTerritoriesValid(StateSnapshot snapshot, Claim target,
                                                                     long conqueringCivilizationId) {
        if (target == null || target.isCapital() || target.civilizationId() == conqueringCivilizationId) return false;
        var defender = snapshot.civilization(target.civilizationId());
        var conqueror = snapshot.civilization(conqueringCivilizationId);
        if (defender == null || conqueror == null) return false;

        Set<ChunkKey> defenderChunks = new HashSet<>();
        for (Claim claim : snapshot.claims(target.civilizationId())) defenderChunks.add(claim.key());
        if (!Connectivity.remainsConnectedAfterRemoval(defenderChunks, defender.capital(), target.key())) return false;

        for (Claim claim : snapshot.claims(conqueringCivilizationId)) {
            if (claim.key().cardinallyAdjacent(target.key())) return true;
        }
        return false;
    }

    static int modifier(Set<String> unlocked, String key, TechnologyCatalog technologies) {
        long sum = 0;
        for (String technology : unlocked) {
            TechnologyDefinition definition = technologies.get(technology);
            if (definition != null) sum += definition.modifiers().getOrDefault(key, 0);
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
    }
}
