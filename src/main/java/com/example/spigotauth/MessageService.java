package com.example.spigotauth;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Messages live in plugins/SLogin/messages.yml, independent of config.yml.
 * Existing config.yml messages are migrated once without overwriting custom
 * texts, and the old config receives a dated backup before cleanup.
 */
public final class MessageService {
    private final SpigotAuth plugin;
    private final File messagesFile;
    private volatile YamlConfiguration data;

    public MessageService(SpigotAuth plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");

        if (!messagesFile.isFile()) {
            YamlConfiguration builtIn = bundledDefaults();
            ConfigurationSection legacy =
                    plugin.getConfig().getConfigurationSection("messages");
            YamlConfiguration initial = mergeLegacyMessages(builtIn, legacy);
            if (!AtomicYamlFile.save(initial, messagesFile,
                    plugin.getLogger(), "messages.yml")) {
                throw new IllegalStateException(
                        "No se pudo crear messages.yml; config.yml no será modificado.");
            }
            plugin.getLogger().info(legacy == null
                    ? "SLogin creó messages.yml con los mensajes predeterminados."
                    : "SLogin migró tus mensajes personalizados a messages.yml.");
        }

        if (!reload()) {
            throw new IllegalStateException("messages.yml no se pudo cargar.");
        }
        removeLegacyMessagesAfterBackup();
    }

    /**
     * Produces a standalone copy, preserving every old custom ES/EN key.
     * Missing keys are populated from the new bundled messages.yml.
     */
    static YamlConfiguration mergeLegacyMessages(
            YamlConfiguration builtIn, ConfigurationSection legacy) {
        YamlConfiguration merged = new YamlConfiguration();
        for (String key : builtIn.getKeys(true)) {
            if (!builtIn.isConfigurationSection(key)) {
                merged.set(key, builtIn.get(key));
            }
        }
        if (legacy != null) {
            for (String key : legacy.getKeys(true)) {
                if (!legacy.isConfigurationSection(key)) {
                    merged.set(key, legacy.get(key));
                }
            }
        }
        return merged;
    }

    private YamlConfiguration bundledDefaults() {
        try (InputStream resource = plugin.getResource("messages.yml")) {
            if (resource == null) {
                throw new IllegalStateException("Falta messages.yml dentro del JAR.");
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resource, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Error leyendo messages.yml predeterminado.", exception);
        }
    }

    private void removeLegacyMessagesAfterBackup() {
        if (!plugin.getConfig().isConfigurationSection("messages")) return;

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            if (configFile.isFile()) {
                File backup = new File(plugin.getDataFolder(),
                        "config.yml.backup-messages-" + System.currentTimeMillis());
                Files.copy(configFile.toPath(), backup.toPath());
                plugin.getLogger().info("Respaldo creado: " + backup.getName());
            }
            plugin.getConfig().set("messages", null);
            plugin.saveConfig();
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "No se limpió config.yml; tus mensajes ya están en messages.yml.",
                    exception);
        }
    }

    public boolean reload() {
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.load(messagesFile);
            loaded.setDefaults(bundledDefaults());
            loaded.options().copyDefaults(false);
            this.data = loaded;
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "messages.yml es inválido; conservando los mensajes anteriores.",
                    exception);
            return false;
        }
    }

    public boolean restoreDefaults() {
        try {
            if (messagesFile.isFile()) {
                File backup = new File(plugin.getDataFolder(),
                        "messages.yml.backup-" + System.currentTimeMillis());
                Files.copy(messagesFile.toPath(), backup.toPath());
                plugin.getLogger().info("Respaldo de mensajes: " + backup.getName());
            }
            if (!AtomicYamlFile.save(bundledDefaults(), messagesFile,
                    plugin.getLogger(), "messages.yml")) return false;
            return reload();
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "No se pudieron restaurar los mensajes.", exception);
            return false;
        }
    }

    public String language() {
        String configured = plugin.getConfig().getString("language", "es");
        if (configured == null) return "es";
        return configured.toLowerCase(Locale.ROOT).equals("en") ? "en" : "es";
    }

    private String base() {
        return language() + ".";
    }

    public String raw(String path) {
        YamlConfiguration messages = data;
        String value = messages.getString(
                base() + path,
                messages.getString("es." + path, "&cMissing message: " + path));
        String prefix = messages.getString(
                base() + "prefix", "&8[&bSLogin&8] &r");
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
        YamlConfiguration messages = data;
        List<String> lines = messages.getStringList(base() + path);
        if (lines.isEmpty() && !language().equals("es")) {
            lines = messages.getStringList("es." + path);
        }
        String prefix = messages.getString(
                base() + "prefix", "&8[&bSLogin&8] &r");

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
