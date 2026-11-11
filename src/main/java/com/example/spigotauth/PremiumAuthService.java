package com.example.spigotauth;

import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Finalizes a passwordless Premium login only after the network adapter has
 * proven that the Bukkit player's UUID matches the Mojang UUID challenged for
 * this connection.
 */
public final class PremiumAuthService {

    private final SpigotAuth plugin;
    private final PremiumAccountStore accounts;
    private final AuthManager auth;

    public PremiumAuthService(SpigotAuth plugin, PremiumAccountStore accounts, AuthManager auth) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.auth = Objects.requireNonNull(auth, "auth");
    }

    public boolean authenticateVerified(Player player,
                                        MinecraftSessionService.VerifiedProfile profile) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(profile, "profile");

        if (!player.getUniqueId().equals(profile.uuid())
                || !player.getName().equalsIgnoreCase(profile.name())) {
            plugin.getLogger().warning("SLogin rechazó una identidad Premium inconsistente para "
                    + player.getName());
            return false;
        }

        // This is NOT password registration. It only persists the verified
        // Mojang UUID/name binding so the Premium identity stays protected.
        if (!accounts.ensureAccount(profile.uuid(), profile.name())) {
            player.kickPlayer(plugin.getMessages().raw("premium.save-error"));
            return false;
        }

        auth.markAuthenticated(player);
        return true;
    }
}
