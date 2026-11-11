package com.example.spigotauth;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreAuthGuardTest {
    @Test
    void emptySlotCanBeRestoredWithoutException() {
        ItemStack restored = assertDoesNotThrow(() -> PreAuthGuard.copyOrAir(null));
        assertNotNull(restored);
        assertEquals(Material.AIR, restored.getType());
    }

    @Test
    void occupiedSlotIsCopiedNotAliased() {
        ItemStack original = new ItemStack(Material.DIAMOND, 2);
        ItemStack copy = PreAuthGuard.copyOrAir(original);
        assertNotSame(original, copy);
        // ItemStack.equals consults Bukkit's ItemFactory, which is not
        // available in a standalone JVM unit test. Compare safe fields.
        assertEquals(original.getType(), copy.getType());
        assertEquals(original.getAmount(), copy.getAmount());
        copy.setAmount(1);
        assertEquals(2, original.getAmount());
    }
}