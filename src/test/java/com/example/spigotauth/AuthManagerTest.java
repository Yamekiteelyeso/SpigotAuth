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
}
