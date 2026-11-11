package com.example.spigotauth;

import net.minecraft.network.Connection;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Paper1201LoginAccessTest {

    @Test
    void resolvesExpectedPaper1201LoginLayout() throws Exception {
        Paper1201LoginAccess access = Paper1201LoginAccess.resolve();

        assertNotNull(access);
        assertTrue(access.supportsState("HELLO"));
        assertTrue(access.supportsState("KEY"));
        assertTrue(access.supportsState("AUTHENTICATING"));
        assertTrue(access.supportsState("VERIFYING"));

        Set<String> states = access.stateNames();
        assertTrue(states.contains("WAITING_FOR_DUPE_DISCONNECT"));
        assertTrue(states.contains("PROTOCOL_SWITCHING"));
        assertTrue(states.contains("ACCEPTED"));
    }

    @Test
    void paper1201ConnectionAcceptsSecretKeyEncryptionSetup() throws Exception {
        Method method = Connection.class.getDeclaredMethod(
                "setupEncryption",
                SecretKey.class
        );

        assertEquals(void.class, method.getReturnType());
    }
}
