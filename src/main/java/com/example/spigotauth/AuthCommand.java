package com.example.spigotauth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class AuthCommand implements CommandExecutor {

    private final SpigotAuth plugin;

    public AuthCommand(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "console.player-only");
            return true;
        }

        AuthManager auth = plugin.getAuth();
        AccountStore accounts = plugin.getAccounts();
        String name = player.getName();

        if (auth.isAuthenticated(player)) {
            plugin.getMessages().send(player, "auth.already-authenticated");
            return true;
        }

        if (auth.isLocked(name)) {
            plugin.getMessages().send(player, "auth.locked",
                    java.util.Map.of("seconds", auth.remainingLockSeconds(name)));
            return true;
        }

        if (command.getName().equalsIgnoreCase("register")) {
            if (accounts.isRegistered(name)) {
                plugin.getMessages().send(player, "register.already-registered");
                return true;
            }
            if (args.length != 2) {
                plugin.getMessages().send(player, "register.usage");
                return true;
            }

            int min = plugin.getConfig().getInt("security.min-password-length", 8);
            int max = plugin.getConfig().getInt("security.max-password-length", 64);
            if (args[0].length() < min || args[0].length() > max) {
                plugin.getMessages().send(player, "register.length",
                        java.util.Map.of("min", min, "max", max));
                return true;
            }
            if (!args[0].equals(args[1])) {
                plugin.getMessages().send(player, "register.mismatch");
                return true;
            }
            if (!accounts.register(name, args[0])) {
                plugin.getMessages().send(player, "register.save-error");
                return true;
            }

            auth.markAuthenticated(player);
            plugin.getMessages().send(player, "auth.registered");
            offerPremiumQuestion(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("login")) {
            if (!accounts.isRegistered(name)) {
                plugin.getMessages().send(player, "login.not-registered");
                return true;
            }
            if (args.length != 1) {
                plugin.getMessages().send(player, "login.usage");
                return true;
            }
            if (accounts.check(name, args[0])) {
                auth.markAuthenticated(player);
                plugin.getMessages().send(player, "auth.logged-in");
                offerPremiumQuestion(player);
            } else {
                auth.registerFailure(player);
            }
            return true;
        }

        return false;
    }

    private void offerPremiumQuestion(Player player) {
        if (!plugin.getConfig().getBoolean("smart-auth.premium-question", true)) return;

        String name = player.getName();
        if (!plugin.getAccounts().isRegistered(name)) return;
        if (plugin.getPremiumAccounts().findVerifiedUuidByName(name) != null) return;
        if (plugin.getPremiumPreferences().expectedUuid(name) != null) return;

        plugin.getPremium().check(name, result -> {
            if (!player.isOnline() || !plugin.getAuth().isAuthenticated(player)) return;
            if (result.status() == PremiumChecker.Status.PREMIUM) {
                plugin.getMessages().sendLines(
                        player,
                        "premium.question",
                        java.util.Map.of("player", name)
                );
            }
        });
    }
}
