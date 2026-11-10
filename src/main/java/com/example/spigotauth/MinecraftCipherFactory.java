package com.example.spigotauth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.GeneralSecurityException;

/**
 * Creates the AES/CFB8 stream ciphers used after a successful Minecraft
 * Java login Encryption Response. Create one cipher per direction.
 */
public final class MinecraftCipherFactory {

    private MinecraftCipherFactory() {}

    public static Cipher create(int mode, SecretKey sharedSecret)
            throws GeneralSecurityException {
        if (mode != Cipher.ENCRYPT_MODE && mode != Cipher.DECRYPT_MODE) {
            throw new IllegalArgumentException("Modo de cifrado no válido.");
        }
        if (sharedSecret == null || sharedSecret.getEncoded() == null
                || sharedSecret.getEncoded().length != 16) {
            throw new GeneralSecurityException("La clave compartida AES debe tener 16 bytes.");
        }

        byte[] key = sharedSecret.getEncoded();
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, sharedSecret, new IvParameterSpec(key));
        return cipher;
    }
}
