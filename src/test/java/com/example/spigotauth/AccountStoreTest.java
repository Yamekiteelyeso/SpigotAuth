package com.example.spigotauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void changesPasswordOnlyWithCorrectOldPassword() {
        var store = new AccountStore(
                tempDir.resolve("accounts.yml").toFile(),
                Logger.getLogger("test"));

        assertTrue(store.register("PlayerOne", "old-password"));
        assertFalse(store.changePassword("PlayerOne", "wrong", "new-password"));
        assertTrue(store.check("PlayerOne", "old-password"));

        assertTrue(store.changePassword("PlayerOne", "old-password", "new-password"));
        assertFalse(store.check("PlayerOne", "old-password"));
        assertTrue(store.check("PlayerOne", "new-password"));
    }

    @Test
    void removesLocalPasswordAccount() {
        var store = new AccountStore(
                tempDir.resolve("accounts.yml").toFile(),
                Logger.getLogger("test"));

        assertTrue(store.register("PlayerOne", "password-123"));
        assertTrue(store.remove("PlayerOne"));
        assertFalse(store.isRegistered("PlayerOne"));
    }
}
