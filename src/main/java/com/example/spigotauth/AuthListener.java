package com.example.spigotauth;

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

    private static final Set<String> ALLOWED_COMMANDS =
            Set.of("login", "register", "premiumlogin");
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

        // Online-mode ya valida la sesión contra los servicios oficiales de Minecraft.
        if (plugin.getServer().getOnlineMode()) {
            plugin.getAuth().markAuthenticated(player);
            player.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            player.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE
                    + "Minecraft verificó tu cuenta correctamente.");
            player.sendMessage(ChatColor.GRAY + "Bienvenido, " + ChatColor.AQUA + player.getName()
                    + ChatColor.GRAY + ". Ya puedes jugar.");
            player.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            return;
        }

        player.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage(ChatColor.GOLD + "★ " + ChatColor.WHITE + "¡Bienvenido a nuestro servidor!");
        player.sendMessage(ChatColor.GRAY + "Para demostrar que eres el dueño de tu cuenta premium:");
        player.sendMessage(ChatColor.AQUA + "» " + ChatColor.YELLOW + "/premiumlogin"
                + ChatColor.GRAY + " — verificar con Microsoft (recomendado).");
        player.sendMessage(ChatColor.GRAY + "También puedes usar " + ChatColor.YELLOW
                + "/login <contraseña>" + ChatColor.GRAY + " si ya tienes una cuenta local.");
        if (!plugin.getConfig().getString("microsoft-client-id", "")
                .matches("(?i)^(?!PON_AQUI_TU_CLIENT_ID$).+")) {
            player.sendMessage(ChatColor.RED + "La verificación premium todavía no está configurada por el administrador.");
        }
        player.sendMessage(ChatColor.GRAY + "Tienes "
                + plugin.getConfig().getLong("login-timeout-seconds", 60)
                + " segundos para autenticarte.");
        player.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
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
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
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
                        + "Primero debes autenticarte para hablar."));
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
        if (event.getPlayer() instanceof Player player && blocked(player)) event.setCancelled(true);
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
        if (event.getEntity() instanceof Player player && blocked(player)) event.setCancelled(true);
    }
}
