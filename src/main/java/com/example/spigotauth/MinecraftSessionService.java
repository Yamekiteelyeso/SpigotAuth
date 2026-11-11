package com.example.spigotauth;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

/**
 * Verifies a Minecraft Java login session against Mojang's session service.
 * Call only after validating the client's login Encryption Response.
 */
public final class MinecraftSessionService {

    private static final String SESSION_URL =
            "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    private static final Pattern ID = Pattern.compile(
            "\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"");
    private static final Pattern NAME = Pattern.compile(
            "\\\"name\\\"\\s*:\\s*\\\"([A-Za-z0-9_]{3,16})\\\"");

    private final HttpClient client;
    private final Duration requestTimeout;

    public MinecraftSessionService() {
        this(Duration.ofSeconds(5));
    }

    public MinecraftSessionService(Duration requestTimeout) {
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("El timeout debe ser positivo y de al menos 1 ms.");
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Creates the signed hexadecimal server hash used by hasJoined. */
    public static String createServerHash(String serverId, SecretKey sharedSecret,
                                          PublicKey serverPublicKey) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(sharedSecret, "sharedSecret");
        Objects.requireNonNull(serverPublicKey, "serverPublicKey");
        byte[] secret = sharedSecret.getEncoded();
        if (secret == null || secret.length != PremiumEncryptionChallenge.SHARED_SECRET_LENGTH) {
            throw new IllegalArgumentException("La clave compartida AES debe tener 16 bytes.");
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            sha1.update(secret);
            sha1.update(serverPublicKey.getEncoded());
            return new BigInteger(sha1.digest()).toString(16);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo calcular el hash de sesión de Minecraft.", exception);
        }
    }

    /**
     * An empty result means Mojang did not confirm ownership. Network/service
     * errors complete exceptionally and must never be treated as offline login.
     */
    public CompletableFuture<VerifiedProfile> hasJoined(String requestedName, String serverHash) {
        if (requestedName == null || !requestedName.matches("[A-Za-z0-9_]{3,16}")) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Nombre de Minecraft no válido."));
        }
        if (serverHash == null || !serverHash.matches("-?[0-9a-fA-F]{1,40}")) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Hash de sesión no válido."));
        }

        String query = "username=" + encode(requestedName)
                + "&serverId=" + encode(serverHash);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SESSION_URL + "?" + query))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "SpigotAuth/1.1")
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(response -> {
                    if (response.statusCode() == 204 || response.statusCode() == 404) return null;
                    if (response.statusCode() != 200) {
                        throw new CompletionException(new IOException(
                                "El servicio de sesión respondió HTTP " + response.statusCode()));
                    }
                    return parseVerifiedProfile(requestedName, response.body());
                });
    }

    static VerifiedProfile parseVerifiedProfile(String requestedName, String body) {
        if (requestedName == null || !requestedName.matches("[A-Za-z0-9_]{3,16}")
                || body == null || body.isBlank()) {
            throw new CompletionException(new IOException("Respuesta de sesión de Minecraft no válida."));
        }
        Matcher idMatcher = ID.matcher(body);
        Matcher nameMatcher = NAME.matcher(body);
        if (!idMatcher.find() || !nameMatcher.find()) {
            throw new CompletionException(new IOException("Respuesta de sesión de Minecraft incompleta."));
        }
        String returnedName = nameMatcher.group(1);
        if (!returnedName.equalsIgnoreCase(requestedName)) {
            throw new CompletionException(new SecurityException(
                    "El nombre devuelto por el servicio de sesión no coincide."));
        }
        String rawId = idMatcher.group(1);
        String uuid = rawId.replaceFirst(
                "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                "$1-$2-$3-$4-$5");
        return new VerifiedProfile(UUID.fromString(uuid), returnedName);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record VerifiedProfile(UUID uuid, String name) {
        public VerifiedProfile {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) {
                throw new IllegalArgumentException("Nombre de perfil verificado no válido.");
            }
        }
    }
}
