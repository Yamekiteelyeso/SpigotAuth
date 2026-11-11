package com.example.spigotauth;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Stores an explicit Premium preference for a username.
 * A preference never authenticates a player: it only tells the pre-login
 * adapter which Mojang UUID must successfully complete the encryption/session
 * challenge on the next connection.
 */
public final class PremiumPreferenceStore {

    private final File file;
    private final Logger logger;
    private final YamlConfiguration data;

    public PremiumPreferenceStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("No se pudo crear el directorio de SLogin.");
        }
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private static String base(String name) {
        return "names." + name.toLowerCase(Locale.ROOT);
    }

    public synchronized UUID expectedUuid(String name) {
        if (name == null) return null;
        String raw = data.getString(base(name) + ".uuid");
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            logger.warning("UUID Premium preferido inválido para " + name + ".");
            return null;
        }
    }

    public synchronized boolean isVerified(String name) {
        return data.getBoolean(base(name) + ".verified", false);
    }

    public synchronized boolean request(String name, UUID expectedUuid) {
        return write(name, expectedUuid, false);
    }

    public synchronized boolean confirm(String name, UUID verifiedUuid) {
        return write(name, verifiedUuid, true);
    }

    public synchronized boolean clear(String name) {
        if (name == null) return false;
        String path = base(name);
        Object old = data.get(path);
        if (old == null) return true;

        data.set(path, null);
        if (AtomicYamlFile.save(data, file, logger, "premium-preferences.yml")) {
            return true;
        }
        data.set(path, old);
        return false;
    }

    private boolean write(String name, UUID uuid, boolean verified) {
        if (name == null || uuid == null || !name.matches("[A-Za-z0-9_]{3,16}")) return false;

        String path = base(name);
        String oldUuid = data.getString(path + ".uuid");
        Object oldVerified = data.get(path + ".verified");

        data.set(path + ".uuid", uuid.toString());
        data.set(path + ".verified", verified);

        try {
            data.save(file);
            return true;
        } catch (IOException exception) {
            data.set(path + ".uuid", oldUuid);
            data.set(path + ".verified", oldVerified);
            logger.severe("No se pudo guardar premium-preferences.yml: " + exception.getMessage());
            return false;
        }
    }
}
