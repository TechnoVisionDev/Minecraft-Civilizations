package io.github.empireage.civilizations.runtime;

import io.github.empireage.civilizations.config.Messages;
import io.github.empireage.civilizations.domain.OperationResult;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Main-thread tutorial delivery; inventory and cooldown are saved together in player data. */
public final class TutorialBookService implements Listener {
    public static final Duration COOLDOWN = Duration.ofHours(24);
    private final NamespacedKey nextUseKey;
    private final NamespacedKey initialPendingKey;
    private final Clock clock;

    public TutorialBookService(JavaPlugin plugin) {
        this(plugin, Clock.systemUTC());
    }

    TutorialBookService(JavaPlugin plugin, Clock clock) {
        this.clock = clock;
        nextUseKey = new NamespacedKey(plugin, "tutorial-next-use");
        initialPendingKey = new NamespacedKey(plugin, "tutorial-initial-pending");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var data = player.getPersistentDataContainer();
        if (data.get(nextUseKey, PersistentDataType.LONG) != null) return;
        if (player.hasPlayedBefore() && data.get(initialPendingKey, PersistentDataType.BYTE) == null) return;
        data.set(initialPendingKey, PersistentDataType.BYTE, (byte) 1);
        OperationResult result = give(player);
        if (!result.success()) player.saveData(); // Retry an undelivered welcome book on the next join.
        player.sendMessage(Messages.color("&8[&6Civilizations&8] "
            + (result.success() ? "&eWelcome! " : "&c") + result.message()));
    }

    public OperationResult give(Player player) {
        if (player.isDead()) return OperationResult.denied("Respawn before collecting your tutorial book with /civ tutorial.");
        long now = clock.millis();
        var data = player.getPersistentDataContainer();
        Long nextUse = data.get(nextUseKey, PersistentDataType.LONG);
        if (nextUse != null && nextUse > now) {
            long seconds = (nextUse - now + 999) / 1000;
            return OperationResult.denied("You can get another tutorial book in " + seconds / 3600 + "h "
                + (seconds % 3600) / 60 + "m " + seconds % 60 + "s.");
        }
        // A single book either fits in full or remains entirely in the returned overflow.
        if (!player.getInventory().addItem(createBook()).isEmpty()) {
            return OperationResult.denied("Make room in your inventory, then use /civ tutorial to collect your book."
                + " No cooldown was started.");
        }
        data.set(nextUseKey, PersistentDataType.LONG, now + COOLDOWN.toMillis());
        data.remove(initialPendingKey);
        player.saveData();
        return OperationResult.ok("Your tutorial book is in your inventory. Open it to get started!"
            + " Use /civ tutorial for another copy in 24 hours.");
    }

    public ItemStack createBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Civilizations Tutorial");
        meta.setAuthor("Civilizations");
        meta.setGeneration(BookMeta.Generation.ORIGINAL);
        meta.setPages(pages());
        book.setItemMeta(meta);
        return book;
    }

    private static List<String> pages() {
        return List.of(
            page("Welcome!", """
                Build a civilization,
                settle land, and grow
                with your community.

                First, gather supplies,
                join or found a civ,
                then build together.

                Turn the pages for
                your first steps!"""),
            page("1. Find your place", """
                New players start in
                Overworld wilderness.

                /wild
                Find a new safe spot.
                Has a 12h cooldown.

                /civ map
                See nearby claims.
                /civ inspect
                Check land access."""),
            page("2. Join a civ", """
                /civ list
                Browse civilizations.
                /civ info <name>
                Learn about one.

                Ask a civ leader for
                an invite, then use:
                /civ accept <name>

                /civ members
                See your members."""),
            page("3. Found a civ", """
                /civ create <name>
                Previews the material
                and money costs.

                Confirm in the menu.
                This chunk becomes
                your civ's capital.

                /civ invite <player>
                Invite your friends.
                Check costs first!"""),
            page("4. Chat & travel", """
                /chat global
                Talk to the server.
                /chat local
                Talk to nearby people.
                /chat civ
                Talk to your civ.

                /civ home
                Go to the civ home.
                /nether and /end
                Need expedition tech."""),
            page("5. Craft resources", """
                /civ items
                Browse custom items.
                Click to see recipes.

                /civ refine
                Turn valid materials
                into civic resources.

                Resource tiers:
                Green: 1, cyan: 2,
                purple: 3."""),
            page("6. Share supplies", """
                /civ deposit hand
                Store held civic items.
                /civ deposit all
                Store all civic items.

                /civ stockpile
                View shared supplies.

                You cannot withdraw.
                Keep some supplies
                before depositing."""),
            page("7. Earn Knowledge", """
                /civ workorders
                See community orders
                and missing supplies.

                Everyone can donate
                stockpile supplies.

                Leaders and advisors
                finish ready orders,
                spending supplies to
                earn Knowledge."""),
            page("8. Research", """
                /civ tech
                Browse the tech tree.
                Read requirements
                and tech unlocks.

                Leaders and advisors
                start research using
                shared Knowledge.

                /civ research status
                Check your progress."""),
            page("9. Claim land", """
                /civ claim
                Leaders and advisors
                claim adjacent chunks.
                Read costs and limits
                before confirming.

                /civ inspect
                Check this plot.

                Respect land access;
                ask before building."""),
            page("10. Home plots", """
                Stand in a listed plot
                /civ plot buy

                Check price and tax
                before you confirm.
                /civ taxes
                Check your plot taxes.

                Keep money for taxes:
                unpaid plots can be
                reclaimed by the civ."""),
            page("11. Trade & fund", """
                /sell list
                See what you can sell.
                /sell hand
                Sell held resources.

                /civ treasury balance
                View shared money.

                /civ treasury deposit
                Add an amount after it
                to donate currency."""),
            page("12. The gods", """
                /religion
                Meet the Greek gods,
                gifts and blessings.

                Hold an offering:
                /sacrifice <god>

                Gifts are consumed.
                Acceptance is random;
                read the menu's rules
                and cooldowns first."""),
            page("13. War", """
                /civ war status
                Check the opponent,
                roster and timing.

                War uses scheduled
                combat windows. During
                war, participants can
                fight, break and loot
                under server rules.

                Follow your leader!"""),
            page("Keep this guide", """
                /civ help
                Read command help.
                /civ help resources
                Jump to a help topic.

                /civ tutorial
                Get another copy.
                One copy every 24h,
                starting with this one.

                Enjoy
                Civilizations!""")
        );
    }

    private static String page(String title, String body) {
        return ChatColor.GOLD.toString() + ChatColor.BOLD + title + "\n\n" + ChatColor.RESET + body;
    }
}
