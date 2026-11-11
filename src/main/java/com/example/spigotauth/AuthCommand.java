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
            } else {
                auth.registerFailure(player);
            }
            return true;
        }

        return false;
    }
}
