package io.github.empireage.civilizations.listener.progression;

import io.github.empireage.civilizations.domain.TechnologyMode;
import io.github.empireage.civilizations.service.progression.CapabilityPolicy;
import io.github.empireage.civilizations.service.progression.TechnologyAccess;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Firework;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Mule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.projectiles.ProjectileSource;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Enforces STRICT, CRAFT_ONLY, and DISABLED technology modes at production and use boundaries. */
public final class TechnologyGateListener implements Listener {
    private static final String BYPASS_PERMISSION = "civilizations.bypass.technology";
    private static final long MESSAGE_COOLDOWN_MS = 1500;
    private static final long BREW_AUTHORIZATION_MS = Duration.ofMinutes(10).toMillis();

    private final TechnologyAccess access;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();
    private final Map<BlockKey, BrewAuthorization> brewAuthorizations = new ConcurrentHashMap<>();

    public TechnologyGateListener(TechnologyAccess access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Player player = event.getViewers().stream().filter(Player.class::isInstance).map(Player.class::cast).findFirst().orElse(null);
        ItemStack result = event.getInventory().getResult();
        if (player != null && !allowsProduction(player, CapabilityPolicy.requirementsForProduction(result))) {
            event.getInventory().setResult(null);
            deny(player, CapabilityPolicy.requirementsForProduction(result));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Set<String> requirements = CapabilityPolicy.requirementsForProduction(event.getRecipe().getResult());
        if (!allowsProduction(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareSmith(PrepareSmithingEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        Set<String> requirements = CapabilityPolicy.requirementsForProduction(event.getResult());
        if (!allowsProduction(player, requirements)) {
            event.setResult(null);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Set<String> requirements = CapabilityPolicy.requirementsForProduction(event.getInventory().getResult());
        if (!allowsProduction(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof AbstractHorse horse && isEquine(horse)
            && !allowsUse(player, Set.of("RIDE_EQUINES"))) {
            event.setCancelled(true);
            deny(player, Set.of("RIDE_EQUINES"));
            return;
        }
        if (event.getInventory().getHolder() instanceof Entity entity) {
            Set<String> vehicle = CapabilityPolicy.requirementsForVehicle(entity.getType().name());
            if (!allowsUse(player, vehicle)) {
                event.setCancelled(true);
                deny(player, vehicle);
                return;
            }
        }
        Set<String> requirements = CapabilityPolicy.requirementsForStation(event.getInventory().getType());
        if (!allowsProduction(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
            return;
        }
        if (event.getInventory() instanceof BrewerInventory brewing) authorizeBrewing(brewing, player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || access.mode() != TechnologyMode.STRICT) return;
        ItemStack candidate = equipmentCandidate(event, player);
        Set<String> requirements = CapabilityPolicy.requirementsForUse(candidate);
        if (!requirements.isEmpty() && !allowsUse(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || access.mode() != TechnologyMode.STRICT) return;
        if (!touchesEquipmentSlot(event)) return;
        Set<String> requirements = CapabilityPolicy.requirementsForUse(event.getOldCursor());
        if (!allowsUse(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getItem() != null && event.getItem().getType() == Material.BONE_MEAL
            && event.getClickedBlock() != null && isCrop(event.getClickedBlock().getType())
            && !allowsUse(event.getPlayer(), Set.of("USE_BONE_MEAL_ON_CROPS"))) {
            event.setCancelled(true);
            deny(event.getPlayer(), Set.of("USE_BONE_MEAL_ON_CROPS"));
            return;
        }
        Set<String> itemRequirements = CapabilityPolicy.requirementsForUse(event.getItem());
        if (!allowsUse(event.getPlayer(), itemRequirements)) {
            event.setCancelled(true);
            deny(event.getPlayer(), itemRequirements);
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Set<String> station = CapabilityPolicy.requirementsForStation(clicked.getType());
        if (!allowsProduction(event.getPlayer(), station)) {
            event.setCancelled(true);
            deny(event.getPlayer(), station);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Set<String> requirements = CapabilityPolicy.requirementsForUse(event.getItemInHand());
        if (!allowsUse(event.getPlayer(), requirements)) {
            event.setCancelled(true);
            deny(event.getPlayer(), requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) return;
        Set<String> requirement = isEquine(event.getEntity()) ? Set.of("BREED_EQUINES")
            : isLivestock(event.getEntity()) ? Set.of("BREED_LIVESTOCK") : Set.of();
        if (!allowsUse(player, requirement)) {
            event.setCancelled(true);
            deny(player, requirement);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player)) return;
        Set<String> requirement = event.getEntity() instanceof Horse ? Set.of("TAME_HORSES")
            : event.getEntity() instanceof Donkey ? Set.of("TAME_DONKEYS") : Set.of();
        if (!allowsUse(player, requirement)) {
            event.setCancelled(true);
            deny(player, requirement);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        Set<String> requirement = Set.of();
        if (held.getType() == Material.LEAD && isLivestock(event.getRightClicked())) {
            requirement = Set.of("USE_LEADS_ON_LIVESTOCK");
        } else if (isEquine(event.getRightClicked())) {
            requirement = held.getType() == Material.SADDLE || held.getType().name().endsWith("_HORSE_ARMOR")
                ? Set.of("EQUIP_EQUINES") : Set.of("RIDE_EQUINES");
        } else {
            requirement = CapabilityPolicy.requirementsForVehicle(event.getRightClicked().getType().name());
        }
        if (!allowsUse(event.getPlayer(), requirement)) {
            event.setCancelled(true);
            deny(event.getPlayer(), requirement);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player player)) return;
        Set<String> requirements = CapabilityPolicy.requirementsForVehicle(event.getVehicle().getType().name());
        if (!allowsUse(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Set<String> requirements = new java.util.LinkedHashSet<>();
        requirements.addAll(CapabilityPolicy.requirementsForUse(event.getMainHandItem()));
        requirements.addAll(CapabilityPolicy.requirementsForUse(event.getOffHandItem()));
        if (!allowsUse(event.getPlayer(), requirements)) {
            event.setCancelled(true);
            deny(event.getPlayer(), requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        Set<String> requirements = CapabilityPolicy.requirementsForUse(player.getInventory().getItemInMainHand());
        if (!allowsUse(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMine(BlockBreakEvent event) {
        Set<String> requirements = CapabilityPolicy.requirementsForUse(event.getPlayer().getInventory().getItemInMainHand());
        if (!allowsUse(event.getPlayer(), requirements)) {
            event.setCancelled(true);
            deny(event.getPlayer(), requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Set<String> requirements = CapabilityPolicy.requirementsForUse(event.getItem());
        if (!allowsUse(event.getPlayer(), requirements)) {
            event.setCancelled(true);
            deny(event.getPlayer(), requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Set<String> requirements = new java.util.LinkedHashSet<>(CapabilityPolicy.requirementsForUse(event.getBow()));
        requirements.addAll(CapabilityPolicy.requirementsForUse(event.getConsumable()));
        if (!allowsUse(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Firework)) return;
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player player)) return;
        if (!player.isGliding()) return;
        Set<String> requirement = Set.of(CapabilityPolicy.BOOST_ELYTRA_WITH_FIREWORKS);
        if (!allowsUse(player, requirement)) {
            event.setCancelled(true);
            deny(player, requirement);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player player)) return;
        ItemStack chestplate = player.getInventory().getChestplate();
        if (chestplate == null || chestplate.getType() != Material.ELYTRA) return;
        Set<String> requirement = Set.of(CapabilityPolicy.GLIDE_WITH_ELYTRA);
        if (!allowsUse(player, requirement)) {
            event.setCancelled(true);
            deny(player, requirement);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorDispense(BlockDispenseArmorEvent event) {
        if (!(event.getTargetEntity() instanceof Player player)) return;
        Set<String> requirements = CapabilityPolicy.requirementsForUse(event.getItem());
        if (!allowsUse(player, requirements)) {
            event.setCancelled(true);
            deny(player, requirements);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        Set<String> requirement = Set.of(CapabilityPolicy.ENCHANT_ITEMS);
        if (!allowsProduction(event.getEnchanter(), requirement)) {
            event.setCancelled(true);
            deny(event.getEnchanter(), requirement);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Set<String> requirement = Set.of(CapabilityPolicy.ENCHANT_ITEMS);
        if (!allowsProduction(event.getEnchanter(), requirement)) {
            event.setCancelled(true);
            deny(event.getEnchanter(), requirement);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (!TechnologyAccess.productionGated(access.mode())) return;
        BlockKey key = BlockKey.of(event.getBlock());
        BrewAuthorization authorization = brewAuthorizations.get(key);
        Player authorizer = authorization == null ? null : org.bukkit.Bukkit.getPlayer(authorization.playerId());
        if (authorization == null || System.currentTimeMillis() - authorization.atMillis() > BREW_AUTHORIZATION_MS
            || authorizer == null || !allowsProduction(authorizer, brewingRequirement(event))) {
            brewAuthorizations.remove(key);
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastMessage.remove(playerId);
        brewAuthorizations.entrySet().removeIf(entry -> entry.getValue().playerId().equals(playerId));
    }

    private boolean allowsProduction(Player player, Set<String> requirements) {
        return player.hasPermission(BYPASS_PERMISSION) || access.allowsProduction(player, requirements);
    }

    private boolean allowsUse(Player player, Set<String> requirements) {
        return player.hasPermission(BYPASS_PERMISSION) || access.allowsUse(player, requirements);
    }

    private ItemStack equipmentCandidate(InventoryClickEvent event, Player player) {
        boolean equipmentSlot = event.getSlotType() == InventoryType.SlotType.ARMOR
            || event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == 40;
        if (equipmentSlot) {
            if (event.getAction() == InventoryAction.HOTBAR_SWAP || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
                int button = event.getHotbarButton();
                return button >= 0 ? player.getInventory().getItem(button) : null;
            }
            return switch (event.getAction()) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
                default -> null;
            };
        }
        if (event.getView().getTopInventory().getType() == InventoryType.CRAFTING
            && event.getClickedInventory() instanceof PlayerInventory
            && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && isWearable(event.getCurrentItem())) {
            return event.getCurrentItem();
        }
        return null;
    }

    private boolean touchesEquipmentSlot(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getType() != InventoryType.CRAFTING) return false;
        return event.getRawSlots().stream().anyMatch(slot -> slot >= 5 && slot <= 8 || slot == 45);
    }

    private boolean isWearable(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        String name = item.getType().name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
            || name.endsWith("_BOOTS") || item.getType() == Material.ELYTRA;
    }

    private void authorizeBrewing(BrewerInventory inventory, Player player) {
        BrewingStand holder = inventory.getHolder();
        if (holder == null) return;
        brewAuthorizations.put(BlockKey.of(holder.getBlock()), new BrewAuthorization(player.getUniqueId(), System.currentTimeMillis()));
    }

    private Set<String> brewingRequirement(BrewEvent event) {
        ItemStack ingredient = event.getContents().getIngredient();
        if (ingredient != null && ingredient.getType() == Material.GUNPOWDER) return Set.of("CRAFT_SPLASH_POTIONS");
        if (ingredient != null && ingredient.getType() == Material.DRAGON_BREATH) return Set.of("CRAFT_LINGERING_POTIONS");
        return Set.of(CapabilityPolicy.BREW_DRINKABLE_POTIONS);
    }

    private static boolean isCrop(Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, SWEET_BERRY_BUSH,
                 MELON_STEM, PUMPKIN_STEM, ATTACHED_MELON_STEM, ATTACHED_PUMPKIN_STEM -> true;
            default -> false;
        };
    }

    private static boolean isEquine(Entity entity) {
        return entity instanceof Horse || entity instanceof Donkey || entity instanceof Mule;
    }

    private static boolean isLivestock(Entity entity) {
        if (entity instanceof Llama) return true;
        if (!(entity instanceof Animals)) return false;
        return switch (entity.getType().name()) {
            case "COW", "SHEEP", "PIG", "CHICKEN", "RABBIT", "GOAT", "LLAMA", "TRADER_LLAMA" -> true;
            default -> false;
        };
    }

    private void deny(Player player, Set<String> requirements) {
        if (requirements.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long previous = lastMessage.put(player.getUniqueId(), now);
        if (previous != null && now - previous < MESSAGE_COOLDOWN_MS) return;
        player.sendMessage("Your civilization has not unlocked: " + String.join(", ", requirements) + ".");
    }

    private record BrewAuthorization(UUID playerId, long atMillis) {}

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
