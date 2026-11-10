package com.example.spigotauth;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class SpigotAuth extends JavaPlugin {

    private static SpigotAuth instance;
    private AccountStore accounts;
    private PremiumChecker premium;
    private AuthManager auth;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        accounts = new AccountStore(new File(getDataFolder(), "accounts.yml"));
        premium = new PremiumChecker(this, getConfig().getLong("premium-cache-minutes", 10) * 60_000L);
        auth = new AuthManager(this);

        getServer().getPluginManager().registerEvents(new AuthListener(this), this);

        AuthCommand cmd = new AuthCommand(this);
        getCommand("register").setExecutor(cmd);
        getCommand("login").setExecutor(cmd);

        getLogger().info("SpigotAuth activado.");
    }

    public static SpigotAuth getInstance() { return instance; }
    public AccountStore getAccounts() { return accounts; }
    public PremiumChecker getPremium() { return premium; }
    public AuthManager getAuth() { return auth; }
}
