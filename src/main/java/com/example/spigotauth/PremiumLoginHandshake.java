package com.example.spigotauth;

import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates the cryptographic and session-verification stages for one
 * candidate Premium login. Network transport and NMS listener replacement
 * remain the responsibility of the Paper-specific adapter.
 */
public final class PremiumLoginHandshake {

    @FunctionalInterface
    public interface SessionVerifier {
        CompletableFuture<MinecraftSessionService.VerifiedProfile> hasJoined(
                String playerName, String serverHash);
    }

    public record VerifiedLogin(SecretKey sharedSecret,
                                MinecraftSessionService.VerifiedProfile profile) {}

    private final String playerName;
    private final String serverId;
    private final KeyPair serverKeyPair;
    private final PremiumEncryptionChallenge challenge;
    private final SessionVerifier sessionVerifier;

    public PremiumLoginHandshake(String playerName,
                                 String serverId,
                                 KeyPair serverKeyPair,
                                 SecureRandom random,
                                 SessionVerifier sessionVerifier) {
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Nombre de Minecraft no válido.");
        }
        this.playerName = playerName;
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.serverKeyPair = Objects.requireNonNull(serverKeyPair, "serverKeyPair");
        this.challenge = PremiumEncryptionChallenge.create(
                Objects.requireNonNull(random, "random"));
        this.sessionVerifier = Objects.requireNonNull(sessionVerifier, "sessionVerifier");
    }

    /** Token to place in the server's Encryption Request packet. */
    public byte[] verifyToken() {
        return challenge.token();
    }

    /** Public key to place in the server's Encryption Request packet. */
    public byte[] publicKeyEncoded() {
        return serverKeyPair.getPublic().getEncoded().clone();
    }

    /**
     * Completes the cryptographic challenge before contacting Mojang.
     * A failed RSA/token check never invokes the session verifier.
     */
    public CompletableFuture<VerifiedLogin> complete(byte[] encryptedSecret,
                                                      byte[] encryptedToken) {
        final SecretKey secret;
        try {
            secret = challenge.verifyAndDecrypt(
                    encryptedToken, encryptedSecret, serverKeyPair.getPrivate());
        } catch (GeneralSecurityException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        final String serverHash;
        try {
            serverHash = MinecraftSessionService.createServerHash(
                    serverId, secret, serverKeyPair.getPublic());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        final CompletableFuture<MinecraftSessionService.VerifiedProfile> verification;
        try {
            verification = sessionVerifier.hasJoined(playerName, serverHash);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (verification == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("El verificador de sesión devolvió null."));
        }

        return verification.thenApply(profile -> {
            if (profile == null) {
                throw new java.util.concurrent.CompletionException(
                        new SecurityException("Mojang no confirmó la sesión Premium."));
            }
            if (!profile.name().equalsIgnoreCase(playerName)) {
                throw new java.util.concurrent.CompletionException(
                        new SecurityException("El perfil verificado no coincide con el jugador."));
            }
            return new VerifiedLogin(secret, profile);
        });
    }
}
