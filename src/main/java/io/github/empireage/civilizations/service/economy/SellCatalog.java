package io.github.empireage.civilizations.service.economy;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed server shop offers used by the sell command and its listing menu. */
public final class SellCatalog {
    private static final List<Offer> OFFERS = List.of(
        new Offer(Material.ROTTEN_FLESH, "Rotten Flesh", 32, 6),
        new Offer(Material.BONE, "Bones", 32, 8),
        new Offer(Material.STRING, "String", 32, 9),
        new Offer(Material.SPIDER_EYE, "Spider Eyes", 16, 7),
        new Offer(Material.GUNPOWDER, "Gunpowder", 32, 12),
        new Offer(Material.SLIME_BALL, "Slimeballs", 16, 10),
        new Offer(Material.PRISMARINE_SHARD, "Prismarine Shards", 64, 10),
        new Offer(Material.PRISMARINE_CRYSTALS, "Prismarine Crystals", 32, 12),
        new Offer(Material.ENDER_PEARL, "Ender Pearls", 16, 14),
        new Offer(Material.PHANTOM_MEMBRANE, "Phantom Membranes", 8, 15),
        new Offer(Material.MAGMA_CREAM, "Magma Cream", 16, 14),
        new Offer(Material.BLAZE_ROD, "Blaze Rods", 16, 18),
        new Offer(Material.GHAST_TEAR, "Ghast Tears", 4, 22),
        new Offer(Material.SHULKER_SHELL, "Shulker Shells", 8, 32),
        new Offer(Material.DRAGON_BREATH, "Dragon's Breath", 16, 26)
    );

    private final Map<Material, Offer> byMaterial;

    public SellCatalog() {
        Map<Material, Offer> indexed = new LinkedHashMap<>();
        for (Offer offer : OFFERS) indexed.put(offer.material(), offer);
        byMaterial = Map.copyOf(indexed);
    }

    public List<Offer> offers() {
        return OFFERS;
    }

    public Offer offer(Material material) {
        return byMaterial.get(material);
    }

    /** Returns only complete bundles; unsold remainders are intentionally omitted. */
    public Quote quote(Map<Material, Integer> available) {
        Objects.requireNonNull(available, "available");
        Map<Material, Integer> removals = new LinkedHashMap<>();
        int items = 0;
        int bundles = 0;
        int payout = 0;
        for (Offer offer : OFFERS) {
            int amount = Math.max(0, available.getOrDefault(offer.material(), 0));
            int offerBundles = amount / offer.bundleSize();
            if (offerBundles == 0) continue;
            int removal = Math.multiplyExact(offerBundles, offer.bundleSize());
            removals.put(offer.material(), removal);
            items = Math.addExact(items, removal);
            bundles = Math.addExact(bundles, offerBundles);
            payout = Math.addExact(payout, Math.multiplyExact(offerBundles, offer.payout()));
        }
        return new Quote(Map.copyOf(removals), items, bundles, payout);
    }

    public record Offer(Material material, String displayName, int bundleSize, int payout) {
        public Offer {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(displayName, "displayName");
            if (bundleSize <= 0 || bundleSize > 64) {
                throw new IllegalArgumentException("Invalid bundle size for " + material);
            }
            if (payout <= 0) throw new IllegalArgumentException("Payout must be positive");
        }
    }

    public record Quote(Map<Material, Integer> removals, int itemCount, int bundleCount, int payout) {
        public Quote {
            removals = Map.copyOf(removals);
        }

        public boolean empty() {
            return removals.isEmpty();
        }
    }
}
