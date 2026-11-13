package ci.fixture;

import com.mojang.authlib.GameProfile;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;

/**
 * CI-ONLY test plugin: swaps the SLogin verifier with a fake session service.
 * This class is built into a completely separate fixture JAR. It is NEVER
 * included in SLogin's reobfuscated production JAR.
 */
public final class SessionStubPlugin extends JavaPlugin implements Listener {

    private static final Map<String, UUID> TEST_PROFILES = Map.of(
            "premiumpending", UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "ci_premiumfresh", UUID.fromString("22222222-2222-4222-8222-222222222222")
    );

    public static final class FakeSessionService {
        public GameProfile hasJoinedServer(GameProfile requested,
                                           String serverHash,
                                           InetAddress remoteAddress) {
            if (requested == null || requested.name() == null
                    || serverHash == null
                    || !serverHash.matches("-?[a-fA-F0-9]{1,40}")) return null;
            UUID expected = TEST_PROFILES.get(
                    requested.name().toLowerCase(java.util.Locale.ROOT));
            if (expected == null) return null;
            return new GameProfile(expected, requested.name());
        }
    }

    @Override
    public void onEnable() {
        try {
            Plugin sl = getServer().getPluginManager().getPlugin("SLogin");
            if (sl == null || !sl.isEnabled())
                throw new IllegalStateException("SLogin did not start");

            Object adapter = sl.getClass().getMethod("getSmartAuthAdapter").invoke(sl);
            if (adapter == null) throw new IllegalStateException("Smart Auth inactive");

            Field field = adapter.getClass().getDeclaredField("sessionVerifier");
            field.setAccessible(true);

            Class<?> verifierClass = sl.getClass().getClassLoader()
                    .loadClass("com.example.spigotauth.VanillaSessionVerifier");
            Constructor<?> constructor = verifierClass.getDeclaredConstructor(Object.class);
            constructor.setAccessible(true);

            Object oldVerifier = field.get(adapter);
            Object mockedVerifier = constructor.newInstance(new FakeSessionService());
            field.set(adapter, mockedVerifier);

            if (oldVerifier != null) {
                Method close = verifierClass.getDeclaredMethod("close");
                close.setAccessible(true);
                close.invoke(oldVerifier);
            }

            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("CI_ONLY_SESSION_STUB_INSTALLED (not in production JAR)");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("CI fixture could not install test verifier", failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID expected = TEST_PROFILES.get(player.getName().toLowerCase(java.util.Locale.ROOT));
        if (expected == null) return;
        try {
            Plugin sl = getServer().getPluginManager().getPlugin("SLogin");
            Object auth = sl.getClass().getMethod("getAuth").invoke(sl);
            boolean authenticated = (boolean) auth.getClass()
                    .getMethod("isAuthenticated", Player.class)
                    .invoke(auth, player);
            getLogger().info("CI_PREMIUM_JOIN name=" + player.getName()
                    + " uuid=" + player.getUniqueId()
                    + " authenticated=" + authenticated);
        } catch (ReflectiveOperationException ex) {
            getLogger().severe("CI_PREMIUM_JOIN introspection failed: " + ex);
        }
    }
}
