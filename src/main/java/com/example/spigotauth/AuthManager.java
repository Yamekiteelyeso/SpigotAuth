package com.example.spigotauth;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final SpigotAuth plugin;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> premiumPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> locked = new ConcurrentHashMap<>();

    public AuthManager(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    public boolean isAuthenticated(Player p) {
        return authenticated.contains(p.getUniqueId());
    }

    public void markPremium(Player p) {
        premiumPlayers.add(p.getUniqueId());
    }

    public boolean isPremium(Player p) {
        return premiumPlayers.contains(p.getUniqueId());
    }

    public void markAuthenticated(Player p) {
        authenticated.add(p.getUniqueId());
        failures.remove(p.getUniqueId());
        p.sendMessage(ChatColor.GREEN + "¡Sesión iniciada correctamente!");
    }

    public void clear(Player p) {
        authenticated.remove(p.getUniqueId());
        premiumPlayers.remove(p.getUniqueId());
        failures.remove(p.getUniqueId());
    }

    public boolean isLocked(String name) {
        Long until = locked.get(name.toLowerCase());
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            locked.remove(name.toLowerCase());
            return false;
        }
        return true;
    }

    public void registerFailure(Player p) {
        int max = plugin.getConfig().getInt("max-attempts", 5);
        int count = failures.merge(p.getUniqueId(), 1, Integer::sum);

        if (count >= max) {
            long lockMs = plugin.getConfig().getLong("lock-minutes", 5) * 60_000L;
            locked.put(p.getName().toLowerCase(), System.currentTimeMillis() + lockMs);
            failures.remove(p.getUniqueId());
            p.kickPlayer(ChatColor.RED + "Demasiados intentos fallidos. Espera unos minutos.");
        } else {
            p.sendMessage(ChatColor.RED + "Contraseña incorrecta. Intentos restantes: " + (max - count));
        }
    }

    public void startTimeout(Player p) {
        long seconds = plugin.getConfig().getLong("login-timeout-seconds", 60);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !isAuthenticated(p)) {
                p.kickPlayer(ChatColor.RED + "Tiempo de login agotado.");
            }
        }, seconds * 20L);
    }
}
