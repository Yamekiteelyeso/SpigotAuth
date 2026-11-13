package com.example.spigotauth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreAuthCommandSecurityTest {

    @Test
    void acceptsOnlyRealSLoginPasswordCommandsBeforeAuthentication() {
        assertTrue(AuthListener.isAllowedPreAuthCommand("login"));
        assertTrue(AuthListener.isAllowedPreAuthCommand("REGISTER"));
        assertTrue(AuthListener.isAllowedPreAuthCommand("l"));
        assertTrue(AuthListener.isAllowedPreAuthCommand("r"));
        assertTrue(AuthListener.isAllowedPreAuthCommand("slogin:login"));
        assertTrue(AuthListener.isAllowedPreAuthCommand("SLOGIN:REG"));
        // The command itself requires an authenticated player before it can
        // modify any Premium preference; pre-auth users get an explanation.
        assertTrue(AuthListener.isAllowedPreAuthCommand("premium"));
        assertTrue(AuthListener.isAllowedPreAuthCommand("slogin:premium"));

        assertFalse(AuthListener.isAllowedPreAuthCommand("otherplugin:login"));
        assertFalse(AuthListener.isAllowedPreAuthCommand("minecraft:register"));
        assertFalse(AuthListener.isAllowedPreAuthCommand("slogin:slogin"));
        assertFalse(AuthListener.isAllowedPreAuthCommand("slogin:login:extra"));
        assertFalse(AuthListener.isAllowedPreAuthCommand("op"));
        assertFalse(AuthListener.isAllowedPreAuthCommand("slogin"));
        assertFalse(AuthListener.isAllowedPreAuthCommand(""));
        assertFalse(AuthListener.isAllowedPreAuthCommand(null));
    }
}
