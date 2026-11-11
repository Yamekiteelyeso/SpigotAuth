package com.example.spigotauth;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import net.kyori.adventure.key.Key;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.bukkit.craftbukkit.v1_20_R1.CraftServer;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Paper 1.20.1 selective Premium authentication.
 *
 * Paper invokes ChannelInitializeListener before the first Minecraft packet is
 * processed. This guarantees SLogin is present for Login Start instead of
 * racing against an already-active connection.
 */
public final class Paper1201SmartAuthAdapter {

    private static final Key INITIALIZER_KEY = Key.key("slogin", "smart-auth");
    private static final String HANDLER_NAME = "slogin-smart-auth";
    private static final long VERIFIED_TICKET_TTL_MS = 30_000L;

    private final SpigotAuth plugin;
    private final Logger logger;
    private final Map<String, PremiumTicket> premiumTickets = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    public Paper1201SmartAuthAdapter(SpigotAuth plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean enable() {
        if (!"1.20.1".equals(plugin.getServer().getMinecraftVersion())) {
            logger.severe("SLogin Smart Auth requiere exactamente Paper 1.20.1.");
            return false;
        }
        if (!(plugin.getServer() instanceof CraftServer)) {
            logger.severe("SLogin Smart Auth requiere Paper 1.20.1.");
            return false;
        }
        if (plugin.getServer().getOnlineMode()) {
            logger.info("SLogin detectó online-mode=true; Minecraft ya verifica las cuentas Premium.");
            return false;
        }

        try {
            ChannelInitializeListenerHolder.addListener(INITIALIZER_KEY, this::install);
            enabled = true;
            logger.info("SLogin Smart Auth activo mediante Paper ChannelInitializeListener.");
            return true;
        } catch (Throwable failure) {
            logger.log(Level.SEVERE, "No se pudo registrar el interceptor temprano de SLogin.", failure);
            return false;
        }
    }

    public void disable() {
        enabled = false;
        premiumTickets.clear();
        try {
            ChannelInitializeListenerHolder.removeListener(INITIALIZER_KEY);
        } catch (Throwable ignored) {
            // Server shutdown: best-effort cleanup.
        }
    }

    /**
     * Returns true only when the player joined with the exact Mojang UUID that
     * was discovered before the encryption/session challenge.
     */
    public boolean consumeVerifiedJoin(String name, UUID actualUuid) {
        if (name == null || actualUuid == null) return false;
        PremiumTicket ticket = premiumTickets.remove(normalize(name));
        if (ticket == null) return false;
        if (System.currentTimeMillis() > ticket.expiresAt()) return false;
        return ticket.expectedUuid().equals(actualUuid);
    }

    private void install(Channel channel) {
        if (!enabled || channel == null) return;
        channel.eventLoop().execute(() -> {
            try {
                if (!enabled || !channel.isActive()) return;
                if (channel.pipeline().get(HANDLER_NAME) != null) return;

                Object packetHandler = channel.pipeline().get("packet_handler");
                if (!(packetHandler instanceof Connection connection)) {
                    logger.warning("SLogin: la conexión no contiene el packet_handler esperado.");
                    return;
                }

                channel.pipeline().addBefore(
                        "packet_handler",
                        HANDLER_NAME,
                        new LoginStartInterceptor(connection)
                );
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "SLogin no pudo inyectar una conexión nueva.", failure);
            }
        });
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
            ctx.channel().config().setAutoRead(false);

            plugin.getPremium().check(name, result ->
                    ctx.channel().eventLoop().execute(() -> decide(ctx, msg, hello, result)));
        }

        private void decide(ChannelHandlerContext ctx,
                            Object originalPacket,
                            ServerboundHelloPacket hello,
                            PremiumChecker.Result result) {
            if (!ctx.channel().isActive()) return;

            try {
                switch (result.status()) {
                    case NOT_FOUND -> continueOffline(ctx, originalPacket);
                    case UNAVAILABLE -> {
                        boolean protectedName = plugin.getPremiumAccounts().isProtectedName(hello.name());
                        boolean failClosed = plugin.getConfig().getBoolean(
                                "smart-auth.fail-closed-on-lookup-error", false);
                        if (protectedName || failClosed) {
                            disconnect("premium.verification-unavailable");
                        } else {
                            continueOffline(ctx, originalPacket);
                        }
                    }
                    case PREMIUM -> beginPremiumChallenge(hello, result.profileId());
                }
            } catch (Throwable failure) {
                logger.log(Level.WARNING,
                        "SLogin rechazó el challenge Premium de " + hello.name(), failure);
                premiumTickets.remove(normalize(hello.name()));
                disconnect("premium.verification-start-error");
            }
        }

        private void continueOffline(ChannelHandlerContext ctx, Object originalPacket) {
            ctx.fireChannelRead(originalPacket);
            resume(ctx.channel());
        }

        private void beginPremiumChallenge(ServerboundHelloPacket hello, UUID expectedUuid)
                throws ReflectiveOperationException {
            if (expectedUuid == null) {
                throw new IllegalStateException("Mojang devolvió un perfil Premium sin UUID");
            }
            if (!(connection.getPacketListener() instanceof ServerLoginPacketListenerImpl login)) {
                disconnect("premium.incompatible-login-state");
                return;
            }

            MinecraftServer server = ((CraftServer) plugin.getServer()).getServer();
            Field stateField = findStateField();
            Field profileField = findField(ServerLoginPacketListenerImpl.class, GameProfile.class);
            Field challengeField = findField(ServerLoginPacketListenerImpl.class, byte[].class);
            Field serverIdField = findField(ServerLoginPacketListenerImpl.class, String.class);

            byte[] challenge = ((byte[]) challengeField.get(login)).clone();
            String serverId = (String) serverIdField.get(login);

            // We intentionally do not call handleHello here because offline-mode
            // would take the offline branch. Instead we put the vanilla login
            // listener into its KEY stage, then let its normal handleKey path do
            // RSA nonce validation, AES enablement and Mojang hasJoined auth.
            profileField.set(login, new GameProfile(expectedUuid, hello.name()));
            stateField.set(login, enumConstant(stateField.getType(), "KEY"));

            premiumTickets.put(
                    normalize(hello.name()),
                    new PremiumTicket(expectedUuid,
                            System.currentTimeMillis() + VERIFIED_TICKET_TTL_MS)
            );

            connection.send(new ClientboundHelloPacket(
                    serverId,
                    server.getKeyPair().getPublic().getEncoded(),
                    challenge
            ));

            Channel channel = connectionChannel();
            if (channel != null) resume(channel);
        }

        private void disconnect(String messagePath) {
            connection.disconnect(Component.literal(plugin.getMessages().raw(messagePath)));
        }

        private Channel connectionChannel() {
            ChannelHandlerContext context = connectionContext();
            return context == null ? null : context.channel();
        }

        private ChannelHandlerContext connectionContext() {
            // Connection itself is in the pipeline as packet_handler, so find
            // its context without reflecting the private channel field.
            for (String name : new String[]{"packet_handler"}) {
                Channel channel = findPipelineChannel();
                if (channel != null) return channel.pipeline().context(name);
            }
            return null;
        }

        private Channel findPipelineChannel() {
            try {
                Field field = Connection.class.getDeclaredField("channel");
                field.setAccessible(true);
                return (Channel) field.get(connection);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
    }

    private static void resume(Channel channel) {
        if (channel == null || !channel.isActive()) return;
        channel.config().setAutoRead(true);
        channel.read();
    }

    private static Field findStateField() throws ReflectiveOperationException {
        for (Field field : ServerLoginPacketListenerImpl.class.getDeclaredFields()) {
            if (!field.getType().isEnum()) continue;
            for (Object constant : field.getType().getEnumConstants()) {
                if (((Enum<?>) constant).name().equals("KEY")) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException("ServerLoginPacketListenerImpl state");
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

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record PremiumTicket(UUID expectedUuid, long expiresAt) {}
}
