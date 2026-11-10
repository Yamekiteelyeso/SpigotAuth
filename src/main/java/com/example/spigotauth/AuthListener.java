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

import java.util.Set;

public class AuthListener implements Listener {

    private final SpigotAuth plugin;
    private static final Set<String> ALLOWED_COMMANDS = Set.of("login", "register");

    public AuthListener(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    private boolean blocked(Player p) {
        return !plugin.getAuth().isAuthenticated(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.setJoinMessage(null);
        plugin.getAuth().startTimeout(p);

        plugin.getPremium().check(p.getName(), isPremium -> {
            if (!p.isOnline()) return;
            if (isPremium) {
                plugin.getAuth().markPremium(p);
                p.sendMessage(ChatColor.GOLD + "Cuenta premium detectada. "
                        + ChatColor.WHITE + "La verificación automática aún no está activa: usa tu contraseña.");
            }
            if (plugin.getAccounts().isRegistered(p.getName())) {
                p.sendMessage(ChatColor.AQUA + "Usa /login <contraseña> para entrar.");
            } else {
                p.sendMessage(ChatColor.AQUA + "Usa /register <contraseña> <confirmar> para crear tu cuenta.");
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.setQuitMessage(null);
        plugin.getAuth().clear(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!blocked(p)) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return; // solo rotación de cabeza, se permite
        }
        Location fixed = from.clone();
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        e.setTo(fixed);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!blocked(e.getPlayer())) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(ChatColor.RED + "Inicia sesión para hablar.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!blocked(e.getPlayer())) return;
        String word = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (!ALLOWED_COMMANDS.contains(word)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Inicia sesión primero.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (blocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventory(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && blocked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (blocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(PlayerPickupItemEvent e) {
        if (blocked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true);
    }
}
