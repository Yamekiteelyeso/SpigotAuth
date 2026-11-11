package com.example.spigotauth;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Map;

public final class SLoginCommand implements CommandExecutor {

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

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.getMessages().send(sender, "admin.reload-ok",
                    Map.of("language", plugin.getMessages().language()));
            return true;
        }

        sender.sendMessage("/slogin reload");
        return true;
    }
}
