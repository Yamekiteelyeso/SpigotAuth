package com.example.spigotauth;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persists the link between a verified premium UUID and a SpigotAuth account.
 * A public username lookup is never sufficient to create this association.
 */
public final class PremiumAccountStore {

    private final File file;
    private final YamlConfiguration data;
    private final Logger logger;

    public PremiumAccountStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("No se pudo crear el directorio de datos premium.");
        }
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private String uuidPath(UUID uuid) {
        return "premium." + uuid + ".name";
    }

    private String namePath(String name) {
        return "names." + name.toLowerCase(Locale.ROOT);
    }

    public synchronized boolean canLink(String name, UUID uuid) {
        String existingUuid = data.getString(namePath(name));
        return existingUuid == null || existingUuid.equalsIgnoreCase(uuid.toString());
    }

    public synchronized boolean isLinked(String name, UUID uuid) {
        String linkedUuid = data.getString(namePath(name));
        String linkedName = data.getString(uuidPath(uuid));
        return uuid.toString().equalsIgnoreCase(linkedUuid)
                && name.equalsIgnoreCase(linkedName);
    }

    public synchronized boolean link(String name, UUID uuid) {
        if (!canLink(name, uuid)) {
            return false;
        }

        String oldName = data.getString(uuidPath(uuid));
        if (oldName != null && !oldName.equalsIgnoreCase(name)) {
            data.set(namePath(oldName), null);
        }

        data.set(uuidPath(uuid), name);
        data.set(namePath(name), uuid.toString());
        try {
            data.save(file);
            return true;
        } catch (IOException exception) {
            data.set(uuidPath(uuid), oldName);
            data.set(namePath(name), null);
            logger.severe("No se pudo guardar premium-accounts.yml: " + exception.getMessage());
            return false;
        }
    }
}
