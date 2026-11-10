package com.example.spigotauth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * JDK-only cryptographic portion of the Minecraft Java login Encryption
 * Request/Response exchange. It deliberately does not touch Netty or NMS.
 */
public final class PremiumEncryptionChallenge {

    public static final int TOKEN_LENGTH = 4;
    public static final int SHARED_SECRET_LENGTH = 16;

    private final byte[] verifyToken;

    private PremiumEncryptionChallenge(byte[] verifyToken) {
        this.verifyToken = verifyToken.clone();
    }

    public static PremiumEncryptionChallenge create(SecureRandom random) {
        byte[] token = new byte[TOKEN_LENGTH];
        random.nextBytes(token);
        return new PremiumEncryptionChallenge(token);
    }

    public byte[] token() {
        return verifyToken.clone();
    }

    /**
     * Decrypts the client's RSA-encrypted verify token and AES shared secret.
     * A token mismatch fails closed; callers must not enable channel encryption
     * or call Mojang hasJoined unless this method succeeds.
     */
    public SecretKey verifyAndDecrypt(byte[] encryptedToken,
                                      byte[] encryptedSecret,
                                      PrivateKey privateKey)
            throws GeneralSecurityException {
        if (encryptedToken == null || encryptedSecret == null || privateKey == null) {
            throw new GeneralSecurityException("Respuesta de cifrado incompleta.");
        }

        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] receivedToken = rsa.doFinal(encryptedToken);
        if (!MessageDigest.isEqual(verifyToken, receivedToken)) {
            throw new GeneralSecurityException("El token de verificación no coincide.");
        }

        byte[] secretBytes = rsa.doFinal(encryptedSecret);
        if (secretBytes.length != SHARED_SECRET_LENGTH) {
            Arrays.fill(secretBytes, (byte) 0);
            throw new GeneralSecurityException("La clave AES recibida no tiene 16 bytes.");
        }
        return new SecretKeySpec(secretBytes, "AES");
    }
}
