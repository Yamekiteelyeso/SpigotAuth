package com.example.spigotauth;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreAuthMovementTest {
    @Test
    void fallingAndLookingInPlaceRemainAllowed() {
        Location old = new Location(null, 3.5, 70, -2.5);
        Location next = new Location(null, 3.5, 69.6, -2.5, 90, 20);
        assertNull(AuthListener.confinePreAuthMovement(old, next));
        assertNull(AuthListener.confinePreAuthMovement(old, null));
    }

    @Test
    void horizontalMovementIsBlockedWithoutFreezingVerticalMotion() {
        Location old = new Location(null, 3.5, 70, -2.5);
        Location next = new Location(null, 4.2, 69.6, -1.8, 90, 20);
        Location fixed = AuthListener.confinePreAuthMovement(old, next);
        assertNotNull(fixed);
        assertEquals(old.getX(), fixed.getX());
        assertEquals(old.getZ(), fixed.getZ());
        assertEquals(next.getY(), fixed.getY());
        assertEquals(next.getYaw(), fixed.getYaw());
        assertEquals(next.getPitch(), fixed.getPitch());
    }
}
