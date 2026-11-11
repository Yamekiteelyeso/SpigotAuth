package com.example.spigotauth;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Locale;
import java.util.Set;

public final class AuthListener implements Listener {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "login", "l", "log",
            "register", "r", "reg"
    );

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

        // Existing unauthenticated players must also be visually masked for
        // this newly joined viewer.
        plugin.getPreAuthGuard().refreshViewer(player);

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

        plugin.getPreAuthGuard().activate(player);
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!blocked(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()) {
            return; // Allow head rotation only.
        }

        Location fixed = from.clone();
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        event.setTo(fixed);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!blocked(event.getPlayer())) return;

        String raw = event.getMessage();
        if (raw.length() <= 1) {
            event.setCancelled(true);
            return;
        }

        String command = raw.substring(1).split("\\s+", 2)[0];
        int namespace = command.indexOf(':');
        if (namespace >= 0) command = command.substring(namespace + 1);
        command = command.toLowerCase(Locale.ROOT);

        if (!ALLOWED_COMMANDS.contains(command)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (blocked(event.getPlayer()) && event.getNewGameMode() != GameMode.SURVIVAL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
            return;
        }

        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player && blocked(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }
}
