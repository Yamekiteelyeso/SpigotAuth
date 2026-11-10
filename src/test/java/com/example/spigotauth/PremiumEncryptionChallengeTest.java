package com.example.spigotauth;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PremiumEncryptionChallengeTest {

    @Test
    void acceptsValidRsaEncryptedTokenAndAesSecret() throws Exception {
        KeyPair pair = rsaKeyPair();
        PremiumEncryptionChallenge challenge =
                PremiumEncryptionChallenge.create(new SecureRandom());
        byte[] secret = new byte[PremiumEncryptionChallenge.SHARED_SECRET_LENGTH];
        new SecureRandom().nextBytes(secret);

        SecretKey result = challenge.verifyAndDecrypt(
                rsaEncrypt(pair, challenge.token()),
                rsaEncrypt(pair, secret),
                pair.getPrivate());

        assertEquals("AES", result.getAlgorithm());
        assertArrayEquals(secret, result.getEncoded());
    }

    @Test
    void rejectsAValidlyEncryptedButIncorrectVerifyToken() throws Exception {
        KeyPair pair = rsaKeyPair();
        PremiumEncryptionChallenge challenge =
                PremiumEncryptionChallenge.create(new SecureRandom());
        byte[] wrongToken = challenge.token();
        wrongToken[0] ^= 0x01;
        byte[] secret = new byte[PremiumEncryptionChallenge.SHARED_SECRET_LENGTH];

        assertThrows(java.security.GeneralSecurityException.class, () ->
                challenge.verifyAndDecrypt(
                        rsaEncrypt(pair, wrongToken),
                        rsaEncrypt(pair, secret),
                        pair.getPrivate()));
    }

    @Test
    void returnsDefensiveCopyOfVerifyToken() {
        PremiumEncryptionChallenge challenge =
                PremiumEncryptionChallenge.create(new SecureRandom());
        byte[] exposed = challenge.token();
        exposed[0] ^= 0x7f;
        assertFalse(Arrays.equals(exposed, challenge.token()));
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private static byte[] rsaEncrypt(KeyPair pair, byte[] value) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pair.getPublic());
        return cipher.doFinal(value);
    }
}
