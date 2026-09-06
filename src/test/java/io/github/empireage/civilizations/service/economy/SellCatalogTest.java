package io.github.empireage.civilizations.service.economy;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellCatalogTest {
    private final SellCatalog catalog = new SellCatalog();

    @Test
    void containsEveryConfiguredOffer() {
        assertEquals(15, catalog.offers().size());
        assertOffer(Material.ROTTEN_FLESH, 32, 6);
        assertOffer(Material.BONE, 32, 8);
        assertOffer(Material.STRING, 32, 9);
        assertOffer(Material.SPIDER_EYE, 16, 7);
        assertOffer(Material.GUNPOWDER, 32, 12);
        assertOffer(Material.SLIME_BALL, 16, 10);
        assertOffer(Material.PRISMARINE_SHARD, 64, 10);
        assertOffer(Material.PRISMARINE_CRYSTALS, 32, 12);
        assertOffer(Material.ENDER_PEARL, 16, 14);
        assertOffer(Material.PHANTOM_MEMBRANE, 8, 15);
        assertOffer(Material.MAGMA_CREAM, 16, 14);
        assertOffer(Material.BLAZE_ROD, 16, 18);
        assertOffer(Material.GHAST_TEAR, 4, 22);
        assertOffer(Material.SHULKER_SHELL, 8, 32);
        assertOffer(Material.DRAGON_BREATH, 16, 26);
    }

    @Test
    void quoteSellsOnlyCompleteBundlesAndCombinesPayouts() {
        SellCatalog.Quote quote = catalog.quote(Map.of(
            Material.ROTTEN_FLESH, 70,
            Material.GHAST_TEAR, 9,
            Material.DIAMOND, 64
        ));

        assertFalse(quote.empty());
        assertEquals(Map.of(Material.ROTTEN_FLESH, 64, Material.GHAST_TEAR, 8), quote.removals());
        assertEquals(72, quote.itemCount());
        assertEquals(4, quote.bundleCount());
        assertEquals(56, quote.payout());
    }

    @Test
    void quoteIsEmptyWhenNoCompleteBundleExists() {
        assertTrue(catalog.quote(Map.of(Material.SHULKER_SHELL, 7)).empty());
    }

    private void assertOffer(Material material, int bundle, int payout) {
        SellCatalog.Offer offer = catalog.offer(material);
        assertEquals(bundle, offer.bundleSize());
        assertEquals(payout, offer.payout());
    }
}
