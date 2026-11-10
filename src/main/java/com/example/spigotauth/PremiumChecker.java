package com.example.spigotauth;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PremiumChecker {

    private record Entry(boolean premium, long expires) {}

    private final SpigotAuth plugin;
    private final long cacheMillis;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public PremiumChecker(SpigotAuth plugin, long cacheMillis) {
        this.plugin = plugin;
        this.cacheMillis = cacheMillis;
    }

    /** Consulta si el nombre es de una cuenta premium. El callback se ejecuta en el hilo principal. */
    public void check(String name, Consumer<Boolean> callback) {
        String key = name.toLowerCase();
        Entry cached = cache.get(key);
        if (cached != null && cached.expires() > System.currentTimeMillis()) {
            callback.accept(cached.premium());
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Boolean result = queryMojang(name);
            if (result != null) {
                cache.put(key, new Entry(result, System.currentTimeMillis() + cacheMillis));
            }
            boolean value = Boolean.TRUE.equals(result);
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(value));
        });
    }

    /** true = premium, false = no existe, null = error de red (no se cachea). */
    private Boolean queryMojang(String name) {
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200) return true;
            if (code == 204 || code == 404) return false;
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
