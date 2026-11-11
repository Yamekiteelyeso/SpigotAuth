package com.example.spigotauth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistent, optional teleports for pre-authentication and post-authentication.
 * Coordinates are stored independently of config.yml, so /slogin reload never
 * resets the locations. All mutations use atomic write-and-swap semantics.
 */
public final class LoginLocationStore {

    public enum Spot {
        SPAWNLOGIN("spawnlogin"),
        LOGINREADY("loginready");

        private final String key;

        Spot(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Spot from(String raw) {
            for (Spot spot : values()) {
                if (spot.key.equalsIgnoreCase(raw)) return spot;
            }
            return null;
        }
    }

    public record SavedLocation(String worldId,
                                String worldName,
                                double x,
                                double y,
                                double z,
                                float yaw,
                                float pitch) {

        public SavedLocation {
            if (worldName == null || worldName.isBlank()
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Ubicación inválida.");
            }
        }

        static SavedLocation of(Location location) {
            World world = location.getWorld();
            if (world == null) {
                throw new IllegalArgumentException("La ubicación necesita un mundo.");
            }
            return new SavedLocation(world.getUID().toString(), world.getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
        }

        Location resolve() {
            World world = null;
            try {
                if (worldId != null) world = Bukkit.getWorld(UUID.fromString(worldId));
            } catch (IllegalArgumentException ignored) {
                // A renamed or regenerated world can still be resolved by name.
            }
            if (world == null) world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }
    }

    private final File file;
    private final Logger logger;
    private YamlConfiguration data;

    public LoginLocationStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private static String base(Spot spot) {
        return "locations." + spot.key();
    }

    public synchronized SavedLocation get(Spot spot) {
        String path = base(spot);
        if (!data.isConfigurationSection(path)) return null;

        String name = data.getString(path + ".world");
        if (name == null || name.isBlank()) return null;

        try {
            return new SavedLocation(
                    data.getString(path + ".world-uuid"),
                    name,
                    data.getDouble(path + ".x"),
                    data.getDouble(path + ".y"),
                    data.getDouble(path + ".z"),
                    (float) data.getDouble(path + ".yaw"),
                    (float) data.getDouble(path + ".pitch")
            );
        } catch (IllegalArgumentException exception) {
            logger.warning("Ubicación SLogin inválida en " + spot.key() + ": "
                    + exception.getMessage());
            return null;
        }
    }

    /** Center at the feet of a player standing on a block, preserving view. */
    public static Location standingPosition(Player player) {
        Location feet = player.getLocation();
        return new Location(feet.getWorld(),
                feet.getBlockX() + 0.5,
                feet.getBlockY(),
                feet.getBlockZ() + 0.5,
                feet.getYaw(), feet.getPitch());
    }

    public synchronized boolean set(Spot spot, Location location) {
        return write(spot, SavedLocation.of(location));
    }

    public synchronized boolean save(Spot spot, SavedLocation location) {
        if (location == null) return false;
        return write(spot, location);
    }

    public synchronized boolean clear(Spot spot) {
        return write(spot, null);
    }

    private boolean write(Spot spot, SavedLocation point) {
        YamlConfiguration next = new YamlConfiguration();
        try {
            next.loadFromString(data.saveToString());
        } catch (InvalidConfigurationException exception) {
            logger.severe("No se pudo copiar la configuración de ubicaciones: "
                    + exception.getMessage());
            return false;
        }

        String path = base(spot);
        next.set(path, null);
        if (point != null) {
            next.set(path + ".world", point.worldName());
            next.set(path + ".world-uuid", point.worldId());
            next.set(path + ".x", point.x());
            next.set(path + ".y", point.y());
            next.set(path + ".z", point.z());
            next.set(path + ".yaw", point.yaw());
            next.set(path + ".pitch", point.pitch());
        }

        if (!AtomicYamlFile.save(next, file, logger, "locations.yml")) return false;
        data = next;
        return true;
    }

    public boolean teleport(Player player, Spot spot) {
        SavedLocation saved = get(spot);
        if (saved == null) return false;
        Location location = saved.resolve();
        if (location == null) {
            logger.warning("No se encontró el mundo '" + saved.worldName()
                    + "' para /slogin " + spot.key());
            return false;
        }
        // Player teleport stays on the main server thread. Plugin-caused
        // teleport is permitted by the pre-auth guard, other causes are not.
        return player.teleport(location);
    }
}
