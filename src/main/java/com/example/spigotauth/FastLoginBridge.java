package com.example.spigotauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Connects SpigotAuth to FastLogin without compiling against FastLogin internals.
 *
 * FastLogin performs the protocol/session-server verification. This bridge only
 * receives FastLogin's verified result and applies SpigotAuth's account policy.
 */
public final class FastLoginBridge {

    private static final String FASTLOGIN_PLUGIN = "FastLogin";
    private static final String AUTH_PLUGIN_API =
            "com.github.games647.fastlogin.core.hooks.AuthPlugin";

    private final SpigotAuth plugin;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile boolean installed;

    public FastLoginBridge(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    public boolean install() {
        if (plugin.getServer().getOnlineMode()) {
            return false;
        }

        Plugin fastLogin = Bukkit.getPluginManager().getPlugin(FASTLOGIN_PLUGIN);
        if (fastLogin == null || !fastLogin.isEnabled()) {
            plugin.getLogger().warning("FastLogin no está instalado. El acceso premium automático permanecerá desactivado.");
            return false;
        }

        try {
            Object core = fastLogin.getClass().getMethod("getCore").invoke(fastLogin);
            if (core == null) {
                throw new IllegalStateException("FastLogin todavía no ha inicializado su API.");
            }

            ClassLoader loader = fastLogin.getClass().getClassLoader();
            Class<?> hookType = Class.forName(AUTH_PLUGIN_API, true, loader);
            Object existingHook = core.getClass().getMethod("getAuthPluginHook").invoke(core);
            if (existingHook != null) {
                plugin.getLogger().warning("FastLogin ya tiene otro hook de autenticación. SpigotAuth no lo reemplazará.");
                return false;
            }

            InvocationHandler handler = this::invokeHook;
            Object hook = Proxy.newProxyInstance(loader, new Class<?>[]{hookType}, handler);
            Method setter = core.getClass().getMethod("setAuthPluginHook", hookType);
            setter.invoke(core, hook);

            installed = true;
            plugin.getLogger().info("Integración FastLogin instalada: SpigotAuth gestionará el auto-login premium.");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            plugin.getLogger().severe("No se pudo integrar FastLogin: " + exception.getMessage());
            plugin.getLogger().warning("El login premium automático seguirá desactivado por seguridad.");
            return false;
        }
    }

    public boolean isInstalled() {
        return installed;
    }

    public String getVerifiedStatus(Player player) {
        if (!installed || player == null) {
            return "UNKNOWN";
        }
        try {
            Plugin fastLogin = Bukkit.getPluginManager().getPlugin(FASTLOGIN_PLUGIN);
            if (fastLogin == null || !fastLogin.isEnabled()) {
                return "UNKNOWN";
            }
            Object status = fastLogin.getClass()
                    .getMethod("getStatus", UUID.class)
                    .invoke(fastLogin, player.getUniqueId());
            return status == null ? "UNKNOWN" : status.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            return "UNKNOWN";
        }
    }

    /**
     * If a player had to use their existing local password once, bind that
     * account only when FastLogin independently reports a verified premium
     * session for the same online UUID.
     */
    public void linkAfterPasswordAuthentication(Player player) {
        if (!installed || player == null || !player.isOnline()) {
            return;
        }

        try {
            Plugin fastLogin = Bukkit.getPluginManager().getPlugin(FASTLOGIN_PLUGIN);
            if (fastLogin == null || !fastLogin.isEnabled()) {
                return;
            }

            Object status = fastLogin.getClass()
                    .getMethod("getStatus", UUID.class)
                    .invoke(fastLogin, player.getUniqueId());
            if (status == null || !"PREMIUM".equalsIgnoreCase(status.toString())) {
                return;
            }

            String name = player.getName();
            UUID premiumUuid = player.getUniqueId();
            if (!plugin.getAccounts().isRegistered(name)) {
                return;
            }
            if (!plugin.getPremiumAccounts().canLink(name, premiumUuid)) {
                plugin.getLogger().warning("No se vinculó " + name
                        + ": el nombre ya pertenece a otro UUID premium.");
                return;
            }
            if (!plugin.getPremiumAccounts().isLinked(name, premiumUuid)
                    && plugin.getPremiumAccounts().link(name, premiumUuid)) {
                player.sendMessage(org.bukkit.ChatColor.GREEN
                        + "Tu cuenta local quedó vinculada a tu identidad premium verificada.");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            plugin.getLogger().warning("No se pudo comprobar el estado premium para vincular "
                    + player.getName() + ": " + exception.getMessage());
        }
    }

    private Object invokeHook(Object proxy, Method method, Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "isRegistered" -> plugin.getAccounts().isRegistered((String) args[0]);
            case "forceLogin" -> authenticatePremium((Player) args[0], null);
            case "forceRegister" -> authenticatePremium((Player) args[0], (String) args[1]);
            case "toString" -> "SpigotAuthFastLoginHook";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Método FastLogin no soportado: " + method.getName());
        };
    }

    private synchronized boolean authenticatePremium(Player player, String suppliedPassword) {
        if (player == null || !runOnMainThread(player::isOnline)) {
            return false;
        }

        String name = player.getName();
        UUID premiumUuid = player.getUniqueId();
        PremiumAccountStore links = plugin.getPremiumAccounts();
        AccountStore accounts = plugin.getAccounts();

        // Never let a verified premium session silently take over an existing
        // offline account that has not been explicitly linked to this UUID.
        if (accounts.isRegistered(name)) {
            if (!links.isLinked(name, premiumUuid)) {
                plugin.getLogger().warning("Se bloqueó el auto-login premium de " + name
                        + ": la cuenta local no está vinculada a su UUID premium.");
                return false;
            }
        } else {
            if (!links.canLink(name, premiumUuid)) {
                plugin.getLogger().warning("Se bloqueó el registro premium de " + name
                        + ": el nombre ya está vinculado a otro UUID.");
                return false;
            }

            String password = suppliedPassword == null || suppliedPassword.isBlank()
                    ? generatePassword() : suppliedPassword;
            if (!accounts.register(name, password)) {
                // A concurrent registration may have created the account. It
                // still must be linked explicitly before any automatic login.
                if (!accounts.isRegistered(name) || !links.isLinked(name, premiumUuid)) {
                    return false;
                }
            } else if (!links.link(name, premiumUuid)) {
                plugin.getLogger().severe("La cuenta premium " + name
                        + " se registró, pero no se pudo guardar su vínculo de UUID.");
                return false;
            }
        }

        return runOnMainThread(() -> {
            if (!player.isOnline()) {
                return false;
            }
            if (!plugin.getAuth().isAuthenticated(player)) {
                plugin.getAuth().markAuthenticated(player);
            }
            return true;
        });
    }

    private boolean runOnMainThread(Callable<Boolean> action) {
        try {
            if (Bukkit.isPrimaryThread()) {
                return action.call();
            }
            return Bukkit.getScheduler().callSyncMethod(plugin, action).get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            plugin.getLogger().warning("No se pudo completar el auto-login premium en el hilo principal: "
                    + exception.getMessage());
            return false;
        }
    }

    private String generatePassword() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
