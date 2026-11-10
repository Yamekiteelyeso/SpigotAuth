package com.example.spigotauth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftSessionServiceTest {

    @Test
    void parsesVerifiedProfileAndNormalizesUuid() {
        var profile = MinecraftSessionService.parseVerifiedProfile(
                "Notch",
                "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}");

        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), profile.uuid());
        assertEquals("Notch", profile.name());
    }

    @Test
    void rejectsProfileWithDifferentName() {
        var error = assertThrows(RuntimeException.class, () ->
                MinecraftSessionService.parseVerifiedProfile(
                        "Notch",
                        "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"OtherPlayer\"}"));

        assertTrue(error.getCause() instanceof SecurityException);
    }

    @Test
    void rejectsMalformedSessionResponse() {
        assertThrows(RuntimeException.class, () ->
                MinecraftSessionService.parseVerifiedProfile("Notch", "{\"id\":\"bad\"}"));
    }

    @Test
    void rejectsInvalidNamesBeforeMakingNetworkRequest() {
        var service = new MinecraftSessionService();
        var error = assertThrows(java.util.concurrent.CompletionException.class,
                () -> service.hasJoined("../Notch", "0123456789abcdef").join());
        assertTrue(error.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void rejectsInvalidServerHashesBeforeMakingNetworkRequest() {
        var service = new MinecraftSessionService();
        var error = assertThrows(java.util.concurrent.CompletionException.class,
                () -> service.hasJoined("Notch", "not-a-hash").join());
        assertTrue(error.getCause() instanceof IllegalArgumentException);
    }
}
