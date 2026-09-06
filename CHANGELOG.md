# Changelog

All notable release changes are documented here. Database migrations are forward-only; follow [docs/OPERATIONS.md](docs/OPERATIONS.md) before upgrading or rolling back.

## Unreleased

### Fixed

- Treasury withdrawals fail closed while the cache is unavailable and verify current leadership and membership under database locks before debiting funds.
- Container access flags no longer grant permission to break containers. Wartime container protection remains enforced.
- Multi-block placements, including beds across chunk boundaries, require permission at every affected block.
- Failed civic deposits and founding attempts use a durable local item refund journal, with saved inventory receipts to prevent duplicate delivery after restart. Full inventories leave refunds queued.
- Founding recovery is registered for player login events and drains confirmed failures before database shutdown.

### Changed

- Nether/End dimension travel now finds random safe unclaimed destinations inside each world border, with supported End island landings and Nether roof/hazard rejection. Added `/nether` and `/end` shortcuts; expedition requirements and the existing dimension warmup/shared cooldown still apply.

- `/civ stockpile` now opens a read-only resource GUI with stack counts, exact hover totals, tier and recipe details, pagination, and refresh. The history command remains available.
- Civic item names consistently use green, cyan, and purple for tiers 1–3 across physical items and GUI previews, with compatible recoloring of existing authenticated items.

- Civilization work orders now display shared stockpile readiness and may be completed only by leaders or advisors, atomically spending the required civic resource.
- Wars are now timed windows for fighting, breaking enemy territory, and looting containers. Removed capture objectives, War Standard crafting, winners/losers, automatic land transfers, and stockpile spoils. Mutual peace ends the window immediately. Existing schedules, truces, and protected-block policies still apply.
- Looting access is rechecked for open inventory clicks and drags, so it expires with the war window. Block drops are enabled by default; existing configured drop policies are preserved.

### Added

- A written tutorial book for first-time players and `/civ tutorial` replacements, with a saved 24-hour per-player cooldown, full-inventory handling, and pending welcome-book retries on join.

- `/wild` with a persistent 12-hour cooldown, safe random surface destinations within the configured Overworld's current world border, and claim/hazard checks.
- Automatic free wilderness placement for first-time players, with persistent retries after failed placement and no effect on their `/wild` cooldown.

- Weekly private-plot taxes, defaulting to $0 per civilization, with leader-only `/civ taxes set <amount>`, a `/civ taxes` GUI, automatic offline-capable Vault collection, treasury ledger entries, and durable owner notices.
- Automatic civic reclamation of the specific plot on verified insufficient funds, preserving builds and excluding all public/capital plots. Provider errors and ambiguous outcomes never trigger reclamation.
- Forward migration `V6` for tax policies, ownership-specific billing schedules, and restart-safe tax bills. Purchase confirmations show the recurring rate, and new ownership/rate changes allow at least seven days before charging.

- A configurable Greek pantheon GUI and `/sacrifice` flow with per-god offerings, random 1–32 acceptance trials, blessings, lightning rejection, and persistent four-hour per-player cooldowns.
- Forward migration `V5` for restart-safe sacrifice cooldown and outcome history.

## 1.0.0 — 2026-08-17

Initial release for Minecraft Java Edition and Spigot API 26.2, built for Java 25.

### Added

- Persistent civilization founding, unique names/tags, invitations, membership cooldowns, activity-based establishment, roles, leadership transfer, chat, and audited disbanding.
- Cardinally connected chunk claims, configurable capacity/cost bands, capital movement, homes, local maps, and detailed access inspection.
- Civic/common/private/for-sale plot states, private trust and flags, civilization listings, Commerce resale, confirmed purchases/surrender, and durable money/stockpile ledgers.
- Cache-only, fail-closed protection for direct and indirect block, entity, container, explosion, fluid, piston, dispenser, hopper, portal, and boundary behavior.
- PDC-authenticated civic resources, configurable recipes and tiers, refining/decompression, permanent stockpile deposits, and anti-forgery checks.
- Daily deterministic work orders with per-player contribution caps and Knowledge rewards.
- Work-order-only Knowledge acquisition, data-driven technologies, timed research queues, cancellation grace, restart-safe completion, and strict/craft-only/disabled capability enforcement.
- Timezone-aware scheduled campaigns with eligibility rules, declaration cost snapshots, authenticated Charters and Standards, locked rosters/objectives, frontier capture, mutual peace, cancellation grace, truce, and temporary siege cleanup.
- Optional Vault economy integration with persisted pre-dispatch and ambiguity states, safe recovery for founding charges, treasury deposits, plot transfers, treasury withdrawals, seller credits, and disband payouts, plus administrator-visible fail-closed reconciliation when Vault cannot prove an outcome.
- Optional PlaceholderAPI expansion for civilization, claim, research, and campaign values.
- Optional WorldGuard claim-overlap checks and CoreProtect operational detection.
- Ordered checksummed MySQL 8 migration `V1`, HikariCP connection pooling, asynchronous database work, immutable state snapshots, per-civilization serialization, and durable audit context.
- Administrator inspection, role/claim/balance/research/war repair commands, audit queries, schema reporting, and periodic read-only invariant checks.
- JUnit coverage for lifecycle policy, inventory costs, connectivity, territory and protection policy, progression catalogs and gates, work-order scheduling, command parsing/confirmations, placeholders, admin repair policy, and war scheduling.

### Operational notes

- This release creates schema version `1` automatically on startup.
- Back up MySQL and the complete plugin data folder before installing a later release.
- Vault, PlaceholderAPI, WorldGuard, and CoreProtect remain optional soft dependencies.
- Version 1 supports one active Minecraft server process per Civilizations schema; `server-id` is audit context, not distributed cache coordination.
