# Civilizations Operations Guide

This guide is for server owners and administrators operating Civilizations `1.0.0` on Minecraft Java Edition/Spigot `26.2`, Java `25`, and MySQL `8.x`.

## Production assumptions

- Run one active Civilizations server process against a schema. `server-id` is recorded in audit rows, but version 1 does not provide distributed cache invalidation between multiple Minecraft servers.
- Use a dedicated MySQL schema and account. Every plugin table uses InnoDB and `utf8mb4`.
- Keep the MySQL clock and host clock synchronized. Campaign calculations use stored UTC instants and the configured IANA timezone, but reliable clocks remain operationally important.
- Stop the Minecraft server for jar replacement, schema restore, or structural configuration changes. Do not use a third-party plugin manager to hot-load, unload, or replace Civilizations.
- Take a database backup and a copy of `plugins/Civilizations/` before every upgrade.

## Initial database provisioning

Create a dedicated schema and user as a MySQL administrator:

```sql
CREATE DATABASE civilizations
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'civilizations'@'127.0.0.1'
  IDENTIFIED BY 'replace-with-a-long-random-password';

GRANT ALL PRIVILEGES ON civilizations.*
  TO 'civilizations'@'127.0.0.1';
```

If MySQL runs on another machine, replace `127.0.0.1` with the exact Minecraft server source host or a suitably narrow network pattern. Configure host firewall rules and MySQL TLS as appropriate for that network. Set `database.ssl: true` when the MySQL endpoint is configured for TLS.

The plugin needs normal DML privileges plus `CREATE`, `ALTER`, `INDEX`, and `REFERENCES` for migrations. The schema-scoped `GRANT ALL` above also permits future forward migrations that may need `DROP`; it does not grant privileges outside the Civilizations schema.

Do not place the database password on a public issue, in a paste, or in a startup argument. Restrict filesystem access to `plugins/Civilizations/config.yml`, and use a dedicated secret rather than a reused administrator password.

## Deployment procedure

1. Verify the server runs Java 25:

   ```bash
   java -version
   ```

2. Stop the Minecraft server cleanly and confirm the process has exited.
3. Put `Civilizations-1.0.0.jar` in `plugins/`. Remove older Civilizations jars so only one copy can load.
4. On a first installation, start briefly to generate the data folder, then stop before opening the server to players.
5. Edit `plugins/Civilizations/config.yml`:
   - set `server-id` to a stable identifier for this server;
   - enter MySQL host, port, schema, username, password, TLS, pool, and retry values;
   - review `worlds.allowed` and `worlds.blacklisted-biomes`;
   - review founding, membership, claim, plot, protection, and economy rules;
   - set the IANA `war.timezone` and intended campaign window;
   - choose technology enforcement and optional integrations.
6. Review `resources.yml`, `technologies.yml`, and `workorders.yml` before the first player creates persistent data.
7. Start the server and watch the complete Civilizations startup sequence.
8. Run `/civ admin migrate` and `/civ admin invariants`.
9. Join with a test account and run `/civ help`, `/civ map`, and `/civ inspect` before admitting players.

## Startup and readiness

Civilizations registers protection before it starts database connectivity. It then connects, applies missing ordered migrations, validates migration checksums, loads the authoritative state snapshot, and starts normal state changes.

Expected messages include:

- the plugin version and Spigot API target;
- a migration message only when a version has not yet been installed;
- `MySQL is ready and the authoritative civilization cache is loaded.`;
- status for each enabled optional integration;
- invariant-scan success or a detailed violation report.

The database retry interval is `database.retry-seconds` (30 seconds by default). The complete cache is also refreshed once per minute while MySQL is healthy, in addition to refreshes after normal mutations.

Before readiness:

- state-changing commands are denied;
- unknown territory is treated as protected, not as wilderness;
- indirect block and inventory paths across unknown boundaries are blocked;
- the plugin retries database startup without allowing unsafe mutations.

After a later MySQL outage, the last successfully loaded immutable snapshot remains in memory. Known claims stay protected, reads may be stale, and all database mutations are denied until connectivity and cache refresh recover.

## Post-deployment validation

Run these commands in order:

```text
/civ admin migrate
/civ admin invariants
/civ admin audit page=1 size=20
```

`/civ admin migrate` is intentionally read-only. A healthy report should show:

- database healthy;
- current schema version equal to expected schema version;
- the packaged and installed checksums matching;
- no missing required tables;
- no migration warnings.

`/civ admin invariants` is also read-only. It checks:

- duplicate or orphaned membership;
- active civilizations whose claims are empty, omit the capital, cross worlds, or are disconnected;
- invalid private-plot owners;
- multiple current campaigns or mismatched `current_war_id` pointers;
- persisted research keys missing from `technologies.yml`;
- stale or compensation-pending economy work.

For direct MySQL confirmation:

```sql
SELECT version, description, checksum, installed_at, success
FROM schema_history
ORDER BY version;

SELECT COUNT(*) AS audit_rows FROM civ_audit_log;
```

Do not edit `schema_history` to silence a warning.

## Monitoring

Alert on these log patterns:

- `MySQL connection/migration failed`
- `MySQL became unavailable; mutations are locked`
- `Civilization cache refresh failed`
- `COMPENSATION_PENDING`, `PENDING_DELIVERY`, any stale `*_IN_FLIGHT` state, or repeated economy-provider failures
- `Civilizations invariant scan found`
- `Civilizations invariant scan failed`
- campaign transition or temporary-block cleanup failures
- optional integration loaded but inactive

The default periodic invariant interval is 30 minutes (`notifications.invariant-check-minutes`). Run `/civ admin invariants` after any incident, manual data repair, restore, or campaign override.

Useful read-only inspection commands:

```text
/civ admin inspect civ <name | id>
/civ admin inspect claim
/civ admin inspect claim <world-uuid> <chunk-x> <chunk-z>
/civ admin inspect player <name | uuid>
/civ admin inspect war <id>
/civ admin audit action=admin. page=1 size=50
```

Inspections deliberately show both cached and persisted values and warn when their identities differ.

## Backup policy

Back up the database daily and before every jar or catalog update. Retain enough generations to cover the longest period in which a data problem could go unnoticed; a practical starting point is 7 daily and 4 weekly backups.

Include `plugins/Civilizations/item-refunds/` and the Minecraft player data in the same stopped-server backup as the database. The refund journals and player inventory receipts must be restored together; mixing different snapshots can lose or duplicate refunded items.

Use `--single-transaction` for a consistent nonblocking InnoDB snapshot and `--hex-blob` for binary UUID columns:

```bash
mysqldump \
  --host=127.0.0.1 \
  --user=civilizations \
  --password \
  --single-transaction \
  --quick \
  --hex-blob \
  --routines \
  --triggers \
  --events \
  --set-gtid-purged=OFF \
  civilizations > civilizations-YYYYMMDD-HHMMSS.sql
```

The client prompts for the password. Avoid `--password=...`, which exposes it in shell history and process listings.

Also copy the complete plugin data folder while the server is stopped or the files are otherwise snapshotted atomically:

```text
plugins/Civilizations/config.yml
plugins/Civilizations/resources.yml
plugins/Civilizations/technologies.yml
plugins/Civilizations/workorders.yml
plugins/Civilizations/messages.yml
```

Keep the exact release jar with each backup. A database dump without the catalog and jar versions may not be sufficient to reproduce technology and resource identities.

Periodically test restoration into a separate MySQL instance. A backup that has never been restored is not yet a verified backup.

## Upgrade procedure

Migrations are **forward-only** and are automatically applied during startup.

1. Read `CHANGELOG.md` for the target version.
2. Run `/civ admin migrate` and `/civ admin invariants`; resolve or record every existing warning.
3. Stop the Minecraft server.
4. Dump MySQL and copy the current data folder and jar.
5. Replace the jar. Do not delete existing configuration or catalog files.
6. Apply any documented configuration additions. Preserve persistent resource and technology keys.
7. Start the server and watch migration output until the cache-ready message appears.
8. Run `/civ admin migrate`, `/civ admin invariants`, and a small read-only gameplay smoke test.
9. Keep the pre-upgrade backup until the release has completed a full campaign cycle and at least one backup/restore verification.

Rules for migration files:

- Never change the contents of a migration that has run in production.
- Add a new ordered migration for every schema change.
- Never update a checksum in `schema_history` by hand.
- Never run the packaged migration SQL manually on a live database unless a release-specific recovery procedure explicitly calls for it.

If startup reports that an applied migration checksum differs from the packaged migration, stop. The running database and jar do not belong to the same release lineage. Restore the matching jar or restore a known matched backup; do not overwrite the checksum.

## Safe rollback

A previous jar is not automatically compatible with a schema after a forward migration. The safe rollback unit is the **jar, plugin data folder, and database backup together**.

1. Stop the Minecraft server and preserve the failed/upgraded database and logs for diagnosis.
2. Create a new empty schema, or drop and recreate the dedicated schema only during a declared maintenance window after confirming the exact target. Prefer a new schema because it preserves the failed state for analysis.
3. Restore the pre-upgrade dump into that empty schema:

   ```bash
   mysql --host=127.0.0.1 --user=database_admin --password civilizations_restore \
     < civilizations-PREUPGRADE.sql
   ```

   Here, `database_admin` means the MySQL account authorized to create and restore the replacement schema; it is not a literal required username.

4. Restore the matching old jar and `plugins/Civilizations/` files.
5. Point `database.database` at the restored schema if a new schema name was used.
6. Start the server in maintenance mode, wait for cache readiness, and run schema/invariant checks.
7. Reopen to players only after a functional smoke test.

Do not attempt rollback by deleting rows from `schema_history`, reversing DDL ad hoc, or starting the previous jar against an unverified newer schema.

## Incident: MySQL unavailable

Symptoms include mutation commands reporting storage unavailable, connection warnings, and a cache that never becomes ready after startup.

1. Leave the plugin enabled. Its fail-closed behavior is the protection boundary.
2. Confirm MySQL is reachable from the Minecraft host and that DNS, firewall, TLS, port, account, and password are correct.
3. Test with the same endpoint and account:

   ```bash
   mysql --host=127.0.0.1 --user=civilizations --password \
     --execute='SELECT 1' civilizations
   ```

4. Check connection limits and `SHOW PROCESSLIST`; the configured pool defaults to 10 connections.
5. Check `SHOW GRANTS FOR 'civilizations'@'127.0.0.1';` and MySQL server logs.
6. Restore service. The plugin retries on `database.retry-seconds`; wait for the cache-ready message.
7. Run `/civ admin migrate` and `/civ admin invariants`.
8. Review incomplete economy operations before reopening expensive transactions.

Never disable protection or temporarily treat cache misses as wilderness to work around a database failure.

## Incident: cache and database disagree

Use the relevant `/civ admin inspect` command. A database commit can succeed even if the immediate cache refresh fails; the mutation result will warn that protection remains fail-closed until refresh.

1. Stop further administrator mutations.
2. Confirm MySQL health.
3. Wait for the periodic refresh, then inspect again.
4. If disagreement persists, restart during maintenance to force a complete cache rebuild.
5. Run the invariant scanner.
6. Use a narrowly scoped audited repair only after determining which database row is authoritative.

Do not repair the cache directly; it is reconstructed from MySQL.

## Incident: incomplete Vault operation

Civilizations records external transfers in `economy_operations`. The recovery service waits at least ten seconds before selecting incomplete rows and runs once per minute when both MySQL and the Vault provider are healthy. It retries a bounded batch of 50 operations in order, but only when the durable state proves that replay is safe.

Inspect pending work without modifying it:

```sql
SELECT HEX(operation_id) AS operation_id,
       operation_type,
       HEX(player_uuid) AS player_uuid,
       HEX(beneficiary_uuid) AS beneficiary_uuid,
       civ_id,
       claim_id,
       amount,
       state,
       retry_count,
       provider_response,
       updated_at
FROM economy_operations
WHERE state NOT IN ('COMPLETED', 'FAILED')
ORDER BY updated_at;
```

State meanings:

- `PENDING`: operation is staged and no external withdrawal dispatch has begun.
- `WITHDRAWAL_IN_FLIGHT`: a withdrawal was dispatched, but the process lost a conclusive durable result.
- `EXTERNAL_APPLIED`: withdrawal succeeded and the database-side mutation/finalization remains.
- `DB_APPLIED`: the database-side change committed and an external delivery remains.
- `DELIVERY_IN_FLIGHT`: an external payout was dispatched, but the process lost a conclusive durable result.
- `PENDING_DELIVERY`: a payout must be delivered.
- `COMPENSATION_PENDING`: a refund or seller credit must be retried or reviewed.
- `REFUND_IN_FLIGHT`: a refund was dispatched, but the process lost a conclusive durable result.
- `COMPLETED`: all required sides completed.
- `FAILED`: the operation was declined or was compensated and is terminal.

Recovery covers plot purchases, treasury deposits, disband payouts, treasury withdrawals, and known-applied founding charges. A failed compensation is retried on later passes. The three `*_IN_FLIGHT` states are intentionally not retried: Vault exposes neither durable idempotency keys nor a portable transaction lookup, so a restart cannot distinguish “call never reached provider” from “provider applied it before the server stopped.”

Before any manual database change:

1. Match the operation ID in `economy_operations`, `treasury_ledger`, `civ_audit_log`, and the external economy provider if it exposes history.
2. Determine whether the provider actually debited or credited the account.
3. Take a fresh backup.
4. For `PENDING`, `EXTERNAL_APPLIED`, `DB_APPLIED`, `PENDING_DELIVERY`, or `COMPENSATION_PENDING`, prefer restoring Vault/provider availability and letting the recovery worker retry.
5. For an `*_IN_FLIGHT` state, do not change or replay it until provider history or balance evidence establishes whether money moved. Record that evidence and the reconciliation decision.
6. If manual settlement is unavoidable, document the provider transaction, operation ID, exact amount, recipient, and approving administrator outside the database, then add a corresponding auditable in-game repair where available.

Never mark an operation `COMPLETED` solely to clear a warning. That can silently create or destroy currency.

## Incident: invariant violation

The invariant scanner reports issues but does not mutate data.

- **Duplicate/orphan membership:** use inspections and `/civ admin setrole` only when role state is wrong. Membership moves do not have a general force command; restore from backup or prepare a reviewed forward repair for structural corruption.
- **Disconnected claims:** inspect the capital and affected chunks. Use `forceclaim` or `forceunclaim` only after its preview explains the topology impact; include the incident in the audit trail.
- **Invalid plot owner:** verify current membership and plot tenure. Do not set raw owner UUIDs manually; membership removal contains the required trust/tenure cleanup.
- **Multiple current wars/pointer mismatch:** inspect both civilizations and each war, then use the scoped war repair commands with a recorded reason.
- **Undefined research:** restore the missing key in `technologies.yml` if removal was accidental. If the key was intentionally retired, deploy an explicit data migration rather than silently substituting another technology.
- **Economy compensation:** follow the Vault runbook above.

After repair, wait for cache refresh and rerun `/civ admin invariants` until it is clean.

## Incident: campaign transition or cleanup is stuck

1. Run `/civ war status <civilization>` and `/civ admin inspect war <id>`.
2. Check the configured timezone, weekday, start/end times, roster snapshot, and current server clock.
3. Confirm MySQL is healthy; war transitions require durable writes.
4. Inspect unresolved temporary siege records:

   ```sql
   SELECT id, war_id, HEX(world_uuid) AS world_uuid, block_x, block_y, block_z,
          placed_material, cleanup_state, placed_at, cleaned_at
   FROM war_block_changes
   WHERE cleanup_state <> 'CLEANED'
   ORDER BY war_id, id;
   ```

5. Prefer allowing the runtime reconciler to finish after connectivity is restored.
6. If a campaign row itself is invalid, preview and use `/civ admin war cancel`, `resolve`, or `setstate` with `confirm` and a specific incident reason.
7. Use CoreProtect manually for environmental investigation or rollback only after matching the campaign, world, area, and time window. Civilizations does not automatically drive CoreProtect.
8. Rerun invariant checks and inspect both civilizations after any override.

## Configuration reload

`/civ admin reload` reloads `messages.yml`, validates `config.yml`, and writes an audit result. It deliberately leaves live gameplay, database, integration, and balancing-catalog state unchanged. It is not a substitute for a restart after those changes.

Restart for changes to:

- MySQL connection, TLS, pool, or retry values;
- `server-id`;
- allowed world strategy;
- economy mode/provider availability;
- war timezone/window and scheduler intervals;
- integration enablement;
- recipe or persistent identity structure.

Before changing a catalog:

1. Back up the file and database.
2. Preserve all deployed resource and technology keys.
3. Validate prerequisites do not introduce cycles or missing keys.
4. Verify all material names and resource cost keys exist.
5. Restart during a maintenance window so the replacement catalog is loaded atomically.
6. Run schema and invariant checks after readiness.

If `config.yml` validation fails during `/civ admin reload`, live structural settings remain in place and the failure is audited. Correct the file before the next restart.

## Administrator command discipline

- Grant `civilizations.admin` separately from the data, war, reload, and bypass permissions.
- Run read-only inspect, schema, audit, and invariant commands before any repair.
- Capture the preview output for high-risk commands and review every warning.
- Supply an incident-specific reason for campaign overrides.
- Use signed resource and Knowledge grants carefully; negative results are rejected rather than allowing a negative balance.
- Treat protection, technology, and war bypass nodes as temporary emergency capabilities. Remove them when the incident is over.
- Review administrator activity with `/civ admin audit action=admin.` and the broader audit stream.

## Routine maintenance checklist

Daily:

- verify the latest MySQL backup completed and is nonempty;
- scan logs for database, cache, economy, campaign, and invariant warnings;
- review old incomplete economy operations;
- confirm disk space for MySQL binary logs/backups and the Minecraft world.

Weekly:

- run `/civ admin migrate` and `/civ admin invariants`;
- review administrator audit entries;
- verify the coming campaign window and server timezone expectations;
- confirm optional plugin versions still match their server versions.

Before each release:

- run `mvn clean verify` from a clean source tree;
- retain the produced jar and test reports with the release;
- take and verify a pre-upgrade backup;
- perform a maintenance-mode startup, schema check, invariant check, and gameplay smoke test.

After any restore or manual repair:

- restart to rebuild the cache;
- run schema and invariant checks;
- inspect every affected civilization, claim, player, or campaign;
- review incomplete economy operations;
- create a fresh known-good backup.


## Item refund recovery

Civic deposits and founding attempts write an item journal before removing inventory. Journals live under `plugins/Civilizations/item-refunds/deposits/` and `plugins/Civilizations/item-refunds/founding/`; this folder must be writable even when MySQL is unavailable. Each entry contains an operation UUID, player UUID, serialized item stacks, and a recovery state.

- `REFUND` means the operation was confirmed eligible for compensation. The player receives it on login or when retrying the action. If the complete refund will not fit, it stays queued until the player frees inventory space and retries or rejoins. Refunds are never dropped on the ground.
- `HELD` means automatic compensation is unsafe. An interrupted operation may have committed. Preserve the record and establish the database outcome before any manual repair. Deposit journal UUIDs match `stockpile_ledger.operation_id`; founding journals identify the player and must be checked against founding audit and payment records. Do not simply change every `HELD` entry to `REFUND`.

The inventory debit and refund each save a player-data receipt. A restart between delivering items and deleting a journal therefore does not deliver the same items again. Keep these receipts intact during repairs. Ordinary successful operations remove their journals, and obsolete receipts are cleaned on subsequent recovery checks. Check server logs for journal or player-save errors and preserve the journal when investigating an unresolved outcome.

These records protect newly started operations; this update cannot reconstruct refunds already lost by an older version. Restore the journal directory, player data, and database from one consistent backup when recovering a server.

## Weekly plot taxes

Migration V6 creates `civ_plot_taxes`, `plot_tax_accounts`, and `plot_tax_bills`. No existing private plot is billed retroactively on installation. Every civilization defaults to $0; a leader enables a nonzero amount with `/civ taxes set <amount>`. Vault money features must be active for collection. New plots and changes to scheduled tax rates allow at least seven days before charging. Already-issued bills retain their amount.

Tax bills use `PLOT_TAX` economy operations and ledger reasons. They are unique per ownership period and due date. Tax operation records intentionally retain the claim ID in their context rather than their claim foreign key, so a deleted claim does not destroy the evidence needed to refund its former owner. Only the tax worker resumes these operations; the general economy-recovery worker does not dispatch them.

A `WITHDRAWAL_IN_FLIGHT` or `REFUND_IN_FLIGHT` operation is ambiguous: never reset it to `PENDING` without independently establishing whether the economy provider moved money. Ownership remains protected while unresolved. After verifying a withdrawal was applied, reconcile it to `EXTERNAL_APPLIED`; the tax worker then credits the treasury once, or refunds if the original ownership is no longer valid. If the withdrawal definitely never occurred, reconcile to `PENDING` for a safe retry. A confirmed refund can be reconciled to `FAILED` with a `REFUNDED:` provider response. Record the evidence and operator action using your normal database repair process. Do not mark a tax operation `COMPLETED` manually: its treasury ledger, bill, and next due date must commit together through the tax worker.

Verified insufficient balance is a distinct server-generated result, separate from a provider failure message. It causes immediate civic reclamation of the affected plot and clears private ownership, trust, listing, flags, and home metadata. Physical world blocks are not edited. Owners are notified of successful taxes and reclamations, including after returning from offline play. Inspect `/civ taxes`, treasury history, the tax bill status, and `PLOT_TAX_PAID` / `PLOT_TAX_RECLAIMED` audit entries to trace a result.

For an isolated test schema, run `mvn -Dcivilizations.mysql.it=true -Dcivilizations.mysql.url=<test-jdbc-url> test` to include the MySQL migration and tax/payment recovery tests. These tests migrate the schema and roll back their tax fixtures; use a dedicated test database.
