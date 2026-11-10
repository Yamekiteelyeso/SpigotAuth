package com.example.spigotauth;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PremiumLoginHandshakeTest {

    @Test
    void validatesChallengeBeforeCallingSessionService() throws Exception {
        KeyPair pair = keyPair();
        AtomicBoolean called = new AtomicBoolean();
        var handshake = new PremiumLoginHandshake(
                "Notch", "", pair, new SecureRandom(),
                (name, hash) -> {
                    called.set(true);
                    return CompletableFuture.completedFuture(
                            new MinecraftSessionService.VerifiedProfile(
                                    java.util.UUID.randomUUID(), name));
                });

        byte[] wrongToken = handshake.verifyToken();
        wrongToken[0] ^= 0x55;
        byte[] secret = new byte[16];

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> handshake.complete(
                        encrypt(pair, secret), encrypt(pair, wrongToken)).join());
        assertFalse(called.get());
    }

    @Test
    void passesOnlyVerifiedProfileToSuccessfulResult() throws Exception {
        KeyPair pair = keyPair();
        byte[] secretBytes = new byte[16];
        new SecureRandom().nextBytes(secretBytes);
        SecretKey expectedSecret = new SecretKeySpec(secretBytes, "AES");
        AtomicBoolean called = new AtomicBoolean();

        var handshake = new PremiumLoginHandshake(
                "Notch", "", pair, new SecureRandom(),
                (name, hash) -> {
                    called.set(true);
                    assertEquals("Notch", name);
                    assertNotNull(hash);
                    assertFalse(hash.isEmpty());
                    return CompletableFuture.completedFuture(
                            new MinecraftSessionService.VerifiedProfile(
                                    java.util.UUID.fromString(
                                            "069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                                    "Notch"));
                });

        var result = handshake.complete(
                encrypt(pair, secretBytes),
                encrypt(pair, handshake.verifyToken())).join();

        assertTrue(called.get());
        assertEquals(expectedSecret, result.sharedSecret());
        assertEquals("Notch", result.profile().name());
    }

    @Test
    void rejectsNullSessionProfile() throws Exception {
        KeyPair pair = keyPair();
        var handshake = new PremiumLoginHandshake(
                "Notch", "", pair, new SecureRandom(),
                (name, hash) -> CompletableFuture.completedFuture(null));

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> handshake.complete(
                        encrypt(pair, new byte[16]),
                        encrypt(pair, handshake.verifyToken())).join());
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private static byte[] encrypt(KeyPair pair, byte[] value) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pair.getPublic());
        return cipher.doFinal(value);
    }
}
