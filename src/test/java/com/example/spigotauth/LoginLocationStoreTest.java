package com.example.spigotauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LoginLocationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void locationsAreOptionalPersistentAndIndependent() {
        var file = tempDir.resolve("locations.yml").toFile();
        var store = new LoginLocationStore(file, Logger.getLogger("location-test"));
        assertNull(store.get(LoginLocationStore.Spot.SPAWNLOGIN));
        assertNull(store.get(LoginLocationStore.Spot.LOGINREADY));

        var login = new LoginLocationStore.SavedLocation(
                "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                "world", 10.5, 64.0, -15.5, 135.0f, 10.0f);
        var ready = new LoginLocationStore.SavedLocation(
                "853c80ef-3c37-49fd-aa49-938b674adae6",
                "world_nether", -2.5, 69.5, 25.5, -90.0f, 0.0f);

        assertTrue(store.save(LoginLocationStore.Spot.SPAWNLOGIN, login));
        assertTrue(store.save(LoginLocationStore.Spot.LOGINREADY, ready));

        var restarted = new LoginLocationStore(file, Logger.getLogger("location-test"));
        assertEquals(login, restarted.get(LoginLocationStore.Spot.SPAWNLOGIN));
        assertEquals(ready, restarted.get(LoginLocationStore.Spot.LOGINREADY));

        assertTrue(restarted.clear(LoginLocationStore.Spot.SPAWNLOGIN));
        assertNull(restarted.get(LoginLocationStore.Spot.SPAWNLOGIN));
        assertEquals(ready, restarted.get(LoginLocationStore.Spot.LOGINREADY));

        var reloaded = new LoginLocationStore(file, Logger.getLogger("location-test"));
        assertNull(reloaded.get(LoginLocationStore.Spot.SPAWNLOGIN));
        assertEquals(ready, reloaded.get(LoginLocationStore.Spot.LOGINREADY));
    }

    @Test
    void editingReplacesPreviousLocationAndPreservesOrientation() {
        var store = new LoginLocationStore(
                tempDir.resolve("editing.yml").toFile(), Logger.getLogger("location-test"));
        var first = new LoginLocationStore.SavedLocation(
                null, "world", 3.5, 70.0, 4.5, 12.0f, 5.0f);
        var edited = new LoginLocationStore.SavedLocation(
                null, "world", 8.5, 71.5, 9.5, 180.0f, -25.0f);
        assertTrue(store.save(LoginLocationStore.Spot.LOGINREADY, first));
        assertTrue(store.save(LoginLocationStore.Spot.LOGINREADY, edited));
        assertEquals(edited, store.get(LoginLocationStore.Spot.LOGINREADY));
    }

    @Test
    void rejectsInvalidCoordinatesAndRecognizesSpotNames() {
        assertThrows(IllegalArgumentException.class, () ->
                new LoginLocationStore.SavedLocation(null,
                        "world", Double.NaN, 10.0, 11.0, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () ->
                new LoginLocationStore.SavedLocation(null,
                        " ", 1.0, 10.0, 11.0, 0.0f, 0.0f));
        assertEquals(LoginLocationStore.Spot.SPAWNLOGIN,
                LoginLocationStore.Spot.from("SPAWNLOGIN"));
        assertEquals(LoginLocationStore.Spot.LOGINREADY,
                LoginLocationStore.Spot.from("loginready"));
        assertNull(LoginLocationStore.Spot.from("unknown"));
    }
}
