# Minecraft Civilizations

A civilization-building survival plugin for **Minecraft Java Edition**. Found a nation, settle connected territory, buy a home plot, manufacture civic resources, research new technology, worship the Greek gods, and fight scheduled border wars.

This README is both the **server hosting guide** and the **player wiki** for the code in this repository. Numbers below describe the bundled defaults; a server owner can change many of them. In-game previews and menus show the server's current values.

| Project | Target |
| --- | --- |
| Plugin name | Civilizations |
| Artifact version | `1.0.0` |
| Minecraft / Spigot API | `26.2` |
| Java | `25` |
| Build | Maven; shaded plugin JAR |
| Persistent storage | MySQL 8.x, InnoDB, `utf8mb4` |
| Client installation | No client mod is required by this plugin |

**For players:** start with [your first session](#your-first-session). **For server owners:** start with [hosting and installation](#hosting-and-installation). **For administrators:** see [administration and maintenance](#administration-and-maintenance) and the detailed [operations runbook](docs/OPERATIONS.md).

## Contents

- [What the plugin adds](#what-the-plugin-adds)
- [Hosting and installation](#hosting-and-installation)
- [Your first session](#your-first-session)
- [Civilizations, membership, and roles](#civilizations-membership-and-roles)
- [Territory, capitals, and protection](#territory-capitals-and-protection)
- [Private plots and housing](#private-plots-and-housing)
- [Weekly plot taxes](#weekly-plot-taxes)
- [Travel and dimensions](#travel-and-dimensions)
- [Civic resources and the stockpile](#civic-resources-and-the-stockpile)
- [Work orders and Knowledge](#work-orders-and-knowledge)
- [Research and the technology tree](#research-and-the-technology-tree)
- [Money, selling, and the treasury](#money-selling-and-the-treasury)
- [Religion and sacrifices](#religion-and-sacrifices)
- [Scheduled war windows](#scheduled-war-windows)
- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration reference](#configuration-reference)
- [PlaceholderAPI](#placeholderapi)
- [Administration and maintenance](#administration-and-maintenance)
- [Troubleshooting and player FAQ](#troubleshooting-and-player-faq)
- [Building, testing, and contributing](#building-testing-and-contributing)
- [Project scope and glossary](#project-scope-and-glossary)

## What the plugin adds

- Named civilizations with one leader, advisors, citizens, invitations, activity tracking, and establishment requirements.
- Connected chunk claims, a permanent capital, common land, government land, private ownership, trust, listings, and citizen resale.
- Protection for building, containers, entities, and indirect interactions across claim boundaries.
- Five civic-resource families, three colored tiers, recipe and refining menus, and a permanent shared stockpile.
- Civilization work orders that consume stockpile resources and award Knowledge for research.
- A technology tree that unlocks equipment, transport, industry, enchanting, brewing, dimensions, and civilization capacity.
- Vault-backed resource sales, plot purchases, treasuries, and automatic weekly private-plot taxes.
- Automatic first-join wilderness placement, `/wild`, and safe random Nether/End travel.
- Six Greek gods with offerings, temporary blessings, and per-god sacrifice cooldowns.
- Scheduled war windows for fighting, territory breaking, and looting, followed by truces.
- MySQL persistence, automatic schema migrations, transaction history, administrator diagnostics, and recovery of interrupted payments.

## Hosting and installation

### Requirements and compatibility

You need a **Spigot 26.2 server**, **Java 25**, and a reachable **MySQL 8.x** database. A vanilla server cannot load Bukkit/Spigot plugins. Other server distributions must implement the required Spigot API; this repository does not establish compatibility with every fork. Folia support is not declared.

Java 25 is required for both building and running this plugin. There is no SQLite, flat-file, or database-free mode. Setting the economy to `DISABLED` disables currency features, not MySQL.

Use **one active Minecraft server process per Civilizations database schema**. `server-id` identifies audit records; it does not provide shared-network synchronization between multiple servers.

| Integration | Needed for | Setup |
| --- | --- | --- |
| Vault **and a registered economy provider** | Currency costs, `/sell`, paid plots, treasury transfers, automatic tax collection | Install both and set `economy.mode: VAULT` |
| PlaceholderAPI | `%civ_*%` placeholders for compatible displays | Install it and leave `integrations.placeholderapi: true` |
| WorldGuard 7+ | Rejecting claims that overlap protected regions | Install its required dependencies and leave `integrations.worldguard: true` |
| CoreProtect | Integration status / operator visibility | Install it if desired; investigation and rollback use CoreProtect's own tools |

These are optional soft dependencies, not libraries bundled into the Civilizations JAR. Vault alone does not create player balances. The economy provider must support the operations you intend to use, including offline withdrawals for plot taxes. WorldGuard and other plugins can impose additional restrictions beyond Civilizations.

### 1. Prepare a Minecraft server

If using a hosting panel, select a compatible Spigot server version, select Java 25 for its startup environment, and obtain a dedicated MySQL database from the host. Confirm that the database can be reached from the game server, not just from your own computer.

For a self-hosted server, follow the official [Spigot BuildTools guide](https://www.spigotmc.org/wiki/buildtools/) and [Spigot installation guide](https://www.spigotmc.org/wiki/spigot-installation/). Use the matching server revision. Keep the running Minecraft server in its own directory, separate from this source checkout.

After obtaining the server JAR, an example launch command is:

```bash
java -Xms2G -Xmx4G -jar spigot-26.2.jar nogui
```

Replace the filename with the actual server JAR. The heap values are an example, not a measured capacity recommendation; leave memory for the operating system and MySQL, and tune for your player count, world generation, and other plugins.

Read the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and accept it in the generated `eula.txt` only if you agree. Use the server's `stop` command for a clean shutdown. A hosting panel or service manager should keep the process running after you close your terminal.

Configure your game connection address/port and firewall for your hosting environment. Keep database access restricted to the Minecraft host or a private network. Do not expose MySQL just to let players join Minecraft.

### 2. Build or obtain the plugin JAR

If you have a release JAR for this source version, use it. Otherwise install a **JDK 25** and Maven, open a terminal in this repository, and run:

```bash
java -version
mvn -version
mvn clean verify
```

Both version commands should report Java 25 for their respective processes. Maven downloads the compile-time Spigot API and other dependencies; you do not need BuildTools to build **this plugin**.

Install this artifact:

```text
target/Civilizations-1.0.0.jar
```

Do not install an `original-*.jar`, a source JAR, or a test JAR. The release JAR includes its database/runtime libraries; the server provides the Spigot API.

### 3. Create the database

If your host already provisioned a database, use the exact database name, username, password, and endpoint it supplied. Otherwise run this example as a MySQL administrator:

```sql
CREATE DATABASE civilizations
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'civilizations'@'127.0.0.1'
  IDENTIFIED BY 'replace-with-a-long-random-password';

GRANT ALL PRIVILEGES ON civilizations.*
  TO 'civilizations'@'127.0.0.1';
```

The account host must match the Minecraft server's source host as MySQL sees it. On a remote database, `127.0.0.1` in this example is not the game server's address. On a container host, `127.0.0.1` inside the game container refers to that container; use the database endpoint available on its network.

Privileges are limited to this dedicated schema. Schema creation/alteration privileges are needed because the plugin applies migrations itself. Use a dedicated password and account, not your database administrator account.

### 4. Install and generate configuration

1. Stop the Minecraft server.
2. Copy `Civilizations-1.0.0.jar` into `plugins/`, removing older Civilizations JARs from that directory.
3. Add any optional integration plugins you plan to use.
4. Start once to generate `plugins/Civilizations/`, then stop before admitting players.
5. Edit the generated configuration and catalogs.

A first startup with the placeholder database password may report connection failures. Territory remains protected while the plugin waits for storage; finish configuration before opening the server.

The plugin generates:

```text
plugins/Civilizations/
├── config.yml
├── resources.yml
├── technologies.yml
├── workorders.yml
├── religion.yml
└── messages.yml
```

### 5. Configure storage, economy, and worlds

Edit the matching sections in the **generated** `plugins/Civilizations/config.yml`. This is an excerpt, not a replacement for the entire file:

```yaml
server-id: "primary"

database:
  host: "127.0.0.1"
  port: 3306
  database: "civilizations"
  username: "civilizations"
  password: "replace-with-your-database-password"
  ssl: false
  pool-size: 10
  connection-timeout-ms: 5000
  retry-seconds: 30

economy:
  mode: "VAULT"

worlds:
  allowed: ["world"]
  blacklisted-biomes: []
  fail-closed-during-warmup: true

travel:
  warmup-seconds: 5
  cooldown-seconds: 60
  safe-search-radius: 8
  claim-protection-radius-chunks: 1
  destinations:
    overworld:
      world: "world"
    nether:
      world: "world_nether"
    end:
      world: "world_the_end"
```

Choose `DISABLED` instead of `VAULT` if you want no currency features. The bundled default is `DISABLED`. With currency disabled, founding money costs are waived, effective plot prices are zero, and treasury transfers, selling, and tax collection are unavailable. Civic resources and Knowledge still matter.

Set `database.ssl: true` when using an endpoint configured for TLS. Keep real credentials out of source control. The files in `src/main/resources/` are distributable templates; the files in the running server's plugin directory are its live configuration.

Use actual **loaded** world names. Civilizations does not create or load the Nether/End for you. `worlds.allowed` controls founding/new claims; travel target worlds are configured separately. An empty allowed-world list permits every world, so keep the default explicit list unless that is intended. One civilization's normal territory remains connected in one world.

For `/civ teleport overworld`, omit `x`, `y`, and `z` to use world spawn, or supply all three plus optional `yaw` and `pitch` for a fixed return location. Nether/End entries ignore those coordinates and use random safe ground in their configured worlds.

### 6. Set borders and the war schedule

Set a deliberate border in each travel world before players arrive. `/wild`, `/nether`, and `/end` sample positions using the **target world's current border**, including its center. A very large ungenerated area can cause expensive chunk generation during travel. Pre-generate the intended play area using your server's normal tooling if appropriate.

Keep a safe Overworld return point inside its border. Random Nether/End travel is refused if that return route cannot be resolved. Public claim reservations remain around the configured spawn/coordinate anchors, including the legacy Nether/End anchors; the default reservation radius is one chunk.

The default campaign window is **Saturday, 14:00–18:00, `America/Los_Angeles`**. Change `war.timezone`, weekday, start, and end to your intended schedule before players declare wars. The timezone is an IANA identifier, not a fixed UTC offset; scheduled times follow its daylight-saving rules.

### 7. Verify startup and admit players

Start the server and wait for:

```text
MySQL is ready and the authoritative civilization cache is loaded.
```

Missing migrations are applied automatically. Do not import the packaged SQL files manually. Run these as an operator in game; omit the leading slash in the server console:

```text
/civ admin migrate
/civ admin invariants
/civ admin audit page=1 size=20
```

`migrate` reports migration status; it does not manually execute a migration. Check for a healthy database, matching schema/checksums, no missing tables, and no invariant violations.

Before opening the server, use a normal test player to check first-join placement, `/wild`, `/civ help`, `/civ items`, and `/civ inspect`. Test founding, access restrictions, and a plot purchase on a staging server. Test dimension travel with the required technologies unlocked. If using taxes, confirm the economy provider can debit an offline test account.

## Your first session

First-time players automatically receive a **Civilizations Tutorial** book covering joining/founding, travel, resources, research, land, taxes, trading, religion, and war. Use `/civ tutorial` for a replacement. Each successful delivery (including the welcome book) starts a **24-hour per-player cooldown**, saved across reconnects and server restarts. Existing players can use the command immediately if they have never received a book. A full inventory does not consume the cooldown or drop the book: clear space and use the command. Undelivered welcome books are retried on the next join.

### A practical starting route

1. **Arrive in the wilderness.** Your first join automatically finds safe unclaimed Overworld ground. That placement is free: you can immediately use `/wild` again if you want another start.
2. **Read `/civ help`.** Use `/civ help resources` or another topic to jump ahead. `/civ inspect` explains the chunk you are standing in; `/civ map` shows nearby territory.
3. **Gather basic supplies.** With default strict technology rules, many vanilla tools, vehicles, and stations require civilization research. Start with the tools and activities currently available to you.
4. **Join or found a civilization.** Browse `/civ list`, ask a leader/advisor for an invitation, then `/civ accept <name>`. To found your own, craft the founding materials described below and use `/civ create <name>` on eligible land.
5. **Contribute to the stockpile.** Browse `/civ items`, craft civic resources, then deposit them with `/civ deposit hand` or `/civ deposit all`.
6. **Earn Knowledge together.** Check `/civ workorders`. Leaders/advisors complete ready orders using the shared stockpile.
7. **Research and expand.** Leaders/advisors choose research in `/civ tech`, claim adjacent land, and make common areas or plots for citizens.
8. **Buy a plot when ready.** Read its purchase price and weekly tax before confirming. Keep money in your personal balance if weekly taxes are enabled.

You can play without leading a civilization: manufacturing materials, completing your community's supply needs, building, trading supported resources, researching as an advisor, and defending territory all contribute to its progress.

### Three different balances

| Balance | Where it lives | Main uses |
| --- | --- | --- |
| Your currency balance | The server's Vault economy provider | Buy plots, pay weekly plot taxes, donate to the treasury |
| Civilization treasury | Civilization money balance | Government funds, tax/sale receipts, leader withdrawals |
| Civic stockpile and Knowledge | Separate shared civilization resource balances | Claims, research, work orders, capital moves, war declarations |

Depositing civic materials does not create currency. Donating money does not create Knowledge. Research belongs to the civilization and benefits its current members.

## Civilizations, membership, and roles

### Founding a civilization

Stand in the chunk you want as your capital, carry the required **physical civic items**, and run:

```text
/civ create River Kingdom
```

Names contain 3–24 characters and may use letters, numbers, spaces, apostrophes, and hyphens. Names are case-insensitive identifiers; there is no separate civilization tag required by this command.

Default founding cost:

| Cost | Amount |
| --- | --- |
| Heavy Cobblestone (`masonry:1`) | 4 |
| Bound Timber (`timber:1`) | 2 |
| Currency | 0 |

That is 36 ordinary cobblestone and 18 accepted logs if you craft all six civic items from scratch. These costs come from your inventory because a new civilization has no stockpile yet.

The site must be an eligible unclaimed chunk in an allowed world/biome, outside reserved/protected areas, and satisfy the default 12-chunk minimum separation rule from other civilizations. You must be unaffiliated and clear of membership cooldowns. Review the green/red confirmation GUI before founding; it rechecks the price and site when you accept.

Founding makes you leader, establishes the capital claim, and starts a **14-day founding peace shield**. While the shield is active, the civilization cannot declare or be targeted by a normal campaign.

### Joining and leaving

A leader or advisor uses `/civ invite <player>`. Invitations expire after **10 minutes** by default. Accept with `/civ accept <civilization>` or decline with `/civ deny <civilization>`. A player belongs to one civilization at a time.

The default membership-change cooldown is **24 hours after departure**. Joining a new civilization does not carry the former civilization's researched technology with you. Leaving or being removed terminates private plot rights without a refund; trust and listings are cleaned up. A leader must transfer leadership or disband rather than leave the civilization leaderless. Campaign membership locks can prevent changes.

### Leadership transfer and disbanding

Use `/civ transfer <player>` to make another current member the leader. The former leader becomes an advisor if a slot is available (or the recipient vacated one), otherwise a citizen.

`/civ disband` previews and confirms archival of the civilization. It releases claims, ends membership/private rights, clears invitations, and cancels active research. Remaining treasury money is staged for payout to the former leader; it is not divided among citizens. Physical builds remain in the world, but the civilization’s protection ends. Campaign locks and unresolved-operation checks can prevent disbanding.

### Who can do what

| Action | Citizen | Advisor | Leader |
| --- | --- | --- | --- |
| Contribute resources, inspect stockpile, buy eligible plots | Yes | Yes | Yes |
| Manage an owned private plot | Yes | Yes | Yes |
| Invite members, manage eligible citizen removals | No | Yes | Yes |
| Claim/unclaim eligible land and manage public plots | No | Yes | Yes |
| Start/cancel research; complete stockpile work orders | No | Yes | Yes |
| Set the civilization home in its capital | No | Yes | Yes |
| Appoint advisors, transfer leadership, disband | No | No | Yes |
| Move the capital, withdraw treasury money, set weekly taxes | No | No | Yes |
| Declare/cancel a campaign or offer/accept peace | No | No | Yes |

Advisors do not receive unrestricted control over leaders, other advisors, or citizens' private property. Ownership rules and current campaign restrictions still apply. Server administrator permissions are separate from these civilization roles.

The default advisor limit is **3**, increased by research. Use `/civ members` to see roles and establishment progress.

### Established members and capacity

A member becomes established after **72 hours of membership** and at least **120 minutes of tracked activity within the 14-day activity window**, with sufficiently recent activity. Establishment is recalculated; simply inviting inactive accounts does not immediately increase capacity.

Default claim capacity is:

```text
min(128, 8 + 2 × established non-leader members + research bonuses + administrator bonus)
```

The capital counts as a claim. The leader is excluded from the per-member claim bonus. Research can also increase advisor limits, private-plot limits, research queues, and research queues.

## Territory, capitals, and protection

### Claims and expansion

A claim is one Minecraft chunk: **16×16 blocks horizontally**, covering the vertical column. Use the Java client's chunk-border display if you need to locate its edges.

Normal claims must touch your existing territory on a **north, south, east, or west** edge. Diagonal contact alone is insufficient. All land must stay connected to the capital; removing a connecting chunk is denied. Capacity and allowed-world/biome rules also apply.

Deposit materials first, stand in the target chunk, then run `/civ claim`. Claims spend the **shared stockpile**, not the items still in your inventory. Default costs depend on the resulting total claim count:

| Total claims after purchase | Civic cost of this claim |
| --- | --- |
| Up to 8 | 2 Heavy Cobblestone + 1 Bound Timber |
| 9–24 | 1 Reinforced Masonry + 2 Bound Timber |
| 25–48 | 2 Reinforced Masonry + 1 Engineered Timber + 1 Forged Components |
| 49–96 | 1 Monumental Masonry + 1 Engineered Timber + 1 Tempered Mechanisms |
| 97–128 | 2 Monumental Masonry + 1 Great Beams + 2 Tempered Mechanisms |

`/civ unclaim` releases eligible land and returns the configured percentage of its recorded resource cost to the stockpile: **50% by default**, subject to whole-item rounding. It cannot release the capital or disconnect the territory. Review any private-ownership or campaign restriction shown by the command.

### Capital and civilization home

The capital is public government land and keeps its ownership during war. Use `/civ sethome` from the capital to set the civilization arrival, then `/civ home` to return. Home travel is enabled by default with a **5-second warmup** and **60-second cooldown**; the destination must be safe.

Only the leader can move the capital, using `/civ setcapital` on an eligible unlisted civic claim. The default move cost is **2 Reinforced Masonry + 1 Engineered Timber**, with a **7-day capital-move cooldown**. Campaign restrictions apply.

### Plot types and everyday access

Sovereignty and ownership are different: the civilization controls the claim, while a citizen may privately own a plot within it.

| Type | Normal building/controller access |
| --- | --- |
| `CAPITAL` | Government: leader/advisors; the capital cannot be privately sold or conquered |
| `CIVIC` | Government: leader/advisors |
| `COMMON` | Shared land for the civilization's citizens |
| `PRIVATE` | Owner and explicitly trusted current citizens |
| `FOR_SALE` | Access depends on whether it is a government listing or an owner's private resale; listing does not grant everyone building access |

An advisor or leader does **not** automatically gain building access to another citizen's private plot. Interaction/container flags can grant narrower access without transferring ownership. `/civ inspect` is the best explanation of a specific location's effective permissions.

Protection covers direct building and many indirect paths, including containers, buckets, fire, fluids, pistons, dispensers, explosions, hoppers, animals, vehicles, hanging entities, and armor stands. Machines that cross different access boundaries may be blocked even when both ends belong to the same civilization.

By default, civilization friendly fire is disabled in its own claims and enabled outside its own claims. Animal protection and mob-grief prevention are enabled. Claims are not a blanket guarantee against every form of PvP; war and server combat rules still matter.

Villager and wandering-trader commerce is disabled, including direct merchant-inventory access. Physical Nether/End portals and End gateways are also suppressed; use the travel commands.

## Private plots and housing

### Government listing to citizen ownership

1. A leader/advisor stands on eligible public land and runs `/civ plot list [price]`.
2. A citizen of that civilization stands there and runs `/civ plot buy`.
3. The buyer reviews the purchase price **and weekly tax**, then clicks the green confirmation button.
4. After a successful purchase, the plot becomes private; the owner manages its trust and access.

The default listing price is **100 currency units** when money features are enabled. Leaders/advisors can set their civilization's default with `/civ plot defaultprice <amount>`. The default private-plot limit is **4 per player**, plus civilization research bonuses. The capital is never an ordinary private listing.

Government-sale proceeds go to the civilization treasury. Existing trust, flags, and personal metadata are cleared on ownership changes; a buyer should set their own permissions.

### Selling, surrendering, and losing ownership

An owner can use `/civ plot sell <price>` to list their tenure for another current citizen. The default **10% sale tax** goes to the civilization treasury; the seller receives the remainder. This one-time resale fee is separate from weekly plot tax. `/civ plot unlist` withdraws an authorized listing.

`/civ plot surrender` gives up private ownership to the civilization **without a refund**. Leaving, being removed, and unpaid tax reclamation can also end private rights. War does not change plot ownership. These ownership changes do not erase the buildings, but access changes with the new controller. Do not assume blocks left behind will remain available to the former owner.

Due or unresolved weekly taxes can block a resale purchase or surrender while money features are active. Inspect `/civ taxes` if a transfer is refused.

### Trust, labels, and flags

Use these while standing in your private plot:

```text
/civ plot trust Alex
/civ plot untrust Alex
/civ plot label Riverside House
/civ plot greeting Welcome to the workshop
/civ plot label clear
/civ plot greeting clear
```

Trust is per plot and can only be granted to current citizens of the same civilization. Labels are limited to **32 characters**, greetings to **160**. A label is descriptive metadata, not an additional teleport destination.

Use `/civ plot flags` to inspect flags, then `/civ plot flags <flag> <on|off>` where authorized:

| Flag | Effect when enabled |
| --- | --- |
| `public_interact` | Permit ordinary interactions by visitors |
| `citizen_interact` | Permit ordinary interactions by fellow citizens |
| `public_containers` | Permit container access by visitors |
| `citizen_containers` | Permit container access by fellow citizens |
| `redstone` | Permit redstone interactions through the plot access policy |

These do not grant general block-breaking/building rights. Enabling public container access allows other players to use those containers; keep it off for private storage.

## Weekly plot taxes

Each civilization starts at **0 currency units per privately owned plot per week**. Only the leader can change the rate:

```text
/civ taxes
/civ taxes set 25
```

In this example, an owner of three private plots pays **75 per week in total**, with each plot charged on its own schedule. The GUI shows plots, rates, next due dates, and payment status.

- The charge is per owned plot, not a percentage of the player's balance or the purchase price.
- Capital, civic, common, and unsold government plots are exempt. An owner's plot listed for private resale is still taxable.
- A new purchase receives a full seven-day period before its first bill. Existing plots enrolled by the tax migration are not billed retroactively.
- Changing the rate gives at least seven days before the new scheduled charge. Already-issued bills retain their original amount. Setting `0` stops future scheduled bills.
- Payments are automatically deducted from the **owner's personal Vault balance**, including while they are offline, and credited to the civilization treasury.
- **Verified insufficient funds immediately reclaims that particular plot for the government as `CIVIC`.** There is no debt period, grace period, or automatic payment toggle.
- Reclamation clears private ownership, trust, listings, flags, and personal plot metadata. Buildings and containers remain physically in the world. No purchase refund is paid.
- Owners receive payment/reclamation notices online or on their next login. Treasury history records receipts.

The worker checks once per minute. Each plot gets at most one overdue bill after downtime before beginning its next seven-day period; missed weeks are not stacked into catch-up charges. When multiple payments are due, oldest work is processed first, with due date and claim ID ordering the due plots.

Currency-disabled or unavailable economy services do not collect taxes or trigger reclamation. Definite provider errors are retried. An uncertain external transfer preserves ownership for administrator reconciliation. A provider error is not treated as proof that the player has insufficient funds.

## Travel and dimensions

| Command / event | Destination | Requirement | Default timing |
| --- | --- | --- | --- |
| First-ever join | Safe random unclaimed Overworld ground | Automatic after territory readiness | Free; does not use `/wild` cooldown |
| `/wild` | Safe random unclaimed Overworld ground | `civilizations.wild`, granted by default | 12 hours after successful manual use |
| `/nether` | Random safe Nether ground below its roof | Nether Expedition technology + 1 Nether Ember | 5-second warmup; shared 60-second dimension cooldown |
| `/end` | Random safe End island ground | End Expedition technology + 1 End Sigil | Same dimension warmup/cooldown |
| `/civ teleport nether` / `end` | Same as the shortcuts | Same as the shortcuts | Same shared dimension cooldown |
| `/civ teleport overworld` | Configured safe Overworld return point | No expedition technology | Same dimension warmup/cooldown |
| `/civ home` | Civilization home in its capital | Membership and enabled home travel | Separate 5-second warmup / 60-second home cooldown |

Dimension technology checks still apply in `CRAFT_ONLY` mode. `DISABLED` technology enforcement or an explicit technology bypass can waive them. Nether/End random travel works even if you are already in that target dimension. Overworld dimension-return travel is a fixed return, not another `/wild`.

Random travel uses the destination world's live border. `/wild` requires safe surface ground with two empty blocks above it; dimension travel requires a supported clear 3×3 patch. Nether searches reject the roof, bedrock floors, and hazardous landings. End searches require End stone ground, rejecting void, obsidian platforms, and obstructed landing patches.

A search tries at most **64 random candidates** and times out after **two minutes** (after the dimension warmup where applicable). Work is spread over server ticks. If no suitable land is found, you stay where you are and can retry without consuming a cooldown. Searches can take longer on unexplored terrain.

Both shortcut and `/civ teleport` dimension travel require a genuine custom ritual offering in your inventory or offhand, even when technology enforcement is disabled or bypassed. One is consumed per successful trip, including travel within the same dimension. Missing or removed offerings block travel. Cancelled, failed, or timed-out travel preserves the offering; Overworld returns require none.

Craft these **shapeless recipes** at a crafting table (one item per occupied slot; each recipe yields one offering). They are automatically added to the recipe book; `/nether recipe` and `/end recipe` also list ingredients.

| Offering | Ingredients | Gameplay encouraged |
| --- | --- | --- |
| Nether Ember | 2 obsidian, 1 amethyst shard, 1 honeycomb, 1 rabbit foot, 1 gunpowder, 1 gold ingot | Mining, geode exploration, beekeeping, rabbit hunting/breeding, and hostile mobs; all ingredients obtainable before entering the Nether |
| End Sigil | 2 eyes of ender, 1 ghast tear, 1 blaze rod, 1 prismarine crystals, 1 phantom membrane, 1 diamond, 1 amethyst shard | Nether expeditions, ocean exploration, night combat, and deep mining; no End-only ingredients |

Renaming a vanilla item cannot make an offering. Ritual items cannot be used as ordinary crafting ingredients.

Moving or taking damage cancels pending dimension travel. Successful travel clears falling momentum. The dimension cooldown is separate from `/wild`; using `/nether` does not consume a 12-hour wilderness use.

The `/wild` cooldown and pending first-join placement are saved in player data and survive reconnects/restarts. If first placement fails or you disconnect, a later join or `/wild` retries that free placement. Returning players who already completed it are not moved automatically. Home and dimension cooldowns are runtime state and reset when the plugin/server restarts.

## Civic resources and the stockpile

### Five families, three tiers

Open `/civ items` to browse the custom items and click an item to see its recipe. Civic names use the same colors on physical items and in menus: **Tier 1 green, Tier 2 cyan, Tier 3 purple**.

| Family / persistent key | Tier 1 — green | Tier 2 — cyan | Tier 3 — purple |
| --- | --- | --- | --- |
| `masonry` | Heavy Cobblestone | Reinforced Masonry | Monumental Masonry |
| `timber` | Bound Timber | Engineered Timber | Great Beams |
| `metalwork` | Forged Components | Tempered Mechanisms | Imperial Mechanisms |
| `scholarship` | Research Folio | Bound Archive | Grand Codex |
| `arcana` | Arcane Matrix | Resonant Matrix | Sovereign Matrix |

Recipes produce authenticated items. An ordinary cobblestone renamed “Heavy Cobblestone” does not work. Custom item identity comes from persistent item data, not just its visible name or vanilla icon.

### Crafting and refining

Each Tier 1 recipe is shapeless and produces one civic item:

| Output | Ingredients |
| --- | --- |
| Heavy Cobblestone | 9 cobblestone |
| Bound Timber | 9 accepted logs; accepted types may be mixed |
| Forged Components | 8 iron ingots + 1 charcoal |
| Research Folio | 8 paper + 1 ink sac |
| Arcane Matrix | 4 lapis lazuli + 4 redstone + 1 amethyst shard |

Accepted default logs are oak, spruce, birch, jungle, acacia, dark oak, mangrove, cherry, and pale oak. Planks and other items are not a substitute for the accepted log materials.

Compress **9 identical items of the same family/tier into 1 of the next tier**, using the crafting recipe or `/civ refine`. Thus 1 Tier 3 item represents 81 Tier 1 civic items. Refining also decompresses one Tier 3 into nine Tier 2, or one Tier 2 into nine Tier 1. Tier 1 items cannot be decompressed back into vanilla ingredients through this interface.

The refining menu operates on physical inventory items. It is useful to choose the required tiers **before depositing**.

### Depositing and reading the GUI

```text
/civ deposit hand
/civ deposit all
/civ stockpile
/civ stockpile history
```

Deposits are **permanent**. There is no player command to withdraw items from the stockpile. They become numerical civilization balances used for claims, research, work orders, and other civic expenses. Creative deposits are disabled by default.

The stockpile GUI shows all civic items, including empty resources. Families are arranged in columns and tiers in rows. Stack counts show available quantities up to the inventory display limit of 64; **hover for the exact total**, including amounts above 64 and zero balances. Hover text also explains the tier and recipe. Use refresh to update balances and page controls when needed. It is a read-only view, not a chest you can take items from.

Existing authentic items are normalized to the consistent tier name colors when players join, open inventories, or pick them up. Renaming a catalog's persistent key or changing recipe identity is a server migration concern; players should use the current item menu.

## Work orders and Knowledge

`/civ workorders` displays one persistent civilization-wide order in each category: **Basic, Industrial, and Strategic**. Everyone can inspect what is needed. A leader/advisor clicks a ready order to spend its exact civic stockpile amount and earn Knowledge for the civilization.

Simply carrying matching items is not enough: deposit them first. The menu compares the order with current shared balances. Completing an order consumes resources; supplying an order and funding research may compete for the same materials.

Each category has a **24-hour cooldown after completion** by default. Orders and cooldowns persist; reopening a menu or restarting does not reroll them. Template selection uses configured weights and technology requirements.

| Category | Possible default requests | Knowledge |
| --- | --- | --- |
| Basic | 32 Heavy Cobblestone; 24 Bound Timber; 12 Forged Components; 12 Research Folios; or 8 Arcane Matrices | 2 |
| Industrial | 5 Reinforced Masonry; 4 Engineered Timber; 4 Tempered Mechanisms; 4 Bound Archives; 64 Heavy Cobblestone; or 48 Bound Timber | 3 |
| Strategic | 1 Monumental Masonry; 1 Great Beams; 1 Imperial Mechanisms; 1 Grand Codex; or 4 Resonant Matrices | 4 |
| Strategic, Sovereign Matrix template | 1 Sovereign Matrix | 5 |

Industrial Tempered Mechanisms require Ironworking; Industrial Bound Archives require Scholarship. Strategic Imperial Mechanisms require Engineering, Grand Codex requires Scholarship, and Sovereign Matrix requires Nether Expedition. See [workorders.yml](src/main/resources/workorders.yml) for exact templates and weights.

## Research and the technology tree

### Starting and completing research

Open `/civ tech`, `/civ techtree`, or `/civ research tree`. Hover technologies for costs, prerequisites, capabilities, and progress. A leader/advisor can select an eligible project or use its key:

```text
/civ research start civic_planning
/civ research status
/civ research cancel 1
```

Starting research requires completed prerequisites, enough **Knowledge and civic stockpile materials**, and a free queue. Both kinds of cost are spent when research starts. Research uses elapsed real time and persists through logout/restarts; completion is processed by the server's research checks, every **30 seconds** by default.

There is one queue initially. Imperial Administration adds a second queue. Scholarship reduces subsequent research durations by **10%**. The configured base durations are listed below; inspect your actual project for its completion time.

Cancellation is available during the first **5 minutes** by default and refunds all recorded costs. After that grace period, normal cancellation is denied. If more than one queue is active, specify the queue number.

### Technology enforcement

| Mode | Behavior |
| --- | --- |
| `STRICT` (default) | Gate mapped crafting/production and mapped use/equipment actions |
| `CRAFT_ONLY` | Gate mapped crafting/production; ordinary item-use enforcement is relaxed, but dimension commands still check expedition technology |
| `DISABLED` | Disable capability enforcement; research/civilization systems remain available |

In strict mode, receiving locked equipment from another player does not unlock its use. Unaffiliated players have no civilization research. Technology is action-specific: for example, some recipes require two branches together. Powered rails require Mechanical Transport and Redstone Engineering; spectral arrows require Fletching and Nether Expedition; tipped arrows require Fletching and Advanced Alchemy.

Plain water bottles can be drunk without Alchemy, including for hydration plugins. Other drinkable potions and water bottles with custom potion effects still require Alchemy; splash and lingering potions still require Advanced Alchemy.

### Default research catalog

The table lists all bundled technologies. Costs beyond Knowledge are shown in the in-game tree and [technologies.yml](src/main/resources/technologies.yml). The full default tree costs **1,002 Knowledge**. “Era” groups organize the catalog; the civilization's displayed age follows the milestone rules below.

#### Settlement technologies

| Technology / command key | Prerequisites | Knowledge | Base time | Main unlocks |
| --- | --- | --- | --- | --- |
| Agriculture (`agriculture`) | None | 9 | 3h | Smokers, composters, crop bone meal |
| Animal Husbandry (`animal_husbandry`) | `agriculture` | 10 | 3h | Livestock breeding and leads |
| Seafaring (`seafaring`) | None | 10 | 3h | Boats, chest boats, fishing rods |
| Copperworking (`copperworking`) | None | 10 | 3h | Copper tools, weapons, armor |
| Archery (`archery`) | None | 12 | 4h | Bows, normal arrows, target crafting |
| Civic Planning (`civic_planning`) | None | 13 | 4h | +8 claim capacity |
| Horseback Riding (`horseback_riding`) | None | 14 | 4h | Equine taming, riding, equipment, breeding |
| Ironworking (`ironworking`) | `copperworking` | 15 | 4h | Iron gear, shields, buckets, shears, flint and steel |
| Scholarship (`scholarship`) | None | 15 | 6h | 10% shorter subsequent research |

#### Iron Age technologies

| Technology / command key | Prerequisites | Knowledge | Base time | Main unlocks |
| --- | --- | --- | --- | --- |
| Navigation (`navigation`) | `seafaring`, `scholarship` | 23 | 7h | Navigation items and cartography tables |
| Fletching (`fletching`) | `archery`, `ironworking` | 23 | 7h | Crossbows; combined arrow unlocks |
| Mechanical Transport (`mechanical_transport`) | `engineering` | 25 | 8h | Advanced minecarts and rails; combined powered-rail unlock |
| Engineering (`engineering`) | `ironworking` | 28 | 8h | Anvils, blast furnaces, hoppers, pistons, basic minecarts/rails |
| Fortification (`fortification`) | `civic_planning`, `engineering` | 30 | 10h | +12 claim capacity; advanced border capability |
| Administration II (`administration_ii`) | `civic_planning` | 31 | 10h | +8 claims, +1 private plot per player, +2 advisors |
| Redstone Engineering (`redstone_engineering`) | `engineering`, `scholarship` | 35 | 12h | Advanced redstone and TNT |
| Nether Expedition (`nether_expedition`) | `ironworking`, `scholarship` | 35 | 12h | `/nether`; Nether progression capability |

#### High Age technologies

| Technology / command key | Prerequisites | Knowledge | Base time | Main unlocks |
| --- | --- | --- | --- | --- |
| Diamondworking (`diamondworking`) | `engineering`, `scholarship` | 55 | 18h | Diamond tools, weapons, armor |
| Enchanting (`enchanting`) | `diamondworking`, `scholarship` | 53 | 16h | Enchanting, enchanted equipment, enchantment-related grindstone use |
| Alchemy (`alchemy`) | `nether_expedition`, `scholarship` | 53 | 16h | Brewing stands and drinkable potions |
| Administration III (`administration_iii`) | `administration_ii`, `fortification` | 60 | 18h | +20 claims, +2 private plots per player |
| Advanced Alchemy (`advanced_alchemy`) | `alchemy`, `scholarship` | 65 | 20h | Splash and lingering potions; combined tipped-arrow unlock |

#### Imperial technologies

| Technology / command key | Prerequisites | Knowledge | Base time | Main unlocks |
| --- | --- | --- | --- | --- |
| End Expedition (`end_expedition`) | `diamondworking`, `enchanting` | 88 | 24h | `/end`; End progression capability |
| Aeronautics (`aeronautics`) | `end_expedition`, `engineering` | 90 | 24h | Elytra equipment, gliding, firework boosting |
| Netherite Smithing (`netherite_smithing`) | `diamondworking`, `nether_expedition` | 95 | 30h | Netherite gear and smithing |
| Imperial Administration (`imperial_administration`) | `administration_iii`, `fortification` | 105 | 36h | +32 claims, +3 private plots per player, +1 research queue |

### Displayed civilization age

| Displayed age | Completed milestones |
| --- | --- |
| Settlement | Starting age |
| Iron Age | Ironworking **and** Civic Planning |
| High Age | Diamondworking **and any two** of Enchanting, Alchemy, Administration III, Advanced Alchemy |
| Imperial Age | Imperial Administration |

Completing a technology from a later catalog group does not by itself satisfy every displayed-age milestone. Use `/civ info` to see the civilization's current age and progress.

## Money, selling, and the treasury

Currency features require `economy.mode: VAULT`, Vault, and an active economy provider. Prices are units of that provider's currency; a dollar sign in an example is not real money.

### Selling resource bundles

`/sell list` opens the supported-resource GUI. `/sell hand` sells complete bundles of the supported item in your main hand; `/sell all` checks your storage inventory. Partial bundles stay in your inventory. Sales credit your personal economy balance.

These are fixed offers in [SellCatalog.java](src/main/java/io/github/empireage/civilizations/service/economy/SellCatalog.java), not a configurable buy/sell marketplace:

| Ordinary resource | Items per bundle | Payout per bundle |
| --- | --- | --- |
| Rotten flesh | 32 | 6 |
| Bones | 32 | 8 |
| String | 32 | 9 |
| Spider eyes | 16 | 7 |
| Gunpowder | 32 | 12 |
| Slimeballs | 16 | 10 |
| Prismarine shards | 64 | 10 |
| Prismarine crystals | 32 | 12 |
| Ender pearls | 16 | 14 |
| Phantom membranes | 8 | 15 |
| Magma cream | 16 | 14 |
| Blaze rods | 16 | 18 |
| Ghast tears | 4 | 22 |
| Shulker shells | 8 | 32 |
| Dragon's breath | 16 | 26 |

For example, 40 bones sell as one 32-bone bundle for 8, leaving 8 bones. The shop matches vanilla material types. Renamed or customized items using a supported material can also be included by `/sell all`; keep any such items out of the storage slots you intend to sell.

### Civilization treasury

```text
/civ treasury balance
/civ treasury deposit 100
/civ treasury withdraw 50
/civ treasury history
```

Members can donate personal money to the treasury. **Only the leader can withdraw.** Government plot sales, the resale tax share, and weekly plot taxes credit the treasury. Ledger history records money movements.

Civilizations records external payment stages so interrupted purchases and transfers can be recovered. If a payment is unresolved, do not repeatedly retry or try to work around the denial. Have an administrator inspect it; uncertain Vault transfers cannot always be safely replayed automatically.

## Religion and sacrifices

Religion is available through `/religion`, independent of civilization leadership. Choose a god in the menu or run `/religion <god>`, then hold your intended offering and use `/sacrifice [god]`. Giving the god key directly avoids relying on your current menu selection.

| God / key | Required ordinary item | Default blessing |
| --- | --- | --- |
| Zeus (`zeus`) | Gold ingot | Speed I + Jump Boost II |
| Poseidon (`poseidon`) | Cod | Water Breathing I + Dolphin's Grace I |
| Demeter (`demeter`) | Bread | Regeneration I + Health Boost I |
| Ares (`ares`) | Iron ingot | Strength I + Resistance I |
| Athena (`athena`) | Book | Haste I + Luck I |
| Artemis (`artemis`) | Feather | Speed I + Night Vision I |

Blessings last **30 minutes** by default. The sacrifice cooldown is **4 hours per player, per god**, and persists in MySQL. Choosing a god for `/sacrifice` is a menu/command selection, not a permanent faction conversion.

A recorded sacrifice consumes the **entire held stack**, succeeds only if it is the correct item and its size **exceeds** a random roll from **1 through 32**, and starts that god's cooldown whether accepted or rejected. Rejection grants no blessing and calls down lightning. Sacrifices require Survival or Adventure mode.

For a correct item, the default chance is:

| Stack offered | Acceptance chance |
| --- | --- |
| 1 | 0% |
| 16 | 15/32 = 46.875% |
| 32 | 31/32 = 96.875% |
| 33 or more | 100% |

The wrong material is rejected regardless of amount. A cooldown or storage denial is not an accepted sacrifice; the command restores items when the result proves restoration is safe. Read the offering in the menu before using the command, since offering the wrong held stack is still a real trial.

## Scheduled war windows

Wars are scheduled campaigns between **bordering civilizations**, not unrestricted permanent raiding. Membership, building rights, and the allowed time window are enforced by the plugin. Wars have no winner, loser, score, or capture objectives.

### Declaring a campaign

The attacking leader needs an authentic **War Charter** in their inventory and these default costs in the stockpile:

| Resource | Amount |
| --- | --- |
| Reinforced Masonry (`masonry:2`) | 4 |
| Engineered Timber (`timber:2`) | 4 |
| Tempered Mechanisms (`metalwork:2`) | 2 |

Use `/civ war declare <civilization>` and review the confirmation GUI. A normal declaration requires:

- A shared cardinal border.
- At least **3 established members on each side**.
- Neither civilization protected by its founding peace shield.
- Neither civilization already in an unresolved campaign.
- No active post-campaign truce between the two civilizations.
- The Charter and required stockpile resources.

The campaign is scheduled for a valid weekly window with at least **24 hours' notice**. With the default schedule, combat runs Saturday **14:00–18:00 in `America/Los_Angeles`**. Always inspect `/civ war status`: it shows the actual campaign's dates rather than assuming the next Saturday is eligible.

Membership is locked for participants when the campaign is declared. The combat roster is recorded when the campaign activates. A civilization can have only one unresolved campaign at a time.

### Crafting a War Charter

Use this shaped crafting recipe. A renamed vanilla book is not a substitute for a crafted War Charter.

**War Charter:** `P` = paper, `I` = iron ingot, `B` = writable book (book and quill).

```text
P I P
I B I
P I P
```

The Charter is consumed on a valid declaration. War Standards and objective selection are no longer used.

### What war permits

During the exact active window, eligible rostered participants may fight, break blocks in opposing territory, and open and loot enemy containers, including on private plots. These war permissions end at the scheduled cutoff, even if the server has not processed the end-of-war update yet. Open containers are checked again when items are moved. Ordinary server PvP and friendly-fire rules still apply.

The default siege placement list is ladders, scaffolding, dirt, cobblestone, and TNT. TNT and always-protected blocks have explicit material policies in `config.yml`; technology requirements also apply. Protected materials and the capital monument retain protection from destruction. Capital combat is enabled by default. Block drops are enabled by default; an existing server's `war.attacker-block-drops` setting still applies. TNT cannot destroy containers. War does not grant access to animals, vehicles, or armor stands.

Temporary siege placements are tracked for cleanup. Broken buildings and looted items are not restored at the end of war. Civilizations does not automatically ask CoreProtect to roll back a campaign.

### Ending a war and making peace

The war simply ends when its scheduled window expires. There are no winners, losers, draws, automatic claim transfers, or stockpile rewards. Land and plot ownership stay with their existing owners. Loot is whatever participants take during the window.

A **7-day truce** follows the scheduled end. Use `/civ war peace` to propose or accept mutual peace; acceptance by both leaders ends a pending or active war immediately. The attacking leader may use `/civ war cancel` during the default **10-minute declaration grace period**; successful grace cancellation refunds declaration materials and returns the Charter.

## Commands

Use the tables below as a complete command reference. `<value>` is required; `[value]` is optional; `|` separates alternatives. Gameplay commands are for players; administrator diagnostics also work from console.

Civilization gameplay is under `/civ`; standalone commands are `/wild`, `/nether`, `/end`, `/chat`, `/sell`, `/religion`, and `/sacrifice`. Global chat is selected by default. Local chat reaches players in the same world within `chat.local-radius-blocks` (100 blocks by default). Tab completion and the paginated tutorial at `/civ help [page | topic]` are included. Most authority comes from the player's civilization role, not from separate server permission nodes.

### General and membership

| Command | Purpose |
| --- | --- |
| `/wild` | Teleport to safe unclaimed Overworld land inside the current world border; 12-hour cooldown after a successful use. First-join placement is automatic and free. |
| `/civ help [page \| topic]` | Read the eight-page new-player tutorial or jump to membership, territory, plots, resources, research, treasury, or war. |
| `/civ tutorial` | Receive the written tutorial book; player-only, with a persistent 24-hour cooldown after each successful delivery. |
| `/civ create <name>` | Preview exact costs, then use the confirmation GUI to found a civilization in the current chunk. Names are the sole identifiers and are case-insensitive. |
| `/civ info [civilization]` | Show age, leader, members, establishment, claims/capacity, Knowledge, treasury, research, shield, and war state. |
| `/civ list [page]` | Browse active civilizations. |
| `/civ map [radius]` | Render a local chunk map; radius is limited to 10. |
| `/civ inspect` | Explain the current chunk's controller and the player's break, place, interaction, and container access. |
| `/chat <global \| local \| civ>` | Switch the audience for ordinary messages; global is the default. |
| `/civ chat [message]` | Send a civilization-only message, or toggle civilization chat when no message is supplied. |
| `/civ invite <player>` | Invite a known player; leader/advisor. |
| `/civ accept <civilization>` | Accept an unexpired invitation. |
| `/civ deny <civilization>` | Decline an invitation. |
| `/civ members [page]` | Show the roster, roles, establishment, activity, and war lock state. |
| `/civ kick <player>` | Remove a citizen; leader/advisor. A confirmation GUI opens when private plots are affected. |
| `/civ leave` | Leave the civilization, subject to membership and war locks. |
| `/civ advisor <add \| remove> <player>` | Promote or demote an advisor; leader only. |
| `/civ transfer <player>` | Transfer leadership to another member; leader only. |
| `/civ disband` | Preview and then confirm archival in a GUI; leader only. |

### Territory and plots

| Command | Purpose |
| --- | --- |
| `/civ claim` | Preview the exact cost, then confirm in a GUI to claim the current eligible cardinally adjacent chunk; leader/advisor. |
| `/civ unclaim` | Preview and confirm release of the current eligible chunk in a GUI; leader/advisor. |
| `/civ setcapital` | Preview and confirm moving the capital to the current claim in a GUI; leader only. |
| `/civ sethome` | Set the civilization home in the capital; leader/advisor. |
| `/civ home` | Teleport to the home after the configured warm-up and cooldown when safe. |
| `/civ teleport <overworld\|nether\|end>` | Return to the configured Overworld arrival, or find random safe Nether/End wilderness; Nether/End expedition technology is required. |
| `/nether`, `/end` | Shortcuts for random safe travel below the Nether roof or onto an End island, including from within that dimension. |
| `/civ plot type <civic \| common>` | Change a non-capital public plot's access type; leader/advisor. |
| `/civ plot defaultprice <amount>` | Set the civilization's default public listing price; leader/advisor. |
| `/civ plot list [price]` | List the current public claim for citizens; leader/advisor. |
| `/civ plot unlist` | Remove a civilization listing, or withdraw the owner's private resale. |
| `/civ plot buy` | Preview and confirm purchase of the current listing in a GUI. |
| `/civ plot sell <price>` | List owned private tenure to other citizens. |
| `/civ plot surrender` | Confirm in a GUI to permanently surrender owned tenure to civilization control without a refund. |
| `/civ plot trust <player>` | Trust a current citizen on the owner's private plot. |
| `/civ plot untrust <player>` | Remove private-plot trust. |
| `/civ plot label <text \| clear>` | Set/clear the current owned private plot label, up to 32 characters. |
| `/civ plot greeting <text \| clear>` | Set/clear the current owned private plot greeting, up to 160 characters. |
| `/civ plot flags` | View the current plot's access flags. |
| `/civ plot flags <flag> <on \| off>` | Edit `public_interact`, `citizen_interact`, `public_containers`, `citizen_containers`, or `redstone` where authorized. |

### Resources, research, religion, treasury, and war

| Command | Purpose |
| --- | --- |
| `/sell hand` | Sell every complete bundle of the supported resource in the main hand through Vault; any partial bundle remains. |
| `/sell all` | Sell all complete supported-resource bundles across the player's storage inventory through Vault. |
| `/sell list` | Open a read-only GUI showing every resource bundle and its coin payout. |
| `/civ refine` | Open the civic-material refining/decompression interface. |
| `/civ deposit <hand \| all>` | Permanently deposit authenticated civic materials into the shared stockpile. |
| `/civ stockpile [history [page]]` | Open the stockpile GUI with all civic items, stack counts, and detailed hover totals; `history` shows the ledger. |
| `/civ items` | Open the tiered custom-item browser; click an item to view its recipe. |
| `/civ workorders` or `/civ orders` | Compare each persistent order with the shared stockpile; leaders/advisors may click a ready order to spend its resources and earn Knowledge. |
| `/religion [god]` | Open the Greek pantheon or choose a god directly; the GUI lists offerings, blessings, and cooldowns. |
| `/sacrifice [god]` | Offer the entire held stack to the selected god. A correct stack must exceed a random roll of 1–32; rejection consumes it and calls down lightning. |
| `/civ tech`, `/civ techtree`, `/civ research tree` | Open the technology tree and select active research. |
| `/civ research start <technology>` | Start research; leader/advisor. |
| `/civ research status` | Show occupied queues and completion times. |
| `/civ research cancel [queue]` | Cancel within the configured grace period; leader/advisor. |
| `/civ taxes` | Open your weekly plot-tax GUI with each plot’s charge, next due date, and payment status. |
| `/civ taxes set <amount>` | Leader only: set the civilization’s weekly currency tax per privately owned plot; defaults to `0`. |
| `/civ treasury balance` | Show the civilization treasury balance. |
| `/civ treasury deposit <amount>` | Donate through Vault when money features are active. |
| `/civ treasury withdraw <amount>` | Withdraw through Vault; leader only. |
| `/civ treasury history [page]` | Show the audited treasury ledger. |
| `/civ war declare <civilization>` | Preview exact costs, then confirm in a GUI to consume an authentic War Charter and schedule a valid campaign; leader only. |
| `/civ war status [civilization]` | Show state, exact window, roster, peace proposals, and war permissions. |
| `/civ war peace` | Offer or accept mutual peace; leader only. |
| `/civ war cancel` | Cancel during the declaration grace period; attacking leader only. |

Costly and destructive gameplay commands open a short-lived GUI with a green confirm block and red deny block. The operation is revalidated after the click and is rejected if the underlying claim, listing, role, or cost changed.

### Administration

Every administrator command requires `civilizations.admin`. The permission shown below is the additional permission required for a mutating or reload operation.

| Command | Additional permission | Purpose |
| --- | --- | --- |
| `/civ admin help` | none | Show only the administrator commands the sender may use. |
| `/civ admin reload` | `civilizations.admin.reload` | Reload `messages.yml` and validate `config.yml`; gameplay, database, integration, and catalog changes require restart. |
| `/civ admin inspect civ <name \| id>` | none | Compare cached and persisted civilization state. |
| `/civ admin inspect claim [world-uuid x z]` | none | Inspect the current or specified chunk claim. |
| `/civ admin inspect player <player \| uuid>` | none | Inspect membership and related persisted state. |
| `/civ admin inspect war <id>` | none | Inspect a campaign and its cached identity. |
| `/civ admin setrole <player> <leader \| advisor \| citizen>` | `civilizations.admin.data` | Repair a role while preserving the single-leader invariant. |
| `/civ admin forceclaim <civilization>` | `civilizations.admin.data` | Assign the current chunk with a warning GUI and an audit entry. |
| `/civ admin forceunclaim` | `civilizations.admin.data` | Remove the current claim with a connectivity/capital warning GUI and an audit entry. |
| `/civ admin grantresource <civ> <key> <tier> <signed-amount>` | `civilizations.admin.data` | Adjust a validated civic-resource balance. |
| `/civ admin grantknowledge <civ> <signed-amount>` | `civilizations.admin.data` | Adjust Knowledge without allowing a negative balance. |
| `/civ admin research <unlock \| lock> <civ> <technology> [confirm]` | `civilizations.admin.data` | Repair research/unlock state with dependency warnings. |
| `/civ admin war <cancel \| resolve> <id> [confirm] [reason]` | `civilizations.admin.war` | Repair a campaign and record the stated reason. |
| `/civ admin war setstate <id> <state> [confirm]` | `civilizations.admin.war` | Override campaign state after preview. |
| `/civ admin audit [filters]` | none | Query audit entries using `civ=`, `actor=`, `action=`, `target=TYPE:ID`, `since=`, `before=`, `page=`, and `size=`. |
| `/civ admin migrate` | none | Report database product, schema version/checksum, required tables, and warnings. It does not run a manual migration. |
| `/civ admin invariants` | none | Run the read-only invariant scanner immediately. |

High-risk repairs show warnings before continuing. In-game administrators receive the same green/red confirmation GUI; console operators append `confirm` because a console cannot open an inventory. Administrator changes are durable and audited.

## Permissions

| Node | Default | Purpose |
| --- | --- | --- |
| `civilizations.wild` | everyone | Use `/wild`; its 12-hour manual cooldown still applies. |
| `civilizations.use` | everyone | Use `/civ`, `/nether`, and `/end`; role/technology requirements still apply. |
| `civilizations.create` | everyone | Found a civilization. |
| `civilizations.chat` | everyone | Use `/chat` and civilization chat. |
| `civilizations.sell` | everyone | Use `/sell hand`, `/sell all`, and `/sell list`. |
| `civilizations.religion` | everyone | View the pantheon and make sacrifices. |
| `civilizations.admin` | operators | Read-only diagnostics, audit queries, schema status, and invariant checks. |
| `civilizations.admin.reload` | operators | Reload safe configuration sections. |
| `civilizations.admin.data` | operators | Data repairs and balance grants. |
| `civilizations.admin.war` | operators | Campaign repair and override actions. |
| `civilizations.bypass.protection` | nobody | Explicitly bypass claim protection. |
| `civilizations.bypass.technology` | nobody | Explicitly bypass technology gates. |
| `civilizations.bypass.war` | nobody | Explicitly bypass campaign roster restrictions. |

Grant bypasses narrowly through your permissions plugin. Their `default: false` setting means ordinary operators do not silently receive them.

## Configuration reference

The complete defaults are committed under [`src/main/resources/`](src/main/resources/). Edit the generated files under `plugins/Civilizations/` on the running server. Use spaces for YAML indentation and preserve material names, resource identities, and prerequisite keys.

### Files and their responsibilities

| File | What it controls |
| --- | --- |
| [config.yml](src/main/resources/config.yml) | Database, server identity, worlds, membership, claims, plots, travel, economy, enforcement, war, protection, integrations, scheduler intervals |
| [resources.yml](src/main/resources/resources.yml) | Civic families/items, recipes, accepted logs, tier conversion ratio, creative deposits, claim-cost bands |
| [technologies.yml](src/main/resources/technologies.yml) | Technology keys, prerequisites, Knowledge/material costs, duration, capabilities, numerical modifiers, displayed-age milestones |
| [workorders.yml](src/main/resources/workorders.yml) | Persistent order categories, resource targets, weights, eligibility, Knowledge rewards, completion cooldown |
| [religion.yml](src/main/resources/religion.yml) | Gods, icons, offerings, potion effects, amplifiers, blessing duration, per-god cooldown |
| [messages.yml](src/main/resources/messages.yml) | Shared player-facing messages, supporting legacy `&` colors; not every string in the plugin is externalized |
| [plugin.yml](src/main/resources/plugin.yml) | Packaged plugin identity, registered commands, permissions, and optional dependencies; not a generated gameplay settings file |

A resource amount uses a stable key such as `"masonry:2": 4`. It means four Tier 2 masonry civic items, not four vanilla stone bricks. A potion amplifier in `religion.yml` is zero-based: `0` means level I and `1` means level II.

### Main setting groups and defaults

| Group / key | Bundled default | Meaning |
| --- | --- | --- |
| `server-id` | `primary` | Stable audit identity; one active server per schema |
| `database.*` | Local MySQL, port 3306, pool 10, timeout 5000 ms, retry 30 s | Credentials and connectivity; change the placeholder password |
| `worlds.allowed` | `["world"]` | Founding/new-claim world allowlist; empty means all worlds |
| `worlds.blacklisted-biomes` | `[]` | Additional biome restrictions |
| `worlds.fail-closed-during-warmup` | `true` | Required protection invariant while territory data is unknown |
| `chat.local-radius-blocks` | `100` | Same-world local chat range |
| `founding.minimum-distance-chunks` | `12` | Founding separation rule |
| `founding.peace-shield-days` | `14` | Initial protection from campaign declarations |
| `founding.money-cost` / `material-cost` | `0`; 4 `masonry:1`, 2 `timber:1` | Personal founding payment |
| `membership.invite-expiry-minutes` | `10` | Invitation lifetime |
| `membership.change-cooldown-hours` | `24` | Membership-change cooldown after departure |
| `membership.established-after-hours` | `72` | Membership age needed for establishment |
| `membership.established-active-minutes` / `established-window-days` | `120` / `14` | Activity requirement and rolling window |
| `membership.advisor-base-limit` | `3` | Advisor slots before technology bonuses |
| `claims.base-capacity` / `established-member-bonus` / `absolute-cap` | `8` / `2` / `128` | Claim-capacity calculation |
| `claims.refund-percent` | `50` | Eligible unclaim resource refund |
| `claims.capital-move-cooldown-days` / `capital-move-cost` | `7`; 2 `masonry:2`, 1 `timber:2` | Moving the capital |
| `plots.base-limit` / `default-price` | `4` / `100.00` | Private-plot capacity and initial default listing price |
| `plots.sale-tax-percent` | `10` | One-time private resale share paid to the treasury |
| `plots.confirmation-seconds` | `30` | Plot/confirmation lifetime where used |
| `plots.home-enabled` / `home-warmup-seconds` / `home-cooldown-seconds` | `true` / `5` / `60` | Civilization home travel |
| `economy.mode` | `DISABLED` | Enable `VAULT` for currency-backed gameplay |
| `technology.enforcement` / `cancellation-grace-minutes` | `STRICT` / `5` | Capability checks and research cancellation |
| `travel.warmup-seconds` / `cooldown-seconds` | `5` / `60` | Shared dimension-travel timing |
| `travel.safe-search-radius` | `8` | Search radius around the fixed Overworld return anchor |
| `travel.claim-protection-radius-chunks` | `1` | Public reservation around each configured anchor |
| `travel.destinations.*` | `world`, `world_nether`, `world_the_end` | Travel target world names; optional coordinates apply to the Overworld return |
| `war.timezone` / `weekday` / `start-time` / `end-time` | `America/Los_Angeles` / `SATURDAY` / `14:00` / `18:00` | Weekly campaign window |
| `war.notice-hours` / `truce-days` | `24` / `7` | Declaration notice and post-campaign truce |
| `war.declaration-grace-minutes` | `10` | Declaration cancellation deadline |
| `war.minimum-established-members` | `3` | Established members required on each side |
| `war.capital-monument-radius` / `capital-monument-height` | `3` / `8` | Protected capital monument dimensions |
| `war.allow-capital-combat` / `attacker-block-drops` | `true` / `true` | Campaign combat and drop policy |
| `war.declaration-cost` | 4 `masonry:2`, 4 `timber:2`, 2 `metalwork:2` | Shared-resource cost in addition to a Charter |
| `war.siege-place-materials` / `tnt-breakable-materials` / `always-protected-materials` | Explicit material lists | Allowed siege construction and destruction; see full configuration |
| `protection.friendly-fire-own-claims` / `friendly-fire-wilderness` | `false` / `true` | Civilization friendly-fire policy |
| `protection.protect-animals` / `block-mob-griefing` | `true` / `true` | Entity/environment protection |
| `integrations.worldguard` / `placeholderapi` / `coreprotect` | All `true` | Enable detection/use when installed |
| `notifications.claim-entry-seconds` | `3` | Claim-entry notification interval |
| `notifications.research-check-seconds` / `war-tick-seconds` | `30` / `1` | Research and war processing intervals |
| `notifications.invariant-check-minutes` | `30` | Periodic data-consistency scan |

The `membership.inactivity-succession-enabled`, `inactivity-days`, and `succession-notice-hours` settings are parsed, but this implementation does not run an automatic succession worker. Do not rely on them to transfer an inactive leader's role; use a reviewed administrator repair when necessary.

The **12-hour `/wild` cooldown**, random-search bounds, and **weekly tax period** are implementation constants, not existing YAML settings. The weekly tax **amount** is civilization-specific database state set by `/civ taxes set`; it is not `plots.sale-tax-percent`.

### Reloads and catalog upgrades

`/civ admin reload` reloads `messages.yml` and validates `config.yml`. It does **not** replace the live database, gameplay, integration, scheduler, or catalog configuration. Fully restart after changing those settings. Do not use a plugin manager or `/reload` to replace a running Civilizations instance.

Catalogs have `catalog-version` markers. When a file’s version differs from the shipped version, startup backs up that file as a `.pre-v*.bak` file and restores the packaged catalog (the religion v1→v2 upgrade instead changes Demeter’s default wheat offering to bread while preserving other settings); a legacy work-order format also has an upgrade path. Review and reapply compatible custom balancing after that upgrade. Do not simply bump the version marker to bypass a required format conversion.

Resource keys, technology keys, recipe versions, and world UUIDs participate in persistent identity. Preserve deployed keys and the matching worlds. Renaming/removing a used key requires an intentional data migration, not just a cosmetic YAML edit. Display names and balancing also need a restart and a review of their effect on existing items/orders.

## PlaceholderAPI

When PlaceholderAPI is installed and its integration is enabled, the plugin registers identifier `civ`:

- `%civ_name%`
- `%civ_role%`
- `%civ_age%`
- `%civ_claims%`
- `%civ_claim_limit%`
- `%civ_members%`
- `%civ_research%`
- `%civ_research_remaining%`
- `%civ_war_state%`
- `%civ_war_enemy%`
- `%civ_war_remaining%`

Unknown placeholders are left unresolved. Unaffiliated-player text values are empty and numeric values are zero.

## Administration and maintenance

### Readiness and database failures

MySQL is authoritative. The plugin uses an in-memory snapshot for quick claim and membership lookups, applies normal mutations transactionally, and refreshes the cache after changes and periodically.

On startup, unknown territory is protected until the database has connected, migrations have succeeded, and the cache is loaded. State-changing commands are refused during that period. After a later database outage, the last successfully loaded snapshot remains available to protect known claims, while mutations are locked until recovery. Read-only information may be stale.

The default database retry interval is **30 seconds**. Restore connectivity and wait for readiness. Disabling Civilizations or allowing unknown territory as wilderness removes the protection boundary rather than fixing storage.

### Useful operator checks

```text
/civ admin migrate
/civ admin invariants
/civ admin inspect civ River Kingdom
/civ admin inspect claim
/civ admin inspect player Alex
/civ admin help
/civ admin audit action=admin. page=1 size=50
```

Use `/civ admin help` for the exact available admin syntax. Schema reports and invariant scans are read-only. Inspections compare persisted and cached state. The audit filters support civilization, actor, action prefix, target, date range, page, and page size.

Review previews before force-claiming, changing roles, granting resources, or overriding campaigns. Repairs require the additional permissions in the [administrator command table](#administration). They are recorded in the audit log; bypass nodes are explicit permissions, not ordinary leader abilities.

### Back up the whole game state

For a consistent restore point, stop the server cleanly and back up:

1. The Civilizations MySQL schema.
2. All Minecraft worlds, including world UUID files and player data. Player data contains physical items, the `/wild` cooldown, and pending first placement.
3. The complete `plugins/Civilizations/` directory, including `religion.yml`, customized catalogs, and catalog upgrade backups.
4. The Vault economy provider's data/database and any other gameplay-critical plugin data.
5. The exact plugin JAR, server version, and configuration used with the backup.

An example InnoDB dump, run from a private backup directory, is:

```bash
mysqldump \
  --host=127.0.0.1 \
  --user=civilizations \
  --password \
  --single-transaction \
  --quick \
  --hex-blob \
  --no-tablespaces \
  --set-gtid-purged=OFF \
  civilizations > civilizations-YYYYMMDD-HHMMSS.sql
```

Use an actual timestamp in the output name. `--password` prompts without including the password in the command itself. Binary UUID columns need lossless backup handling; `--hex-blob` provides it. Consult the [MySQL dump reference](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html) for your database/server configuration.

Keep backups outside the public repository and copy them to separate storage. Regularly restore a backup into an isolated test environment to verify it. A mismatched world, economy balance store, and civilization database can produce incorrect ownership or payment state even when each individual backup file is readable.

### Upgrade and rollback

1. Read [CHANGELOG.md](CHANGELOG.md) and check your current schema/invariants.
2. Stop the Minecraft server and take a matching backup of all state above.
3. Replace the plugin JAR and review any documented configuration/catalog changes.
4. Start in maintenance mode. Startup applies missing ordered migrations and checks existing checksums.
5. Wait for cache readiness, then run `/civ admin migrate` and `/civ admin invariants`.
6. Inspect customized catalogs after any automatic version upgrade and complete a gameplay smoke test before reopening.

Migrations are **forward-only**. Never edit an already-applied migration or change `schema_history` to silence a checksum error. Returning to an older JAR may require restoring its matching database, plugin files, worlds, and economy data. Prefer restoring into a separate empty schema/environment while investigating a failed update.

The detailed [operations runbook](docs/OPERATIONS.md) contains database provisioning, monitoring, restoration, cache recovery, economy reconciliation, and war-cleanup procedures.

### Interrupted payments and plot-tax incidents

Ordinary payment operations are recorded in `economy_operations`. Tax billing additionally uses `civ_plot_taxes`, `plot_tax_accounts`, and `plot_tax_bills` from migration V6.

Recovery automatically retries operations whose persisted state proves retrying is safe. `WITHDRAWAL_IN_FLIGHT`, `DELIVERY_IN_FLIGHT`, and `REFUND_IN_FLIGHT` mean an external call may have happened without a conclusive recorded result. Vault has no portable transaction lookup/idempotency mechanism to settle that ambiguity automatically.

For an unresolved operation, inspect the audit/ledger records and the economy provider's history before any manual reconciliation. Do not reset it to pending, repeat a charge, or mark it completed just to remove a warning. Tax operations are handled by the tax worker, not the generic recovery worker; ambiguous tax calls preserve ownership while unresolved. Follow the [tax operations procedure](docs/OPERATIONS.md#weekly-plot-taxes).

### Ongoing checks

Keep clocks synchronized and monitor database/cache failures, stale economy operations, invariant warnings, and campaign transition/temporary-block cleanup errors. Run manual invariants after a restore, an administrator repair, or a payment incident. Maintain regular backups and verify free disk space for world generation, database logs, and backup retention.

## Troubleshooting and player FAQ

| Symptom / question | Explanation and next step |
| --- | --- |
| Plugin fails with `UnsupportedClassVersionError` | The server launched with an older Java runtime. Select Java 25 in the actual service/panel startup environment, not only your terminal. |
| Maven reports an unsupported release | Check `mvn -version`; Maven must run under JDK 25. |
| Unsupported API version or missing Bukkit methods | Check that the server implements Spigot API 26.2. A newer label or a different fork is not proof of compatibility. |
| Maven cannot resolve dependencies | The first build needs repository access. Inspect the reported repository/network error; offline mode only works after dependencies are cached. |
| Everyone is blocked by “territory data is warming up” | Check database endpoint, credentials, grants, TLS, migration logs, and the readiness message. Do not disable protection as a workaround. |
| MySQL “access denied” | Check both username/password and the allowed source host for that MySQL account. Container/remote source addresses differ from localhost. |
| Migration checksum mismatch | Use the matching release lineage or restore a matched backup; do not edit checksums or old migrations. |
| Vault is installed but payments do not work | Install/enable a compatible economy provider and set `economy.mode: VAULT`, then restart and check provider readiness. |
| Plots cost nothing and taxes are not collected | The default economy mode is `DISABLED`; money-backed play requires Vault mode and a provider. |
| `/nether` or `/end` is unknown or belongs to another plugin | Confirm the new Civilizations JAR loaded. For command collisions, try `/civilizations:nether` or `/civilizations:end` and review server aliases. |
| Dimension travel says the world is unavailable | The configured world must exist, be loaded, and have the correct environment. Civilizations does not generate/load dimensions itself. |
| Dimension travel says the return route is unsafe | Make the configured Overworld spawn/coordinate destination safe and keep it inside the border. |
| Random travel cannot find a location | The border may be tiny, mostly ocean/void, heavily claimed, or unsafe. Retry; the failed attempt did not use a cooldown. Operators should review the border and terrain. |
| Random travel causes lag | Candidate chunks may need generation on Spigot's main thread. Use an intentional play-area border and review pre-generation/server performance. |
| First join moved me but `/wild` is still available | Intended: automatic first placement is free. The 12-hour timer begins only after a successful manual use. |
| Can I use portals instead of research? | Physical Nether/End portals and gateways are suppressed. Use the command route with the required technology. |
| I found/bought an iron tool but cannot use it | Under strict enforcement, your civilization must unlock the matching technology. Owning an item is not an unlock. |
| Villagers will not trade | Villager and wandering-trader commerce is deliberately disabled. |
| I have materials but cannot found a civilization | Founding needs authenticated physical civic items, an eligible site, and clear membership/cooldown state. Use `/civ items`, not an anvil rename. |
| I have materials but cannot claim/research/complete an order | These actions spend the shared stockpile. Deposit the correct family/tier, check Knowledge/prerequisites/capacity, and use an authorized role. |
| I cannot claim diagonally or unclaim a bridge chunk | Normal territory must stay cardinally connected to its capital. |
| I am leader but cannot build in a citizen's home | A private plot is controlled by its owner/trust, not automatically by the government role. |
| A hopper/piston/fluid cannot cross my property line | Indirect interactions respect access-controller boundaries; adjacent plots can have different controllers. |
| My stockpile item says 64 but I deposited more | Inventory counts are capped for display. Hover to read the exact balance. |
| Can I withdraw/decompress stockpile balances? | No player stockpile withdrawal exists. Refine physical items before depositing; deposits are permanent. |
| Work orders are not using the items in my hand | Ready orders consume shared stockpile balances and must be completed by a leader/advisor. |
| Research did not instantly unlock at the displayed time | Completion is checked periodically, every 30 seconds by default, and requires healthy storage. |
| My private plot became civic land | Possible causes include verified unpaid tax, surrender, or departure/removal. War does not transfer ownership. Inspect `/civ taxes` and ask an administrator to check the audit history. |
| Why did 32 offerings fail? | Your stack must strictly exceed the 1–32 roll. A roll of 32 rejects 32 items; 33 correct items guarantee acceptance under the defaults. |
| I changed `config.yml` but behavior is unchanged | `/civ admin reload` only reloads messages and validates configuration. Structural/gameplay changes require a full restart. |
| A customized catalog was replaced | Startup detected a mismatched catalog version or legacy format and backed it up before restoring the shipped format. Review `.pre-v*.bak` and reapply compatible changes. |
| Can I connect several servers to one schema? | Not with this implementation. There is no cross-server cache invalidation; use a separate schema per active server. |

When reporting a bug, include the exact plugin/server/Java versions, reproduction steps, relevant command and error text, active integrations, and a sanitized log excerpt. Omit database passwords, private player data, and full database dumps from public issues.

## Building, testing, and contributing

### Local build

```bash
mvn clean verify
```

The result is `target/Civilizations-1.0.0.jar`. Java 25 and Maven are required. Spigot is a provided dependency; HikariCP, MySQL Connector/J, Gson, and the logging bridge are included through the shaded build. Do not copy those dependency JARs individually into the game server's plugins directory.

For a local package without tests:

```bash
mvn -DskipTests package
```

Use the full verification build before releasing. Normal tests run without a live Minecraft server. Database integration tests are opt-in and are skipped unless enabled. Automated tests do not replace in-game checks of actual world generation, plugin integrations, GUIs, or performance.

### Optional MySQL integration tests

Use a **dedicated disposable test schema**, never a production database. The migration test applies real migrations; the tax integration tests exercise billing and recovery against MySQL.

The test suite reads these Java system properties:

| Property | Meaning |
| --- | --- |
| `civilizations.mysql.it` | Set to `true` to enable MySQL integration tests |
| `civilizations.mysql.url` | JDBC URL for the isolated test schema |
| `civilizations.mysql.user` | Test database user; defaults to `root` if omitted |
| `civilizations.mysql.password` | Test database password; defaults to empty if omitted |

To keep the password out of the command itself, create **`.mvn/mysql-it.local.properties`** (ignored by this repository):

```properties
civilizations.mysql.it=true
civilizations.mysql.url=jdbc:mysql://127.0.0.1:3306/civilizations_test
civilizations.mysql.user=civilizations_test
civilizations.mysql.password=replace-with-test-password
```

Pass it to [Surefire's system-properties file option](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html#systemPropertiesFile):

```bash
mvn -Dsurefire.systemPropertiesFile=.mvn/mysql-it.local.properties verify
```

Keep this file private. Java properties-file escaping applies to backslashes and special characters in its values. Test reports can include system properties, so treat `target/surefire-reports/` as private when running with database credentials.

These credentials belong only to the test environment. The plugin's live `config.yml` is separate; runtime configuration does not automatically read these test properties or a `.env` file.

### Repository layout

```text
pom.xml                         Java target, dependencies, test/build configuration
README.md                       Hosting guide and player wiki
CHANGELOG.md                    Release history
docs/OPERATIONS.md              Detailed administrator and recovery runbook
src/main/java/.../              Plugin code
  command/                     Commands and confirmation menus
  config/                      Settings, catalogs, text formatting
  domain/                      Civilization, claim, member, research, war models
  database/                    Storage, migrations, queries
  cache/                       Read snapshots
  service/                     Lifecycle, territory, progression, economy, religion, war
  listener/                    Gameplay events, protection, menus
  runtime/                     Travel, chat, scheduled activity/tax work
  integration/                 Optional plugin adapters
src/main/resources/            Default YAML catalogs and database migration SQL
src/test/java/.../             Unit and opt-in integration tests
target/                        Generated classes, test reports, release JAR (ignored)
```

### Git hygiene and contribution notes

The supplied [`.gitignore`](.gitignore) excludes Maven output, IDE/OS files, local Minecraft server directories, logs, crash dumps, backup files, local credentials, and private Maven test settings. Keep local servers under `server/`, `test-server/`, `dev-server/`, or `run/`, or outside the checkout. Add a local rule if using a different runtime directory.

Default YAML files and migration SQL **remain trackable**. There is no blanket `*.yml`, `*.sql`, or `*.jar` ignore rule; a Maven wrapper JAR could be committed if a wrapper is added later. Prefer attaching built plugin JARs to a release rather than committing generated `target/` contents.

Ignore rules do not remove files that Git already tracks and do not erase secrets from history. Check the staged file list before a public push. Sanitized `.env.example` / `.env.sample` files are allowed, but this plugin does not itself load them as runtime configuration.

For code changes, add focused tests for meaningful behavior, preserve the distinction between civilization sovereignty and private ownership, and keep database mutations transactional. Add a new ordered migration for a schema change; never rewrite one already deployed. Update the command/catalog documentation and changelog when player-visible behavior changes.

No license file is currently included in this repository; this README does not assign a new software license.

## Project scope and glossary

This version does not implement alliances/federations, a diplomacy graph, general civilization upkeep, territory decay, automatic leadership succession, automatic CoreProtect rollback, or shared multi-server operation. Weekly **private-plot tax** is implemented and is distinct from general civilization upkeep. There is no player command to withdraw civic stockpile items.

| Term | Meaning |
| --- | --- |
| Civilization / civ | The persistent community with a leader, members, territory, research, and shared balances |
| Claim | A chunk under a civilization's sovereignty |
| Capital | The civilization's anchor claim; government-owned and non-conquerable |
| Civic / common plot | Government-controlled land / land shared with all citizens |
| Private tenure | A citizen's ownership rights inside a civilization claim |
| Stockpile | Permanent numerical balances of deposited civic resources |
| Knowledge | Shared research currency earned from work orders |
| Treasury | Civilization money, separate from the stockpile and personal player balances |
| Established member | A member meeting membership-age and recent-activity requirements |
| Capability | An action unlocked by technology, such as using a tool or entering a dimension |
| Campaign | A scheduled war with a fixed fighting and looting window, roster, and truce |
| Fail-closed protection | Unknown territory is protected while authoritative state is unavailable |

For exact defaults, use the linked source catalogs. For incident recovery, use [the operations guide](docs/OPERATIONS.md). For changes between versions, use [the changelog](CHANGELOG.md).
