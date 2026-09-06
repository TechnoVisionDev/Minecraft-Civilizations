package io.github.empireage.civilizations.testutil;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.mockito.MockedConstruction;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** In-memory item metadata for testing plugin behavior without a running Bukkit server. */
public final class BukkitItemMocks implements AutoCloseable {
    private final MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class, (stack, context) -> {
        Material material = (Material) context.arguments().getFirst();
        AtomicInteger amount = new AtomicInteger(context.arguments().size() > 1 ? (int) context.arguments().get(1) : 1);
        ItemMeta meta = mock(ItemMeta.class);
        AtomicReference<String> name = new AtomicReference<>();
        AtomicReference<java.util.List<String>> lore = new AtomicReference<>();
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        Map<NamespacedKey, Object> values = new HashMap<>();
        when(data.get(any(), any())).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
            .when(data).set(any(), any(), any());
        doAnswer(call -> { values.remove(call.getArgument(0)); return null; }).when(data).remove(any());
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(meta.getDisplayName()).thenAnswer(call -> name.get());
        when(meta.hasDisplayName()).thenAnswer(call -> name.get() != null);
        doAnswer(call -> { name.set(call.getArgument(0)); return null; }).when(meta).setDisplayName(any());
        when(meta.getLore()).thenAnswer(call -> lore.get());
        doAnswer(call -> { lore.set(call.getArgument(0)); return null; }).when(meta).setLore(any());
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(call -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        when(stack.getItemMeta()).thenReturn(meta);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.setItemMeta(any())).thenReturn(true);
    });

    @Override
    public void close() {
        stacks.close();
    }
}
