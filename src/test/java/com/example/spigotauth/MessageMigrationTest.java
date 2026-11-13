package com.example.spigotauth;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MessageMigrationTest {
    @Test
    void keepsExistingCustomMessagesButAddsNewDefaults() {
        var stream = getClass().getClassLoader().getResourceAsStream("messages.yml");
        assertNotNull(stream);
        var bundled = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        var old = new YamlConfiguration();
        old.set("messages.es.auth.registered", "&aMi mensaje personalizado");
        old.set("messages.en.login.prompt", java.util.List.of("Custom line"));
        old.set("messages.es.custom.special", "Texto propio");
        var migrated = MessageService.mergeLegacyMessages(
                bundled, old.getConfigurationSection("messages"));

        assertEquals("&aMi mensaje personalizado",
                migrated.getString("es.auth.registered"));
        assertEquals(java.util.List.of("Custom line"),
                migrated.getStringList("en.login.prompt"));
        assertEquals("Texto propio", migrated.getString("es.custom.special"));
        assertTrue(migrated.contains("es.premium.not-owner"));
        assertTrue(migrated.contains("en.admin.status-auth-mode"));
    }

    @Test
    void freshInstallationUsesOnlyBundledMessages() {
        var bundled = new YamlConfiguration();
        bundled.set("es.prefix", "Original");
        var migrated = MessageService.mergeLegacyMessages(bundled, null);
        assertEquals("Original", migrated.getString("es.prefix"));
        assertFalse(migrated.contains("messages.es.prefix"));
    }
}
