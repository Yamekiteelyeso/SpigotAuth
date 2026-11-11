package com.example.spigotauth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public final class AccountManagementCommand implements CommandExecutor, TabCompleter {

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

        // /premium is available ONLY to players who previously registered
        // and authenticated using their local password.
        if (!plugin.getAccounts().isRegistered(name)) {
            plugin.getMessages().send(player, "premium.command-register-first");
            return true;
        }

        if (plugin.getPremiumPreferences().isPending(name)) {
            plugin.getMessages().send(player, "premium.command-pending");
            return true;
        }

        Paper1201SmartAuthAdapter adapter = plugin.getSmartAuthAdapter();
        if (plugin.getServer().getOnlineMode() || adapter == null || !adapter.isEnabled()) {
            plugin.getMessages().send(player, "premium.command-disabled");
            return true;
        }

        // A nickname lookup does not prove account ownership. It can also
        // return false negatives during Mojang throttling/outages. The real
        // proof happens at the next login via encrypted hasJoinedServer.
        if (!plugin.getPremiumPreferences().requestUnresolved(name)) {
            plugin.getMessages().send(player, "premium.command-save-error");
            return true;
        }

        plugin.getMessages().sendLines(player, "premium.command-ready",
                java.util.Map.of("player", name));

        // Ask the user to reconnect explicitly: surprise-kicking can look like
        // /premium is broken. The local password remains stored until official
        // session verification succeeds.
        return true;
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        // Never suggest player names or old passwords for credential commands.
        return List.of();
    }

}
