package com.example.spigotauth;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PluginMetadataTest {

    private static YamlConfiguration load(String name) {
        var in = PluginMetadataTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(in, "Missing resource: " + name);
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test
    void allUserCommandsAndAliasesAreDeclared() {
        var meta = load("plugin.yml");
        assertEquals("SLogin", meta.getString("name"));

        for (String name : new String[]{
                "login", "register", "premium", "changepass", "slogin"}) {
            assertTrue(meta.isConfigurationSection("commands." + name),
                    "Unregistered /" + name);
        }
        assertTrue(meta.getStringList("commands.login.aliases").contains("l"));
        assertTrue(meta.getStringList("commands.register.aliases").contains("r"));
        assertTrue(meta.isConfigurationSection("permissions.slogin.admin"));
    }

    @Test
    void teleportSettingsAndBilingualMessagesExist() {
        var config = load("config.yml");
        assertTrue(config.getBoolean("teleports.on-join-to-spawnlogin"));
        assertTrue(config.getBoolean("teleports.after-auth-to-loginready"));
        assertTrue(config.getLong("security.attempt-window-seconds") > 0);
        for (String locale : new String[]{"es", "en"}) {
            for (String key : new String[]{
                    "admin.premium-check-usage",
                    "admin.premium-check-start",
                    "admin.premium-check-found",
                    "admin.premium-check-missing",
                    "admin.premium-check-unavailable",
                    "premium.verification-invalid",
                    "premium.verification-unavailable",
                    "admin.location-set",
                    "admin.location-removed",
                    "admin.location-unset",
                    "admin.location-save-error",
                    "admin.location-stand-on-block",
                    "admin.location-teleported",
                    "admin.location-info",
                    "admin.status-server",
                    "admin.status-locations",
                    "admin.help-spawnlogin",
                    "admin.help-loginready",
                    "premium.verified",
                    "login.prompt",
                    "register.prompt"
            }) {
                assertTrue(config.contains("messages." + locale + "." + key),
                        "Missing " + locale + " message: " + key);
            }
        }
    }
}
