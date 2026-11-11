package com.example.spigotauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PremiumPreferenceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesRequestedUuidWithoutTreatingItAsVerified() {
        var store = new PremiumPreferenceStore(
                tempDir.resolve("prefs.yml").toFile(),
                Logger.getLogger("test"));
        UUID uuid = UUID.randomUUID();

        assertTrue(store.request("PremiumUser", uuid));
        assertEquals(uuid, store.expectedUuid("premiumuser"));
        assertFalse(store.isVerified("PREMIUMUSER"));
    }

    @Test
    void pendingPreferenceCanBeRolledBack() {
        var store = new PremiumPreferenceStore(
                tempDir.resolve("prefs-rollback.yml").toFile(),
                Logger.getLogger("test"));
        UUID uuid = UUID.randomUUID();

        assertTrue(store.request("PremiumUser", uuid));
        assertEquals(uuid, store.expectedUuid("PremiumUser"));
        assertTrue(store.clear("PremiumUser"));
        assertNull(store.expectedUuid("PremiumUser"));
        assertFalse(store.isVerified("PremiumUser"));
    }

    @Test
    void confirmedPreferenceBecomesVerified() {
        var store = new PremiumPreferenceStore(
                tempDir.resolve("prefs.yml").toFile(),
                Logger.getLogger("test"));
        UUID uuid = UUID.randomUUID();

        assertTrue(store.request("PremiumUser", uuid));
        assertTrue(store.confirm("PremiumUser", uuid));
        assertTrue(store.isVerified("premiumuser"));
        assertEquals(uuid, store.expectedUuid("PremiumUser"));
    }
}
