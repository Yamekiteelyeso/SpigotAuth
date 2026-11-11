package com.example.spigotauth;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;

public final class SpigotAuth extends JavaPlugin {

    private AccountStore accounts;
    private PremiumChecker premium;
    private AuthManager auth;
    private PremiumAccountStore premiumAccounts;
    private PremiumAuthService premiumAuth;
    private MinecraftSessionService sessionService;

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
        long sessionTimeoutMs = Math.max(1000L,
                getConfig().getLong("smart-auth.session-timeout-ms", 5000L));
        sessionService = new MinecraftSessionService(Duration.ofMillis(sessionTimeoutMs));

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
            getLogger().warning("Servidor en offline-mode: las cuentas locales están protegidas, "
                    + "pero Smart Authentication Premium seguirá desactivado hasta que el adaptador "
                    + "pre-login de Paper 1.20.1 esté instalado y validado.");
        }
    }

    @Override
    public void onDisable() {
        if (auth != null) auth.shutdown();
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

    public MinecraftSessionService getSessionService() {
        return sessionService;
    }
}
