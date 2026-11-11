package com.example.spigotauth;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Set;

public final class AuthListener implements Listener {

    private static final Set<String> ALLOWED_COMMANDS = Set.of("login", "register", "slogin");
    private final SpigotAuth plugin;

    public AuthListener(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    private boolean blocked(Player player) {
        return !plugin.getAuth().isAuthenticated(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.setJoinMessage(null);

        if (plugin.getServer().getOnlineMode()) {
            plugin.getAuth().markAuthenticated(player);
            plugin.getMessages().sendLines(player, "premium.verified",
                    java.util.Map.of("player", player.getName()));
            return;
        }

        Paper1201SmartAuthAdapter smart = plugin.getSmartAuthAdapter();
        if (smart != null && smart.consumeVerifiedJoin(player.getName(), player.getUniqueId())) {
            var verified = new MinecraftSessionService.VerifiedProfile(
                    player.getUniqueId(), player.getName());
            if (plugin.getPremiumAuth().authenticateVerified(player, verified)) {
                plugin.getMessages().sendLines(player, "premium.verified",
                        java.util.Map.of("player", player.getName()));
            }
            return;
        }

        plugin.getAuth().startTimeout(player);
        long timeout = plugin.getConfig().getLong("security.login-timeout-seconds", 60);
        if (plugin.getAccounts().isRegistered(player.getName())) {
            plugin.getMessages().sendLines(player, "login.prompt",
                    java.util.Map.of("player", player.getName(), "seconds", timeout));
        } else {
            plugin.getMessages().sendLines(player, "register.prompt",
                    java.util.Map.of("player", player.getName(), "seconds", timeout));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        plugin.getAuth().clear(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!blocked(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        Location fixed = from.clone();
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        event.setTo(fixed);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!blocked(event.getPlayer())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.getMessages().send(player, "auth.chat-blocked"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!blocked(event.getPlayer())) return;
        String message = event.getMessage();
        String command = message.substring(1).split("\\s+", 2)[0];
        int namespace = command.indexOf(':');
        if (namespace >= 0) command = command.substring(namespace + 1);
        command = command.toLowerCase(Locale.ROOT);

        if (!ALLOWED_COMMANDS.contains(command)) {
            event.setCancelled(true);
            plugin.getMessages().send(event.getPlayer(), "auth.command-blocked");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(PlayerPickupItemEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }
}
