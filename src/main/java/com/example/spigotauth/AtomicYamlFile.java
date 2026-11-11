package com.example.spigotauth;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

final class AtomicYamlFile {

    private AtomicYamlFile() {}

    static boolean save(YamlConfiguration yaml,
                        File file,
                        Logger logger,
                        String label) {
        Path target = file.toPath();
        Path parent = target.toAbsolutePath().getParent();
        Path temp = null;

        try {
            if (parent != null) Files.createDirectories(parent);

            temp = Files.createTempFile(
                    parent,
                    file.getName() + ".",
                    ".tmp"
            );

            Files.writeString(
                    temp,
                    yaml.saveToString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            try (FileChannel channel =
                         FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            return true;
        } catch (IOException exception) {
            logger.severe("No se pudo guardar " + label + ": "
                    + exception.getMessage());
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
