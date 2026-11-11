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
    private final PremiumAccountStore premiumAccounts;
    private final PremiumPreferenceStore premiumPreferences;
    private final AccountStore localAccounts;
    private final AuthManager auth;

    public PremiumAuthService(SpigotAuth plugin,
                              PremiumAccountStore premiumAccounts,
                              PremiumPreferenceStore premiumPreferences,
                              AccountStore localAccounts,
                              AuthManager auth) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.premiumAccounts = Objects.requireNonNull(premiumAccounts, "premiumAccounts");
        this.premiumPreferences = Objects.requireNonNull(premiumPreferences, "premiumPreferences");
        this.localAccounts = Objects.requireNonNull(localAccounts, "localAccounts");
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

        if (!premiumAccounts.ensureAccount(profile.uuid(), profile.name())) {
            player.kickPlayer(plugin.getMessages().raw("premium.save-error"));
            return false;
        }

        // Once protocol verification succeeds, future logins can challenge this
        // exact UUID immediately without relying on the public profile lookup.
        premiumPreferences.confirm(profile.name(), profile.uuid());

        // A verified Premium identity must not keep a stale local password
        // account that could later be mistaken for an alternate auth route.
        localAccounts.remove(profile.name());

        auth.markAuthenticated(player);
        return true;
    }
}
