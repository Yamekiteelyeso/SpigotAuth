package com.example.spigotauth;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Completes passwordless premium account handling after a trusted login adapter
 * has obtained a VerifiedProfile from MinecraftSessionService.
 *
 * This class does not verify packets itself. The login adapter is responsible
 * for ensuring that the profile came from a successful hasJoined response for
 * this exact connection.
 */
public final class PremiumAuthService {

    private final PremiumAccountStore accounts;
    private final AuthManager auth;

    public PremiumAuthService(PremiumAccountStore accounts, AuthManager auth) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.auth = Objects.requireNonNull(auth, "auth");
    }

    /**
     * Auto-registers the verified Mojang UUID on first login and authenticates
     * this live Bukkit session. Call only on the server thread and only after
     * protocol-level verification for this connection.
     */
    public boolean authenticateVerified(Player player,
                                         MinecraftSessionService.VerifiedProfile profile) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(profile, "profile");

        if (!accounts.ensureAccount(profile.uuid(), profile.name())) {
            player.kickPlayer(ChatColor.RED
                    + "No se pudo guardar tu identidad Premium. Inténtalo más tarde.");
            return false;
        }

        auth.markAuthenticated(player);
        player.sendMessage(ChatColor.GOLD + "★ " + ChatColor.WHITE
                + "Cuenta Premium verificada: " + ChatColor.YELLOW + profile.name());
        return true;
    }
}
