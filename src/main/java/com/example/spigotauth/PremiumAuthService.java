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

        boolean wasPendingMigration = premiumPreferences.isPending(profile.name());

        // Once protocol verification succeeds, future logins can challenge this
        // exact UUID immediately without relying on the public profile lookup.
        if (!premiumPreferences.confirm(profile.name(), profile.uuid())) {
            player.kickPlayer(plugin.getMessages().raw("premium.save-error"));
            return false;
        }

        // Name collisions are possible on mixed-mode servers. A verified
        // Mojang identity does not own an existing LOCAL account merely
        // because its username matches. Preserve the local password so we
        // never destroy another player's account on automatic Premium login.
        // Only an explicit /premium migration can retire a local password,
        // after official proof and separate player-data migration policy.
        if (wasPendingMigration) {
            plugin.getLogger().info("Migración Premium confirmada para "
                    + profile.name() + "; contraseña local conservada para recuperación.");
        }

        auth.markAuthenticated(player);
        return true;
    }
}
