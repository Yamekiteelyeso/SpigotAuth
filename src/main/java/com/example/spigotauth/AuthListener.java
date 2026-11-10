package com.example.spigotauth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

    private static final Set<String> ALLOWED_COMMANDS = Set.of("login", "register");
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
        plugin.getAuth().startTimeout(player);

        // online-mode already means Minecraft validated the account session.
        if (plugin.getServer().getOnlineMode()) {
            plugin.getAuth().markAuthenticated(player);
            player.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            player.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE
                    + "Tu cuenta fue verificada por Minecraft.");
            player.sendMessage(ChatColor.GRAY + "Bienvenido, " + ChatColor.AQUA + player.getName()
                    + ChatColor.GRAY + ". Ya puedes jugar.");
            player.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            return;
        }

        if (plugin.getFastLoginBridge().isInstalled()) {
            player.sendMessage(ChatColor.GOLD + "★ " + ChatColor.WHITE
                    + "Comprobando tu sesión premium de Minecraft...");
            // FastLogin completes its protocol/session verification asynchronously.
            // Only show local credentials if it did not authenticate the player.
            waitForFastLogin(player, 10);

            return;
        }

        // Without a verified protocol provider, profile lookup is informational only.
        plugin.getPremium().check(player.getName(), result -> {
            if (!player.isOnline()) return;

            player.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            switch (result.status()) {
                case PREMIUM -> player.sendMessage(ChatColor.GOLD + "★ "
                        + ChatColor.WHITE + "Encontramos un perfil público con este nombre, pero eso no verifica su propietario.");
                case NOT_FOUND -> player.sendMessage(ChatColor.GRAY
                        + "No encontramos un perfil premium con este nombre.");
                case UNAVAILABLE -> player.sendMessage(ChatColor.YELLOW
                        + "No pudimos consultar Mojang ahora mismo.");
            }
            sendLocalLoginInstructions(player);
        });
    }

    private void waitForFastLogin(Player player, int checksRemaining) {
        if (!player.isOnline() || plugin.getAuth().isAuthenticated(player)) {
            return;
        }

        String status = plugin.getFastLoginBridge().getVerifiedStatus(player);
        if ("PREMIUM".equals(status) || "CRACKED".equals(status) || checksRemaining <= 0) {
            if (!plugin.getAuth().isAuthenticated(player)) {
                sendLocalLoginInstructions(player);
            }
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> waitForFastLogin(player, checksRemaining - 1), 10L);
    }

    private void sendLocalLoginInstructions(Player player) {
        if (plugin.getAccounts().isRegistered(player.getName())) {
            player.sendMessage(ChatColor.AQUA + "» " + ChatColor.WHITE
                    + "Inicia sesión con " + ChatColor.YELLOW + "/login <contraseña>");
        } else {
            player.sendMessage(ChatColor.AQUA + "» " + ChatColor.WHITE
                    + "Crea tu cuenta con " + ChatColor.YELLOW
                    + "/register <contraseña> <confirmar>");
        }
        player.sendMessage(ChatColor.GRAY + "Tienes "
                + plugin.getConfig().getLong("login-timeout-seconds", 60)
                + " segundos para autenticarte.");
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
                () -> player.sendMessage(ChatColor.RED + "✖ " + ChatColor.GRAY
                        + "Primero debes iniciar sesión para hablar."));
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
            event.getPlayer().sendMessage(ChatColor.RED + "✖ " + ChatColor.GRAY
                    + "Debes autenticarte antes de usar comandos.");
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
