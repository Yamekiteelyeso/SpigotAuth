package com.example.spigotauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthManagerTest {

    @Test
    void lockKeyIsCaseInsensitiveForUsername() {
        assertEquals(
                AuthManager.lockKey("PlayerOne", "203.0.113.10"),
                AuthManager.lockKey("playerone", "203.0.113.10")
        );
    }

    @Test
    void differentAddressesDoNotSharePasswordLockoutKey() {
        assertNotEquals(
                AuthManager.lockKey("PlayerOne", "203.0.113.10"),
                AuthManager.lockKey("PlayerOne", "203.0.113.11")
        );
    }

    @Test
    void differentAccountsDoNotSharePasswordLockoutKey() {
        assertNotEquals(
                AuthManager.lockKey("PlayerOne", "203.0.113.10"),
                AuthManager.lockKey("PlayerTwo", "203.0.113.10")
        );
    }
    @Test
    void failureCountSurvivesReconnectDuringAttemptWindow() {
        long now = 1_000_000L;
        long window = 600_000L;
        assertEquals(2, AuthManager.nextFailureCount(1, now - 10_000L, now, window));
        assertEquals(4, AuthManager.nextFailureCount(3, now - 30_000L, now, window));
        assertEquals(1, AuthManager.nextFailureCount(4, now - window, now, window));
        assertEquals(1, AuthManager.nextFailureCount(4, now - window * 2, now, window));
    }

}
