package com.example.spigotauth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremiumRegistrationModeTest {
    @Test
    void normalModeKeepsAutomaticPremiumFirstLogin() {
        assertFalse(Paper1201SmartAuthAdapter.requireLocalRegistrationFirst(
                false, false, false));
    }

    @Test
    void enabledModeRoutesOnlyUnboundNamesThroughLocalRegistration() {
        assertTrue(Paper1201SmartAuthAdapter.requireLocalRegistrationFirst(
                true, false, false));
        assertFalse(Paper1201SmartAuthAdapter.requireLocalRegistrationFirst(
                true, true, false));
        assertFalse(Paper1201SmartAuthAdapter.requireLocalRegistrationFirst(
                true, false, true));
    }

    @Test
    void disabledModeNeverChangesExistingPremiumOrMigrationHandling() {
        assertFalse(Paper1201SmartAuthAdapter.requireLocalRegistrationFirst(
                false, true, false));
        assertFalse(Paper1201SmartAuthAdapter.requireLocalRegistrationFirst(
                false, false, true));
    }
}
