package com.example.spigotauth;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

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
    private final Map<UUID, BukkitTask> timeoutTasks = new ConcurrentHashMap<>();

    public AuthManager(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    public boolean isAuthenticated(Player player) {
        return authenticated.contains(player.getUniqueId());
    }

    public void markAuthenticated(Player player) {
        UUID id = player.getUniqueId();
        authenticated.add(id);
        failures.remove(id);
        cancelTimeout(id);

    }

    public void clear(Player player) {
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        failures.remove(id);
        cancelTimeout(id);
    }

    public void shutdown() {
        timeoutTasks.values().forEach(BukkitTask::cancel);
        timeoutTasks.clear();
        authenticated.clear();
        failures.clear();
    }

    public boolean isLocked(String name) {
        String key = normalize(name);
        Long until = locked.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            locked.remove(key, until);
            return false;
        }
        return true;
    }

    public long remainingLockSeconds(String name) {
        Long until = locked.get(normalize(name));
        if (until == null) return 0L;
        return Math.max(0L, (until - System.currentTimeMillis() + 999L) / 1000L);
    }

    public void registerFailure(Player player) {
        int max = Math.max(1, plugin.getConfig().getInt("max-attempts", 5));
        int count = failures.merge(player.getUniqueId(), 1, Integer::sum);

        if (count >= max) {
            long minutes = Math.max(1L, plugin.getConfig().getLong("lock-minutes", 5));
            long lockMillis = Math.multiplyExact(minutes, 60_000L);
            locked.put(normalize(player.getName()), System.currentTimeMillis() + lockMillis);
            failures.remove(player.getUniqueId());
            player.kickPlayer(plugin.getMessages().format("auth.locked-kick",
                    Map.of("minutes", minutes)));
        } else {
            plugin.getMessages().send(player, "auth.wrong-password",
                    Map.of("remaining", max - count));
        }
    }

    public void startTimeout(Player player) {
        long seconds = Math.max(10L, plugin.getConfig().getLong("login-timeout-seconds", 60));
        UUID playerId = player.getUniqueId();
        cancelTimeout(playerId);

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            timeoutTasks.remove(playerId);
            if (player.isOnline() && player.getUniqueId().equals(playerId)
                    && !isAuthenticated(player)) {
                player.kickPlayer(plugin.getMessages().raw("auth.timeout-kick"));
            }
        }, Math.multiplyExact(seconds, 20L));
        timeoutTasks.put(playerId, task);
    }

    private void cancelTimeout(UUID playerId) {
        BukkitTask task = timeoutTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
