package com.example.spigotauth;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AuthCommand implements CommandExecutor {

    private final SpigotAuth plugin;

    public AuthCommand(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo jugadores pueden usar este comando.");
            return true;
        }

        AuthManager auth = plugin.getAuth();
        AccountStore accounts = plugin.getAccounts();
        String name = p.getName();

        if (auth.isLocked(name)) {
            p.sendMessage(ChatColor.RED + "Tu cuenta está bloqueada temporalmente por intentos fallidos.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("register")) {
            if (auth.isAuthenticated(p)) {
                p.sendMessage(ChatColor.YELLOW + "Ya has iniciado sesión.");
                return true;
            }
            if (accounts.isRegistered(name)) {
                p.sendMessage(ChatColor.YELLOW + "Ya tienes una cuenta. Usa /login <contraseña>.");
                return true;
            }
            if (args.length != 2) {
                p.sendMessage(ChatColor.RED + "Uso: /register <contraseña> <confirmar>");
                return true;
            }
            int min = plugin.getConfig().getInt("min-password-length", 6);
            if (args[0].length() < min) {
                p.sendMessage(ChatColor.RED + "La contraseña debe tener al menos " + min + " caracteres.");
                return true;
            }
            if (!args[0].equals(args[1])) {
                p.sendMessage(ChatColor.RED + "Las contraseñas no coinciden.");
                return true;
            }
            accounts.register(name, args[0]);
            auth.markAuthenticated(p);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("login")) {
            if (auth.isAuthenticated(p)) {
                p.sendMessage(ChatColor.YELLOW + "Ya has iniciado sesión.");
                return true;
            }
            if (!accounts.isRegistered(name)) {
                p.sendMessage(ChatColor.YELLOW + "No tienes cuenta. Usa /register <contraseña> <confirmar>.");
                return true;
            }
            if (args.length != 1) {
                p.sendMessage(ChatColor.RED + "Uso: /login <contraseña>");
                return true;
            }
            if (accounts.check(name, args[0])) {
                auth.markAuthenticated(p);
            } else {
                auth.registerFailure(p);
            }
            return true;
        }

        return false;
    }
}
