package com.example.spigotauth;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PremiumImpersonationTest {
    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOfflineClientClaimingAnAlreadyProtectedPremiumName() {
        UUID official = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertTrue(Paper1201SmartAuthAdapter.isOfflineUuidClaim(
                "Notch", offlineUuid("Notch"), official));
    }

    @Test
    void officialUuidAndAbsentHintDoNotShortCircuitVerification() {
        UUID official = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertFalse(Paper1201SmartAuthAdapter.isOfflineUuidClaim(
                "Notch", official, official));
        assertFalse(Paper1201SmartAuthAdapter.isOfflineUuidClaim(
                "Notch", null, official));
        assertFalse(Paper1201SmartAuthAdapter.isOfflineUuidClaim(
                "Notch", offlineUuid("Notch"), null));
    }

    @Test
    void arbitraryForgedUuidCannotTriggerOfflineRejectionInsteadOfChallenge() {
        UUID official = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        UUID forged = UUID.fromString("22222222-2222-4222-8222-222222222222");
        assertFalse(Paper1201SmartAuthAdapter.isOfflineUuidClaim(
                "Notch", forged, official));
        assertFalse(Paper1201SmartAuthAdapter.isOfflineUuidClaim(
                "Notch", offlineUuid("Other"), official));
    }
}
