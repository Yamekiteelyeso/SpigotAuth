package com.example.spigotauth;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class AccountStore {

    private final File file;
    private final YamlConfiguration cfg;

    public AccountStore(File file) {
        this.file = file;
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    private String key(String name) {
        return "accounts." + name.toLowerCase();
    }

    public synchronized boolean isRegistered(String name) {
        return cfg.contains(key(name));
    }

    public synchronized boolean register(String name, String password) {
        if (isRegistered(name)) return false;
        cfg.set(key(name), PasswordUtil.hash(password));
        save();
        return true;
    }

    public synchronized boolean check(String name, String password) {
        String stored = cfg.getString(key(name));
        return stored != null && PasswordUtil.verify(password, stored);
    }

    private void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            SpigotAuth.getInstance().getLogger().severe("No se pudo guardar accounts.yml: " + e.getMessage());
        }
    }
}
