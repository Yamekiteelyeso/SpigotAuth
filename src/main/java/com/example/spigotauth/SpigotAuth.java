package com.example.spigotauth;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SpigotAuth extends JavaPlugin {

    private AccountStore accounts;
    private PremiumChecker premium;
    private AuthManager auth;
    private PremiumAccountStore premiumAccounts;
    private PremiumAuthService premiumAuth;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        accounts = new AccountStore(new File(getDataFolder(), "accounts.yml"), getLogger());
        premium = new PremiumChecker(this,
                getConfig().getLong("premium-cache-minutes", 10) * 60_000L);
        auth = new AuthManager(this);
        premiumAccounts = new PremiumAccountStore(
                new File(getDataFolder(), "premium-accounts.yml"), getLogger());
        premiumAuth = new PremiumAuthService(premiumAccounts, auth);

        getServer().getPluginManager().registerEvents(new AuthListener(this), this);

        AuthCommand executor = new AuthCommand(this);
        if (getCommand("register") == null || getCommand("login") == null) {
            getLogger().severe("Faltan los comandos register/login en plugin.yml. Desactivando SpigotAuth.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("register").setExecutor(executor);
        getCommand("login").setExecutor(executor);

        getLogger().info("SpigotAuth v1.1.0 activado correctamente.");
        if (!getServer().getOnlineMode()) {
            getLogger().warning("El servidor usa offline-mode: la detección premium es informativa y no autentica propietarios.");
        }
    }

    public AccountStore getAccounts() {
        return accounts;
    }

    public PremiumChecker getPremium() {
        return premium;
    }

    public AuthManager getAuth() {
        return auth;
    }

    public PremiumAccountStore getPremiumAccounts() {
        return premiumAccounts;
    }

    public PremiumAuthService getPremiumAuth() {
        return premiumAuth;
    }
}
