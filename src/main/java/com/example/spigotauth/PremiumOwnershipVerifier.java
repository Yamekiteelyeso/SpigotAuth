package com.example.spigotauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies actual ownership of a Minecraft Java profile through Microsoft
 * Device Authorization + Xbox Live + XSTS + Minecraft Services.
 *
 * This flow is required in offline-mode. A username lookup is never proof
 * of ownership. Tokens are held only in memory and are never persisted/logged.
 */
public final class PremiumOwnershipVerifier {
    private static final String DEVICE_ENDPOINT =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_ENDPOINT =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final SpigotAuth plugin;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public PremiumOwnershipVerifier(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    public void start(Player player) {
        if (plugin.getServer().getOnlineMode()) {
            player.sendMessage(ChatColor.GREEN + "Este servidor ya verifica las cuentas con Mojang.");
            return;
        }
        String clientId = plugin.getConfig().getString("microsoft-client-id", "").trim();
        if (clientId.isEmpty() || clientId.equalsIgnoreCase("PON_AQUI_TU_CLIENT_ID")) {
            player.sendMessage(ChatColor.RED + "La verificación Microsoft aún no está configurada.");
            player.sendMessage(ChatColor.GRAY + "Un administrador debe configurar microsoft-client-id en config.yml.");
            return;
        }
        UUID sessionId = player.getUniqueId();
        if (!running.add(sessionId)) {
            player.sendMessage(ChatColor.YELLOW + "Ya tienes una verificación premium en curso.");
            return;
        }

        player.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage(ChatColor.AQUA + "VERIFICACIÓN PREMIUM");
        player.sendMessage(ChatColor.GRAY + "Preparando una comprobación segura con Microsoft...");
        player.sendMessage(ChatColor.GRAY + "No compartas tu contraseña ni códigos con nadie.");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                verify(player, sessionId, clientId);
            } catch (Exception ex) {
                plugin.getLogger().warning("Falló la verificación Microsoft: " + ex.getClass().getSimpleName());
                finish(player, sessionId, false,
                        "No se pudo completar la verificación. Inténtalo de nuevo o usa /login.");
            } finally {
                running.remove(sessionId);
            }
        });
    }

    private void verify(Player player, UUID sessionId, String clientId) throws Exception {
        JsonObject device = postForm(DEVICE_ENDPOINT,
                "client_id=" + enc(clientId) + "&scope=" + enc("XboxLive.signin offline_access"));
        String deviceCode = required(device, "device_code");
        String userCode = required(device, "user_code");
        String verificationUri = device.has("verification_uri")
                ? device.get("verification_uri").getAsString()
                : "https://microsoft.com/devicelogin";
        int interval = Math.max(2, device.has("interval") ? device.get("interval").getAsInt() : 5);
        int expires = Math.min(900, Math.max(60,
                device.has("expires_in") ? device.get("expires_in").getAsInt() : 900));

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.getUniqueId().equals(sessionId)) return;
            player.sendMessage(ChatColor.GOLD + "1. " + ChatColor.WHITE + "Abre: "
                    + ChatColor.AQUA + verificationUri);
            player.sendMessage(ChatColor.GOLD + "2. " + ChatColor.WHITE + "Introduce este código: "
                    + ChatColor.GREEN + userCode);
            player.sendMessage(ChatColor.GRAY + "Esperando la confirmación de Microsoft...");
        });

        JsonObject tokenResponse = null;
        long deadline = System.currentTimeMillis() + expires * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            JsonObject poll = postForm(TOKEN_ENDPOINT,
                    "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                            + "&client_id=" + enc(clientId)
                            + "&device_code=" + enc(deviceCode));
            if (poll.has("access_token")) {
                tokenResponse = poll;
                break;
            }
            String error = poll.has("error") ? poll.get("error").getAsString() : "";
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) {
                interval += 5;
                continue;
            }
            if ("authorization_declined".equals(error) || "expired_token".equals(error)
                    || "bad_verification_code".equals(error)) {
                finish(player, sessionId, false, "La autorización fue cancelada o expiró.");
                return;
            }
            throw new IOException("Microsoft token endpoint returned " + error);
        }
        if (tokenResponse == null) {
            finish(player, sessionId, false, "El código de Microsoft expiró. Ejecuta /premiumlogin para intentarlo otra vez.");
            return;
        }

        String microsoftToken = required(tokenResponse, "access_token");
        JsonObject xbl = postJson("https://user.auth.xboxlive.com/user/authenticate",
                "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\","
                        + "\"RpsTicket\":\"d=" + jsonEscape(microsoftToken) + "\"},"
                        + "\"RelyingParty\":\"http://auth.xboxlive.com/\",\"TokenType\":\"JWT\"}");
        String xblToken = required(xbl, "Token");
        String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject().get("uhs").getAsString();

        JsonObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize",
                "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\""
                        + jsonEscape(xblToken) + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\","
                        + "\"TokenType\":\"JWT\"}");
        String xstsToken = required(xsts, "Token");

        JsonObject minecraftAuth = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                "{\"identityToken\":\"XBL3.0 x=" + jsonEscape(uhs) + ";"
                        + jsonEscape(xstsToken) + "\"}");
        String minecraftToken = required(minecraftAuth, "access_token");

        JsonObject profile = getJson("https://api.minecraftservices.com/minecraft/profile", minecraftToken);
        String profileName = required(profile, "name");
        String profileId = required(profile, "id");
        if (!profileName.equalsIgnoreCase(player.getName())) {
            finish(player, sessionId, false,
                    "La cuenta Microsoft autorizada pertenece a " + profileName
                            + ", no a " + player.getName() + ". No se inició sesión.");
            return;
        }

        UUID verifiedProfile = UUID.fromString(profileId.replaceFirst(
                "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                "$1-$2-$3-$4-$5"));
        finish(player, sessionId, true,
                "Cuenta Minecraft Java confirmada: " + profileName + " (" + verifiedProfile + ").");
    }

    private void finish(Player player, UUID sessionId, boolean success, String message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline()
                    || !player.getUniqueId().equals(sessionId)) return;
            if (success) {
                plugin.getAuth().markAuthenticated(player);
                player.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE + message);
            } else {
                player.sendMessage(ChatColor.RED + "✖ " + ChatColor.GRAY + message);
            }
        });
    }

    private static JsonObject postForm(String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return parse(sendOAuthForm(request));
    }

    private static JsonObject postJson(String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return parse(send(request));
    }

    private static JsonObject getJson(String url, String bearer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .GET().build();
        return parse(send(request));
    }

    // Microsoft responde HTTP 400 con authorization_pending durante el device flow.
    // Es una respuesta de protocolo esperada, no un fallo de red.
    private static String sendOAuthForm(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 500) {
            throw new IOException("OAuth HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static JsonObject parse(String body) throws IOException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("Invalid JSON response", ex);
        }
    }

    private static String required(JsonObject object, String key) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IOException("Missing response field: " + key);
        }
        return object.get(key).getAsString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
