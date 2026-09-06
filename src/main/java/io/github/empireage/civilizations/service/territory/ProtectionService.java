package io.github.empireage.civilizations.service.territory;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarState;
import org.bukkit.Material;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Constant-time protection policy over the immutable StateCache snapshot. No
 * method in this class performs database or Bukkit world I/O.
 */
public final class ProtectionService {
    private final Supplier<StateSnapshot> snapshots;
    private final BooleanSupplier ready;
    private final Settings.Protection settings;
    private final WarAccessPort warAccess;
    private final Clock clock;
    private final boolean attackerBlockDrops;

    public ProtectionService(StateCache cache, Settings.Protection settings,
                             WarAccessPort warAccess, Clock clock) {
        this(Objects.requireNonNull(cache, "cache")::snapshot, cache::ready, settings, warAccess, clock, false);
    }

    public ProtectionService(StateCache cache, Settings.Protection settings,
                             WarAccessPort warAccess, Clock clock, boolean attackerBlockDrops) {
        this(Objects.requireNonNull(cache, "cache")::snapshot, cache::ready, settings, warAccess, clock,
            attackerBlockDrops);
    }

    /** Pure-policy constructor useful for tests and alternate immutable cache adapters. */
    public ProtectionService(Supplier<StateSnapshot> snapshots, BooleanSupplier ready,
                             Settings.Protection settings, WarAccessPort warAccess, Clock clock) {
        this(snapshots, ready, settings, warAccess, clock, false);
    }

    public ProtectionService(Supplier<StateSnapshot> snapshots, BooleanSupplier ready,
                             Settings.Protection settings, WarAccessPort warAccess, Clock clock,
                             boolean attackerBlockDrops) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.ready = Objects.requireNonNull(ready, "ready");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.warAccess = Objects.requireNonNull(warAccess, "warAccess");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.attackerBlockDrops = attackerBlockDrops;
    }

    public Decision authorize(UUID actor, ChunkKey location, Action action, Material material, boolean bypass) {
        return authorize(actor, location, action, material, bypass, false);
    }

    public Decision authorize(UUID actor, ChunkKey location, Action action, Material material,
                              boolean protectionBypass, boolean warRosterBypass) {
        if (protectionBypass) return Decision.allow(false, "Explicit administrator bypass");
        if (!ready.getAsBoolean()) return Decision.deny("Territory data is warming up; protection is fail-closed.");
        StateSnapshot snapshot = snapshots.get();
        Claim claim = snapshot.claim(location);
        if (claim == null) return Decision.allow(false, "Wilderness");
        if (actor == null) return Decision.deny("Claimed land rejects actorless destructive changes.");

        Member member = snapshot.member(actor);
        Long actorCivilization = member == null ? null : member.civilizationId();
        if (member != null && member.civilizationId() == claim.civilizationId()) {
            if (sameCivilizationAllowed(member, actor, claim, action)) {
                return Decision.allow(false, "Authorized by plot policy");
            }
            return Decision.deny("Your role or plot trust does not authorize this action.");
        }
        if (publicFlagAllows(claim, member, action)) return Decision.allow(false, "Allowed by plot flag");
        WarAccessPort.Action warAction = warAction(action);
        boolean campaignAllowed = warAction != null && (warRosterBypass
            ? warAccess.authorizedIgnoringRoster(actor, actorCivilization, claim, warAction, material, clock.instant())
            : warAccess.authorized(actor, actorCivilization, claim, warAction, material, clock.instant()));
        if (campaignAllowed) {
            return Decision.allow(true, "Authorized campaign action");
        }
        return Decision.deny("This claim does not authorize you.");
    }

    public Decision friendlyFire(UUID attacker, UUID victim, ChunkKey location, boolean bypass) {
        if (bypass) return Decision.allow(false, "Explicit administrator bypass");
        if (!ready.getAsBoolean()) return Decision.deny("Territory data is warming up; protection is fail-closed.");
        if (attacker == null || victim == null || attacker.equals(victim)) return Decision.allow(false, "Not friendly fire");
        StateSnapshot snapshot = snapshots.get();
        Member source = snapshot.member(attacker);
        Member target = snapshot.member(victim);
        if (source == null || target == null || source.civilizationId() != target.civilizationId()) {
            return Decision.allow(false, "Players are not civilization allies");
        }
        Claim claim = snapshot.claim(location);
        boolean ownClaim = claim != null && claim.civilizationId() == source.civilizationId();
        boolean enabled = ownClaim ? settings.friendlyFireOwnClaims() : settings.friendlyFireWilderness();
        return enabled ? Decision.allow(false, "Friendly fire is enabled here")
            : Decision.deny("Friendly fire is disabled here.");
    }

    /**
     * Used for pistons, liquids, growth, falling blocks, dispensers, and inventory
     * transport. A boundary is compatible only if both endpoints have the same
     * access controller; this prevents public machinery bypassing private plots.
     */
    public boolean boundaryCompatible(ChunkKey from, ChunkKey to) {
        if (!ready.getAsBoolean()) return false;
        StateSnapshot snapshot = snapshots.get();
        return zone(snapshot.claim(from)).equals(zone(snapshot.claim(to)));
    }

    /** Explosions may not cross even between adjacent claims with the same controller. */
    public boolean sameClaimBoundary(ChunkKey from, ChunkKey to) {
        if (!ready.getAsBoolean()) return false;
        StateSnapshot snapshot = snapshots.get();
        Claim source = snapshot.claim(from);
        Claim target = snapshot.claim(to);
        if (source == null || target == null) return source == null && target == null;
        return source.id() == target.id();
    }

    /** Fire is completely suppressed in claims participating in an active campaign. */
    public boolean fireSpreadAllowed(ChunkKey from, ChunkKey to) {
        if (!ready.getAsBoolean()) return false;
        StateSnapshot snapshot = snapshots.get();
        if (activeWarClaim(snapshot, from) || activeWarClaim(snapshot, to)) return false;
        return zone(snapshot.claim(from)).equals(zone(snapshot.claim(to)));
    }

    /** During war, liquids remain inside the exact claim where they originated. */
    public boolean fluidFlowAllowed(ChunkKey from, ChunkKey to) {
        if (!ready.getAsBoolean()) return false;
        StateSnapshot snapshot = snapshots.get();
        if (activeWarClaim(snapshot, from) || activeWarClaim(snapshot, to)) {
            Claim source = snapshot.claim(from);
            Claim target = snapshot.claim(to);
            return source != null && target != null && source.id() == target.id();
        }
        return zone(snapshot.claim(from)).equals(zone(snapshot.claim(to)));
    }

    public boolean attackerBlockDrops() {
        return attackerBlockDrops;
    }

    public boolean claimed(ChunkKey location) {
        // Unknown is deliberately treated as protected during warmup.
        return !ready.getAsBoolean() || snapshots.get().claim(location) != null;
    }

    public boolean mobGriefAllowed(ChunkKey location) {
        if (!ready.getAsBoolean()) return false;
        return !settings.blockMobGriefing() || snapshots.get().claim(location) == null;
    }

    public boolean animalDamageAllowed(UUID actor, ChunkKey location, boolean bypass) {
        if (!ready.getAsBoolean() && !bypass) return false;
        if (!settings.protectAnimals()) return true;
        return authorize(actor, location, Action.ENTITY_DAMAGE, null, bypass).allowed();
    }

    public Claim claim(ChunkKey location) {
        return ready.getAsBoolean() ? snapshots.get().claim(location) : null;
    }

    public boolean ready() {
        return ready.getAsBoolean();
    }

    public boolean wartimeTargetProtected(ChunkKey location, Action action, Material actionMaterial,
                                           Material targetMaterial, int blockX, int blockY, int blockZ) {
        if (!ready.getAsBoolean()) return true;
        Claim claim = snapshots.get().claim(location);
        return claim != null && warAccess.targetProtected(claim, warAction(action), actionMaterial,
            targetMaterial, blockX, blockY, blockZ);
    }

    private boolean sameCivilizationAllowed(Member member, UUID actor, Claim claim, Action action) {
        boolean trusted = claim.trustedPlayers().contains(actor);
        boolean controller = switch (claim.plotType()) {
            case CAPITAL, CIVIC -> member.role().atLeast(Role.ADVISOR) || trusted;
            case COMMON -> true;
            case PRIVATE -> actor.equals(claim.plotOwnerId()) || trusted;
            case FOR_SALE -> "MEMBER".equals(claim.listingKind())
                && (actor.equals(claim.plotOwnerId()) || trusted);
        };
        if (controller) return true;
        return citizenFlagAllows(claim, action);
    }

    private static boolean citizenFlagAllows(Claim claim, Action action) {
        return switch (action) {
            case INTERACT -> claim.flags().getOrDefault("citizen_interact", false);
            case REDSTONE -> claim.flags().getOrDefault("redstone", false)
                || claim.flags().getOrDefault("citizen_interact", false);
            case CONTAINER -> claim.flags().getOrDefault("citizen_containers", false);
            default -> false;
        };
    }

    private static boolean publicFlagAllows(Claim claim, Member actorMembership, Action action) {
        // Citizenship flags apply only to members of the sovereign civilization;
        // public flags may apply to anyone.
        boolean citizen = actorMembership != null && actorMembership.civilizationId() == claim.civilizationId();
        return switch (action) {
            case INTERACT -> claim.flags().getOrDefault("public_interact", false)
                || citizen && claim.flags().getOrDefault("citizen_interact", false);
            case REDSTONE -> claim.flags().getOrDefault("redstone", false)
                || claim.flags().getOrDefault("public_interact", false)
                || citizen && claim.flags().getOrDefault("citizen_interact", false);
            case CONTAINER -> claim.flags().getOrDefault("public_containers", false)
                || citizen && claim.flags().getOrDefault("citizen_containers", false);
            default -> false;
        };
    }

    private static WarAccessPort.Action warAction(Action action) {
        return switch (action) {
            case BREAK -> WarAccessPort.Action.BREAK;
            case PLACE, BUCKET, IGNITE -> WarAccessPort.Action.PLACE;
            case INTERACT, REDSTONE, CONTAINER -> WarAccessPort.Action.INTERACT;
            case EXPLOSION -> WarAccessPort.Action.EXPLOSION;
            case PVP -> WarAccessPort.Action.PVP;
            case ENTITY_DAMAGE, HANGING, VEHICLE, ARMOR_STAND, INVENTORY_TRANSFER -> null;
        };
    }

    private boolean activeWarClaim(StateSnapshot snapshot, ChunkKey location) {
        Claim claim = snapshot.claim(location);
        if (claim == null) return false;
        War war = snapshot.warFor(claim.civilizationId());
        return war != null && war.effectiveState(clock.instant()) == WarState.ACTIVE;
    }

    private static Zone zone(Claim claim) {
        if (claim == null) return Zone.WILDERNESS;
        return switch (claim.plotType()) {
            case CAPITAL, CIVIC -> new Zone("ADMIN", claim.civilizationId());
            case COMMON -> new Zone("COMMON", claim.civilizationId());
            case PRIVATE, FOR_SALE -> new Zone("CLAIM", claim.id());
        };
    }

    public enum Action {
        BREAK,
        PLACE,
        INTERACT,
        CONTAINER,
        REDSTONE,
        BUCKET,
        IGNITE,
        EXPLOSION,
        ENTITY_DAMAGE,
        HANGING,
        VEHICLE,
        ARMOR_STAND,
        INVENTORY_TRANSFER,
        PVP
    }

    public record Decision(boolean allowed, boolean wartime, String reason) {
        static Decision allow(boolean wartime, String reason) { return new Decision(true, wartime, reason); }
        static Decision deny(String reason) { return new Decision(false, false, reason); }
    }

    private record Zone(String kind, long controller) {
        static final Zone WILDERNESS = new Zone("WILDERNESS", 0);
    }
}
