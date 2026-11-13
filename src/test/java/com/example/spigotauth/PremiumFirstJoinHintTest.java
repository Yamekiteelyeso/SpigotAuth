package com.example.spigotauth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PremiumFirstJoinHintTest {

    @Test
    void onlineUuidHintsTriggerSessionChallengeNotAutomaticAuthentication() {
        assertTrue(Paper1201SmartAuthAdapter.looksLikeOnlineUuid(
                UUID.fromString("22222222-2222-4222-8222-222222222222")));
        assertTrue(Paper1201SmartAuthAdapter.looksLikeOnlineUuid(
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
    }

    @Test
    void missingAndOfflineUuidsDoNotForceChallenge() {
        assertFalse(Paper1201SmartAuthAdapter.looksLikeOnlineUuid(null));
        assertFalse(Paper1201SmartAuthAdapter.looksLikeOnlineUuid(
                UUID.fromString("cb9e09f5-1aaa-32ea-9207-d073101f7fdb")));
    }
}
