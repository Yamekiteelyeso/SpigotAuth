package com.example.spigotauth;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {

    private final SpigotAuth plugin;
    // Bind auth to the actual connection, not just an offline-mode UUID.
    private final Map<UUID, Player> authenticated = new ConcurrentHashMap<>();
    private record FailedAttempts(int count, long lastAttemptMillis) {}
    private record PendingTimeout(Player owner, BukkitTask task) {}

    // Attempt counters are tied to account + IP and deliberately survive
    // disconnects; reconnecting must not reset password rate limiting.
    private final Map<String, FailedAttempts> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> locked = new ConcurrentHashMap<>();
    private final Map<UUID, PendingTimeout> timeoutTasks = new ConcurrentHashMap<>();

    public AuthManager(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    public boolean isAuthenticated(Player player) {
        return authenticated.get(player.getUniqueId()) == player;
    }

    public void markAuthenticated(Player player) {
        UUID id = player.getUniqueId();
        authenticated.put(id, player);
        failures.remove(lockKey(player));
        locked.remove(lockKey(player));
        cancelTimeout(player);
        plugin.getPreAuthGuard().deactivate(player);
        if (plugin.getConfig().getBoolean("teleports.after-auth-to-loginready", true)) {
            plugin.getLoginLocations().teleport(player, LoginLocationStore.Spot.LOGINREADY);
        }
    }

    public void clear(Player player) {
        UUID id = player.getUniqueId();
        authenticated.remove(id, player);
        // Keep failed attempts across reconnects until the attempt window
        // expires or the user successfully authenticates.
        cancelTimeout(player);
        plugin.getPreAuthGuard().deactivate(player);
    }

    public void shutdown() {
        timeoutTasks.values().forEach(pending -> pending.task().cancel());
        timeoutTasks.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.getPreAuthGuard().deactivate(player);
        }
        authenticated.clear();
        failures.clear();
        locked.clear();
    }

    public boolean isLocked(Player player) {
        String key = lockKey(player);
        Long until = locked.get(key);
        if (until == null) return false;

        if (System.currentTimeMillis() >= until) {
            locked.remove(key, until);
            return false;
        }
        return true;
    }

    public long remainingLockSeconds(Player player) {
        String key = lockKey(player);
        Long until = locked.get(key);
        if (until == null) return 0L;

        if (System.currentTimeMillis() >= until) {
            locked.remove(key, until);
            return 0L;
        }

        return Math.max(
                0L,
                (until - System.currentTimeMillis() + 999L) / 1000L
        );
    }

    static int nextFailureCount(int previousCount, long previousAt,
                                long now, long windowMillis) {
        return previousAt <= 0L || now - previousAt >= windowMillis
                ? 1
                : previousCount + 1;
    }

    public void registerFailure(Player player) {
        int max = Math.max(1, Math.min(20,
                plugin.getConfig().getInt("security.max-attempts", 5)));

        long minutes = Math.max(1L, Math.min(1440L,
                plugin.getConfig().getLong("security.lock-minutes", 5)));

        long windowSeconds = Math.max(30L, Math.min(3600L,
                plugin.getConfig().getLong("security.attempt-window-seconds", 600)));
        long windowMillis = windowSeconds * 1000L;
        long now = System.currentTimeMillis();

        String key = lockKey(player);
        FailedAttempts attempt = failures.compute(key, (unused, previous) -> {
            int count = previous == null
                    ? 1
                    : nextFailureCount(previous.count(),
                            previous.lastAttemptMillis(), now, windowMillis);
            return new FailedAttempts(count, now);
        });

        if (failures.size() > 8192) {
            failures.entrySet().removeIf(entry ->
                    now - entry.getValue().lastAttemptMillis() > windowMillis);
        }

        if (attempt.count() >= max) {
            locked.put(key, now + minutes * 60_000L);
            failures.remove(key);
            player.kickPlayer(plugin.getMessages().format(
                    "auth.locked-kick", Map.of("minutes", minutes)));
        } else {
            plugin.getMessages().send(player, "auth.wrong-password",
                    Map.of("remaining", max - attempt.count()));
        }
    }

    public void startTimeout(Player player) {
        long seconds = Math.max(10L, Math.min(600L,
                plugin.getConfig().getLong("security.login-timeout-seconds", 60)));

        UUID playerId = player.getUniqueId();
        cancelTimeout(playerId);

        BukkitTask task = plugin.getServer()
                .getScheduler()
                .runTaskLater(plugin, () -> {
                    PendingTimeout pending = timeoutTasks.get(playerId);
                    if (pending == null || pending.owner() != player
                            || !timeoutTasks.remove(playerId, pending)) return;
                    if (player.isOnline() && !isAuthenticated(player)) {
                        player.kickPlayer(plugin.getMessages().raw("auth.timeout-kick"));
                    }
                }, Math.multiplyExact(seconds, 20L));

        timeoutTasks.put(playerId, new PendingTimeout(player, task));
    }

    private void cancelTimeout(Player player) {
        UUID id = player.getUniqueId();
        PendingTimeout pending = timeoutTasks.get(id);
        if (pending != null && pending.owner() == player
                && timeoutTasks.remove(id, pending)) pending.task().cancel();
    }

    private void cancelTimeout(UUID playerId) {
        PendingTimeout pending = timeoutTasks.remove(playerId);
        if (pending != null) pending.task().cancel();
    }

    static String lockKey(String name, String address) {
        String normalizedName = name == null
                ? ""
                : name.toLowerCase(Locale.ROOT);

        String normalizedAddress = address == null || address.isBlank()
                ? "unknown"
                : address;

        return normalizedName + "|" + normalizedAddress;
    }

    private static String lockKey(Player player) {
        InetSocketAddress address = player.getAddress();
        String host = address == null || address.getAddress() == null
                ? "unknown"
                : address.getAddress().getHostAddress();

        return lockKey(player.getName(), host);
    }
}
