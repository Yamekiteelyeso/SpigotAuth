package com.example.spigotauth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public final class AccountManagementCommand implements CommandExecutor {

    private final SpigotAuth plugin;

    public AccountManagementCommand(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "console.player-only");
            return true;
        }

        if (!plugin.getAuth().isAuthenticated(player)) {
            return true;
        }

        if (command.getName().equalsIgnoreCase("changepass")) {
            return changePassword(player, args);
        }

        if (command.getName().equalsIgnoreCase("premium")) {
            return enablePremium(player, args);
        }

        return false;
    }

    private boolean changePassword(Player player, String[] args) {
        String name = player.getName();
        AccountStore accounts = plugin.getAccounts();

        if (!accounts.isRegistered(name)) {
            plugin.getMessages().send(player, "changepass.not-local");
            return true;
        }
        if (args.length != 2) {
            plugin.getMessages().send(player, "changepass.usage");
            return true;
        }

        int min = plugin.getConfig().getInt("security.min-password-length", 8);
        int max = plugin.getConfig().getInt("security.max-password-length", 64);
        if (args[1].length() < min || args[1].length() > max) {
            plugin.getMessages().send(player, "register.length",
                    Map.of("min", min, "max", max));
            return true;
        }

        if (!accounts.check(name, args[0])) {
            plugin.getMessages().send(player, "changepass.wrong-old");
            return true;
        }

        if (!accounts.changePassword(name, args[0], args[1])) {
            plugin.getMessages().send(player, "changepass.save-error");
            return true;
        }

        plugin.getMessages().send(player, "changepass.success");
        return true;
    }

    private boolean enablePremium(Player player, String[] args) {
        if (args.length != 0) {
            plugin.getMessages().send(player, "premium.command-usage");
            return true;
        }

        String name = player.getName();
        UUID alreadyVerified = plugin.getPremiumAccounts().findVerifiedUuidByName(name);
        if (alreadyVerified != null) {
            plugin.getMessages().send(player, "premium.command-already");
            return true;
        }

        plugin.getMessages().send(player, "premium.command-checking");

        plugin.getPremium().check(name, result -> {
            if (!player.isOnline()) return;

            switch (result.status()) {
                case NOT_FOUND -> plugin.getMessages().send(player, "premium.command-not-found");
                case UNAVAILABLE -> plugin.getMessages().send(player, "premium.command-unavailable");
                case PREMIUM -> {
                    UUID expected = result.profileId();
                    if (expected == null || !plugin.getPremiumPreferences().request(name, expected)) {
                        plugin.getMessages().send(player, "premium.command-save-error");
                        return;
                    }

                    plugin.getMessages().send(player, "premium.command-enabled");
                    if (plugin.getConfig().getBoolean(
                            "smart-auth.premium-command-reconnect", true)) {
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            if (player.isOnline()) {
                                player.kickPlayer(plugin.getMessages().raw(
                                        "premium.command-reconnect"));
                            }
                        }, 20L);
                    }
                }
            }
        });

        return true;
    }
}
