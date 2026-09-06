package io.github.empireage.civilizations.cache;

import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ClaimSource;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Civilization;
import io.github.empireage.civilizations.domain.CivilizationStatus;
import io.github.empireage.civilizations.domain.HomeLocation;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.ObjectiveState;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.ResearchEntry;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.domain.War;
import io.github.empireage.civilizations.domain.WarObjective;
import io.github.empireage.civilizations.domain.WarState;
import io.github.empireage.civilizations.util.NameNormalizer;
import io.github.empireage.civilizations.util.TimeUtil;
import io.github.empireage.civilizations.util.UuidBytes;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StateRepository {
    public StateSnapshot load(Connection connection) throws Exception {
        Map<Long, Civilization> civilizations = loadCivilizations(connection);
        Map<UUID, Member> memberships = new LinkedHashMap<>();
        Map<Long, List<Member>> membersByCivilization = new LinkedHashMap<>();
        loadMembers(connection, memberships, membersByCivilization);
        Map<Long, Set<UUID>> trust = loadTrust(connection);
        Map<ChunkKey, Claim> claims = new LinkedHashMap<>();
        Map<Long, List<Claim>> claimsByCivilization = new LinkedHashMap<>();
        loadClaims(connection, trust, claims, claimsByCivilization);
        Map<Long, Set<String>> technologies = loadTechnologies(connection);
        Map<Long, List<ResearchEntry>> research = loadResearch(connection);
        Map<Long, Set<UUID>> rosters = loadRosters(connection);
        Map<Long, War> wars = loadWars(connection, rosters);
        Map<Long, War> warsByCivilization = new LinkedHashMap<>();
        wars.values().stream().filter(war -> war.state().current()).forEach(war -> {
            warsByCivilization.put(war.attackerCivilizationId(), war);
            warsByCivilization.put(war.defenderCivilizationId(), war);
        });
        Map<Long, List<WarObjective>> objectives = loadObjectives(connection);
        Map<String, Long> names = new LinkedHashMap<>();
        civilizations.values().forEach(civ -> names.put(NameNormalizer.normalize(civ.name()), civ.id()));
        return new StateSnapshot(civilizations, names, memberships, membersByCivilization, claims,
            claimsByCivilization, technologies, research, wars, warsByCivilization, objectives, Instant.now());
    }

    private Map<Long, Civilization> loadCivilizations(Connection connection) throws Exception {
        Map<Long, Civilization> values = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
            SELECT * FROM civilizations WHERE status <> 'DISBANDED'
            """)) {
            while (result.next()) {
                UUID worldId = UuidBytes.get(result, "capital_world_uuid");
                Civilization civilization = new Civilization(
                    result.getLong("id"), result.getString("name"),
                    UuidBytes.get(result, "leader_uuid"), CivilizationStatus.valueOf(result.getString("status")),
                    new ChunkKey(worldId, result.getInt("capital_chunk_x"), result.getInt("capital_chunk_z")),
                    result.getString("capital_world_name"),
                    new HomeLocation(worldId, result.getDouble("home_x"), result.getDouble("home_y"), result.getDouble("home_z"),
                        result.getFloat("home_yaw"), result.getFloat("home_pitch")),
                    result.getBigDecimal("default_plot_price"), result.getBigDecimal("treasury_balance"),
                    result.getLong("knowledge_balance"), TimeUtil.instant(result.getTimestamp("peace_shield_until")),
                    nullableLong(result, "current_war_id"), result.getInt("admin_claim_bonus"),
                    TimeUtil.instant(result.getTimestamp("capital_moved_at")), TimeUtil.instant(result.getTimestamp("created_at")),
                    result.getLong("row_version")
                );
                values.put(civilization.id(), civilization);
            }
        }
        return values;
    }

    private void loadMembers(Connection connection, Map<UUID, Member> memberships,
                             Map<Long, List<Member>> byCivilization) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT * FROM civ_members")) {
            while (result.next()) {
                Member member = new Member(
                    result.getLong("civ_id"), UuidBytes.get(result, "player_uuid"), result.getString("last_known_name"),
                    Role.valueOf(result.getString("role")), TimeUtil.instant(result.getTimestamp("joined_at")),
                    TimeUtil.instant(result.getTimestamp("last_active_at")), result.getLong("eligible_playtime_seconds"),
                    result.getBoolean("established"), result.getLong("contribution_total"), result.getBoolean("membership_locked")
                );
                memberships.put(member.playerId(), member);
                byCivilization.computeIfAbsent(member.civilizationId(), ignored -> new ArrayList<>()).add(member);
            }
        }
    }

    private Map<Long, Set<UUID>> loadTrust(Connection connection) throws Exception {
        Map<Long, Set<UUID>> trust = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT claim_id, trusted_player_uuid FROM plot_trust")) {
            while (result.next()) trust.computeIfAbsent(result.getLong(1), ignored -> new LinkedHashSet<>()).add(UuidBytes.fromBytes(result.getBytes(2)));
        }
        return trust;
    }

    private void loadClaims(Connection connection, Map<Long, Set<UUID>> trust, Map<ChunkKey, Claim> claims,
                            Map<Long, List<Claim>> byCivilization) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT * FROM civ_claims")) {
            while (result.next()) {
                long id = result.getLong("id");
                Claim claim = new Claim(
                    id,
                    new ChunkKey(UuidBytes.get(result, "world_uuid"), result.getInt("chunk_x"), result.getInt("chunk_z")),
                    result.getString("world_name"), result.getLong("civ_id"), PlotType.valueOf(result.getString("plot_type")),
                    UuidBytes.get(result, "plot_owner_uuid"), result.getString("listing_kind"), UuidBytes.get(result, "listing_seller_uuid"),
                    result.getBigDecimal("listing_price"), result.getBigDecimal("original_purchase_price"),
                    JsonData.booleans(result.getString("plot_flags")), result.getString("home_label"), result.getString("greeting"),
                    ClaimSource.valueOf(result.getString("acquisition_source")), JsonData.costs(result.getString("claim_cost_snapshot")),
                    TimeUtil.instant(result.getTimestamp("claimed_at")), UuidBytes.get(result, "claimed_by"),
                    trust.getOrDefault(id, Set.of()), result.getLong("row_version")
                );
                claims.put(claim.key(), claim);
                byCivilization.computeIfAbsent(claim.civilizationId(), ignored -> new ArrayList<>()).add(claim);
            }
        }
    }

    private Map<Long, Set<String>> loadTechnologies(Connection connection) throws Exception {
        Map<Long, Set<String>> values = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT civ_id, technology_key FROM civ_technologies")) {
            while (result.next()) values.computeIfAbsent(result.getLong(1), ignored -> new LinkedHashSet<>()).add(result.getString(2));
        }
        return values;
    }

    private Map<Long, List<ResearchEntry>> loadResearch(Connection connection) throws Exception {
        Map<Long, List<ResearchEntry>> values = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
            "SELECT * FROM research_queue WHERE state = 'ACTIVE'")) {
            while (result.next()) {
                JsonData.ResearchCost cost = JsonData.researchCost(result.getString("cost_snapshot"));
                ResearchEntry entry = new ResearchEntry(
                    result.getLong("id"), result.getLong("civ_id"), result.getInt("queue_slot"), result.getString("technology_key"),
                    result.getString("state"), UuidBytes.get(result, "started_by"), TimeUtil.instant(result.getTimestamp("started_at")),
                    TimeUtil.instant(result.getTimestamp("completes_at")), cost.knowledge(), cost.materials(), result.getLong("row_version")
                );
                values.computeIfAbsent(entry.civilizationId(), ignored -> new ArrayList<>()).add(entry);
            }
        }
        return values;
    }

    private Map<Long, Set<UUID>> loadRosters(Connection connection) throws Exception {
        Map<Long, Set<UUID>> values = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
            "SELECT war_id, player_uuid FROM war_roster WHERE eligible = TRUE")) {
            while (result.next()) values.computeIfAbsent(result.getLong(1), ignored -> new LinkedHashSet<>()).add(UuidBytes.fromBytes(result.getBytes(2)));
        }
        return values;
    }

    private Map<Long, War> loadWars(Connection connection, Map<Long, Set<UUID>> rosters) throws Exception {
        Map<Long, War> values = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
            SELECT * FROM wars WHERE state IN ('PENDING', 'ACTIVE', 'RESOLVING', 'TRUCE')
            """)) {
            while (result.next()) {
                long id = result.getLong("id");
                long attacker = result.getLong("attacker_civ_id");
                long defender = result.getLong("defender_civ_id");
                Set<UUID> fullRoster = rosters.getOrDefault(id, Set.of());
                Set<UUID> attackerRoster = rosterFor(connection, id, attacker, fullRoster);
                Set<UUID> defenderRoster = rosterFor(connection, id, defender, fullRoster);
                War war = new War(id, attacker, defender, WarState.valueOf(result.getString("state")),
                    UuidBytes.get(result, "declaration_actor"), TimeUtil.instant(result.getTimestamp("declared_at")),
                    TimeUtil.instant(result.getTimestamp("scheduled_start")), TimeUtil.instant(result.getTimestamp("scheduled_end")),
                    ZoneId.of(result.getString("timezone_id")), TimeUtil.instant(result.getTimestamp("cancellation_grace_until")),
                    TimeUtil.instant(result.getTimestamp("objective_lock_at")), TimeUtil.instant(result.getTimestamp("truce_until")),
                    result.getBoolean("attacker_peace"), result.getBoolean("defender_peace"), attackerRoster, defenderRoster);
                values.put(id, war);
            }
        }
        return values;
    }

    private Set<UUID> rosterFor(Connection connection, long warId, long civilizationId, Set<UUID> fallback) throws Exception {
        Set<UUID> values = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement(
            "SELECT player_uuid FROM war_roster WHERE war_id = ? AND civ_id = ? AND eligible = TRUE")) {
            statement.setLong(1, warId);
            statement.setLong(2, civilizationId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(UuidBytes.fromBytes(result.getBytes(1)));
            }
        }
        return Set.copyOf(values);
    }

    private Map<Long, List<WarObjective>> loadObjectives(Connection connection) throws Exception {
        Map<Long, List<WarObjective>> values = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
            SELECT o.* FROM war_objectives o JOIN wars w ON w.id = o.war_id
            WHERE w.state IN ('PENDING', 'ACTIVE', 'RESOLVING')
            """)) {
            while (result.next()) {
                UUID standardWorld = UuidBytes.get(result, "standard_world_uuid");
                WarObjective.BlockPosition position = standardWorld == null ? null : new WarObjective.BlockPosition(
                    standardWorld, result.getInt("standard_x"), result.getInt("standard_y"), result.getInt("standard_z"));
                WarObjective objective = new WarObjective(
                    result.getLong("id"), result.getLong("war_id"), result.getLong("nominating_civ_id"),
                    result.getLong("target_claim_id"),
                    new ChunkKey(UuidBytes.get(result, "frozen_world_uuid"), result.getInt("frozen_chunk_x"), result.getInt("frozen_chunk_z")),
                    ObjectiveState.valueOf(result.getString("state")), position, result.getLong("accumulated_control_ms"),
                    TimeUtil.instant(result.getTimestamp("last_progress_at")), UuidBytes.get(result, "secured_by"),
                    TimeUtil.instant(result.getTimestamp("secured_at")), result.getLong("row_version")
                );
                values.computeIfAbsent(objective.warId(), ignored -> new ArrayList<>()).add(objective);
            }
        }
        return values;
    }

    private Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
