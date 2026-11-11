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
        assertEquals(original, copy);
        copy.setAmount(1);
        assertEquals(2, original.getAmount());
    }
}