package io.github.empireage.civilizations.config;

import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.TechnologyMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record Settings(
    String serverId,
    Database database,
    Worlds worlds,
    Chat chat,
    Founding founding,
    Membership membership,
    Claims claims,
    Plots plots,
    EconomyMode economyMode,
    Technology technology,
    Travel travel,
    WorkOrders workOrders,
    War war,
    Protection protection,
    Notifications notifications
) {
    public enum EconomyMode { VAULT, DISABLED }

    public record Database(String host, int port, String schema, String username, String password, boolean ssl,
                           int poolSize, long connectionTimeoutMs, Duration retryDelay) {
        public String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=" + ssl
                + "&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
        }
    }

    public record Worlds(Set<String> allowed, Set<String> blacklistedBiomes, boolean failClosedDuringWarmup) {
        public boolean allowed(String worldName) {
            return allowed.isEmpty() || allowed.stream().anyMatch(value -> value.equalsIgnoreCase(worldName));
        }
    }

    public record Chat(int localRadiusBlocks) {}

    public record Founding(int minimumDistanceChunks, Duration peaceShield, BigDecimal moneyCost,
                           Map<ResourceKey, Long> materialCost) {}

    public record Membership(Duration inviteExpiry, Duration changeCooldown, Duration establishedAfter,
                             Duration establishedActive, Duration establishedWindow, int baseAdvisorLimit,
                             boolean inactivitySuccessionEnabled, Duration inactivity, Duration successionNotice) {}

    public record Claims(int baseCapacity, int establishedMemberBonus, int absoluteCap, int refundPercent,
                         Duration capitalMoveCooldown, Map<ResourceKey, Long> capitalMoveCost) {}

    public record Plots(int baseLimit, BigDecimal defaultPrice, int saleTaxPercent, Duration confirmationExpiry,
                        boolean homeEnabled, Duration homeWarmup, Duration homeCooldown) {}

    public record Technology(TechnologyMode enforcement, Duration cancellationGrace) {}

    public record Travel(Duration warmup, Duration cooldown, int safeSearchRadius, int claimProtectionRadiusChunks,
                         Destination overworld, Destination nether, Destination end) {}

    /** A destination uses the world's spawn when x/y/z are omitted. */
    public record Destination(String world, Double x, Double y, Double z, float yaw, float pitch) {
        public boolean usesWorldSpawn() {
            return x == null;
        }
    }

    /** Persistent order timing and pools live in workorders.yml. */
    public record WorkOrders() {}

    public record War(ZoneId zone, DayOfWeek weekday, LocalTime start, LocalTime end, Duration notice,
                      Duration truce, Duration declarationGrace,
                      int minimumEstablishedMembers, int capitalMonumentRadius, int capitalMonumentHeight,
                      boolean allowCapitalCombat,
                      boolean attackerBlockDrops, Map<ResourceKey, Long> declarationCost,
                      Set<Material> siegePlaceMaterials, Set<Material> tntBreakableMaterials,
                      Set<Material> alwaysProtectedMaterials) {}

    public record Protection(boolean friendlyFireOwnClaims, boolean friendlyFireWilderness,
                             boolean protectAnimals, boolean blockMobGriefing) {}

    public record Notifications(Duration claimEntry, Duration researchCheck, Duration warTick,
                                Duration invariantCheck) {}

    public static Settings load(FileConfiguration config) {
        String serverId = required(config.getString("server-id", "primary"), "server-id");
        Database database = new Database(
            required(config.getString("database.host"), "database.host"),
            positive(config.getInt("database.port", 3306), "database.port"),
            required(config.getString("database.database"), "database.database"),
            required(config.getString("database.username"), "database.username"),
            config.getString("database.password", ""),
            config.getBoolean("database.ssl", false),
            positive(config.getInt("database.pool-size", 10), "database.pool-size"),
            positive(config.getLong("database.connection-timeout-ms", 5000), "database.connection-timeout-ms"),
            Duration.ofSeconds(positive(config.getLong("database.retry-seconds", 30), "database.retry-seconds"))
        );
        boolean failClosedDuringWarmup = config.getBoolean("worlds.fail-closed-during-warmup", true);
        if (!failClosedDuringWarmup) {
            throw new IllegalArgumentException("worlds.fail-closed-during-warmup must be true; unsafe warmup is not supported");
        }
        Worlds worlds = new Worlds(
            lowercaseSet(config.getStringList("worlds.allowed")),
            uppercaseSet(config.getStringList("worlds.blacklisted-biomes")),
            true
        );
        Chat chat = new Chat(positive(config.getInt("chat.local-radius-blocks", 100), "chat.local-radius-blocks"));
        Founding founding = new Founding(
            nonNegative(config.getInt("founding.minimum-distance-chunks", 12), "founding.minimum-distance-chunks"),
            Duration.ofDays(nonNegative(config.getLong("founding.peace-shield-days", 14), "founding.peace-shield-days")),
            money(config.getString("founding.money-cost", "0.00")),
            costs(config.getConfigurationSection("founding.material-cost"))
        );
        Membership membership = new Membership(
            Duration.ofMinutes(positive(config.getLong("membership.invite-expiry-minutes", 10), "membership.invite-expiry-minutes")),
            Duration.ofHours(nonNegative(config.getLong("membership.change-cooldown-hours", 24), "membership.change-cooldown-hours")),
            Duration.ofHours(nonNegative(config.getLong("membership.established-after-hours", 72), "membership.established-after-hours")),
            Duration.ofMinutes(nonNegative(config.getLong("membership.established-active-minutes", 120), "membership.established-active-minutes")),
            Duration.ofDays(positive(config.getLong("membership.established-window-days", 14), "membership.established-window-days")),
            nonNegative(config.getInt("membership.advisor-base-limit", 3), "membership.advisor-base-limit"),
            config.getBoolean("membership.inactivity-succession-enabled", false),
            Duration.ofDays(positive(config.getLong("membership.inactivity-days", 30), "membership.inactivity-days")),
            Duration.ofHours(positive(config.getLong("membership.succession-notice-hours", 72), "membership.succession-notice-hours"))
        );
        Claims claims = new Claims(
            positive(config.getInt("claims.base-capacity", 8), "claims.base-capacity"),
            nonNegative(config.getInt("claims.established-member-bonus", 2), "claims.established-member-bonus"),
            positive(config.getInt("claims.absolute-cap", 128), "claims.absolute-cap"),
            percent(config.getInt("claims.refund-percent", 50), "claims.refund-percent"),
            Duration.ofDays(nonNegative(config.getLong("claims.capital-move-cooldown-days", 7), "claims.capital-move-cooldown-days")),
            costs(config.getConfigurationSection("claims.capital-move-cost"))
        );
        Plots plots = new Plots(
            nonNegative(config.getInt("plots.base-limit", 4), "plots.base-limit"),
            money(config.getString("plots.default-price", "100.00")),
            percent(config.getInt("plots.sale-tax-percent", 10), "plots.sale-tax-percent"),
            Duration.ofSeconds(positive(config.getLong("plots.confirmation-seconds", 30), "plots.confirmation-seconds")),
            config.getBoolean("plots.home-enabled", true),
            Duration.ofSeconds(nonNegative(config.getLong("plots.home-warmup-seconds", 5), "plots.home-warmup-seconds")),
            Duration.ofSeconds(nonNegative(config.getLong("plots.home-cooldown-seconds", 60), "plots.home-cooldown-seconds"))
        );
        EconomyMode economyMode = enumValue(EconomyMode.class, config.getString("economy.mode", "DISABLED"), "economy.mode");
        Technology technology = new Technology(
            enumValue(TechnologyMode.class, config.getString("technology.enforcement", "STRICT"), "technology.enforcement"),
            Duration.ofMinutes(positive(config.getLong("technology.cancellation-grace-minutes", 5), "technology.cancellation-grace-minutes"))
        );
        Travel travel = new Travel(
            Duration.ofSeconds(nonNegative(config.getLong("travel.warmup-seconds", 5), "travel.warmup-seconds")),
            Duration.ofSeconds(nonNegative(config.getLong("travel.cooldown-seconds", 60), "travel.cooldown-seconds")),
            nonNegative(config.getInt("travel.safe-search-radius", 8), "travel.safe-search-radius"),
            nonNegative(config.getInt("travel.claim-protection-radius-chunks", 1), "travel.claim-protection-radius-chunks"),
            destination(config, "travel.destinations.overworld", "world"),
            destination(config, "travel.destinations.nether", "world_nether"),
            destination(config, "travel.destinations.end", "world_the_end")
        );
        WorkOrders workOrders = new WorkOrders();
        War war = new War(
            ZoneId.of(config.getString("war.timezone", "America/Los_Angeles")),
            enumValue(DayOfWeek.class, config.getString("war.weekday", "SATURDAY"), "war.weekday"),
            LocalTime.parse(config.getString("war.start-time", "14:00")),
            LocalTime.parse(config.getString("war.end-time", "18:00")),
            Duration.ofHours(positive(config.getLong("war.notice-hours", 24), "war.notice-hours")),
            Duration.ofDays(positive(config.getLong("war.truce-days", 7), "war.truce-days")),
            Duration.ofMinutes(positive(config.getLong("war.declaration-grace-minutes", 10), "war.declaration-grace-minutes")),
            nonNegative(config.getInt("war.minimum-established-members", 3), "war.minimum-established-members"),
            nonNegative(config.getInt("war.capital-monument-radius", 3), "war.capital-monument-radius"),
            nonNegative(config.getInt("war.capital-monument-height", 8), "war.capital-monument-height"),
            config.getBoolean("war.allow-capital-combat", true),
            config.getBoolean("war.attacker-block-drops", true),
            costs(config.getConfigurationSection("war.declaration-cost")),
            materials(config.getStringList("war.siege-place-materials"), "war.siege-place-materials"),
            materials(config.getStringList("war.tnt-breakable-materials"), "war.tnt-breakable-materials"),
            materials(config.getStringList("war.always-protected-materials"), "war.always-protected-materials")
        );
        if (!war.end().isAfter(war.start())) throw new IllegalArgumentException("war.end-time must be after war.start-time");
        Protection protection = new Protection(
            config.getBoolean("protection.friendly-fire-own-claims", false),
            config.getBoolean("protection.friendly-fire-wilderness", true),
            config.getBoolean("protection.protect-animals", true),
            config.getBoolean("protection.block-mob-griefing", true)
        );
        Notifications notifications = new Notifications(
            Duration.ofSeconds(positive(config.getLong("notifications.claim-entry-seconds", 3), "notifications.claim-entry-seconds")),
            Duration.ofSeconds(positive(config.getLong("notifications.research-check-seconds", 30), "notifications.research-check-seconds")),
            Duration.ofSeconds(positive(config.getLong("notifications.war-tick-seconds", 1), "notifications.war-tick-seconds")),
            Duration.ofMinutes(positive(config.getLong("notifications.invariant-check-minutes", 30), "notifications.invariant-check-minutes"))
        );
        return new Settings(serverId, database, worlds, chat, founding, membership, claims, plots, economyMode,
            technology, travel, workOrders, war, protection, notifications);
    }

    private static Destination destination(FileConfiguration config, String path, String defaultWorld) {
        String world = required(config.getString(path + ".world", defaultWorld), path + ".world");
        boolean x = config.isSet(path + ".x");
        boolean y = config.isSet(path + ".y");
        boolean z = config.isSet(path + ".z");
        if (x != y || x != z) {
            throw new IllegalArgumentException(path + " must set all of x, y, and z, or omit all three to use the world spawn");
        }
        return new Destination(world,
            x ? config.getDouble(path + ".x") : null,
            y ? config.getDouble(path + ".y") : null,
            z ? config.getDouble(path + ".z") : null,
            (float) config.getDouble(path + ".yaw", 0.0),
            (float) config.getDouble(path + ".pitch", 0.0));
    }

    private static Map<ResourceKey, Long> costs(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<ResourceKey, Long> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            long value = section.getLong(key);
            if (value < 0) throw new IllegalArgumentException("Negative cost for " + key);
            values.put(ResourceKey.parse(key), value);
        }
        return Map.copyOf(values);
    }

    private static Set<Material> materials(Iterable<String> names, String path) {
        Set<Material> values = new LinkedHashSet<>();
        for (String name : names) {
            Material value = Material.matchMaterial(name);
            if (value == null) throw new IllegalArgumentException("Unknown material " + name + " at " + path);
            values.add(value);
        }
        return Set.copyOf(values);
    }

    private static Set<String> lowercaseSet(Iterable<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.toLowerCase(Locale.ROOT)));
        return Set.copyOf(result);
    }

    private static Set<String> uppercaseSet(Iterable<String> values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.toUpperCase(Locale.ROOT)));
        return Set.copyOf(result);
    }

    private static BigDecimal money(String value) {
        BigDecimal result = new BigDecimal(required(value, "money")).setScale(2);
        if (result.signum() < 0) throw new IllegalArgumentException("Money value cannot be negative");
        return result;
    }

    private static int percent(int value, String path) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(path + " must be 0-100");
        return value;
    }

    private static int positive(int value, String path) {
        if (value <= 0) throw new IllegalArgumentException(path + " must be positive");
        return value;
    }

    private static long positive(long value, String path) {
        if (value <= 0) throw new IllegalArgumentException(path + " must be positive");
        return value;
    }

    private static int nonNegative(int value, String path) {
        if (value < 0) throw new IllegalArgumentException(path + " cannot be negative");
        return value;
    }

    private static long nonNegative(long value, String path) {
        if (value < 0) throw new IllegalArgumentException(path + " cannot be negative");
        return value;
    }

    private static String required(String value, String path) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(path + " is required");
        return value;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String path) {
        try {
            return Enum.valueOf(type, required(value, path).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid " + path + ": " + value, exception);
        }
    }
}
