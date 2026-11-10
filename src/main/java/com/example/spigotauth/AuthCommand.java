package com.example.spigotauth;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class AuthCommand implements CommandExecutor {

    private final SpigotAuth plugin;

    public AuthCommand(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Este comando solo puede utilizarse dentro del juego.");
            return true;
        }

        AuthManager auth = plugin.getAuth();
        AccountStore accounts = plugin.getAccounts();
        String commandName = command.getName();

        if (auth.isAuthenticated(player)) {
            player.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.GRAY + "Tu sesión ya está iniciada.");
            return true;
        }

        if (commandName.equalsIgnoreCase("premiumlogin")) {
            if (plugin.getServer().getOnlineMode()) {
                player.sendMessage(ChatColor.GREEN + "El servidor ya verifica tu cuenta directamente con Minecraft.");
                return true;
            }
            plugin.getPremiumOwnership().start(player);
            return true;
        }

        String name = player.getName();
        if (auth.isLocked(name)) {
            player.sendMessage(ChatColor.RED + "✖ " + ChatColor.GRAY
                    + "Has superado el número de intentos permitidos. Inténtalo más tarde.");
            return true;
        }

        if (commandName.equalsIgnoreCase("register")) {
            if (accounts.isRegistered(name)) {
                player.sendMessage(ChatColor.YELLOW + "Ya tienes una cuenta. Utiliza "
                        + ChatColor.AQUA + "/login <contraseña>");
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(ChatColor.YELLOW + "Uso: "
                        + ChatColor.WHITE + "/register <contraseña> <confirmar>");
                return true;
            }
            int min = plugin.getConfig().getInt("min-password-length", 8);
            int max = plugin.getConfig().getInt("max-password-length", 64);
            if (args[0].length() < min || args[0].length() > max) {
                player.sendMessage(ChatColor.RED + "La contraseña debe tener entre "
                        + min + " y " + max + " caracteres.");
                return true;
            }
            if (!args[0].equals(args[1])) {
                player.sendMessage(ChatColor.RED + "Las contraseñas no coinciden. Revísalas e inténtalo de nuevo.");
                return true;
            }
            if (!accounts.register(name, args[0])) {
                player.sendMessage(ChatColor.RED + "No se pudo guardar tu cuenta. Contacta a un administrador.");
                return true;
            }
            auth.markAuthenticated(player);
            player.sendMessage(ChatColor.GRAY + "Tu cuenta quedó registrada correctamente.");
            return true;
        }

        if (commandName.equalsIgnoreCase("login")) {
            if (!accounts.isRegistered(name)) {
                player.sendMessage(ChatColor.YELLOW + "Todavía no tienes una cuenta. Utiliza "
                        + ChatColor.AQUA + "/register <contraseña> <confirmar>");
                return true;
            }
            if (args.length != 1) {
                player.sendMessage(ChatColor.YELLOW + "Uso: "
                        + ChatColor.WHITE + "/login <contraseña>");
                return true;
            }
            if (accounts.check(name, args[0])) {
                auth.markAuthenticated(player);
                player.sendMessage(ChatColor.GRAY + "¡Qué bueno tenerte de vuelta!");
            } else {
                auth.registerFailure(player);
            }
            return true;
        }
        return false;
    }
}
