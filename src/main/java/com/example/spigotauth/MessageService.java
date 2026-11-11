package com.example.spigotauth;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public final class MessageService {
    private final SpigotAuth plugin;
    private final File file;
    private YamlConfiguration messages;

    public MessageService(SpigotAuth plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        try (var stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                var defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                messages.setDefaults(defaults);
                messages.options().copyDefaults(true);
                messages.save(file);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "No se pudo actualizar messages.yml", exception);
        }
    }

    public String raw(String path) {
        String value = messages.getString(path, "&cMensaje no configurado: " + path);
        String prefix = messages.getString("prefix", "&8[&bSpigotAuth&8] &r");
        return color(value.replace("{prefix}", prefix));
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
        for (String line : messages.getStringList(path)) {
            String text = color(line.replace("{prefix}", messages.getString("prefix", "")));
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
