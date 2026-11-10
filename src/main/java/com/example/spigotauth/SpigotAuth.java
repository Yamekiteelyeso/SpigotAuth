package com.example.spigotauth;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SpigotAuth extends JavaPlugin {

    private AccountStore accounts;
    private PremiumChecker premium;
    private PremiumOwnershipVerifier premiumOwnership;
    private AuthManager auth;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        accounts = new AccountStore(new File(getDataFolder(), "accounts.yml"), getLogger());
        premium = new PremiumChecker(this,
                getConfig().getLong("premium-cache-minutes", 10) * 60_000L);
        premiumOwnership = new PremiumOwnershipVerifier(this);
        auth = new AuthManager(this);

        getServer().getPluginManager().registerEvents(new AuthListener(this), this);

        AuthCommand executor = new AuthCommand(this);
        if (getCommand("register") == null || getCommand("login") == null
                || getCommand("premiumlogin") == null) {
            getLogger().severe("Faltan comandos en plugin.yml. Desactivando SpigotAuth.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("register").setExecutor(executor);
        getCommand("login").setExecutor(executor);
        getCommand("premiumlogin").setExecutor(executor);

        getLogger().info("SpigotAuth v1.2.0 activado correctamente.");
        if (!getServer().getOnlineMode()) {
            String clientId = getConfig().getString("microsoft-client-id", "").trim();
            if (clientId.isEmpty() || clientId.equalsIgnoreCase("PON_AQUI_TU_CLIENT_ID")) {
                getLogger().warning("Configura microsoft-client-id para habilitar la verificación premium real en offline-mode.");
            } else {
                getLogger().info("Verificación de propiedad Microsoft habilitada para offline-mode.");
            }
        }
    }

    public AccountStore getAccounts() { return accounts; }
    public PremiumChecker getPremium() { return premium; }
    public PremiumOwnershipVerifier getPremiumOwnership() { return premiumOwnership; }
    public AuthManager getAuth() { return auth; }
}
