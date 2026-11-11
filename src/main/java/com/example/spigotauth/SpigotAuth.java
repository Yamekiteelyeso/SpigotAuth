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
    private Paper1201SmartAuthAdapter smartAuthAdapter;
    private MessageService messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new MessageService(this);

        accounts = new AccountStore(new File(getDataFolder(), "accounts.yml"), getLogger());
        premium = new PremiumChecker(this,
                getConfig().getLong("premium-cache-minutes", 10) * 60_000L);
        auth = new AuthManager(this);
        premiumAccounts = new PremiumAccountStore(
                new File(getDataFolder(), "premium-accounts.yml"), getLogger());
        premiumAuth = new PremiumAuthService(this, premiumAccounts, auth);
        long sessionTimeoutMs = Math.max(1000L,
                getConfig().getLong("smart-auth.session-timeout-ms", 5000L));
        sessionService = new MinecraftSessionService(Duration.ofMillis(sessionTimeoutMs));

        getServer().getPluginManager().registerEvents(new AuthListener(this), this);

        if (!getServer().getOnlineMode() && getConfig().getBoolean("smart-auth.enabled", true)) {
            smartAuthAdapter = new Paper1201SmartAuthAdapter(this);
            if (!smartAuthAdapter.enable()) {
                getLogger().severe("SLogin Smart Authentication no pudo activarse; se mantiene el login local.");
                smartAuthAdapter = null;
            }
        }

        AuthCommand executor = new AuthCommand(this);
        if (getCommand("register") == null || getCommand("login") == null || getCommand("slogin") == null) {
            getLogger().severe("Faltan comandos en plugin.yml. Desactivando SLogin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("register").setExecutor(executor);
        getCommand("login").setExecutor(executor);
        getCommand("slogin").setExecutor(new SLoginCommand(this));

        getLogger().info("SLogin v1.3.0 — Synex Login activado. Idioma: " + messages.language());
        if (!getServer().getOnlineMode() && smartAuthAdapter != null) {
            getLogger().info("Premium Smart Auth activo: las cuentas verificadas entran sin /register ni /login.");
        }
    }

    @Override
    public void onDisable() {
        if (smartAuthAdapter != null) smartAuthAdapter.disable();
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

    public Paper1201SmartAuthAdapter getSmartAuthAdapter() {
        return smartAuthAdapter;
    }

    public MessageService getMessages() {
        return messages;
    }
}
