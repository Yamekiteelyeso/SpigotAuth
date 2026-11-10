package com.example.spigotauth;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {

    private final SpigotAuth plugin;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> locked = new ConcurrentHashMap<>();

    public AuthManager(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    public boolean isAuthenticated(Player player) {
        return authenticated.contains(player.getUniqueId());
    }

    public void markAuthenticated(Player player) {
        authenticated.add(player.getUniqueId());
        failures.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE
                + "¡Autenticación completada! " + ChatColor.GRAY + "Ya puedes jugar.");
    }

    public void clear(Player player) {
        authenticated.remove(player.getUniqueId());
        failures.remove(player.getUniqueId());
    }

    public boolean isLocked(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Long until = locked.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            locked.remove(key, until);
            return false;
        }
        return true;
    }

    public void registerFailure(Player player) {
        int max = Math.max(1, plugin.getConfig().getInt("max-attempts", 5));
        int count = failures.merge(player.getUniqueId(), 1, Integer::sum);

        if (count >= max) {
            long minutes = Math.max(1L, plugin.getConfig().getLong("lock-minutes", 5));
            long lockMillis = minutes * 60_000L;
            locked.put(player.getName().toLowerCase(Locale.ROOT),
                    System.currentTimeMillis() + lockMillis);
            failures.remove(player.getUniqueId());
            player.kickPlayer(ChatColor.RED + "Has agotado los intentos de acceso. "
                    + "Vuelve a intentarlo en " + minutes + " minutos.");
        } else {
            player.sendMessage(ChatColor.RED + "Contraseña incorrecta. "
                    + ChatColor.GRAY + "Intentos restantes: " + (max - count) + ".");
        }
    }

    public void startTimeout(Player player) {
        long seconds = Math.max(10L, plugin.getConfig().getLong("login-timeout-seconds", 60));
        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && player.getUniqueId().equals(playerId)
                    && !isAuthenticated(player)) {
                player.kickPlayer(ChatColor.RED + "Se agotó el tiempo para iniciar sesión. "
                        + "Vuelve a entrar e inténtalo de nuevo.");
            }
        }, seconds * 20L);
    }
}
