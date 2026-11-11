package com.example.spigotauth;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class MessageService {
    private final SpigotAuth plugin;

    public MessageService(SpigotAuth plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    public String language() {
        String configured = plugin.getConfig().getString("language", "es");
        if (configured == null) return "es";
        String normalized = configured.toLowerCase(Locale.ROOT);
        return normalized.equals("en") ? "en" : "es";
    }

    private String base() {
        return "messages." + language() + ".";
    }

    public String raw(String path) {
        String value = plugin.getConfig().getString(
                base() + path,
                plugin.getConfig().getString("messages.es." + path,
                        "&cMissing message: " + path)
        );
        String prefix = plugin.getConfig().getString(
                base() + "prefix",
                "&8[&bSLogin&8] &r"
        );
        return color((value == null ? "" : value).replace("{prefix}", prefix));
    }

    public String format(String path, Map<String, ?> placeholders) {
        String text = raw(path);
        for (var entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return text;
    }

    public void send(CommandSender target, String path) {
        String text = raw(path);
        if (!text.isBlank()) target.sendMessage(text);
    }

    public void send(CommandSender target, String path, Map<String, ?> placeholders) {
        String text = format(path, placeholders);
        if (!text.isBlank()) target.sendMessage(text);
    }

    public void sendLines(Player player, String path, Map<String, ?> placeholders) {
        List<String> lines = plugin.getConfig().getStringList(base() + path);
        if (lines.isEmpty() && !language().equals("es")) {
            lines = plugin.getConfig().getStringList("messages.es." + path);
        }
        String prefix = plugin.getConfig().getString(base() + "prefix", "&8[&bSLogin&8] &r");

        for (String line : lines) {
            String text = color(line.replace("{prefix}", prefix == null ? "" : prefix));
            for (var entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
            if (!text.isBlank()) player.sendMessage(text);
        }
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}
