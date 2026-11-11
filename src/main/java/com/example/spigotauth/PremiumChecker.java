package com.example.spigotauth;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consulta el perfil público de Minecraft Java en Mojang.
 *
 * IMPORTANTE: encontrar un perfil por nombre no demuestra que el jugador conectado
 * sea su propietario. En servidores offline este resultado es informativo y nunca
 * debe utilizarse como método de autenticación.
 */
public final class PremiumChecker {

    public enum Status {
        PREMIUM, NOT_FOUND, UNAVAILABLE
    }

    public record Result(Status status, UUID profileId) {}

    private record CacheEntry(Result result, long expiresAt) {}

    private static final Pattern PROFILE_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"");
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final SpigotAuth plugin;
    private final long cacheMillis;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PremiumChecker(SpigotAuth plugin, long cacheMillis) {
        this.plugin = plugin;
        this.cacheMillis = Math.max(0L, cacheMillis);
    }

    public void check(String name, Consumer<Result> callback) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            callback.accept(new Result(Status.NOT_FOUND, null));
            return;
        }

        String key = name.toLowerCase(Locale.ROOT);
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt() > System.currentTimeMillis()) {
            callback.accept(entry.result());
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Result result = queryMojang(name);
            if (result.status() != Status.UNAVAILABLE && cacheMillis > 0) {
                cache.put(key, new CacheEntry(result, System.currentTimeMillis() + cacheMillis));
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) return;
                callback.accept(result);
            });
        });
    }

    private Result queryMojang(String name) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "SLogin/1.4");

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                try (InputStream stream = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);

                    Matcher matcher = PROFILE_ID.matcher(body);
                    if (!matcher.find()) return new Result(Status.UNAVAILABLE, null);

                    String rawId = matcher.group(1);
                    String formattedId = rawId.replaceFirst(
                            "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                            "$1-$2-$3-$4-$5");
                    return new Result(Status.PREMIUM, UUID.fromString(formattedId));
                }
            }

            if (status == HttpURLConnection.HTTP_NO_CONTENT
                    || status == HttpURLConnection.HTTP_NOT_FOUND) {
                return new Result(Status.NOT_FOUND, null);
            }

            // Rate limit, errores 5xx y respuestas inesperadas no significan "no premium".
            return new Result(Status.UNAVAILABLE, null);
        } catch (Exception exception) {
            plugin.getLogger().fine("No se pudo consultar el perfil de Mojang: "
                    + exception.getClass().getSimpleName());
            return new Result(Status.UNAVAILABLE, null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
