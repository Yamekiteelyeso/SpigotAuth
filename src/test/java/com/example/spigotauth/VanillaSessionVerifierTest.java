package com.example.spigotauth;

import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class VanillaSessionVerifierTest {

    @Test
    void resolvesAuthlibHasJoinedServerSignature() throws Exception {
        // The 26.3 service API moved; exercise the verifier contract
        // against the CI fake instead of a removed Authlib class name.
        Class<?> type = ci.fixture.SessionStubPlugin.FakeSessionService.class;

        Method method = VanillaSessionVerifier.resolveMethod(type);

        assertEquals("hasJoinedServer", method.getName());
        assertTrue(GameProfile.class.isAssignableFrom(method.getReturnType()));
        assertTrue(method.getParameterCount() == 2
                || method.getParameterCount() == 3);
        assertEquals(GameProfile.class, method.getParameterTypes()[0]);
        assertEquals(String.class, method.getParameterTypes()[1]);
    }
}
