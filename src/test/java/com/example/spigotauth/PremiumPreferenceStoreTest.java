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
    void migrationWithoutLookupRequiresSessionVerification() {
        var file = tempDir.resolve("unresolved.yml").toFile();
        var store = new PremiumPreferenceStore(file, Logger.getLogger("test"));

        assertTrue(store.requestUnresolved("PremiumUser"));
        assertTrue(store.isPending("premiumuser"));
        assertNull(store.expectedUuid("PremiumUser"));
        assertFalse(store.isVerified("PremiumUser"));

        // Pending status must survive a full plugin restart.
        var restored = new PremiumPreferenceStore(file, Logger.getLogger("test"));
        assertTrue(restored.isPending("PremiumUser"));
        UUID verifiedUuid = UUID.randomUUID();
        assertTrue(restored.confirm("PremiumUser", verifiedUuid));
        assertFalse(restored.isPending("PremiumUser"));
        assertTrue(restored.isVerified("PremiumUser"));
        assertEquals(verifiedUuid, restored.expectedUuid("premiumuser"));
    }

    @Test
    void unsuccessfulPendingMigrationCanBeCancelled() {
        var store = new PremiumPreferenceStore(
                tempDir.resolve("pending-cancel.yml").toFile(), Logger.getLogger("test"));
        assertTrue(store.requestUnresolved("PremiumUser"));
        assertTrue(store.isPending("PremiumUser"));
        assertTrue(store.clear("PremiumUser"));
        assertFalse(store.isPending("PremiumUser"));
        assertNull(store.expectedUuid("PremiumUser"));
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
