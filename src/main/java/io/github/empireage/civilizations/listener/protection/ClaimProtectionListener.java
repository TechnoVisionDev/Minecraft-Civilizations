package io.github.empireage.civilizations.listener.protection;

import io.github.empireage.civilizations.domain.ChunkKey;
import io.github.empireage.civilizations.service.territory.ProtectionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Animals;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exhaustive Bukkit event adapter for claim protection. Every hot-path decision is
 * delegated to immutable cache lookups in {@link ProtectionService}; this listener
 * never touches MySQL.
 */
public final class ClaimProtectionListener implements Listener {
    public static final String BYPASS_PERMISSION = "civilizations.bypass.protection";
    public static final String WAR_BYPASS_PERMISSION = "civilizations.bypass.war";

    private final ProtectionService protection;
    private final Map<UUID, ChunkKey> fallingOrigins = new ConcurrentHashMap<>();

    public ClaimProtectionListener(ProtectionService protection) {
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        ProtectionService.Decision decision = decide(event.getPlayer(), event.getBlock().getLocation(), ProtectionService.Action.BREAK,
            event.getBlock().getType());
        if (!decision.allowed()) {
            event.setCancelled(true);
        } else if (decision.wartime()) {
            Block block = event.getBlock();
            if (protection.wartimeTargetProtected(key(block), ProtectionService.Action.BREAK, block.getType(),
                block.getType(), block.getX(), block.getY(), block.getZ())) {
                event.setCancelled(true);
                return;
            }
            if (!protection.attackerBlockDrops()) {
                event.setDropItems(false);
                event.setExpToDrop(0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                if (!placementAllowed(event.getPlayer(), replaced.getBlock())) {
                    event.setCancelled(true);
                    return;
                }
            }
        } else if (!placementAllowed(event.getPlayer(), event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    private boolean placementAllowed(Player player, Block block) {
        ProtectionService.Decision decision = decide(player, block.getLocation(), ProtectionService.Action.PLACE,
            block.getType());
        return decision.allowed() && (!decision.wartime() || !protection.wartimeTargetProtected(key(block),
            ProtectionService.Action.PLACE, block.getType(), block.getType(), block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        ProtectionService.Action action = isContainer(block) ? ProtectionService.Action.CONTAINER
            : isRedstoneControl(block) ? ProtectionService.Action.REDSTONE : ProtectionService.Action.INTERACT;
        if (!decide(event.getPlayer(), block.getLocation(), action, block.getType()).allowed()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Location location = event.getInventory().getLocation();
        if (location != null && !decide(player, location, ProtectionService.Action.CONTAINER, null).allowed()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
            && !inventoryAccessAllowed(player, event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
            && !inventoryAccessAllowed(player, event.getView().getTopInventory())) event.setCancelled(true);
    }

    private boolean inventoryAccessAllowed(Player player, Inventory inventory) {
        Location location = inventory.getLocation();
        return location == null || decide(player, location, ProtectionService.Action.CONTAINER, null).allowed();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (!decide(event.getPlayer(), target.getLocation(), ProtectionService.Action.BUCKET,
            event.getBucket()).allowed()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!decide(event.getPlayer(), event.getBlockClicked().getLocation(), ProtectionService.Action.BUCKET,
            event.getBucket()).allowed()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player actor = event.getPlayer();
        if (actor == null) actor = actor(event.getIgnitingEntity());
        if (!decide(actor, event.getBlock().getLocation(), ProtectionService.Action.IGNITE,
            Material.FIRE).allowed()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!protection.fireSpreadAllowed(key(event.getIgnitingBlock() == null ? event.getBlock() : event.getIgnitingBlock()),
            key(event.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!protection.fireSpreadAllowed(key(event.getSource()), key(event.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (!protection.fluidFlowAllowed(key(event.getBlock()), key(event.getToBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent event) {
        ChunkKey source = key(event.getBlock());
        if (event.getBlocks().stream().map(BlockState::getBlock).map(ClaimProtectionListener::key)
            .anyMatch(target -> !protection.boundaryCompatible(source, target))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        // The piston head itself is a block change too, including an empty push at a chunk edge.
        boolean headCrossesBoundary = !protection.boundaryCompatible(
            key(event.getBlock()), key(event.getBlock().getRelative(event.getDirection())));
        boolean movedBlockCrossesBoundary = event.getBlocks().stream().anyMatch(block ->
            !protection.boundaryCompatible(key(block), key(block.getRelative(event.getDirection()))));
        if (headCrossesBoundary || movedBlockCrossesBoundary) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        boolean headCrossesBoundary = !protection.boundaryCompatible(
            key(event.getBlock()), key(event.getBlock().getRelative(event.getDirection())));
        // getDirection() is the piston's facing direction; a retracted block travels toward
        // the base, which is the opposite direction.
        boolean movedBlockCrossesBoundary = event.getBlocks().stream().anyMatch(block ->
            !protection.boundaryCompatible(key(block),
                key(block.getRelative(event.getDirection().getOppositeFace()))));
        if (headCrossesBoundary || movedBlockCrossesBoundary) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!(event.getBlock().getBlockData() instanceof Directional directional)) return;
        Block target = event.getBlock().getRelative(directional.getFacing());
        if (!protection.boundaryCompatible(key(event.getBlock()), key(target))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        Player actor = actor(event.getEntity());
        Material explosive = event.getEntity() instanceof TNTPrimed ? Material.TNT
            : event.getEntity() instanceof EnderCrystal ? Material.END_CRYSTAL : null;
        ChunkKey origin = key(event.getLocation());
        event.blockList().removeIf(block -> {
            ProtectionService.Decision decision = protection.authorize(actor == null ? null : actor.getUniqueId(), key(block),
                ProtectionService.Action.EXPLOSION, explosive, bypass(actor), warBypass(actor));
            return !protection.sameClaimBoundary(origin, key(block)) || !decision.allowed()
                || decision.wartime() && (isContainer(block) || protection.wartimeTargetProtected(key(block),
                    ProtectionService.Action.EXPLOSION, explosive, block.getType(),
                    block.getX(), block.getY(), block.getZ()));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        ChunkKey origin = key(event.getBlock());
        event.blockList().removeIf(block -> !protection.sameClaimBoundary(origin, key(block))
            || !protection.authorize(null, key(block), ProtectionService.Action.EXPLOSION,
                event.getBlock().getType(), false).allowed());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player source = actor(event.getDamager());
        if (event.getEntity() instanceof Player victim) {
            if (event.getDamager() instanceof TNTPrimed || event.getDamager() instanceof EnderCrystal) {
                Material explosive = event.getDamager() instanceof TNTPrimed ? Material.TNT : Material.END_CRYSTAL;
                if (!protection.sameClaimBoundary(key(event.getDamager().getLocation()), key(victim.getLocation()))
                    || !protection.authorize(source == null ? null : source.getUniqueId(), key(victim.getLocation()),
                        ProtectionService.Action.EXPLOSION, explosive, bypass(source)).allowed()) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (source != null && !protection.friendlyFire(source.getUniqueId(), victim.getUniqueId(),
                key(victim.getLocation()), bypass(source)).allowed()) event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof ArmorStand) {
            if (!authorizeEntity(source, event.getEntity(), ProtectionService.Action.ARMOR_STAND)) event.setCancelled(true);
        } else if (event.getEntity() instanceof Hanging) {
            if (!authorizeEntity(source, event.getEntity(), ProtectionService.Action.HANGING)) event.setCancelled(true);
        } else if (event.getEntity() instanceof Vehicle) {
            if (!authorizeEntity(source, event.getEntity(), ProtectionService.Action.VEHICLE)) event.setCancelled(true);
        } else if (protectedLivingEntity(event.getEntity())
            && !protection.animalDamageAllowed(source == null ? null : source.getUniqueId(),
                key(event.getEntity().getLocation()), bypass(source))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnvironmentalExplosionDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
            && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        if (protection.claimed(key(event.getEntity().getLocation()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (!authorizeEntity(event.getPlayer(), event.getEntity(), ProtectionService.Action.ENTITY_DAMAGE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        if (!authorizeEntity(event.getPlayer(), event.getEntity(), ProtectionService.Action.ENTITY_DAMAGE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (!authorizeEntity(event.getPlayer(), event.getEntity(), ProtectionService.Action.ENTITY_DAMAGE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        ProtectionService.Action action = event.getRightClicked() instanceof ArmorStand
            ? ProtectionService.Action.ARMOR_STAND : ProtectionService.Action.INTERACT;
        if (!authorizeEntity(event.getPlayer(), event.getRightClicked(), action)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!authorizeEntity(event.getPlayer(), event.getRightClicked(), ProtectionService.Action.ARMOR_STAND)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (!authorizeEntity(event.getPlayer(), event.getEntity(), ProtectionService.Action.HANGING)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Player actor = event instanceof HangingBreakByEntityEvent byEntity ? actor(byEntity.getRemover()) : null;
        if (!authorizeEntity(actor, event.getEntity(), ProtectionService.Action.HANGING)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (!authorizeEntity(actor(event.getAttacker()), event.getVehicle(), ProtectionService.Action.VEHICLE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!authorizeEntity(actor(event.getAttacker()), event.getVehicle(), ProtectionService.Action.VEHICLE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryVehicleMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof InventoryHolder)) return;
        if (protection.boundaryCompatible(key(event.getFrom()), key(event.getTo()))) return;
        // VehicleMoveEvent is not cancellable. Move the inventory vehicle back to
        // its last authorized side and stop its momentum before it can extract.
        event.getVehicle().teleport(event.getFrom());
        event.getVehicle().setVelocity(new org.bukkit.util.Vector());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        ProtectionService.Action action = event.getEntity() instanceof ArmorStand
            ? ProtectionService.Action.ARMOR_STAND : ProtectionService.Action.PLACE;
        if (!authorizeEntity(event.getPlayer(), event.getEntity(), action)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberFallingOrigin(EntitySpawnEvent event) {
        if (event.getEntity() instanceof FallingBlock) fallingOrigins.put(event.getEntity().getUniqueId(), key(event.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        ChunkKey target = key(event.getBlock());
        if (event.getEntity() instanceof FallingBlock) {
            if (event.getTo() == Material.AIR) {
                if (!protection.ready()) event.setCancelled(true);
                else fallingOrigins.put(event.getEntity().getUniqueId(), target);
                return;
            }
            ChunkKey origin = fallingOrigins.remove(event.getEntity().getUniqueId());
            if (origin == null ? protection.claimed(target) : !protection.boundaryCompatible(origin, target)) event.setCancelled(true);
            return;
        }
        Player actor = actor(event.getEntity());
        if (actor != null) {
            ProtectionService.Action action = event.getTo() == Material.AIR
                ? ProtectionService.Action.BREAK : ProtectionService.Action.PLACE;
            if (!decide(actor, event.getBlock().getLocation(), action, event.getTo()).allowed()) event.setCancelled(true);
        } else if (!protection.mobGriefAllowed(target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobInteract(EntityInteractEvent event) {
        if (!protection.mobGriefAllowed(key(event.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        ChunkKey origin = key(event.getLocation());
        Player player = event.getPlayer();
        boolean denied = event.getBlocks().stream().anyMatch(state -> {
            ChunkKey target = key(state.getBlock());
            return !protection.boundaryCompatible(origin, target)
                || player != null && !decide(player, state.getLocation(), ProtectionService.Action.PLACE,
                    state.getType()).allowed();
        });
        if (denied) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        ChunkKey origin = key(event.getBlock());
        Player player = event.getPlayer();
        boolean denied = event.getBlocks().stream().anyMatch(state -> !protection.boundaryCompatible(origin, key(state.getBlock()))
            || player != null && !decide(player, state.getLocation(), ProtectionService.Action.PLACE,
                state.getType()).allowed());
        if (denied) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        // Single-block growth is safe inside a policy zone; fail closed while the cache warms.
        if (!protection.ready()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        Player player = actor(event.getEntity());
        if (event.getBlocks().isEmpty()) return;
        if (player == null) {
            ChunkKey origin = key(event.getBlocks().getFirst().getBlock());
            if (!protection.ready() || event.getBlocks().stream().anyMatch(state ->
                !protection.boundaryCompatible(origin, key(state.getBlock())))) event.setCancelled(true);
        } else if (event.getBlocks().stream().anyMatch(state -> !protection.authorize(
            player.getUniqueId(), key(state.getBlock()), ProtectionService.Action.PLACE,
            state.getType(), bypass(player), warBypass(player)).allowed())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Location source = event.getSource().getLocation();
        Location destination = event.getDestination().getLocation();
        if (source != null && destination != null
            && !protection.boundaryCompatible(key(source), key(destination))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Location inventory = event.getInventory().getLocation();
        if (inventory != null && !protection.boundaryCompatible(key(event.getItem().getLocation()), key(inventory))) {
            event.setCancelled(true);
        }
    }

    private ProtectionService.Decision decide(Player player, Location location, ProtectionService.Action action,
                                                Material material) {
        return protection.authorize(player == null ? null : player.getUniqueId(), key(location), action, material,
            bypass(player), warBypass(player));
    }

    private boolean authorizeEntity(Player player, Entity entity, ProtectionService.Action action) {
        return decide(player, entity.getLocation(), action, null).allowed();
    }

    private static boolean bypass(Player player) {
        return player != null && player.hasPermission(BYPASS_PERMISSION);
    }

    private static boolean warBypass(Player player) {
        return player != null && player.hasPermission(WAR_BYPASS_PERMISSION);
    }

    private static boolean protectedLivingEntity(Entity entity) {
        return entity instanceof Animals || entity instanceof Tameable || entity instanceof Villager;
    }

    private static boolean isContainer(Block block) {
        return block.getState() instanceof InventoryHolder;
    }

    private static boolean isRedstoneControl(Block block) {
        return block.getBlockData() instanceof Switch || block.getBlockData() instanceof Openable
            || block.getBlockData() instanceof Powerable;
    }

    private static Player actor(Entity entity) {
        if (entity == null) return null;
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) return actor(projectile.getShooter());
        if (entity instanceof TNTPrimed primed) return actor(primed.getSource());
        if (entity instanceof Tameable tameable) return tameable.getOwner() instanceof Player player ? player : null;
        if (entity instanceof AreaEffectCloud cloud) return actor(cloud.getSource());
        if (entity instanceof EvokerFangs fangs) return fangs.getOwner() instanceof Player player ? player : null;
        return null;
    }

    private static Player actor(ProjectileSource source) {
        return source instanceof Player player ? player : source instanceof Entity entity ? actor(entity) : null;
    }

    private static ChunkKey key(Block block) {
        return key(block.getLocation());
    }

    private static ChunkKey key(Location location) {
        return new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
}
