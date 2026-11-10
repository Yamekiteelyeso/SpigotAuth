package com.example.spigotauth;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistent, passwordless account registry for identities already verified
 * by MinecraftSessionService. Never call ensureAccount with a profile obtained
 * from the public Mojang profile lookup alone.
 */
public final class PremiumAccountStore {

    private final File file;
    private final Logger logger;
    private final YamlConfiguration data;

    public PremiumAccountStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("No se pudo crear el directorio de cuentas Premium.");
        }
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private static String path(UUID uuid) {
        return "premium." + uuid;
    }

    public synchronized boolean isRegistered(UUID uuid) {
        return data.contains(path(uuid));
    }

    public synchronized String getLastKnownName(UUID uuid) {
        return data.getString(path(uuid) + ".name");
    }

    /**
     * Creates a record on first verified login or updates the last known name.
     * Returns false if persistence fails.
     */
    public synchronized boolean ensureAccount(UUID verifiedUuid, String verifiedName) {
        if (verifiedUuid == null || verifiedName == null
                || !verifiedName.matches("[A-Za-z0-9_]{3,16}")) {
            return false;
        }

        String base = path(verifiedUuid);
        String oldName = data.getString(base + ".name");
        String oldFirstSeen = data.getString(base + ".first-seen");
        data.set(base + ".name", verifiedName);
        if (oldFirstSeen == null) {
            data.set(base + ".first-seen", Instant.now().toString());
        }
        data.set(base + ".last-seen", Instant.now().toString());

        try {
            data.save(file);
            return true;
        } catch (IOException exception) {
            data.set(base + ".name", oldName);
            data.set(base + ".first-seen", oldFirstSeen);
            logger.severe("No se pudo guardar premium-accounts.yml: " + exception.getMessage());
            return false;
        }
    }
}
