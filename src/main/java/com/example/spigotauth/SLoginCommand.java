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
            plugin.reloadSLoginConfiguration();
            plugin.getMessages().send(sender, "admin.reload-ok",
                    Map.of("language", plugin.getMessages().language()));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            Paper1201SmartAuthAdapter adapter = plugin.getSmartAuthAdapter();
            boolean active = adapter != null && adapter.isEnabled();

            sender.sendMessage("§8§m--------------------------------");
            sender.sendMessage("§bSLogin §fRuntime Status");
            sender.sendMessage("§7Version: §f" + plugin.getDescription().getVersion());
            sender.sendMessage("§7Minecraft: §f" + plugin.getServer().getMinecraftVersion());
            sender.sendMessage("§7Server: §f" + plugin.getServer().getName());
            sender.sendMessage("§7online-mode: §f" + plugin.getServer().getOnlineMode());
            sender.sendMessage("§7Smart Auth: " + (active ? "§aACTIVE" : "§cINACTIVE"));
            sender.sendMessage("§7challenge-if-premium-nickname: §f"
                    + plugin.getConfig().getBoolean(
                    "smart-auth.challenge-if-premium-nickname", true));
            sender.sendMessage("§7premium-question: §f"
                    + plugin.getConfig().getBoolean(
                    "smart-auth.premium-question", true));
            sender.sendMessage("§7debug: §f"
                    + plugin.getConfig().getBoolean("smart-auth.debug", false));
            sender.sendMessage("§8§m--------------------------------");
            return true;
        }

        sender.sendMessage("/slogin reload");
        sender.sendMessage("/slogin status");
        return true;
    }
}
