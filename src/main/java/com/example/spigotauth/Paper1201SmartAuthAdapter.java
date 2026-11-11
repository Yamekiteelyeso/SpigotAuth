package com.example.spigotauth;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.bukkit.craftbukkit.v1_20_R1.CraftServer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Paper 1.20.1-only network adapter.
 *
 * It intercepts Login Start before the vanilla login listener consumes it.
 * Premium candidates are moved into vanilla's KEY state and receive the
 * vanilla-compatible Encryption Request. The subsequent Encryption Response
 * is deliberately handled by ServerLoginPacketListenerImpl itself, preserving
 * Mojang session verification, encryption setup and duplicate-login checks.
 */
public final class Paper1201SmartAuthAdapter {

    private final SpigotAuth plugin;
    private final Logger logger;
    private final Set<String> challenged = ConcurrentHashMap.newKeySet();
    private final Set<Channel> installedChannels = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;

    public Paper1201SmartAuthAdapter(SpigotAuth plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean enable() {
        if (!"1.20.1".equals(plugin.getServer().getMinecraftVersion())) {
            logger.severe("Smart Auth requiere exactamente Paper 1.20.1.");
            return false;
        }
        if (!(plugin.getServer() instanceof CraftServer craftServer)) {
            logger.severe("Smart Auth requiere Paper/CraftBukkit 1.20.1.");
            return false;
        }
        if (plugin.getServer().getOnlineMode()) {
            logger.info("Smart Auth selectivo no es necesario en online-mode.");
            return false;
        }

        MinecraftServer server = craftServer.getServer();
        ServerConnectionListener listener = server.getConnection();
        if (listener == null) {
            logger.severe("No se encontró ServerConnectionListener.");
            return false;
        }

        enabled = true;
        // Connections are registered before login completes. Polling every tick
        // installs the packet interceptor on their Netty event loop. A connection
        // that has already passed Login Start is intentionally left untouched.
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled) return;
            for (Connection connection : List.copyOf(listener.getConnections())) {
                install(connection);
            }
        }, 0L, 1L);

        logger.info("Smart Authentication Paper 1.20.1 adapter activado.");
        return true;
    }

    public void disable() {
        enabled = false;
        challenged.clear();
        installedChannels.clear();
    }

    public boolean consumeVerifiedJoin(String name, UUID uuid) {
        if (name == null || uuid == null) return false;
        return challenged.remove(name.toLowerCase(java.util.Locale.ROOT));
    }

    private void install(Connection connection) {
        try {
            Field channelField = Connection.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            Channel channel = (Channel) channelField.get(connection);
            if (channel == null || !channel.isActive() || !installedChannels.add(channel)) return;

            channel.eventLoop().execute(() -> {
                if (!channel.isActive()) return;
                if (channel.pipeline().get("spigotauth-smart-login") != null) return;
                if (channel.pipeline().get("packet_handler") == null) return;
                channel.pipeline().addBefore("packet_handler", "spigotauth-smart-login",
                        new LoginStartInterceptor(connection));
            });
        } catch (ReflectiveOperationException exception) {
            logger.severe("No se pudo instalar Smart Auth en una conexión: " + exception.getMessage());
        }
    }

    private final class LoginStartInterceptor extends ChannelInboundHandlerAdapter {
        private final Connection connection;
        private boolean decided;

        private LoginStartInterceptor(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (decided || !(msg instanceof ServerboundHelloPacket hello)) {
                super.channelRead(ctx, msg);
                return;
            }
            decided = true;
            String name = hello.name();

            // Hold subsequent login packets while the profile discovery request
            // is pending. The callback returns to the channel event loop.
            ctx.channel().config().setAutoRead(false);
            plugin.getPremium().check(name, result -> ctx.channel().eventLoop().execute(() -> {
                try {
                    switch (result.status()) {
                        case NOT_FOUND -> {
                            ctx.fireChannelRead(msg);
                            ctx.channel().config().setAutoRead(true);
                            ctx.read();
                        }
                        case UNAVAILABLE -> {
                            if (plugin.getPremiumAccounts().isProtectedName(name)) {
                                connection.disconnect(Component.literal(
                                        "No se pudo verificar tu cuenta Premium. Inténtalo de nuevo."));
                            } else {
                                ctx.fireChannelRead(msg);
                                ctx.channel().config().setAutoRead(true);
                                ctx.read();
                            }
                        }
                        case PREMIUM -> beginVanillaChallenge(hello, result.profileId());
                    }
                } catch (Throwable failure) {
                    logger.warning("Smart Auth rechazó " + name + ": "
                            + failure.getClass().getSimpleName());
                    connection.disconnect(Component.literal(
                            "No se pudo iniciar la verificación Premium."));
                }
            }));
        }

        private void beginVanillaChallenge(ServerboundHelloPacket hello, UUID discoveredUuid)
                throws ReflectiveOperationException {
            if (!(connection.getPacketListener() instanceof ServerLoginPacketListenerImpl login)) {
                connection.disconnect(Component.literal("Estado de login no compatible."));
                return;
            }

            MinecraftServer server = ((CraftServer) plugin.getServer()).getServer();
            Field stateField = findStateField();
            Field profileField = findField(ServerLoginPacketListenerImpl.class, GameProfile.class);
            Field challengeField = findField(ServerLoginPacketListenerImpl.class, byte[].class);
            Field serverIdField = findField(ServerLoginPacketListenerImpl.class, String.class);

            Object keyState = enumConstant(stateField.getType(), "KEY");
            byte[] challenge = ((byte[]) challengeField.get(login)).clone();
            String serverId = (String) serverIdField.get(login);

            // The discovered UUID is only a classification hint. Vanilla's
            // handleKey/session-service path must replace/confirm the identity.
            profileField.set(login, new GameProfile(discoveredUuid, hello.name()));
            stateField.set(login, keyState);
            challenged.add(hello.name().toLowerCase(java.util.Locale.ROOT));

            connection.send(new ClientboundHelloPacket(
                    serverId,
                    server.getKeyPair().getPublic().getEncoded(),
                    challenge));
            ctxResume();
        }

        private void ctxResume() {
            Channel channel = connectionChannel();
            if (channel != null && channel.isActive()) {
                channel.config().setAutoRead(true);
                channel.read();
            }
        }

        private Channel connectionChannel() {
            try {
                Field field = Connection.class.getDeclaredField("channel");
                field.setAccessible(true);
                return (Channel) field.get(connection);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
    }

    private static Field findStateField() throws ReflectiveOperationException {
        for (Field field : ServerLoginPacketListenerImpl.class.getDeclaredFields()) {
            if (field.getType().isEnum()) {
                for (Object constant : field.getType().getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals("KEY")) {
                        field.setAccessible(true);
                        return field;
                    }
                }
            }
        }
        throw new NoSuchFieldException("login state");
    }

    private static Field findField(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException(type.getName());
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object value : type.getEnumConstants()) {
            if (((Enum<?>) value).name().equals(name)) return value;
        }
        throw new IllegalStateException("Estado NMS no encontrado: " + name);
    }
}
