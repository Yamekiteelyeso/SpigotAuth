package com.example.spigotauth;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftCipherFactoryTest {

    @Test
    void encryptsAndDecryptsMinecraftCfb8Stream() throws Exception {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 3);
        var secret = new SecretKeySpec(key, "AES");
        byte[] plain = "SpigotAuth standalone premium login".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Cipher encrypt = MinecraftCipherFactory.create(Cipher.ENCRYPT_MODE, secret);
        Cipher decrypt = MinecraftCipherFactory.create(Cipher.DECRYPT_MODE, secret);
        byte[] encrypted = encrypt.update(plain);
        byte[] decoded = decrypt.update(encrypted);

        assertFalse(Arrays.equals(plain, encrypted));
        assertArrayEquals(plain, decoded);
    }

    @Test
    void rejectsWrongSecretLength() {
        var shortKey = new SecretKeySpec(new byte[8], "AES");
        assertThrows(java.security.GeneralSecurityException.class,
                () -> MinecraftCipherFactory.create(Cipher.ENCRYPT_MODE, shortKey));
    }

    @Test
    void rejectsUnsupportedMode() {
        var key = new SecretKeySpec(new byte[16], "AES");
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftCipherFactory.create(Cipher.WRAP_MODE, key));
    }
}
