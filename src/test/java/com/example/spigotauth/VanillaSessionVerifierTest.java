package com.example.spigotauth;

import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class VanillaSessionVerifierTest {

    @Test
    void resolvesAuthlibHasJoinedServerSignature() throws Exception {
        Class<?> type = Class.forName(
                "com.mojang.authlib.minecraft.MinecraftSessionService");

        Method method = VanillaSessionVerifier.resolveMethod(type);

        assertEquals("hasJoinedServer", method.getName());
        assertTrue(GameProfile.class.isAssignableFrom(method.getReturnType()));
        assertTrue(method.getParameterCount() == 2
                || method.getParameterCount() == 3);
        assertEquals(GameProfile.class, method.getParameterTypes()[0]);
        assertEquals(String.class, method.getParameterTypes()[1]);
    }
}
