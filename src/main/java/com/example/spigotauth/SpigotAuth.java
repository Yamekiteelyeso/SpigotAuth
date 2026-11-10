package com.example.spigotauth;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SpigotAuth extends JavaPlugin {

    private AccountStore accounts;
    private PremiumAccountStore premiumAccounts;
    private PremiumChecker premium;
    private AuthManager auth;
    private FastLoginBridge fastLoginBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        accounts = new AccountStore(new File(getDataFolder(), "accounts.yml"), getLogger());
        premiumAccounts = new PremiumAccountStore(
                new File(getDataFolder(), "premium-accounts.yml"), getLogger());
        premium = new PremiumChecker(this,
                getConfig().getLong("premium-cache-minutes", 10) * 60_000L);
        auth = new AuthManager(this);
        fastLoginBridge = new FastLoginBridge(this);

        if (!getServer().getOnlineMode() && getConfig().getBoolean("smart-auth.enabled", true)) {
            fastLoginBridge.install();
        }

        getServer().getPluginManager().registerEvents(new AuthListener(this), this);

        AuthCommand executor = new AuthCommand(this);
        if (getCommand("register") == null || getCommand("login") == null) {
            getLogger().severe("Faltan los comandos register/login en plugin.yml. Desactivando SpigotAuth.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("register").setExecutor(executor);
        getCommand("login").setExecutor(executor);

        getLogger().info("SpigotAuth v1.2.0 activado correctamente.");
        if (!getServer().getOnlineMode() && !fastLoginBridge.isInstalled()) {
            getLogger().warning("Smart Authentication no está activo. Los jugadores deberán usar autenticación local.");
        }
    }

    public AccountStore getAccounts() {
        return accounts;
    }

    public PremiumAccountStore getPremiumAccounts() {
        return premiumAccounts;
    }

    public PremiumChecker getPremium() {
        return premium;
    }

    public AuthManager getAuth() {
        return auth;
    }

    public FastLoginBridge getFastLoginBridge() {
        return fastLoginBridge;
    }
}
