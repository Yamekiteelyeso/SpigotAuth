package com.example.spigotauth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SLoginCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT = List.of(
            "help", "status", "reload", "resetmessages", "spawnlogin", "loginready", "premiumcancel", "premiumcheck"
    );
    private static final List<String> LOCATION_ACTIONS = List.of(
            "set", "show", "delete", "tp"
    );

    private final SpigotAuth plugin;

    public SLoginCommand(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("slogin.admin")) {
            plugin.getMessages().send(sender, "admin.no-permission");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "reload" -> {
                if (args.length != 1) return usage(sender);
                plugin.reloadSLoginConfiguration();
                plugin.getMessages().send(sender, "admin.reload-ok",
                        Map.of("language", plugin.getMessages().language()));
                return true;
            }
            case "resetmessages" -> {
                if (args.length != 1) return usage(sender);
                if (plugin.restoreDefaultMessages()) {
                    plugin.getMessages().send(sender, "admin.reset-messages-ok");
                } else {
                    plugin.getMessages().send(sender, "admin.reset-messages-failed");
                }
                return true;
            }
            case "status" -> {
                if (args.length != 1) return usage(sender);
                sendStatus(sender);
                return true;
            }
            case "premiumcheck" -> {
                if (args.length != 2 || !args[1].matches("[A-Za-z0-9_]{3,16}")) {
                    plugin.getMessages().send(sender, "admin.premium-check-usage");
                    return true;
                }
                String name = args[1];
                plugin.getMessages().send(sender, "admin.premium-check-start",
                        Map.of("player", name));
                plugin.getPremium().check(name, result -> {
                    String path = switch (result.status()) {
                        case PREMIUM -> "admin.premium-check-found";
                        case NOT_FOUND -> "admin.premium-check-missing";
                        case UNAVAILABLE -> "admin.premium-check-unavailable";
                    };
                    plugin.getMessages().send(sender, path,
                            Map.of("player", name,
                                   "uuid", result.profileId() == null
                                           ? "N/A" : result.profileId()));
                });
                return true;
            }
            case "premiumcancel" -> {
                if (args.length != 2
                        || !args[1].matches("[A-Za-z0-9_]{3,16}")) {
                    plugin.getMessages().send(sender, "admin.premium-cancel-usage");
                    return true;
                }
                String name = args[1];
                if (!plugin.getPremiumPreferences().isPending(name)) {
                    plugin.getMessages().send(sender, "admin.premium-cancel-not-pending",
                            Map.of("player", name));
                    return true;
                }
                if (plugin.getPremiumPreferences().clear(name)) {
                    plugin.getMessages().send(sender, "admin.premium-cancel-ok",
                            Map.of("player", name));
                } else {
                    plugin.getMessages().send(sender, "admin.premium-cancel-failed");
                }
                return true;
            }
            case "spawnlogin", "loginready" -> {
                LoginLocationStore.Spot spot = LoginLocationStore.Spot.from(subcommand);
                return handleLocation(sender, spot, args);
            }
            default -> {
                return usage(sender);
            }
        }
    }

    private boolean handleLocation(CommandSender sender,
                                   LoginLocationStore.Spot spot,
                                   String[] args) {
        if (spot == null || args.length > 2) return usage(sender);

        String action = args.length == 1
                ? "set"
                : args[1].toLowerCase(Locale.ROOT);

        LoginLocationStore locations = plugin.getLoginLocations();
        switch (action) {
            case "set", "edit", "editar" -> {
                if (!(sender instanceof Player player)) {
                    plugin.getMessages().send(sender, "console.player-only");
                    return true;
                }
                if (!player.isOnGround()) {
                    plugin.getMessages().send(sender, "admin.location-stand-on-block");
                    return true;
                }
                if (!locations.set(spot, LoginLocationStore.standingPosition(player))) {
                    plugin.getMessages().send(sender, "admin.location-save-error");
                } else {
                    plugin.getMessages().send(sender, "admin.location-set",
                            Map.of("type", spot.key(), "world", player.getWorld().getName()));
                }
                return true;
            }
            case "remove", "delete", "clear", "borrar" -> {
                if (locations.get(spot) == null) {
                    plugin.getMessages().send(sender, "admin.location-unset",
                            Map.of("type", spot.key()));
                } else if (!locations.clear(spot)) {
                    plugin.getMessages().send(sender, "admin.location-save-error");
                } else {
                    plugin.getMessages().send(sender, "admin.location-removed",
                            Map.of("type", spot.key()));
                }
                return true;
            }
            case "show", "info", "ver" -> {
                LoginLocationStore.SavedLocation point = locations.get(spot);
                if (point == null) {
                    plugin.getMessages().send(sender, "admin.location-unset",
                            Map.of("type", spot.key()));
                    return true;
                }
                plugin.getMessages().send(sender, "admin.location-info", Map.of(
                        "type", spot.key(),
                        "world", point.worldName(),
                        "x", String.format(Locale.ROOT, "%.1f", point.x()),
                        "y", String.format(Locale.ROOT, "%.1f", point.y()),
                        "z", String.format(Locale.ROOT, "%.1f", point.z())
                ));
                return true;
            }
            case "tp", "teleport" -> {
                if (!(sender instanceof Player player)) {
                    plugin.getMessages().send(sender, "console.player-only");
                    return true;
                }
                if (locations.teleport(player, spot)) {
                    plugin.getMessages().send(sender, "admin.location-teleported",
                            Map.of("type", spot.key()));
                } else {
                    plugin.getMessages().send(sender, "admin.location-unavailable",
                            Map.of("type", spot.key()));
                }
                return true;
            }
            default -> {
                return usage(sender);
            }
        }
    }

    private boolean usage(CommandSender sender) {
        plugin.getMessages().send(sender, "admin.usage");
        return true;
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessages().send(sender, "admin.help-header");
        plugin.getMessages().send(sender, "admin.help-commands");
        plugin.getMessages().send(sender, "admin.help-spawnlogin");
        plugin.getMessages().send(sender, "admin.help-loginready");
        plugin.getMessages().send(sender, "admin.help-location-actions");
        plugin.getMessages().send(sender, "admin.help-premium-cancel");
        plugin.getMessages().send(sender, "admin.help-premium-check");
        plugin.getMessages().send(sender, "admin.help-reset-messages");
    }

    private void sendStatus(CommandSender sender) {
        Paper1201SmartAuthAdapter adapter = plugin.getSmartAuthAdapter();
        boolean active = adapter != null && adapter.isEnabled();
        plugin.getMessages().send(sender, "admin.status-header");
        plugin.getMessages().send(sender, "admin.status-server", Map.of(
                "version", plugin.getDescription().getVersion(),
                "minecraft", plugin.getServer().getMinecraftVersion(),
                "online", plugin.getServer().getOnlineMode()
        ));
        plugin.getMessages().send(sender, "admin.status-premium", Map.of(
                "state", active ? "ACTIVE" : "INACTIVE",
                "auto", plugin.getConfig().getBoolean(
                        "smart-auth.auto-login-registered-premium", true),
                "lookup", plugin.getConfig().getBoolean(
                        "smart-auth.challenge-if-premium-nickname", true)
        ));
        plugin.getMessages().send(sender, "admin.status-auth-mode", Map.of(
                "mode", plugin.getConfig().getBoolean(
                        "smart-auth.require-register-before-premium", false)
                        ? "REGISTER + /premium" : "AUTO PREMIUM"
        ));
        plugin.getMessages().send(sender, "admin.status-premium-detail", Map.of(
                "configured", plugin.getConfig().getBoolean("smart-auth.enabled", true),
                "reason", plugin.getSmartAuthStatusDetail()
        ));
        plugin.getMessages().send(sender, "admin.status-locations", Map.of(
                "login", plugin.getLoginLocations().get(LoginLocationStore.Spot.SPAWNLOGIN) == null
                        ? "UNSET" : "SET",
                "ready", plugin.getLoginLocations().get(LoginLocationStore.Spot.LOGINREADY) == null
                        ? "UNSET" : "SET"
        ));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command command,
                                      String alias,
                                      String[] args) {
        if (!sender.hasPermission("slogin.admin")) return List.of();
        if (args.length == 1) return startsWith(ROOT, args[0]);
        if (args.length == 2
                && ("spawnlogin".equalsIgnoreCase(args[0])
                    || "loginready".equalsIgnoreCase(args[0]))) {
            return startsWith(LOCATION_ACTIONS, args[1]);
        }
        return List.of();
    }

    private static List<String> startsWith(List<String> values, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : values) {
            if (candidate.startsWith(prefix)) result.add(candidate);
        }
        return result;
    }
}
