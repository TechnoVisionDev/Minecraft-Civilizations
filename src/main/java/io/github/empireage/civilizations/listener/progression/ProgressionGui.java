package io.github.empireage.civilizations.listener.progression;

import io.github.empireage.civilizations.cache.StateCache;
import io.github.empireage.civilizations.config.ResourceCatalog;
import io.github.empireage.civilizations.config.ResourceText;
import io.github.empireage.civilizations.config.TechnologyCatalog;
import io.github.empireage.civilizations.config.WorkOrderCatalog;
import io.github.empireage.civilizations.domain.Member;
import io.github.empireage.civilizations.domain.ResearchEntry;
import io.github.empireage.civilizations.domain.ResourceKey;
import io.github.empireage.civilizations.domain.TechnologyDefinition;
import io.github.empireage.civilizations.service.progression.CivicItemService;
import io.github.empireage.civilizations.service.progression.ResearchService;
import io.github.empireage.civilizations.service.progression.WorkOrderService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Inventory-based player interface for technologies, stockpiles, supply orders, and civic item recipes. */
public final class ProgressionGui implements Listener {
    private static final Map<String, Material> TECHNOLOGY_ICONS = Map.ofEntries(
        Map.entry("agriculture", Material.WHEAT),
        Map.entry("animal_husbandry", Material.LEAD),
        Map.entry("seafaring", Material.OAK_BOAT),
        Map.entry("copperworking", Material.COPPER_PICKAXE),
        Map.entry("archery", Material.BOW),
        Map.entry("civic_planning", Material.BRICKS),
        Map.entry("horseback_riding", Material.SADDLE),
        Map.entry("ironworking", Material.IRON_CHESTPLATE),
        Map.entry("scholarship", Material.BOOKSHELF),
        Map.entry("navigation", Material.COMPASS),
        Map.entry("fletching", Material.CROSSBOW),
        Map.entry("mechanical_transport", Material.HOPPER_MINECART),
        Map.entry("engineering", Material.PISTON),
        Map.entry("fortification", Material.STONE_BRICKS),
        Map.entry("administration_ii", Material.LECTERN),
        Map.entry("redstone_engineering", Material.REPEATER),
        Map.entry("nether_expedition", Material.OBSIDIAN),
        Map.entry("diamondworking", Material.DIAMOND_PICKAXE),
        Map.entry("enchanting", Material.ENCHANTING_TABLE),
        Map.entry("alchemy", Material.BREWING_STAND),
        Map.entry("administration_iii", Material.BELL),
        Map.entry("advanced_alchemy", Material.SPLASH_POTION),
        Map.entry("end_expedition", Material.ENDER_EYE),
        Map.entry("aeronautics", Material.ELYTRA),
        Map.entry("netherite_smithing", Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
        Map.entry("imperial_administration", Material.BEACON)
    );
    private static final List<AgeSection> AGE_SECTIONS = List.of(
        new AgeSection("SETTLEMENT", "Settlement Age", Material.COPPER_INGOT, 10),
        new AgeSection("IRON_AGE", "Iron Age", Material.IRON_INGOT, 12),
        new AgeSection("HIGH_AGE", "High Age", Material.DIAMOND, 14),
        new AgeSection("IMPERIAL", "Imperial Age", Material.NETHERITE_INGOT, 16)
    );
    private static final int[] CONTENT_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int[] RECIPE_GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};

    private final JavaPlugin plugin;
    private final StateCache cache;
    private final TechnologyCatalog technologies;
    private final ResourceCatalog resources;
    private final CivicItemService civicItems;
    private final ResearchService research;
    private final WorkOrderService workOrders;

    public ProgressionGui(JavaPlugin plugin, StateCache cache, TechnologyCatalog technologies,
                          ResourceCatalog resources, CivicItemService civicItems,
                          ResearchService research, WorkOrderService workOrders) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.technologies = Objects.requireNonNull(technologies, "technologies");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.civicItems = Objects.requireNonNull(civicItems, "civicItems");
        this.research = Objects.requireNonNull(research, "research");
        this.workOrders = Objects.requireNonNull(workOrders, "workOrders");
    }

    public void openTechnologyTree(Player player) {
        Member member = membership(player);
        if (member == null) return;
        Set<String> unlocked = cache.snapshot().technologies(member.civilizationId());
        Set<String> active = cache.snapshot().research(member.civilizationId()).stream()
            .map(ResearchEntry::technologyKey).collect(java.util.stream.Collectors.toSet());
        MenuHolder holder = new MenuHolder(MenuType.TECH_AGES, member.civilizationId());
        Inventory menu = menu(holder, 27, "Technology Tree — Ages");
        var civilization = cache.snapshot().civilization(member.civilizationId());
        menu.setItem(4, item(Material.KNOWLEDGE_BOOK, "&6Civilization Research", List.of(
            "&7Current age: &f" + technologies.age(unlocked),
            "&7Knowledge available: &f" + (civilization == null ? 0 : civilization.knowledge()),
            "&7Choose an age to view its technologies.",
            "&8Red means no technology is available yet.")));
        for (AgeSection age : AGE_SECTIONS) {
            List<TechnologyDefinition> entries = technologiesForAge(age.key());
            long complete = entries.stream().filter(technology -> unlocked.contains(technology.key())).count();
            long researching = entries.stream().filter(technology -> active.contains(technology.key())).count();
            long available = entries.stream().filter(technology -> !unlocked.contains(technology.key())
                && !active.contains(technology.key()) && unlocked.containsAll(technology.prerequisites())).count();
            boolean finished = complete == entries.size() && !entries.isEmpty();
            Material icon = finished ? Material.LIME_CONCRETE : available > 0 ? age.icon() : Material.RED_CONCRETE;
            String status = finished ? "&aAll technologies unlocked"
                : available > 0 ? "&e" + available + " available to research"
                : researching > 0 ? "&cNo new technologies available while research is in progress"
                : "&cNo technologies available yet";
            menu.setItem(age.slot(), item(icon, (finished ? "&a" : available > 0 ? "&6" : "&c")
                + age.name(), List.of(status, "", "&7Unlocked: &f" + complete + " / " + entries.size(),
                "&7Researching: &f" + researching, "&7Available now: &f" + available, "&eClick to view this age.")));
            holder.actions.put(age.slot(), age.key());
        }
        player.openInventory(menu);
    }

    private void openTechnologyAge(Player player, String era) {
        Member member = membership(player);
        if (member == null) return;
        AgeSection age = ageSection(era);
        Set<String> unlocked = cache.snapshot().technologies(member.civilizationId());
        Set<String> active = cache.snapshot().research(member.civilizationId()).stream()
            .map(ResearchEntry::technologyKey).collect(java.util.stream.Collectors.toSet());
        MenuHolder holder = new MenuHolder(MenuType.TECHNOLOGIES, member.civilizationId(), age.key());
        Inventory menu = menu(holder, 45, "Technology Tree — " + age.name());
        var civilization = cache.snapshot().civilization(member.civilizationId());
        menu.setItem(4, item(Material.KNOWLEDGE_BOOK, "&6" + age.name(), List.of(
            "&7Knowledge available: &f" + (civilization == null ? 0 : civilization.knowledge()),
            "&7Available technologies can be selected here.",
            "&8Locked technologies show their missing prerequisites.")));
        List<TechnologyDefinition> sorted = technologiesForAge(age.key());
        for (int index = 0; index < sorted.size() && index < CONTENT_SLOTS.length; index++) {
            TechnologyDefinition technology = sorted.get(index);
            boolean done = unlocked.contains(technology.key());
            boolean researching = active.contains(technology.key());
            boolean available = !done && !researching && unlocked.containsAll(technology.prerequisites());
            Material icon = technologyIcon(technology.key());
            List<String> lore = new ArrayList<>();
            lore.add(done ? "&aUnlocked" : researching ? "&eResearching" : available ? "&6Available" : "&cPrerequisites missing");
            lore.add("");
            lore.add("&7Knowledge: &f" + technology.knowledgeCost());
            lore.add("&7Time: &f" + duration(technology.duration()));
            lore.add("&7Materials:");
            if (technology.materialCosts().isEmpty()) lore.add("&8• &fNone");
            else technology.materialCosts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lore.add("&8• &f" + entry.getValue() + " " + resourceName(resources, entry.getKey())));
            lore.add("&7Requires: &f" + prerequisiteNames(technology));
            lore.add("&7Unlocks:");
            technology.capabilities().stream().sorted().forEach(capability -> lore.add("&8• &f" + title(capability)));
            if (available) lore.add("&eClick to select as active research.");
            int slot = CONTENT_SLOTS[index];
            menu.setItem(slot, item(icon, (done ? "&a" : researching ? "&e" : available ? "&6" : "&7")
                + technology.name(), lore));
            if (available) holder.actions.put(slot, technology.key());
        }
        menu.setItem(40, item(Material.ARROW, "&eBack to ages", List.of("&7Return to the age overview.")));
        holder.actions.put(40, "back");
        player.openInventory(menu);
    }

    public void openWorkOrders(Player player) {
        Member member = membership(player);
        if (member == null) return;
        workOrders.listForPlayer(member.civilizationId(), player.getUniqueId(), Instant.now()).thenAccept(orders -> onMain(() -> {
            if (!player.isOnline()) return;
            MenuHolder holder = new MenuHolder(MenuType.WORK_ORDERS, member.civilizationId());
            Inventory menu = menu(holder, 36, "Civilization Work Orders");
            menu.setItem(4, item(Material.WRITABLE_BOOK, "&6Persistent Supply Orders", List.of(
                "&7Progress is your civilization stockpile balance.",
                "&7Everyone may deposit; leaders and advisors complete orders.",
                "&8Completion permanently spends the listed stockpile resources.")));
            int[] slots = {20, 22, 24};
            for (int index = 0; index < orders.size() && index < slots.length; index++) {
                WorkOrderService.WorkOrderView order = orders.get(index);
                Material icon = order.civicResource() == null ? order.materials().stream().findFirst().orElse(Material.CHEST)
                    : resources.require(order.civicResource()).material();
                List<String> lore = new ArrayList<>();
                lore.add("&8" + title(order.category().name()) + " Supply");
                lore.add("");
                lore.add("&7Required: &f" + targetName(order));
                lore.add("&7Stockpile: &f" + order.stockpileBalance() + " &8(required " + order.target() + ")");
                lore.add(progressBar(order.progress(), order.target()));
                lore.add("&7Reward: &b" + order.reward() + " Knowledge");
                if (order.cooldownUntil() != null && Instant.now().isBefore(order.cooldownUntil())) {
                    lore.add("&cCategory cooldown: " + duration(Duration.between(Instant.now(), order.cooldownUntil())));
                } else if (order.ready()) lore.add("&aReady — leader/advisor may click to complete.");
                else lore.add("&eDeposit " + order.remaining() + " more into the civilization stockpile.");
                int slot = slots[index];
                menu.setItem(slot, item(icon, categoryColor(order.category()) + order.name(), lore));
                holder.actions.put(slot, Long.toString(order.id()));
            }
            player.openInventory(menu);
        })).exceptionally(error -> {
            onMain(() -> player.sendMessage(color("&cCould not load work orders: " + rootMessage(error))));
            return null;
        });
    }

    public void openCustomItems(Player player) {
        MenuHolder holder = new MenuHolder(MenuType.ITEM_TIERS, -1);
        Inventory menu = menu(holder, 27, "Civic Items by Tier");
        int[] slots = {11, 13, 15};
        Material[] icons = {Material.COPPER_INGOT, Material.IRON_INGOT, Material.GOLD_INGOT};
        for (int tier = 1; tier <= 3; tier++) {
            int currentTier = tier;
            List<ResourceCatalog.Tier> entries = resources.tiers().values().stream()
                .filter(value -> value.key().tier() == currentTier).toList();
            menu.setItem(slots[tier - 1], item(icons[tier - 1], ResourceText.tierColor(tier) + "Tier " + tier, List.of(
                "&7" + entries.size() + " custom civic items", "&eClick to browse recipes.")));
            holder.actions.put(slots[tier - 1], Integer.toString(tier));
        }
        player.openInventory(menu);
    }

    public void openStockpile(Player player, long civilizationId, Map<ResourceKey, Long> balances) {
        openStockpile(player, civilizationId, Map.copyOf(balances), 0);
    }

    private void openStockpile(Player player, long civilizationId, Map<ResourceKey, Long> balances, int page) {
        if (!player.isOnline()) return;
        Member member = membership(player);
        if (member == null || member.civilizationId() != civilizationId) return;
        List<String> families = resources.tiers().keySet().stream().map(ResourceKey::family).distinct().sorted().toList();
        int pages = Math.max(1, (families.size() + 6) / 7);
        int currentPage = Math.max(0, Math.min(page, pages - 1));
        MenuHolder holder = new MenuHolder(MenuType.STOCKPILE, civilizationId, Integer.toString(currentPage));
        holder.balances = balances;
        Inventory menu = menu(holder, 54, "Civilization Stockpile");
        menu.setItem(4, item(Material.CHEST, "&6Civilization Stockpile", List.of(
            "&7Each column is a resource family.",
            "&aTier 1 &8• &bTier 2 &8• &dTier 3",
            "&7Stack counts show up to 64; hover for exact totals.",
            "&7Empty resources remain visible with a zero balance.",
            "&7Page " + (currentPage + 1) + " / " + pages)));
        Material[] tierIcons = {Material.LIME_STAINED_GLASS_PANE, Material.CYAN_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE};
        for (int tier = 1; tier <= 3; tier++) {
            menu.setItem(tier * 9, item(tierIcons[tier - 1], ResourceText.tierColor(tier) + "Tier " + tier, List.of()));
        }
        int end = Math.min(families.size(), (currentPage + 1) * 7);
        for (int index = currentPage * 7; index < end; index++) {
            for (int tier = 1; tier <= 3; tier++) {
                ResourceKey key = new ResourceKey(families.get(index), tier);
                if (!resources.tiers().containsKey(key)) continue;
                int slot = tier * 9 + 1 + index % 7;
                menu.setItem(slot, stockpileItem(key, balances.getOrDefault(key, 0L)));
            }
        }
        menu.setItem(48, item(Material.BOOK, "&eStockpile Information", List.of(
            "&7Deposit with /civ deposit hand or /civ deposit all.",
            "&7View contributions with /civ stockpile history.",
            "&7Browse crafting recipes with /civ items.",
            "&8Resources are committed to civilization projects.",
            "&8They cannot be withdrawn from this menu.")));
        menu.setItem(50, item(Material.SUNFLOWER, "&eRefresh balances", List.of("&7Click to load the latest stockpile totals.")));
        holder.actions.put(50, "refresh");
        if (currentPage > 0) {
            menu.setItem(45, item(Material.ARROW, "&ePrevious page", List.of()));
            holder.actions.put(45, "previous");
        }
        if (currentPage + 1 < pages) {
            menu.setItem(53, item(Material.ARROW, "&eNext page", List.of()));
            holder.actions.put(53, "next");
        }
        player.openInventory(menu);
    }

    private ItemStack stockpileItem(ResourceKey key, long quantity) {
        ItemStack display = civicItems.create(key, (int) Math.max(1L, Math.min(64L, quantity)));
        ItemMeta meta = display.getItemMeta();
        meta.setMaxStackSize(64);
        List<String> lore = new ArrayList<>();
        lore.add("&7In stockpile: &f" + String.format(java.util.Locale.US, "%,d", quantity));
        if (quantity == 0) lore.add("&cNone in stockpile — display only.");
        else if (quantity > 64) lore.add("&8Icon count capped at 64; total shown above.");
        lore.add("");
        lore.add("&7Family: &f" + title(key.family()));
        lore.add("&7Tier: " + ResourceText.tierColor(key.tier()) + key.tier());
        lore.add("&7Craft one from:");
        if (key.tier() > 1) {
            lore.add("&8• &f" + resources.higherTierRatio() + " "
                + ResourceText.itemName(resources, new ResourceKey(key.family(), key.tier() - 1)));
        } else {
            ResourceCatalog.BaseRecipe recipe = resources.baseRecipes().get(key.family());
            if (recipe == null) lore.add("&8No base recipe configured.");
            else if (!recipe.acceptedAlternatives().isEmpty()) lore.add("&8• &f9 accepted logs (any mix)");
            else recipe.ingredients().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lore.add("&8• &f" + entry.getValue() + " " + title(entry.getKey().name())));
        }
        lore.add("");
        lore.add("&8Shared civilization resource; cannot be withdrawn.");
        meta.setLore(colors(lore));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ATTRIBUTES);
        display.setItemMeta(meta);
        return display;
    }

    private void openItemTier(Player player, int tier) {
        MenuHolder holder = new MenuHolder(MenuType.ITEM_LIST, tier);
        Inventory menu = menu(holder, 36, "Civic Items — Tier " + tier);
        List<ResourceCatalog.Tier> entries = resources.tiers().values().stream()
            .filter(value -> value.key().tier() == tier).sorted(Comparator.comparing(value -> value.key().family())).toList();
        for (int index = 0; index < entries.size() && index < CONTENT_SLOTS.length; index++) {
            ResourceCatalog.Tier definition = entries.get(index);
            int slot = CONTENT_SLOTS[index];
            ItemStack display = civicItems.create(definition.key(), 1);
            ItemMeta meta = display.getItemMeta();
            meta.setLore(colors(List.of("&7Family: &f" + title(definition.key().family()), "&7Tier: &f" + tier,
                "&eClick to view the crafting recipe.")));
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            display.setItemMeta(meta);
            menu.setItem(slot, display);
            holder.actions.put(slot, definition.key().serialized());
        }
        menu.setItem(31, item(Material.ARROW, "&eBack to tiers", List.of()));
        holder.actions.put(31, "back");
        player.openInventory(menu);
    }

    private void openRecipe(Player player, ResourceKey key) {
        ResourceCatalog.Tier definition = resources.require(key);
        MenuHolder holder = new MenuHolder(MenuType.ITEM_RECIPE, key.tier());
        Inventory menu = menu(holder, 45, "Recipe — " + ChatColor.stripColor(color(definition.displayName())));
        List<ItemStack> ingredients = recipeIngredients(key);
        for (int index = 0; index < ingredients.size() && index < RECIPE_GRID.length; index++) {
            menu.setItem(RECIPE_GRID[index], ingredients.get(index));
        }
        menu.setItem(23, item(Material.CRAFTING_TABLE, "&7Craft", List.of("&7Shapeless recipe")));
        menu.setItem(25, civicItems.create(key, 1));
        menu.setItem(40, item(Material.ARROW, "&eBack to Tier " + key.tier(), List.of()));
        holder.actions.put(40, "back");
        player.openInventory(menu);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
            || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        String action = holder.actions.get(event.getRawSlot());
        if (action == null) return;
        switch (holder.type) {
            case STOCKPILE -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                if (action.equals("refresh")) player.performCommand("civ stockpile");
                else openStockpile(player, holder.civilizationId, holder.balances,
                    Integer.parseInt(holder.context) + (action.equals("next") ? 1 : -1));
            });
            case TECH_AGES -> openTechnologyAge(player, action);
            case TECHNOLOGIES -> {
                if (action.equals("back")) openTechnologyTree(player);
                else startResearch(player, holder.civilizationId, action, holder.context);
            }
            case WORK_ORDERS -> submitOrder(player, Long.parseLong(action));
            case ITEM_TIERS -> openItemTier(player, Integer.parseInt(action));
            case ITEM_LIST -> {
                if (action.equals("back")) openCustomItems(player); else openRecipe(player, ResourceKey.parse(action));
            }
            case ITEM_RECIPE -> openItemTier(player, (int) holder.civilizationId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    private void startResearch(Player player, long civilizationId, String technology, String era) {
        player.closeInventory();
        research.start(civilizationId, player.getUniqueId(), technology).thenAccept(result -> onMain(() -> {
            player.sendMessage(color(result.success() ? "&a" + result.message() : "&c" + result.message()));
            if (player.isOnline()) openTechnologyAge(player, era);
        })).exceptionally(error -> {
            onMain(() -> player.sendMessage(color("&cCould not start research: " + rootMessage(error))));
            return null;
        });
    }

    private void submitOrder(Player player, long orderId) {
        player.closeInventory();
        workOrders.complete(player.getUniqueId(), orderId, Instant.now()).thenAccept(result -> onMain(() -> {
            player.sendMessage(color(result.success() ? "&a" + result.message() : "&c" + result.message()));
            if (player.isOnline()) openWorkOrders(player);
        }));
    }

    private List<ItemStack> recipeIngredients(ResourceKey key) {
        List<ItemStack> ingredients = new ArrayList<>();
        if (key.tier() > 1) {
            ResourceKey lower = new ResourceKey(key.family(), key.tier() - 1);
            for (int count = 0; count < resources.higherTierRatio(); count++) ingredients.add(civicItems.create(lower, 1));
            return ingredients;
        }
        ResourceCatalog.BaseRecipe recipe = resources.baseRecipes().get(key.family());
        if (recipe == null) return ingredients;
        if (!recipe.acceptedAlternatives().isEmpty()) {
            List<Material> alternatives = recipe.acceptedAlternatives().stream().sorted().toList();
            for (int count = 0; count < 9; count++) {
                ItemStack stack = new ItemStack(alternatives.get(count % alternatives.size()));
                ItemMeta meta = stack.getItemMeta();
                meta.setLore(colors(List.of("&7Any accepted log")));
                stack.setItemMeta(meta);
                ingredients.add(stack);
            }
            return ingredients;
        }
        recipe.ingredients().forEach((material, amount) -> {
            for (int count = 0; count < amount; count++) ingredients.add(new ItemStack(material));
        });
        return ingredients;
    }

    private Member membership(Player player) {
        Member member = cache.snapshot().member(player.getUniqueId());
        if (member == null) player.sendMessage(color("&cYou do not belong to a civilization."));
        return member;
    }

    private Inventory menu(MenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < size; slot++) inventory.setItem(slot, filler);
        return inventory;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(colors(lore));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String targetName(WorkOrderService.WorkOrderView order) {
        if (order.civicResource() != null) return ChatColor.stripColor(color(resources.require(order.civicResource()).displayName()));
        if (order.materials().size() > 1) return "Any accepted log";
        return title(order.materials().iterator().next().name());
    }

    private List<TechnologyDefinition> technologiesForAge(String era) {
        return technologies.technologies().values().stream().filter(technology -> technology.era().equalsIgnoreCase(era))
            .sorted(Comparator.comparingLong(TechnologyDefinition::knowledgeCost).thenComparing(TechnologyDefinition::key))
            .toList();
    }

    private AgeSection ageSection(String era) {
        return AGE_SECTIONS.stream().filter(section -> section.key().equalsIgnoreCase(era)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown technology age " + era));
    }

    private String prerequisiteNames(TechnologyDefinition technology) {
        if (technology.prerequisites().isEmpty()) return "None";
        return technology.prerequisites().stream().map(key -> {
            TechnologyDefinition prerequisite = technologies.get(key);
            return prerequisite == null ? title(key) : prerequisite.name();
        }).collect(java.util.stream.Collectors.joining(", "));
    }

    static String resourceName(ResourceCatalog catalog, ResourceKey key) {
        ResourceCatalog.Tier tier = catalog.tiers().get(key);
        return tier == null ? title(key.serialized()) : ChatColor.stripColor(color(tier.displayName()));
    }

    static Material technologyIcon(String technologyKey) {
        return TECHNOLOGY_ICONS.getOrDefault(technologyKey.toLowerCase(java.util.Locale.ROOT), Material.KNOWLEDGE_BOOK);
    }

    private static String progressBar(long progress, long target) {
        int complete = target <= 0 ? 20 : (int) Math.min(20, progress * 20 / target);
        return "&a" + "|".repeat(complete) + "&8" + "|".repeat(20 - complete);
    }

    private static String categoryColor(WorkOrderCatalog.Category category) {
        return switch (category) {
            case BASIC -> "&a";
            case INDUSTRIAL -> "&6";
            case STRATEGIC -> "&d";
        };
    }

    private static String duration(Duration value) {
        long seconds = Math.max(0, value.toSeconds());
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        if (hours > 0) return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        return minutes > 0 ? minutes + "m" : seconds + "s";
    }

    private static String title(String input) {
        String[] words = input.toLowerCase().replace('_', ' ').split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(plugin, action);
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private static List<String> colors(List<String> values) {
        return values.stream().map(ProgressionGui::color).toList();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private enum MenuType { TECH_AGES, TECHNOLOGIES, WORK_ORDERS, STOCKPILE, ITEM_TIERS, ITEM_LIST, ITEM_RECIPE }

    private record AgeSection(String key, String name, Material icon, int slot) {}

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final long civilizationId;
        private final String context;
        private final Map<Integer, String> actions = new LinkedHashMap<>();
        private Map<ResourceKey, Long> balances = Map.of();
        private Inventory inventory;

        private MenuHolder(MenuType type, long civilizationId) {
            this(type, civilizationId, null);
        }

        private MenuHolder(MenuType type, long civilizationId, String context) {
            this.type = type;
            this.civilizationId = civilizationId;
            this.context = context;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
