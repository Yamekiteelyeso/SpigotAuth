package com.example.spigotauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PremiumAccountStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void bindsVerifiedNameToUuidCaseInsensitively() {
        var store = new PremiumAccountStore(
                tempDir.resolve("premium.yml").toFile(),
                Logger.getLogger("test"));
        UUID uuid = UUID.randomUUID();

        assertTrue(store.ensureAccount(uuid, "PremiumUser"));
        assertEquals(uuid, store.findVerifiedUuidByName("premiumuser"));
        assertEquals(uuid, store.findVerifiedUuidByName("PREMIUMUSER"));
        assertTrue(store.isProtectedName("PremiumUser"));
    }

    @Test
    void movesNameBindingWhenVerifiedAccountRenames() {
        var store = new PremiumAccountStore(
                tempDir.resolve("premium.yml").toFile(),
                Logger.getLogger("test"));
        UUID uuid = UUID.randomUUID();

        assertTrue(store.ensureAccount(uuid, "OldName"));
        assertTrue(store.ensureAccount(uuid, "NewName"));

        assertNull(store.findVerifiedUuidByName("OldName"));
        assertEquals(uuid, store.findVerifiedUuidByName("NewName"));
        assertEquals("NewName", store.getLastKnownName(uuid));
    }

    @Test
    void rejectsBindingAProtectedNameToDifferentUuid() {
        var store = new PremiumAccountStore(
                tempDir.resolve("premium.yml").toFile(),
                Logger.getLogger("test"));

        assertTrue(store.ensureAccount(UUID.randomUUID(), "Protected"));
        assertFalse(store.ensureAccount(UUID.randomUUID(), "Protected"));
    }
}
