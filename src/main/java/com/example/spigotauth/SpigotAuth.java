package com.example.spigotauth;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class SpigotAuth extends JavaPlugin {

    private AccountStore accounts;
    private PremiumChecker premium;
    private AuthManager auth;
    private PremiumAccountStore premiumAccounts;
    private PremiumAuthService premiumAuth;
    private PremiumPreferenceStore premiumPreferences;
    private PreAuthGuard preAuthGuard;
    private Paper1201SmartAuthAdapter smartAuthAdapter;
    private String smartAuthStartupError;
    private MessageService messages;
    private LoginLocationStore loginLocations;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages = new MessageService(this);

        accounts = new AccountStore(new File(getDataFolder(), "accounts.yml"), getLogger());
        premium = new PremiumChecker(this,
                getConfig().getLong("premium-cache-minutes", 10) * 60_000L);
        loginLocations = new LoginLocationStore(
                new File(getDataFolder(), "locations.yml"), getLogger());
        preAuthGuard = new PreAuthGuard(this);
        auth = new AuthManager(this);
        premiumAccounts = new PremiumAccountStore(
                new File(getDataFolder(), "premium-accounts.yml"), getLogger());
        premiumPreferences = new PremiumPreferenceStore(
                new File(getDataFolder(), "premium-preferences.yml"), getLogger());
        premiumAuth = new PremiumAuthService(
                this, premiumAccounts, premiumPreferences, accounts, auth);
        getServer().getPluginManager().registerEvents(new AuthListener(this), this);

        if (!getServer().getOnlineMode() && getConfig().getBoolean("smart-auth.enabled", true)) {
            smartAuthAdapter = new Paper1201SmartAuthAdapter(this);
            if (!smartAuthAdapter.enable()) {
                smartAuthStartupError = smartAuthAdapter.failureReason();
                getLogger().severe("SLogin Smart Authentication no pudo activarse; se mantiene el login local.");
                smartAuthAdapter = null;
            }
        }

        AuthCommand executor = new AuthCommand(this);
        AccountManagementCommand accountManagement = new AccountManagementCommand(this);
        SLoginCommand admin = new SLoginCommand(this);

        // Every label/alias is declared in plugin.yml; fail plugin startup
        // loudly instead of leaving a half-working authentication service.
        if (!bindCommand("register", executor)
                || !bindCommand("login", executor)
                || !bindCommand("changepass", accountManagement)
                || !bindCommand("premium", accountManagement)
                || !bindCommand("slogin", admin)) {
            getLogger().severe("Registro de comandos incompleto; desactivando SLogin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("SLogin v1.9.4 — Synex Login activado. Idioma: " + messages.language());
        if (!getServer().getOnlineMode() && smartAuthAdapter != null) {
            getLogger().info("Premium Smart Auth activo: "
                    + (getConfig().getBoolean(
                            "smart-auth.require-register-before-premium", false)
                            ? "nuevos jugadores registran cuenta local antes de /premium."
                            : "las cuentas verificadas entran sin /register ni /login."));
        }
    }

    private boolean bindCommand(String name, CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("El comando /" + name + " no existe en plugin.yml");
            return false;
        }
        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter completer) {
            command.setTabCompleter(completer);
        }
        getLogger().info("Comando registrado: /" + name
                + " (alias: " + command.getAliases() + ")");
        return true;
    }

    /** Restores only messages.yml and keeps a backup of the previous texts. */
    public boolean restoreDefaultMessages() {
        return messages != null && messages.restoreDefaults();
    }

    public void reloadSLoginConfiguration() {
        reloadConfig();
        if (messages != null) messages.reload();
        if (loginLocations != null) loginLocations.reload();

        if (premium != null) {
            premium.setCacheMillis(
                    getConfig().getLong("premium-cache-minutes", 10) * 60_000L
            );
        }

        boolean shouldUseSmartAuth = !getServer().getOnlineMode()
                && getConfig().getBoolean("smart-auth.enabled", true);

        if (!shouldUseSmartAuth) {
            smartAuthStartupError = null;
            if (smartAuthAdapter != null) {
                smartAuthAdapter.disable();
                smartAuthAdapter = null;
                getLogger().info("SLogin Smart Auth desactivado por configuración.");
            }
            return;
        }

        if (smartAuthAdapter == null || !smartAuthAdapter.isEnabled()) {
            Paper1201SmartAuthAdapter replacement =
                    new Paper1201SmartAuthAdapter(this);
            if (replacement.enable()) {
                smartAuthStartupError = null;
                smartAuthAdapter = replacement;
                getLogger().info("SLogin Smart Auth activado tras reload.");
            } else {
                smartAuthStartupError = replacement.failureReason();
                smartAuthAdapter = null;
                getLogger().severe(
                        "SLogin Smart Auth no pudo activarse tras reload: " + smartAuthStartupError
                );
            }
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

    public Paper1201SmartAuthAdapter getSmartAuthAdapter() {
        return smartAuthAdapter;
    }

    /** Explains why enabled:true does not necessarily mean ACTIVE at runtime. */
    public String getSmartAuthStatusDetail() {
        if (!getConfig().getBoolean("smart-auth.enabled", true)) {
            return "smart-auth.enabled=false en config.yml";
        }
        if (getServer().getOnlineMode()) {
            return "online-mode=true: Minecraft ya verifica las cuentas oficiales";
        }
        if (!"1.20.1".equals(getServer().getMinecraftVersion())) {
            return "requiere Paper 1.20.1; detectado " + getServer().getMinecraftVersion();
        }
        if (smartAuthAdapter != null && smartAuthAdapter.isEnabled()) {
            return "challenge y verificacion Mojang preparados";
        }
        return smartAuthStartupError != null
                ? smartAuthStartupError
                : "adaptador inactivo; revisa el error de arranque en latest.log";
    }

    public MessageService getMessages() {
        return messages;
    }

    public PremiumPreferenceStore getPremiumPreferences() {
        return premiumPreferences;
    }

    public PreAuthGuard getPreAuthGuard() {
        return preAuthGuard;
    }

    public LoginLocationStore getLoginLocations() {
        return loginLocations;
    }
}
