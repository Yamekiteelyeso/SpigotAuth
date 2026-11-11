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
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.bukkit.craftbukkit.v1_20_R1.CraftServer;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.security.KeyPair;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Paper1201SmartAuthAdapter {

    private static final Key INITIALIZER_KEY = Key.key("slogin", "smart-auth");
    private static final String HANDLER_NAME = "slogin-smart-auth";
    private static final long VERIFIED_TICKET_TTL_MS = 30_000L;

    private final SpigotAuth plugin;
    private final Logger logger;
    private final Map<UUID, PremiumTicket> premiumTickets = new ConcurrentHashMap<>();
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
            logger.info("SLogin detectó online-mode=true; Minecraft ya verifica Premium.");
            return false;
        }

        try {
            enabled = true;
            ChannelInitializeListenerHolder.addListener(INITIALIZER_KEY, this::install);
            logger.info("SLogin Smart Auth activo para online-mode=false.");
            debug("Interceptor Premium registrado.");
            return true;
        } catch (Throwable failure) {
            enabled = false;
            logger.log(Level.SEVERE, "No se pudo registrar el interceptor de SLogin.", failure);
            return false;
        }
    }

    public void disable() {
        enabled = false;
        premiumTickets.clear();
        try {
            ChannelInitializeListenerHolder.removeListener(INITIALIZER_KEY);
        } catch (Throwable ignored) {
        }
    }

    public boolean consumeVerifiedJoin(String name, UUID actualUuid) {
        if (name == null || actualUuid == null) return false;
        PremiumTicket ticket = premiumTickets.remove(actualUuid);
        if (ticket == null) return false;
        if (System.currentTimeMillis() > ticket.expiresAt()) return false;
        return ticket.name().equalsIgnoreCase(name);
    }

    private void install(Channel channel) {
        if (!enabled || channel == null) return;

        channel.eventLoop().execute(() -> {
            try {
                if (!enabled || !channel.isActive()) return;
                if (channel.pipeline().get(HANDLER_NAME) != null) return;

                // Paper may invoke the initializer before packet_handler exists.
                // If it exists, place SLogin directly before it. Otherwise append
                // SLogin now; packet_handler will be appended after us.
                debug("Inicializando canal de login: " + channel.id());
                if (channel.pipeline().get("packet_handler") != null) {
                    channel.pipeline().addBefore(
                            "packet_handler", HANDLER_NAME, new LoginInterceptor(channel));
                } else {
                    channel.pipeline().addLast(HANDLER_NAME, new LoginInterceptor(channel));
                }
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "SLogin no pudo inyectar una conexión.", failure);
            }
        });
    }

    private final class LoginInterceptor extends ChannelInboundHandlerAdapter {
        private final Channel channel;
        private boolean helloDecided;
        private PremiumAttempt premiumAttempt;

        private LoginInterceptor(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ServerboundHelloPacket hello && !helloDecided) {
                handleHello(ctx, msg, hello);
                return;
            }

            if (msg instanceof ServerboundKeyPacket key && premiumAttempt != null) {
                handlePremiumKey(ctx, key);
                return;
            }

            super.channelRead(ctx, msg);
        }

        private Connection connection() {
            Object handler = channel.pipeline().get("packet_handler");
            return handler instanceof Connection connection ? connection : null;
        }

        private void handleHello(ChannelHandlerContext ctx,
                                 Object originalPacket,
                                 ServerboundHelloPacket hello) {
            helloDecided = true;
            ctx.channel().config().setAutoRead(false);

            // Previously verified identities and /premium preferences do not
            // depend on the public profile API. They immediately challenge the
            // exact UUID that must prove ownership.
            UUID expected = plugin.getPremiumAccounts().findVerifiedUuidByName(hello.name());
            if (expected == null) {
                expected = plugin.getPremiumPreferences().expectedUuid(hello.name());
            }

            if (expected != null) {
                debug("UUID Premium conocido para " + hello.name() + ": " + expected);
                try {
                    beginPremiumChallenge(hello, expected);
                } catch (Throwable failure) {
                    failPremium(hello.name(), failure);
                }
                return;
            }

            plugin.getPremium().check(hello.name(), result ->
                    ctx.channel().eventLoop().execute(() -> decideLookup(
                            ctx, originalPacket, hello, result)));
        }

        private void decideLookup(ChannelHandlerContext ctx,
                                  Object originalPacket,
                                  ServerboundHelloPacket hello,
                                  PremiumChecker.Result result) {
            if (!ctx.channel().isActive()) return;

            try {
                switch (result.status()) {
                    case NOT_FOUND -> continueOffline(ctx, originalPacket);
                    case UNAVAILABLE -> {
                        boolean failClosed = plugin.getConfig().getBoolean(
                                "smart-auth.fail-closed-on-lookup-error", false);
                        if (failClosed) {
                            disconnect("premium.verification-unavailable");
                        } else {
                            continueOffline(ctx, originalPacket);
                        }
                    }
                    case PREMIUM -> {
                        debug("Perfil Premium detectado para " + hello.name()
                                + ": " + result.profileId());
                        beginPremiumChallenge(hello, result.profileId());
                    }
                }
            } catch (Throwable failure) {
                failPremium(hello.name(), failure);
            }
        }

        private void continueOffline(ChannelHandlerContext ctx, Object originalPacket) {
            premiumAttempt = null;
            ctx.fireChannelRead(originalPacket);
            resume(channel);
        }

        private void beginPremiumChallenge(ServerboundHelloPacket hello, UUID expectedUuid)
                throws ReflectiveOperationException {
            Connection connection = connection();
            if (connection == null) {
                throw new IllegalStateException("packet_handler todavía no está disponible.");
            }
            if (expectedUuid == null) {
                throw new IllegalStateException("No existe UUID Premium esperado.");
            }
            if (!(connection.getPacketListener() instanceof ServerLoginPacketListenerImpl login)) {
                disconnect("premium.incompatible-login-state");
                return;
            }

            MinecraftServer server = ((CraftServer) plugin.getServer()).getServer();
            KeyPair keyPair = server.getKeyPair();

            Field stateField = findStateField();
            Field profileField = findField(ServerLoginPacketListenerImpl.class, GameProfile.class);
            Field challengeField = findField(ServerLoginPacketListenerImpl.class, byte[].class);
            Field serverIdField = findField(ServerLoginPacketListenerImpl.class, String.class);

            byte[] challenge = ((byte[]) challengeField.get(login)).clone();
            String serverId = (String) serverIdField.get(login);

            profileField.set(login, new GameProfile(expectedUuid, hello.name()));
            stateField.set(login, enumConstant(stateField.getType(), "KEY"));

            premiumAttempt = new PremiumAttempt(
                    hello.name(), expectedUuid, challenge, serverId,
                    keyPair, login, stateField, profileField
            );

            debug("Enviando Encryption Request a " + hello.name());
            connection.send(new ClientboundHelloPacket(
                    serverId,
                    keyPair.getPublic().getEncoded(),
                    challenge
            ));
            resume(channel);
        }

        private void handlePremiumKey(ChannelHandlerContext ctx, ServerboundKeyPacket packet) {
            PremiumAttempt attempt = premiumAttempt;
            premiumAttempt = null;
            ctx.channel().config().setAutoRead(false);

            try {
                Connection connection = connection();
                if (connection == null) {
                    throw new IllegalStateException("Conexión NMS no disponible.");
                }

                if (!packet.isChallengeValid(
                        attempt.challenge(), attempt.keyPair().getPrivate())) {
                    throw new SecurityException("El nonce RSA no coincide.");
                }

                debug("Encryption Response recibido de " + attempt.name());
                SecretKey sharedSecret = packet.getSecretKey(attempt.keyPair().getPrivate());
                String serverHash = MinecraftSessionService.createServerHash(
                        attempt.serverId(), sharedSecret, attempt.keyPair().getPublic());

                connection.setupEncryption(sharedSecret);
                attempt.stateField().set(
                        attempt.login(),
                        enumConstant(attempt.stateField().getType(), "AUTHENTICATING")
                );

                debug("Consultando hasJoined para " + attempt.name());
                plugin.getSessionService()
                        .hasJoined(attempt.name(), serverHash)
                        .whenComplete((profile, error) ->
                                channel.eventLoop().execute(() ->
                                        finishPremiumVerification(attempt, profile, error)));
            } catch (Throwable failure) {
                failPremium(attempt.name(), failure);
            }
        }

        private void finishPremiumVerification(
                PremiumAttempt attempt,
                MinecraftSessionService.VerifiedProfile verified,
                Throwable error) {
            if (!channel.isActive()) return;

            try {
                if (error != null) {
                    throw new SecurityException("Falló hasJoined de Mojang.", error);
                }
                if (verified == null) {
                    throw new SecurityException("Mojang no confirmó la sesión Premium.");
                }
                if (!verified.uuid().equals(attempt.expectedUuid())
                        || !verified.name().equalsIgnoreCase(attempt.name())) {
                    throw new SecurityException("El perfil verificado no coincide.");
                }

                attempt.profileField().set(
                        attempt.login(),
                        new GameProfile(verified.uuid(), verified.name())
                );

                // Paper 1.20.1 uses VERIFYING for the server-thread stage that
                // runs whitelist/ban/full-server/duplicate checks and completes
                // the normal login. Vanilla-mapped builds call the equivalent
                // state READY_TO_ACCEPT, so enumConstant supports both names.
                attempt.stateField().set(
                        attempt.login(),
                        enumConstant(attempt.stateField().getType(), "VERIFYING")
                );

                premiumTickets.put(
                        verified.uuid(),
                        new PremiumTicket(
                                verified.name(),
                                System.currentTimeMillis() + VERIFIED_TICKET_TTL_MS)
                );

                debug("hasJoined confirmó UUID " + verified.uuid());
                logger.info("SLogin verificó Premium: "
                        + verified.name() + " (" + verified.uuid() + ")");
                resume(channel);
            } catch (Throwable failure) {
                failPremium(attempt.name(), failure);
            }
        }

        private void failPremium(String name, Throwable failure) {
            logger.log(Level.WARNING,
                    "SLogin no pudo verificar la sesión Premium de " + name, failure);
            disconnect("premium.verification-start-error");
        }

        private void disconnect(String messagePath) {
            Connection connection = connection();
            if (connection != null) {
                connection.disconnect(Component.literal(plugin.getMessages().raw(messagePath)));
            } else {
                channel.close();
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
                String name = ((Enum<?>) constant).name();
                if (name.equals("KEY")) {
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

        String fallback = name.equals("VERIFYING") ? "READY_TO_ACCEPT"
                : name.equals("READY_TO_ACCEPT") ? "VERIFYING"
                : null;
        if (fallback != null) {
            for (Object value : type.getEnumConstants()) {
                if (((Enum<?>) value).name().equals(fallback)) return value;
            }
        }

        throw new IllegalStateException("Estado NMS no encontrado: " + name);
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("smart-auth.debug", false)) {
            logger.info("[SmartAuth] " + message);
        }
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record PremiumTicket(String name, long expiresAt) {}

    private record PremiumAttempt(
            String name,
            UUID expectedUuid,
            byte[] challenge,
            String serverId,
            KeyPair keyPair,
            ServerLoginPacketListenerImpl login,
            Field stateField,
            Field profileField
    ) {}
}
