package com.example.spigotauth;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class Paper1201LoginAccessTest {

    @Test
    void resolvesExpectedPaper1201LoginLayout() throws Exception {
        Paper1201LoginAccess access;

        try {
            access = Paper1201LoginAccess.resolve();
        } catch (ReflectiveOperationException exception) {
            fail(exception.getClass().getSimpleName()
                    + ": " + exception.getMessage()
                    + "\nFIELDS:\n" + dumpFields()
                    + "\nMETHODS:\n" + dumpMethods());
            return;
        }

        assertNotNull(access);
        assertTrue(access.supportsState("HELLO"));
        assertTrue(access.supportsState("KEY"));
        assertTrue(access.supportsState("AUTHENTICATING"));
        assertTrue(access.supportsState("READY_TO_ACCEPT"));

        Set<String> states = access.stateNames();
        assertTrue(states.contains("DELAY_ACCEPT"));
        assertTrue(states.contains("ACCEPTED"));
        assertFalse(states.contains("VERIFYING"));
        assertFalse(states.contains("PROTOCOL_SWITCHING"));
    }

    @Test
    void paper1201ConnectionAcceptsSecretKeyEncryptionSetup() throws Exception {
        Method method = Connection.class.getDeclaredMethod(
                "setupEncryption",
                SecretKey.class
        );

        assertEquals(void.class, method.getReturnType());
    }

    private static String dumpFields() {
        return Arrays.stream(ServerLoginPacketListenerImpl.class.getDeclaredFields())
                .map(field -> Modifier.toString(field.getModifiers())
                        + " " + field.getType().getName()
                        + " " + field.getName())
                .collect(Collectors.joining("\n"));
    }

    private static String dumpMethods() {
        return Arrays.stream(ServerLoginPacketListenerImpl.class.getDeclaredMethods())
                .filter(method -> Arrays.asList(method.getParameterTypes())
                        .contains(com.mojang.authlib.GameProfile.class))
                .map(method -> Modifier.toString(method.getModifiers())
                        + " " + method.getReturnType().getName()
                        + " " + method.getName()
                        + Arrays.toString(method.getParameterTypes()))
                .collect(Collectors.joining("\n"));
    }
}
