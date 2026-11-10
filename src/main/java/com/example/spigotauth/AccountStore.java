package com.example.spigotauth;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;

public final class AccountStore {

    private final File file;
    private final YamlConfiguration config;
    private final Logger logger;

    public AccountStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("No se pudo crear el directorio de datos de SpigotAuth.");
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    private String key(String name) {
        return "accounts." + name.toLowerCase(Locale.ROOT);
    }

    public synchronized boolean isRegistered(String name) {
        return config.contains(key(name));
    }

    public synchronized boolean register(String name, String password) {
        if (isRegistered(name)) return false;
        String path = key(name);
        config.set(path, PasswordUtil.hash(password));
        try {
            config.save(file);
            return true;
        } catch (IOException exception) {
            config.set(path, null);
            logger.severe("No se pudo guardar accounts.yml: " + exception.getMessage());
            return false;
        }
    }

    public synchronized boolean check(String name, String password) {
        String stored = config.getString(key(name));
        return stored != null && PasswordUtil.verify(password, stored);
    }
}
