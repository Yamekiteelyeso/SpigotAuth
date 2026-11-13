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
        System.err.println("PAPER_26_3_LOGIN_FIELDS:\n" + dumpFields());
        System.err.println("PAPER_26_3_LOGIN_PROFILE_METHODS:\n" + dumpMethods());
        System.err.println("PAPER_26_3_CONNECTION_SECRET_METHODS:\n"
                + Arrays.stream(Connection.class.getDeclaredMethods())
                    .filter(m -> Arrays.asList(m.getParameterTypes()).contains(SecretKey.class))
                    .map(Method::toString)
                    .collect(Collectors.joining("\n")));
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
        assertTrue(access.supportsState("KEY"));
        assertTrue(access.supportsState("AUTHENTICATING"));
        assertFalse(access.stateNames().isEmpty());

    }

    @Test
    void paper1201ConnectionAcceptsSecretKeyEncryptionSetup() throws Exception {
        Method method = Connection.class.getDeclaredMethod(
                "setEncryptionKey",
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
