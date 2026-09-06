package io.github.empireage.civilizations.service.territory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.cache.StateSnapshot;
import io.github.empireage.civilizations.concurrent.CivilizationLocks;
import io.github.empireage.civilizations.config.Settings;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.database.AuditLog;
import io.github.empireage.civilizations.database.Database;
import io.github.empireage.civilizations.database.JsonData;
import io.github.empireage.civilizations.database.Sql;
import io.github.empireage.civilizations.domain.Claim;
import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.OperationResult;
import io.github.empireage.civilizations.domain.PlotType;
import io.github.empireage.civilizations.domain.Role;
import io.github.empireage.civilizations.service.economy.EconomyOperationState;
import io.github.empireage.civilizations.service.economy.PlotTaxService;
import io.github.empireage.civilizations.util.UuidBytes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Plot state, trust, confirmations, and recoverable economy purchase sagas. */
public final class PlotService {
    private static final String LISTING_CIVILIZATION = "CIVILIZATION";
    private static final String LISTING_CIVIC = "CIV_CIVIC";
    private static final String LISTING_COMMON = "CIV_COMMON";
    private static final String LISTING_MEMBER = "MEMBER";
    private static final String CONFIRM_PURCHASE = "plot:purchase";
    private static final String CONFIRM_SURRENDER = "plot:surrender";
    private static final Set<String> ALLOWED_FLAGS = Set.of(
        "public_interact", "citizen_interact", "public_containers", "citizen_containers", "redstone");

    private final Database database;
    private final StateCache cache;
    private final CivilizationLocks locks;
    private final Settings settings;
    private final Supplier<TechnologyCatalog> technologies;
    private final EconomyPort economy;
    private final ConfirmationTokens confirmations;
    private final Clock clock;

    public PlotService(Database database, StateCache cache, CivilizationLocks locks, Settings settings,
                       Supplier<TechnologyCatalog> technologies, EconomyPort economy,
                       ConfirmationTokens confirmations, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.technologies = Objects.requireNonNull(technologies, "technologies");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<OperationResult> setPublicType(UUID actor, ChunkKey chunk, PlotType type) {
        if (type != PlotType.CIVIC && type != PlotType.COMMON) return denied("Public plots may only be CIVIC or COMMON.");
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            if (!administrates(member)) return OperationResult.denied("Only leaders and advisors may change public plot access.");
            PlotRow claim = lockClaim(connection, chunk);
            if (!sameCivilization(member, claim) || claim.type() == PlotType.CAPITAL || claim.owner() != null
                || claim.type() == PlotType.PRIVATE || claim.type() == PlotType.FOR_SALE) {
                return OperationResult.denied("This is not an eligible public claim.");
            }
            if (hasPendingPurchase(connection, claim.id())) return OperationResult.denied("A purchase is already being processed for this plot.");
            try (PreparedStatement statement = Sql.prepare(connection,
                "UPDATE civ_claims SET plot_type = ?, row_version = row_version + 1 WHERE id = ?", type, claim.id())) {
                statement.executeUpdate();
            }
            AuditLog.write(connection, member.civilizationId(), actor, "PLOT_PUBLIC_TYPE_CHANGED", "CLAIM",
                Long.toString(claim.id()), Map.of("from", claim.type().name(), "to", type.name()), settings.serverId());
            return OperationResult.ok("Plot access changed to " + type.name() + ".");
        });
    }

    public CompletableFuture<OperationResult> setDefaultPrice(UUID actor, BigDecimal price) {
        if (moneyProviderUnavailable()) return denied("Vault economy is configured, but no economy provider is available.");
        BigDecimal normalized = normalizePrice(price);
        if (normalized == null) return denied("The default price must be a non-negative currency amount.");
        if (!economyEnabled()) normalized = BigDecimal.ZERO.setScale(2);
        BigDecimal finalPrice = normalized;
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            if (!administrates(member)) return OperationResult.denied("Only leaders and advisors may set the default plot price.");
            lockCivilization(connection, member.civilizationId());
            try (PreparedStatement statement = Sql.prepare(connection, """
                UPDATE civilizations SET default_plot_price = ?, row_version = row_version + 1 WHERE id = ?
                """, finalPrice, member.civilizationId())) {
                statement.executeUpdate();
            }
            AuditLog.write(connection, member.civilizationId(), actor, "PLOT_DEFAULT_PRICE_CHANGED", "CIVILIZATION",
                Long.toString(member.civilizationId()), Map.of("price", finalPrice.toPlainString()), settings.serverId());
            return OperationResult.ok("Default plot price set to " + finalPrice.toPlainString() + ".");
        });
    }

    public CompletableFuture<OperationResult> listPublicPlot(UUID actor, ChunkKey chunk, BigDecimal requestedPrice) {
        if (moneyProviderUnavailable()) return denied("Vault economy is configured, but no economy provider is available.");
        BigDecimal supplied = requestedPrice == null ? null : normalizePrice(requestedPrice);
        if (requestedPrice != null && supplied == null) return denied("The listing price must be non-negative.");
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            if (!administrates(member)) return OperationResult.denied("Only leaders and advisors may list public plots.");
            PlotRow claim = lockClaim(connection, chunk);
            if (!sameCivilization(member, claim) || (claim.type() != PlotType.CIVIC && claim.type() != PlotType.COMMON)) {
                return OperationResult.denied("Only an unlisted public claim may be listed.");
            }
            if (isObjective(connection, claim.id())) return OperationResult.denied("A campaign objective cannot be listed.");
            if (hasPendingPurchase(connection, claim.id())) return OperationResult.denied("A purchase is already being processed.");
            BigDecimal price = economyEnabled() ? (supplied == null ? civilizationDefaultPrice(connection, member.civilizationId()) : supplied)
                : BigDecimal.ZERO.setScale(2);
            String listingKind = claim.type() == PlotType.COMMON ? LISTING_COMMON : LISTING_CIVIC;
            updateListing(connection, claim.id(), listingKind, null, price);
            AuditLog.write(connection, member.civilizationId(), actor, "PLOT_LISTED_BY_CIVILIZATION", "CLAIM",
                Long.toString(claim.id()), Map.of("price", price.toPlainString(), "former_type", claim.type().name()), settings.serverId());
            return OperationResult.ok("Plot listed for " + price.toPlainString() + ".");
        });
    }

    public CompletableFuture<OperationResult> listOwnedPlot(UUID actor, ChunkKey chunk, BigDecimal requestedPrice) {
        if (moneyProviderUnavailable()) return denied("Vault economy is configured, but no economy provider is available.");
        BigDecimal supplied = normalizePrice(requestedPrice);
        if (supplied == null) return denied("The listing price must be non-negative.");
        if (!economyEnabled()) supplied = BigDecimal.ZERO.setScale(2);
        BigDecimal price = supplied;
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            if (member == null) return OperationResult.denied("You do not belong to a civilization.");
            PlotRow claim = lockClaim(connection, chunk);
            if (!sameCivilization(member, claim) || claim.type() != PlotType.PRIVATE || !actor.equals(claim.owner())) {
                return OperationResult.denied("You do not own private tenure in this plot.");
            }
            if (hasPendingPurchase(connection, claim.id())) return OperationResult.denied("A purchase is already being processed.");
            updateListing(connection, claim.id(), LISTING_MEMBER, actor, price);
            AuditLog.write(connection, member.civilizationId(), actor, "PRIVATE_PLOT_LISTED", "CLAIM",
                Long.toString(claim.id()), Map.of("price", price.toPlainString()), settings.serverId());
            return OperationResult.ok("Private tenure listed for " + price.toPlainString() + ".");
        });
    }

    public CompletableFuture<OperationResult> unlist(UUID actor, ChunkKey chunk) {
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            PlotRow claim = lockClaim(connection, chunk);
            if (!sameCivilization(member, claim) || claim.type() != PlotType.FOR_SALE) return OperationResult.denied("This plot is not your civilization's active listing.");
            if (hasPendingPurchase(connection, claim.id())) return OperationResult.denied("A purchase is already being processed.");
            PlotType restored;
            if (LISTING_MEMBER.equals(claim.listingKind())) {
                if (!actor.equals(claim.owner()) || !actor.equals(claim.seller())) return OperationResult.denied("Only the listing owner may withdraw this resale.");
                restored = PlotType.PRIVATE;
            } else {
                if (!administrates(member)) return OperationResult.denied("Only leaders and advisors may unlist civilization property.");
                restored = LISTING_COMMON.equals(claim.listingKind()) ? PlotType.COMMON : PlotType.CIVIC;
            }
            clearListing(connection, claim.id(), restored, claim.owner());
            AuditLog.write(connection, member.civilizationId(), actor, "PLOT_UNLISTED", "CLAIM", Long.toString(claim.id()),
                Map.of("restored_type", restored.name()), settings.serverId());
            return OperationResult.ok("Plot listing removed.");
        });
    }

    public CompletableFuture<OperationResult> trust(UUID owner, ChunkKey chunk, UUID target) {
        if (owner.equals(target)) return denied("Plot owners already have access.");
        return changeTrust(owner, chunk, target, true);
    }

    public CompletableFuture<OperationResult> untrust(UUID owner, ChunkKey chunk, UUID target) {
        return changeTrust(owner, chunk, target, false);
    }

    public CompletableFuture<OperationResult> setFlag(UUID actor, ChunkKey chunk, String rawFlag, boolean enabled) {
        String flag = rawFlag == null ? "" : rawFlag.toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_FLAGS.contains(flag)) return denied("Unknown or unsafe plot flag: " + rawFlag);
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            PlotRow claim = lockClaim(connection, chunk);
            boolean owner = claim != null && actor.equals(claim.owner());
            boolean publicAdministrator = administrates(member) && claim != null
                && claim.owner() == null && claim.type() != PlotType.CAPITAL;
            if (!sameCivilization(member, claim) || (!owner && !publicAdministrator)) {
                return OperationResult.denied("You do not control this plot's access flags.");
            }
            Map<String, Boolean> flags = new LinkedHashMap<>(claim.flags());
            flags.put(flag, enabled);
            try (PreparedStatement statement = Sql.prepare(connection,
                "UPDATE civ_claims SET plot_flags = ?, row_version = row_version + 1 WHERE id = ?", JsonData.booleans(flags), claim.id())) {
                statement.executeUpdate();
            }
            AuditLog.write(connection, member.civilizationId(), actor, "PLOT_FLAG_CHANGED", "CLAIM", Long.toString(claim.id()),
                Map.of("flag", flag, "enabled", enabled), settings.serverId());
            return OperationResult.ok("Plot flag " + flag + " is now " + (enabled ? "enabled" : "disabled") + ".");
        });
    }

    public CompletableFuture<OperationResult> setHomeLabel(UUID actor, ChunkKey chunk, String value) {
        return setTextMetadata(actor, chunk, "home_label", value, 32, "home label");
    }

    public CompletableFuture<OperationResult> setGreeting(UUID actor, ChunkKey chunk, String value) {
        return setTextMetadata(actor, chunk, "greeting", value, 160, "greeting");
    }

    private CompletableFuture<OperationResult> setTextMetadata(UUID actor, ChunkKey chunk, String column,
                                                               String rawValue, int maximumLength, String label) {
        String value = rawValue == null || rawValue.isBlank() ? null : rawValue.trim();
        if (value != null && value.length() > maximumLength) {
            return denied("Plot " + label + " cannot exceed " + maximumLength + " characters.");
        }
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            PlotRow claim = lockClaim(connection, chunk);
            if (!sameCivilization(member, claim) || !actor.equals(claim.owner())
                || claim.type() != PlotType.PRIVATE && !LISTING_MEMBER.equals(claim.listingKind())) {
                return OperationResult.denied("Only the private plot owner may change its " + label + ".");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE civ_claims SET " + column + " = ?, row_version = row_version + 1 WHERE id = ?")) {
                statement.setString(1, value);
                statement.setLong(2, claim.id());
                statement.executeUpdate();
            }
            AuditLog.write(connection, member.civilizationId(), actor, "PLOT_METADATA_CHANGED", "CLAIM",
                Long.toString(claim.id()), Map.of("field", column, "value", value == null ? "" : value), settings.serverId());
            return OperationResult.ok("Plot " + label + (value == null ? " cleared." : " updated."));
        });
    }

    public SurrenderPreparation prepareSurrender(UUID actor, ChunkKey chunk) {
        if (!cache.ready()) return SurrenderPreparation.denied("Territory data is still warming up.");
        Claim claim = cache.snapshot().claim(chunk);
        if (claim == null || !actor.equals(claim.plotOwnerId())
            || (claim.plotType() != PlotType.PRIVATE && !LISTING_MEMBER.equals(claim.listingKind()))) {
            return SurrenderPreparation.denied("You do not own private tenure in this plot.");
        }
        String fingerprint = fingerprint(claim);
        return SurrenderPreparation.ready(confirmations.issue(actor, CONFIRM_SURRENDER, fingerprint),
            "Surrender is permanent and provides no refund.");
    }

    public CompletableFuture<OperationResult> surrender(UUID actor, ChunkKey chunk, String explicitToken) {
        if (!cache.ready()) return denied("Territory data is still warming up.");
        Claim claim = cache.snapshot().claim(chunk);
        if (claim == null || !confirmations.consume(actor, CONFIRM_SURRENDER, fingerprint(claim), explicitToken)) {
            return denied("That confirmation token is invalid, expired, or the plot changed.");
        }
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            PlotRow locked = lockClaim(connection, chunk);
            if (!sameCivilization(member, locked) || !actor.equals(locked.owner()) || locked.rowVersion() != claim.rowVersion()) {
                return OperationResult.denied("The plot changed before surrender could complete.");
            }
            if (hasPendingPurchase(connection, locked.id())) return OperationResult.denied("A purchase is already being processed.");
            if (economyEnabled() && PlotTaxService.hasUnsettledTax(connection, locked.id(), clock.instant()))
                return OperationResult.denied("This plot's weekly tax is being settled. Try again after it completes.");
            terminateOne(connection, locked.id());
            AuditLog.write(connection, member.civilizationId(), actor, "PRIVATE_PLOT_SURRENDERED", "CLAIM",
                Long.toString(locked.id()), Map.of("refund", "none"), settings.serverId());
            return OperationResult.ok("Private tenure surrendered with no refund.");
        });
    }

    public PurchasePreparation preparePurchase(UUID buyer, ChunkKey chunk) {
        if (moneyProviderUnavailable()) return PurchasePreparation.denied(
            "Vault economy is configured, but no economy provider is available.");
        if (!cache.ready()) return PurchasePreparation.denied("Territory data is still warming up.");
        StateSnapshot snapshot = cache.snapshot();
        Member member = snapshot.member(buyer);
        Claim claim = snapshot.claim(chunk);
        if (member == null || claim == null || claim.civilizationId() != member.civilizationId()
            || claim.plotType() != PlotType.FOR_SALE || claim.listingPrice() == null) {
            return PurchasePreparation.denied("This is not an available plot in your civilization.");
        }
        if (buyer.equals(claim.listingSellerId())) return PurchasePreparation.denied("You already own this listed tenure.");
        int maximum = TerritoryRules.plotCapacity(snapshot.technologies(member.civilizationId()), settings.plots(), technologies.get());
        long owned = snapshot.claims(member.civilizationId()).stream().filter(value -> buyer.equals(value.plotOwnerId())).count();
        if (owned >= maximum) return PurchasePreparation.denied("You are at your private plot limit of " + maximum + ".");
        String fingerprint = purchaseFingerprint(claim, buyer);
        var token = confirmations.issue(buyer, CONFIRM_PURCHASE, fingerprint);
        return new PurchasePreparation(OperationResult.ok("Review the exact price and lease warning."), token,
            effectivePrice(claim.listingPrice()),
            "This buys civilization-bound tenure only. It is non-refundable on departure, conquest, disbanding, or surrender.");
    }

    public CompletableFuture<OperationResult> purchase(UUID buyer, ChunkKey chunk, String explicitToken, BigDecimal weeklyTax) {
        if (moneyProviderUnavailable()) return denied("Vault economy is configured, but no economy provider is available.");
        if (!cache.ready()) return denied("Territory data is still warming up.");
        Claim cached = cache.snapshot().claim(chunk);
        if (cached == null || cached.plotType() != PlotType.FOR_SALE || cached.listingPrice() == null
            || !confirmations.consume(buyer, CONFIRM_PURCHASE, purchaseFingerprint(cached, buyer), explicitToken)) {
            return denied("That purchase token is invalid, expired, or the listing changed.");
        }
        Member member = cache.snapshot().member(buyer);
        if (member == null) return denied("You do not belong to a civilization.");
        UUID operationId = UUID.randomUUID();
        return database.transaction(connection -> locks.withLock(member.civilizationId(),
                () -> stagePurchase(connection, operationId, buyer, chunk, cached, weeklyTax)))
            .thenCompose(stage -> stage.result().success() ? runPurchase(stage.operation())
                : CompletableFuture.completedFuture(stage.result()));
    }

    /** Retries a persisted PENDING/DB_APPLIED/COMPENSATION_PENDING economy saga. */
    public CompletableFuture<OperationResult> resumePurchase(UUID operationId) {
        return database.read(connection -> loadOperation(connection, operationId))
            .thenCompose(operation -> operation == null ? denied("Unknown purchase operation.") : runPurchase(operation));
    }

    /**
     * Transaction hook for membership removal/disbanding. The caller owns the
     * surrounding Database transaction and civilization row lock.
     */
    public int terminatePrivateRights(Connection connection, long civilizationId, UUID formerOwner,
                                      UUID actor, String reason) throws Exception {
        int claims;
        try (PreparedStatement deleteTrust = Sql.prepare(connection, """
                 DELETE t FROM plot_trust t JOIN civ_claims c ON c.id = t.claim_id
                 WHERE c.civ_id = ? AND c.plot_owner_uuid = ?
                 """, civilizationId, formerOwner);
             PreparedStatement update = Sql.prepare(connection, """
                 UPDATE civ_claims SET plot_type = 'CIVIC', plot_owner_uuid = NULL, listing_kind = NULL,
                    listing_seller_uuid = NULL, listing_price = NULL, original_purchase_price = NULL,
                    plot_flags = NULL, home_label = NULL, greeting = NULL, listed_at = NULL,
                    purchased_at = NULL, purchased_by = NULL, row_version = row_version + 1
                 WHERE civ_id = ? AND plot_owner_uuid = ?
                 """, civilizationId, formerOwner)) {
            deleteTrust.executeUpdate();
            claims = update.executeUpdate();
        }
        if (claims > 0) AuditLog.write(connection, civilizationId, actor, "PRIVATE_RIGHTS_TERMINATED", "PLAYER",
            formerOwner.toString(), Map.of("claims", claims, "reason", reason, "refund", "none"), settings.serverId());
        return claims;
    }

    public int plotCapacity(long civilizationId) {
        return TerritoryRules.plotCapacity(cache.snapshot().technologies(civilizationId), settings.plots(), technologies.get());
    }

    private CompletableFuture<OperationResult> changeTrust(UUID actor, ChunkKey chunk, UUID target, boolean add) {
        return mutateOwnedCivilization(actor, connection -> {
            MemberLock member = lockMember(connection, actor);
            MemberLock trusted = lockMember(connection, target);
            PlotRow claim = lockClaim(connection, chunk);
            if (!sameCivilization(member, claim) || !actor.equals(claim.owner())
                || (claim.type() != PlotType.PRIVATE && !LISTING_MEMBER.equals(claim.listingKind()))) {
                return OperationResult.denied("Only the private plot owner may manage trust.");
            }
            if (trusted == null || trusted.civilizationId() != member.civilizationId()) {
                return OperationResult.denied("Only current citizens of this civilization may be trusted.");
            }
            if (add) {
                try (PreparedStatement statement = Sql.prepare(connection, """
                    INSERT INTO plot_trust(claim_id, trusted_player_uuid, permission_mask, granted_by, created_at)
                    VALUES (?, ?, 7, ?, ?) ON DUPLICATE KEY UPDATE permission_mask = 7, granted_by = VALUES(granted_by)
                    """, claim.id(), target, actor, clock.instant())) {
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = Sql.prepare(connection,
                    "DELETE FROM plot_trust WHERE claim_id = ? AND trusted_player_uuid = ?", claim.id(), target)) {
                    statement.executeUpdate();
                }
            }
            AuditLog.write(connection, member.civilizationId(), actor, add ? "PLOT_TRUST_ADDED" : "PLOT_TRUST_REMOVED",
                "CLAIM", Long.toString(claim.id()), Map.of("player", target.toString()), settings.serverId());
            return OperationResult.ok(add ? "Citizen trusted on this plot." : "Citizen removed from this plot's trust list.");
        });
    }

    private Stage stagePurchase(Connection connection, UUID operationId, UUID buyer, ChunkKey chunk,
                                Claim expected, BigDecimal weeklyTax) throws Exception {
        MemberLock member = lockMember(connection, buyer);
        if (member == null) return Stage.denied("You are no longer a member of this civilization.");
        lockCivilization(connection, member.civilizationId());
        PlotRow claim = lockClaim(connection, chunk);
        if (!sameCivilization(member, claim) || claim.type() != PlotType.FOR_SALE || claim.price() == null
            || claim.rowVersion() != expected.rowVersion() || !claim.price().equals(expected.listingPrice())
            || !Objects.equals(claim.listingKind(), expected.listingKind())
            || !Objects.equals(claim.seller(), expected.listingSellerId())) {
            return Stage.denied("The listing changed before purchase processing began.");
        }
        if (buyer.equals(claim.seller())) return Stage.denied("You already own this tenure.");
        if (weeklyTax == null || PlotTaxService.rate(connection, member.civilizationId()).compareTo(weeklyTax) != 0)
            return Stage.denied("The weekly plot tax changed. Run /civ plot buy again to review the new rate.");
        if (economyEnabled() && PlotTaxService.hasUnsettledTax(connection, claim.id(), clock.instant()))
            return Stage.denied("The current owner's weekly tax is being settled. Try again after it completes.");
        PurchaseOperation existing = activePurchase(connection, claim.id());
        if (existing != null) {
            if (existing.buyer().equals(buyer)) return new Stage(OperationResult.ok("Resuming the existing purchase."), existing);
            return Stage.denied("Another buyer's purchase is already being processed.");
        }
        Set<String> unlocked = technologyKeys(connection, member.civilizationId());
        int maximum = TerritoryRules.plotCapacity(unlocked, settings.plots(), technologies.get());
        if (ownedPlotCount(connection, member.civilizationId(), buyer) >= maximum) {
            return Stage.denied("You are at your private plot limit of " + maximum + ".");
        }
        BigDecimal amount = effectivePrice(claim.price());
        Instant now = clock.instant();
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO economy_operations(operation_id, operation_type, player_uuid, beneficiary_uuid, civ_id,
                claim_id, amount, state, provider_response, context_json, created_at, updated_at)
            VALUES (?, 'PLOT_PURCHASE', ?, ?, ?, ?, ?, 'PENDING', NULL, ?, ?, ?)
            """)) {
            statement.setBytes(1, UuidBytes.toBytes(operationId));
            statement.setBytes(2, UuidBytes.toBytes(buyer));
            statement.setBytes(3, UuidBytes.toBytes(claim.seller()));
            statement.setLong(4, member.civilizationId());
            statement.setLong(5, claim.id());
            statement.setBigDecimal(6, amount);
            statement.setString(7, JsonData.object(Map.of(
                "claim_row_version", claim.rowVersion(), "listing_kind", claim.listingKind(),
                "price", amount.toPlainString(), "weekly_tax", weeklyTax.toPlainString())));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setTimestamp(9, Timestamp.from(now));
            statement.executeUpdate();
        }
        return new Stage(OperationResult.ok("Purchase staged."), new PurchaseOperation(operationId, buyer, claim.seller(),
            member.civilizationId(), claim.id(), amount, EconomyOperationState.PENDING, claim.rowVersion(), claim.listingKind(), "", weeklyTax));
    }

    private CompletableFuture<OperationResult> runPurchase(PurchaseOperation operation) {
        return switch (operation.state()) {
            case COMPLETED -> CompletableFuture.completedFuture(OperationResult.ok("Purchase already completed."));
            case FAILED -> denied("This purchase operation has failed and cannot be replayed.");
            case DB_APPLIED -> creditSeller(operation);
            case COMPENSATION_PENDING -> isRefundPending(operation)
                ? retryRefund(operation) : creditSeller(operation);
            case EXTERNAL_APPLIED -> finishChargedPurchase(operation);
            case PENDING -> beginBuyerWithdrawal(operation).thenCompose(start -> {
                if (start.result() != null) return CompletableFuture.completedFuture(start.result());
                if (!start.dispatch()) return runPurchase(start.operation());
                PurchaseOperation claimed = start.operation();
                return economy.withdraw(claimed.id(), claimed.buyer(), claimed.amount())
                    .exceptionally(error -> EconomyPort.Result.ambiguous(rootMessage(error)))
                    .thenCompose(result -> recordBuyerWithdrawal(claimed, result));
            });
            case WITHDRAWAL_IN_FLIGHT, DELIVERY_IN_FLIGHT, REFUND_IN_FLIGHT -> denied(
                "This purchase has an ambiguous Vault outcome and requires administrator reconciliation; "
                    + "it will not be replayed automatically.");
            case PENDING_DELIVERY -> denied("This plot purchase has an invalid economy state.");
        };
    }

    private CompletableFuture<BuyerWithdrawalStart> beginBuyerWithdrawal(PurchaseOperation supplied) {
        return database.transaction(connection -> locks.withLock(supplied.civilizationId(), () -> {
            PurchaseOperation operation = lockOperation(connection, supplied.id());
            if (operation == null) return BuyerWithdrawalStart.done(
                OperationResult.denied("The persisted purchase operation disappeared."));
            if (operation.state() != EconomyOperationState.PENDING) return BuyerWithdrawalStart.continueWith(operation);
            PurchaseValidation validation = validatePurchase(connection, operation);
            if (!validation.valid()) {
                updateOperation(connection, operation.id(), EconomyOperationState.PENDING, EconomyOperationState.FAILED,
                    "PRECHECK_FAILED:" + validation.message());
                return BuyerWithdrawalStart.done(OperationResult.denied(validation.message()));
            }
            updateOperation(connection, operation.id(), EconomyOperationState.PENDING,
                EconomyOperationState.WITHDRAWAL_IN_FLIGHT, "WITHDRAWAL_DISPATCHED");
            return BuyerWithdrawalStart.dispatch(withState(operation, EconomyOperationState.WITHDRAWAL_IN_FLIGHT,
                "WITHDRAWAL_DISPATCHED"));
        }));
    }

    private CompletableFuture<OperationResult> recordBuyerWithdrawal(PurchaseOperation operation,
                                                                      EconomyPort.Result result) {
        if (result.ambiguous()) {
            return recordAmbiguous(operation.id(), EconomyOperationState.WITHDRAWAL_IN_FLIGHT,
                    result.providerMessage())
                .thenApply(ignored -> OperationResult.denied(
                    "The buyer payment outcome is unknown and requires administrator reconciliation."));
        }
        EconomyOperationState next = result.success() ? EconomyOperationState.EXTERNAL_APPLIED
            : EconomyOperationState.FAILED;
        return database.transaction(connection -> {
            updateOperation(connection, operation.id(), EconomyOperationState.WITHDRAWAL_IN_FLIGHT, next,
                result.providerMessage());
            return withState(operation, next, result.providerMessage());
        }).thenCompose(updated -> result.success() ? finishChargedPurchase(updated)
            : denied("Payment failed: " + result.providerMessage()));
    }

    private CompletableFuture<OperationResult> finishChargedPurchase(PurchaseOperation operation) {
        return database.transaction(connection -> locks.withLock(operation.civilizationId(),
                () -> finalizePurchase(connection, operation, operation.providerMessage())))
            .thenCompose(finalization -> {
                if (!finalization.result().success()) {
                    return refundAfterFailedFinalization(finalization.operation(), finalization.result());
                }
                return cache.refreshAfterMutation().handle((ignored, failure) -> failure)
                    .thenCompose(refreshFailure -> {
                        CompletableFuture<OperationResult> completion = finalization.seller() == null
                            || finalization.sellerProceeds().signum() == 0
                            ? CompletableFuture.completedFuture(finalization.result())
                            : creditSeller(finalization.operation());
                        return completion.thenApply(result -> refreshFailure == null ? result : OperationResult.ok(
                            result.message() + " Protection remains fail-closed until the cache reload succeeds."));
                    });
            });
    }

    private Finalization finalizePurchase(Connection connection, PurchaseOperation supplied,
                                          String providerMessage) throws Exception {
        PurchaseOperation operation = lockOperation(connection, supplied.id());
        if (operation == null) return Finalization.denied(supplied, "The persisted purchase operation disappeared.");
        if (operation.state() == EconomyOperationState.COMPLETED) return new Finalization(OperationResult.ok("Purchase already completed."), operation, null, BigDecimal.ZERO);
        if (operation.state() == EconomyOperationState.DB_APPLIED) return memberCreditFinalization(operation);
        if (operation.state() != EconomyOperationState.EXTERNAL_APPLIED) {
            return Finalization.denied(operation, "The buyer payment is not in a finalizable state.");
        }
        PurchaseValidation validation = validatePurchase(connection, operation);
        if (!validation.valid()) return Finalization.denied(operation, validation.message());
        PlotRow claim = validation.claim();
        BigDecimal tax = LISTING_MEMBER.equals(claim.listingKind())
            ? operation.amount().multiply(BigDecimal.valueOf(settings.plots().saleTaxPercent()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : operation.amount();
        Instant purchasedAt = clock.instant();
        BigDecimal sellerProceeds = LISTING_MEMBER.equals(claim.listingKind()) ? operation.amount().subtract(tax) : BigDecimal.ZERO.setScale(2);
        try (PreparedStatement deleteTrust = Sql.prepare(connection, "DELETE FROM plot_trust WHERE claim_id = ?", claim.id());
             PreparedStatement update = connection.prepareStatement("""
                 UPDATE civ_claims SET plot_type = 'PRIVATE', plot_owner_uuid = ?, listing_kind = NULL,
                    listing_seller_uuid = NULL, listing_price = NULL, original_purchase_price = ?,
                    plot_flags = NULL, home_label = NULL, greeting = NULL, listed_at = NULL,
                    purchased_at = ?, purchased_by = ?, row_version = row_version + 1 WHERE id = ? AND row_version = ?
                 """)) {
            deleteTrust.executeUpdate();
            update.setBytes(1, UuidBytes.toBytes(operation.buyer()));
            update.setBigDecimal(2, operation.amount());
            update.setTimestamp(3, Timestamp.from(purchasedAt));
            update.setBytes(4, UuidBytes.toBytes(operation.buyer()));
            update.setLong(5, claim.id());
            update.setLong(6, claim.rowVersion());
            if (update.executeUpdate() != 1) return Finalization.denied(operation, "The plot changed; payment will be refunded.");
        }
        PlotTaxService.startOwnership(connection, claim.id(), operation.civilizationId(), operation.buyer(),
            purchasedAt, purchasedAt, operation.weeklyTax());
        creditTreasury(connection, operation, tax);
        EconomyOperationState state = sellerProceeds.signum() > 0 ? EconomyOperationState.DB_APPLIED : EconomyOperationState.COMPLETED;
        updateOperation(connection, operation.id(), EconomyOperationState.EXTERNAL_APPLIED, state, providerMessage);
        AuditLog.write(connection, operation.civilizationId(), operation.buyer(), "PLOT_PURCHASE_COMPLETED", "CLAIM",
            Long.toString(claim.id()), Map.of("operation", operation.id().toString(), "price", operation.amount().toPlainString(),
                "tax", tax.toPlainString(), "seller_proceeds", sellerProceeds.toPlainString(),
                "lease_non_refundable", true), settings.serverId());
        PurchaseOperation updated = new PurchaseOperation(operation.id(), operation.buyer(), operation.seller(),
            operation.civilizationId(), operation.claimId(), operation.amount(), state,
            operation.claimRowVersion(), operation.listingKind(), providerMessage, operation.weeklyTax());
        return new Finalization(OperationResult.ok("Private plot tenure purchased."), updated, operation.seller(), sellerProceeds);
    }

    private CompletableFuture<OperationResult> creditSeller(PurchaseOperation operation) {
        return database.transaction(connection -> locks.withLock(operation.civilizationId(),
                () -> beginSellerCredit(connection, operation.id())))
            .thenCompose(start -> {
                if (!start.dispatch()) return CompletableFuture.completedFuture(start.result());
                PurchaseOperation claimed = start.operation();
                BigDecimal proceeds = sellerProceeds(claimed);
                return economy.deposit(claimed.id(), claimed.seller(), proceeds)
                    .exceptionally(error -> EconomyPort.Result.ambiguous(rootMessage(error)))
                    .thenCompose(result -> recordSellerCredit(claimed, result));
            });
    }

    private CompletableFuture<OperationResult> refundAfterFailedFinalization(PurchaseOperation operation, OperationResult failure) {
        return dispatchRefund(operation, failure.message());
    }

    private CompletableFuture<OperationResult> retryRefund(PurchaseOperation operation) {
        return dispatchRefund(operation, "The plot purchase could not be completed.");
    }

    private SellerCreditStart beginSellerCredit(Connection connection, UUID operationId) throws Exception {
        PurchaseOperation operation = lockOperation(connection, operationId);
        if (operation == null) return SellerCreditStart.done(OperationResult.denied("Purchase operation disappeared."));
        if (operation.state() == EconomyOperationState.COMPLETED) {
            return SellerCreditStart.done(OperationResult.ok("Private plot tenure purchased; seller payment was already completed."));
        }
        if (operation.state() == EconomyOperationState.DELIVERY_IN_FLIGHT) {
            return SellerCreditStart.done(OperationResult.ok(
                "Private plot tenure purchased; seller payout is ambiguous and requires administrator reconciliation."));
        }
        boolean retry = operation.state() == EconomyOperationState.COMPENSATION_PENDING && !isRefundPending(operation);
        if (operation.state() != EconomyOperationState.DB_APPLIED && !retry) {
            return SellerCreditStart.done(OperationResult.denied("Seller payout cannot run in state " + operation.state() + "."));
        }
        if (operation.seller() == null || sellerProceeds(operation).signum() == 0) {
            updateOperation(connection, operation.id(), operation.state(), EconomyOperationState.COMPLETED,
                "No seller credit required");
            return SellerCreditStart.done(OperationResult.ok("Private plot tenure purchased."));
        }
        updateOperation(connection, operation.id(), operation.state(), EconomyOperationState.DELIVERY_IN_FLIGHT,
            "SELLER_CREDIT_DISPATCHED");
        return SellerCreditStart.dispatch(withState(operation, EconomyOperationState.DELIVERY_IN_FLIGHT,
            "SELLER_CREDIT_DISPATCHED"));
    }

    private CompletableFuture<OperationResult> recordSellerCredit(PurchaseOperation operation, EconomyPort.Result result) {
        if (result.ambiguous()) {
            return recordAmbiguous(operation.id(), EconomyOperationState.DELIVERY_IN_FLIGHT, result.providerMessage())
                .thenApply(ignored -> OperationResult.ok(
                    "Private plot tenure purchased; seller payout is ambiguous and requires administrator reconciliation."));
        }
        EconomyOperationState next = result.success() ? EconomyOperationState.COMPLETED
            : EconomyOperationState.COMPENSATION_PENDING;
        String response = (result.success() ? "SELLER_CREDIT:" : "SELLER_CREDIT_PENDING:") + result.providerMessage();
        return database.transaction(connection -> {
            updateOperation(connection, operation.id(), EconomyOperationState.DELIVERY_IN_FLIGHT, next, response);
            return result.success()
                ? OperationResult.ok("Private plot tenure purchased; the seller was credited.")
                : OperationResult.ok("The purchase completed, but seller credit is queued for retry.");
        });
    }

    private CompletableFuture<OperationResult> dispatchRefund(PurchaseOperation operation, String failureMessage) {
        return database.transaction(connection -> locks.withLock(operation.civilizationId(),
                () -> beginRefund(connection, operation.id())))
            .thenCompose(start -> {
                if (!start.dispatch()) return CompletableFuture.completedFuture(start.result());
                PurchaseOperation claimed = start.operation();
                return economy.refund(claimed.id(), claimed.buyer(), claimed.amount())
                    .exceptionally(error -> EconomyPort.Result.ambiguous(rootMessage(error)))
                    .thenCompose(result -> recordBuyerRefund(claimed, result, failureMessage));
            });
    }

    private RefundStart beginRefund(Connection connection, UUID operationId) throws Exception {
        PurchaseOperation operation = lockOperation(connection, operationId);
        if (operation == null) return RefundStart.done(OperationResult.denied("Purchase operation disappeared."));
        if (operation.state() == EconomyOperationState.FAILED && operation.providerMessage().startsWith("REFUNDED:")) {
            return RefundStart.done(OperationResult.denied("The failed purchase was already refunded."));
        }
        if (operation.state() == EconomyOperationState.REFUND_IN_FLIGHT) {
            return RefundStart.done(OperationResult.denied(
                "The refund outcome is ambiguous and requires administrator reconciliation."));
        }
        boolean retry = operation.state() == EconomyOperationState.COMPENSATION_PENDING && isRefundPending(operation);
        if (operation.state() != EconomyOperationState.EXTERNAL_APPLIED && !retry) {
            return RefundStart.done(OperationResult.denied("Buyer refund cannot run in state " + operation.state() + "."));
        }
        updateOperation(connection, operation.id(), operation.state(), EconomyOperationState.REFUND_IN_FLIGHT,
            "REFUND_DISPATCHED");
        return RefundStart.dispatch(withState(operation, EconomyOperationState.REFUND_IN_FLIGHT,
            "REFUND_DISPATCHED"));
    }

    private CompletableFuture<OperationResult> recordBuyerRefund(PurchaseOperation operation, EconomyPort.Result result,
                                                                  String failureMessage) {
        if (result.ambiguous()) {
            return recordAmbiguous(operation.id(), EconomyOperationState.REFUND_IN_FLIGHT, result.providerMessage())
                .thenApply(ignored -> OperationResult.denied(failureMessage
                    + " The refund outcome is ambiguous and requires administrator reconciliation."));
        }
        EconomyOperationState next = result.success() ? EconomyOperationState.FAILED
            : EconomyOperationState.COMPENSATION_PENDING;
        String response = (result.success() ? "REFUNDED:" : "REFUND_PENDING:") + result.providerMessage();
        return database.transaction(connection -> {
            updateOperation(connection, operation.id(), EconomyOperationState.REFUND_IN_FLIGHT, next, response);
            return result.success() ? OperationResult.denied(failureMessage + " The payment was refunded.")
                : OperationResult.denied(failureMessage + " The refund is queued for retry.");
        });
    }

    private PurchaseValidation validatePurchase(Connection connection, PurchaseOperation operation) throws Exception {
        MemberLock buyer = lockMember(connection, operation.buyer());
        PlotRow claim = lockClaimById(connection, operation.claimId());
        if (buyer == null || buyer.civilizationId() != operation.civilizationId()) {
            return PurchaseValidation.denied("You are no longer a citizen of the selling civilization.");
        }
        if (claim == null || claim.civilizationId() != operation.civilizationId() || claim.type() != PlotType.FOR_SALE
            || claim.rowVersion() != operation.claimRowVersion() || !Objects.equals(claim.listingKind(), operation.listingKind())
            || !Objects.equals(claim.seller(), operation.seller()) || claim.price() == null
            || effectivePrice(claim.price()).compareTo(operation.amount()) != 0) {
            return PurchaseValidation.denied("The plot listing changed; payment will not be taken.");
        }
        Set<String> unlocked = technologyKeys(connection, operation.civilizationId());
        int maximum = TerritoryRules.plotCapacity(unlocked, settings.plots(), technologies.get());
        if (ownedPlotCount(connection, operation.civilizationId(), operation.buyer()) >= maximum) {
            return PurchaseValidation.denied("Your plot limit changed; payment will not be taken.");
        }
        return PurchaseValidation.valid(claim);
    }

    private CompletableFuture<Void> recordAmbiguous(UUID id, EconomyOperationState expected, String providerMessage) {
        return database.transaction(connection -> {
            try (PreparedStatement statement = Sql.prepare(connection, """
                UPDATE economy_operations SET provider_response = ?, retry_count = retry_count + 1,
                    updated_at = ? WHERE operation_id = ? AND state = ?
                """, truncate(providerMessage), clock.instant(), id, expected)) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void creditTreasury(Connection connection, PurchaseOperation operation, BigDecimal amount) throws SQLException {
        BigDecimal prior;
        try (PreparedStatement lock = Sql.prepare(connection,
            "SELECT treasury_balance FROM civilizations WHERE id = ? FOR UPDATE", operation.civilizationId());
             ResultSet result = lock.executeQuery()) {
            if (!result.next()) throw new SQLException("Civilization disappeared during plot sale");
            prior = result.getBigDecimal(1);
        }
        BigDecimal resulting = prior.add(amount);
        try (PreparedStatement update = Sql.prepare(connection, """
                 UPDATE civilizations SET treasury_balance = ?, row_version = row_version + 1 WHERE id = ?
                 """, resulting, operation.civilizationId());
             PreparedStatement ledger = connection.prepareStatement("""
                 INSERT INTO treasury_ledger(operation_id, civ_id, actor_uuid, beneficiary_uuid, amount,
                    prior_balance, resulting_balance, reason, related_type, related_id, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, 'PLOT_SALE', 'CLAIM', ?, ?)
                 """)) {
            update.executeUpdate();
            ledger.setBytes(1, UuidBytes.toBytes(operation.id()));
            ledger.setLong(2, operation.civilizationId());
            ledger.setBytes(3, UuidBytes.toBytes(operation.buyer()));
            ledger.setBytes(4, UuidBytes.toBytes(operation.seller()));
            ledger.setBigDecimal(5, amount);
            ledger.setBigDecimal(6, prior);
            ledger.setBigDecimal(7, resulting);
            ledger.setString(8, Long.toString(operation.claimId()));
            ledger.setTimestamp(9, Timestamp.from(clock.instant()));
            ledger.executeUpdate();
        }
    }

    private CompletableFuture<OperationResult> mutateOwnedCivilization(UUID actor, DbMutation work) {
        if (!cache.ready()) return denied("Territory data is still warming up.");
        Member cached = cache.snapshot().member(actor);
        if (cached == null) return denied("You do not belong to a civilization.");
        return database.transaction(connection -> locks.withLock(cached.civilizationId(), () -> work.run(connection)))
            .thenCompose(result -> {
                if (!result.success()) return CompletableFuture.completedFuture(result);
                return cache.refreshAfterMutation().handle((ignored, failure) -> failure == null ? result
                    : OperationResult.ok(result.message() + " Protection remains fail-closed until the cache reload succeeds."));
            });
    }

    private MemberLock lockMember(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT civ_id, role FROM civ_members WHERE player_uuid = ? FOR UPDATE", player);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? new MemberLock(result.getLong(1), Role.valueOf(result.getString(2))) : null;
        }
    }

    private void lockCivilization(Connection connection, long civilizationId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT id FROM civilizations WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", civilizationId);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Civilization is not active");
        }
    }

    private PlotRow lockClaim(Connection connection, ChunkKey chunk) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, civ_id, plot_type, plot_owner_uuid, listing_kind, listing_seller_uuid, listing_price,
                   plot_flags, row_version FROM civ_claims
            WHERE world_uuid = ? AND chunk_x = ? AND chunk_z = ? FOR UPDATE
            """)) {
            statement.setBytes(1, UuidBytes.toBytes(chunk.worldId()));
            statement.setInt(2, chunk.x());
            statement.setInt(3, chunk.z());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? plotRow(result) : null;
            }
        }
    }

    private PlotRow lockClaimById(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT id, civ_id, plot_type, plot_owner_uuid, listing_kind, listing_seller_uuid, listing_price,
                   plot_flags, row_version FROM civ_claims WHERE id = ? FOR UPDATE
            """, id); ResultSet result = statement.executeQuery()) {
            return result.next() ? plotRow(result) : null;
        }
    }

    private PlotRow plotRow(ResultSet result) throws SQLException {
        return new PlotRow(result.getLong("id"), result.getLong("civ_id"), PlotType.valueOf(result.getString("plot_type")),
            UuidBytes.get(result, "plot_owner_uuid"), result.getString("listing_kind"),
            UuidBytes.get(result, "listing_seller_uuid"), result.getBigDecimal("listing_price"),
            JsonData.booleans(result.getString("plot_flags")), result.getLong("row_version"));
    }

    private Set<String> technologyKeys(Connection connection, long civilizationId) throws SQLException {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT technology_key FROM civ_technologies WHERE civ_id = ?", civilizationId);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) values.add(result.getString(1));
        }
        return Set.copyOf(values);
    }

    private long ownedPlotCount(Connection connection, long civilizationId, UUID owner) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT COUNT(*) FROM civ_claims WHERE civ_id = ? AND plot_owner_uuid = ?", civilizationId, owner);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private BigDecimal civilizationDefaultPrice(Connection connection, long civilizationId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT default_plot_price FROM civilizations WHERE id = ? FOR UPDATE", civilizationId);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Civilization disappeared");
            return result.getBigDecimal(1).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    private boolean isObjective(Connection connection, long claimId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT 1 FROM war_objectives o JOIN wars w ON w.id = o.war_id
            WHERE o.target_claim_id = ? AND w.state IN ('PENDING','ACTIVE','RESOLVING') LIMIT 1 FOR UPDATE
            """, claimId); ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private boolean hasPendingPurchase(Connection connection, long claimId) throws SQLException {
        return activePurchase(connection, claimId) != null;
    }

    private PurchaseOperation activePurchase(Connection connection, long claimId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            SELECT * FROM economy_operations WHERE claim_id = ? AND operation_type = 'PLOT_PURCHASE'
              AND state IN ('PENDING','WITHDRAWAL_IN_FLIGHT','EXTERNAL_APPLIED','DB_APPLIED',
                            'DELIVERY_IN_FLIGHT','COMPENSATION_PENDING','REFUND_IN_FLIGHT')
            ORDER BY created_at LIMIT 1 FOR UPDATE
            """, claimId); ResultSet result = statement.executeQuery()) {
            return result.next() ? operation(result) : null;
        }
    }

    private PurchaseOperation loadOperation(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT * FROM economy_operations WHERE operation_id = ? AND operation_type = 'PLOT_PURCHASE'", operationId);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? operation(result) : null;
        }
    }

    private PurchaseOperation lockOperation(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection,
            "SELECT * FROM economy_operations WHERE operation_id = ? AND operation_type = 'PLOT_PURCHASE' FOR UPDATE", operationId);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? operation(result) : null;
        }
    }

    private PurchaseOperation operation(ResultSet result) throws SQLException {
        String context = result.getString("context_json");
        JsonObject json = context == null ? new JsonObject() : JsonParser.parseString(context).getAsJsonObject();
        return new PurchaseOperation(UuidBytes.get(result, "operation_id"), UuidBytes.get(result, "player_uuid"),
            UuidBytes.get(result, "beneficiary_uuid"), result.getLong("civ_id"), result.getLong("claim_id"),
            result.getBigDecimal("amount"), EconomyOperationState.valueOf(result.getString("state")),
            json.has("claim_row_version") ? json.get("claim_row_version").getAsLong() : -1,
            json.has("listing_kind") ? json.get("listing_kind").getAsString() : "",
            Objects.toString(result.getString("provider_response"), ""),
            json.has("weekly_tax") ? json.get("weekly_tax").getAsBigDecimal() : BigDecimal.ZERO.setScale(2));
    }

    private void updateOperation(Connection connection, UUID id, EconomyOperationState expected,
                                 EconomyOperationState state, String providerMessage) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            UPDATE economy_operations SET state = ?, provider_response = ?, retry_count = retry_count + 1,
                updated_at = ? WHERE operation_id = ? AND state = ?
            """, state, truncate(providerMessage), clock.instant(), id, expected)) {
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Economy operation state changed concurrently");
            }
        }
    }

    private void updateListing(Connection connection, long claimId, String kind, UUID seller, BigDecimal price) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            UPDATE civ_claims SET plot_type = 'FOR_SALE', listing_kind = ?, listing_seller_uuid = ?,
                listing_price = ?, listed_at = ?, row_version = row_version + 1 WHERE id = ?
            """, kind, seller, price, clock.instant(), claimId)) {
            statement.executeUpdate();
        }
    }

    private void clearListing(Connection connection, long claimId, PlotType restored, UUID owner) throws SQLException {
        try (PreparedStatement statement = Sql.prepare(connection, """
            UPDATE civ_claims SET plot_type = ?, plot_owner_uuid = ?, listing_kind = NULL,
                listing_seller_uuid = NULL, listing_price = NULL, listed_at = NULL,
                row_version = row_version + 1 WHERE id = ?
            """, restored, owner, claimId)) {
            statement.executeUpdate();
        }
    }

    private void terminateOne(Connection connection, long claimId) throws SQLException {
        try (PreparedStatement trust = Sql.prepare(connection, "DELETE FROM plot_trust WHERE claim_id = ?", claimId);
             PreparedStatement claim = Sql.prepare(connection, """
                 UPDATE civ_claims SET plot_type = 'CIVIC', plot_owner_uuid = NULL, listing_kind = NULL,
                    listing_seller_uuid = NULL, listing_price = NULL, original_purchase_price = NULL,
                    plot_flags = NULL, home_label = NULL, greeting = NULL, listed_at = NULL,
                    purchased_at = NULL, purchased_by = NULL, row_version = row_version + 1 WHERE id = ?
                 """, claimId)) {
            trust.executeUpdate();
            claim.executeUpdate();
        }
    }

    private Finalization memberCreditFinalization(PurchaseOperation operation) {
        BigDecimal tax = operation.amount().multiply(BigDecimal.valueOf(settings.plots().saleTaxPercent()))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Finalization(OperationResult.ok("Property transfer already committed."), operation,
            operation.seller(), operation.amount().subtract(tax));
    }

    private BigDecimal sellerProceeds(PurchaseOperation operation) {
        BigDecimal tax = operation.amount().multiply(BigDecimal.valueOf(settings.plots().saleTaxPercent()))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return operation.amount().subtract(tax);
    }

    private static boolean isRefundPending(PurchaseOperation operation) {
        return operation.providerMessage().startsWith("REFUND_PENDING:")
            || operation.providerMessage().startsWith("REFUND:");
    }

    private static PurchaseOperation withState(PurchaseOperation operation, EconomyOperationState state,
                                               String providerMessage) {
        return new PurchaseOperation(operation.id(), operation.buyer(), operation.seller(),
            operation.civilizationId(), operation.claimId(), operation.amount(), state,
            operation.claimRowVersion(), operation.listingKind(), providerMessage, operation.weeklyTax());
    }

    private BigDecimal effectivePrice(BigDecimal price) {
        return economyEnabled() ? price.setScale(2, RoundingMode.UNNECESSARY) : BigDecimal.ZERO.setScale(2);
    }

    private boolean economyEnabled() {
        return settings.economyMode() != Settings.EconomyMode.DISABLED;
    }

    private boolean moneyProviderUnavailable() {
        return settings.economyMode() != Settings.EconomyMode.DISABLED && !economy.available();
    }

    private static BigDecimal normalizePrice(BigDecimal price) {
        if (price == null || price.signum() < 0) return null;
        try {
            BigDecimal normalized = price.setScale(2, RoundingMode.UNNECESSARY);
            return normalized.precision() - normalized.scale() <= 17 ? normalized : null;
        } catch (ArithmeticException invalidScale) {
            return null;
        }
    }

    private static boolean administrates(MemberLock member) {
        return member != null && member.role().atLeast(Role.ADVISOR);
    }

    private static boolean sameCivilization(MemberLock member, PlotRow claim) {
        return member != null && claim != null && member.civilizationId() == claim.civilizationId();
    }

    private static String fingerprint(Claim claim) {
        return claim.id() + ":" + claim.rowVersion() + ":" + claim.plotType() + ":" + claim.plotOwnerId();
    }

    private String purchaseFingerprint(Claim claim, UUID buyer) {
        return claim.id() + ":" + claim.rowVersion() + ":" + claim.listingKind() + ":"
            + claim.listingSellerId() + ":" + effectivePrice(claim.listingPrice()).toPlainString() + ":" + buyer;
    }

    private static String truncate(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= 512 ? safe : safe.substring(0, 512);
    }

    private static String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private static CompletableFuture<OperationResult> denied(String message) {
        return CompletableFuture.completedFuture(OperationResult.denied(message));
    }

    public record PurchasePreparation(OperationResult validation, ConfirmationTokens.Confirmation confirmation,
                                      BigDecimal exactPrice, String leaseWarning) {
        static PurchasePreparation denied(String message) {
            return new PurchasePreparation(OperationResult.denied(message), null, null, null);
        }
    }

    public record SurrenderPreparation(OperationResult validation, ConfirmationTokens.Confirmation confirmation,
                                       String warning) {
        static SurrenderPreparation denied(String message) {
            return new SurrenderPreparation(OperationResult.denied(message), null, null);
        }

        static SurrenderPreparation ready(ConfirmationTokens.Confirmation confirmation, String warning) {
            return new SurrenderPreparation(OperationResult.ok(warning), confirmation, warning);
        }
    }

    private record MemberLock(long civilizationId, Role role) {}

    private record PlotRow(long id, long civilizationId, PlotType type, UUID owner, String listingKind,
                           UUID seller, BigDecimal price, Map<String, Boolean> flags, long rowVersion) {}

    private record PurchaseOperation(UUID id, UUID buyer, UUID seller, long civilizationId, long claimId,
                                     BigDecimal amount, EconomyOperationState state, long claimRowVersion,
                                     String listingKind, String providerMessage, BigDecimal weeklyTax) {}

    private record Stage(OperationResult result, PurchaseOperation operation) {
        static Stage denied(String message) { return new Stage(OperationResult.denied(message), null); }
    }

    private record BuyerWithdrawalStart(OperationResult result, PurchaseOperation operation, boolean dispatch) {
        static BuyerWithdrawalStart done(OperationResult result) {
            return new BuyerWithdrawalStart(result, null, false);
        }

        static BuyerWithdrawalStart continueWith(PurchaseOperation operation) {
            return new BuyerWithdrawalStart(null, operation, false);
        }

        static BuyerWithdrawalStart dispatch(PurchaseOperation operation) {
            return new BuyerWithdrawalStart(null, operation, true);
        }
    }

    private record SellerCreditStart(OperationResult result, PurchaseOperation operation, boolean dispatch) {
        static SellerCreditStart done(OperationResult result) {
            return new SellerCreditStart(result, null, false);
        }

        static SellerCreditStart dispatch(PurchaseOperation operation) {
            return new SellerCreditStart(null, operation, true);
        }
    }

    private record RefundStart(OperationResult result, PurchaseOperation operation, boolean dispatch) {
        static RefundStart done(OperationResult result) {
            return new RefundStart(result, null, false);
        }

        static RefundStart dispatch(PurchaseOperation operation) {
            return new RefundStart(null, operation, true);
        }
    }

    private record PurchaseValidation(PlotRow claim, String message) {
        static PurchaseValidation valid(PlotRow claim) { return new PurchaseValidation(claim, null); }
        static PurchaseValidation denied(String message) { return new PurchaseValidation(null, message); }
        boolean valid() { return claim != null; }
    }

    private record Finalization(OperationResult result, PurchaseOperation operation, UUID seller,
                                BigDecimal sellerProceeds) {
        static Finalization denied(PurchaseOperation operation, String message) {
            return new Finalization(OperationResult.denied(message), operation, null, BigDecimal.ZERO);
        }
    }

    @FunctionalInterface
    private interface DbMutation {
        OperationResult run(Connection connection) throws Exception;
    }
}
