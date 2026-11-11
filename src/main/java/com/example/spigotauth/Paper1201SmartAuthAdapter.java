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
import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Selective Mojang authentication for Paper 1.20.1 servers running
 * online-mode=false.
 *
 * SLogin intercepts Login Start only for Premium candidates. Cracked players
 * continue through the untouched vanilla offline path. Premium candidates must
 * complete the normal encryption challenge and Mojang hasJoined verification.
 */
public final class Paper1201SmartAuthAdapter {

    private static final Key INITIALIZER_KEY = Key.key("slogin", "smart-auth");
    private static final String HANDLER_NAME = "slogin-smart-auth";
    private static final long VERIFIED_TICKET_TTL_MS = 30_000L;

    private final SpigotAuth plugin;
    private final Logger logger;
    private final Map<UUID, PremiumTicket> premiumTickets = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private Paper1201LoginAccess loginAccess;

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
            logger.severe("SLogin Smart Auth requiere Paper/CraftBukkit 1.20.1.");
            return false;
        }
        if (plugin.getServer().getOnlineMode()) {
            logger.info("SLogin detectó online-mode=true; Minecraft ya verifica Premium.");
            return false;
        }

        try {
            loginAccess = Paper1201LoginAccess.resolve();

            // Fail at startup if this Paper build does not expose the exact
            // encryption primitive expected by the 1.20.1 adapter.
            Connection.class.getDeclaredMethod("setupEncryption", SecretKey.class);

            enabled = true;
            ChannelInitializeListenerHolder.addListener(INITIALIZER_KEY, this::install);

            logger.info("SLogin Smart Auth validado y activo para online-mode=false.");
            debug("Estados NMS: " + loginAccess.stateNames());
            return true;
        } catch (Throwable failure) {
            enabled = false;
            loginAccess = null;
            logger.log(Level.SEVERE,
                    "SLogin desactivó Smart Auth: esta build de Paper 1.20.1 "
                            + "no coincide con el adaptador validado.",
                    failure);
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

    /**
     * Consumes a one-time ticket created only after Mojang hasJoined succeeded.
     */
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

                debug("Inicializando canal de login " + channel.id());

                if (channel.pipeline().get("packet_handler") != null) {
                    channel.pipeline().addBefore(
                            "packet_handler",
                            HANDLER_NAME,
                            new LoginInterceptor(channel)
                    );
                } else {
                    // ChannelInitializeListener may run before packet_handler
                    // exists. Appending here keeps SLogin before the handler
                    // Paper adds later.
                    channel.pipeline().addLast(
                            HANDLER_NAME,
                            new LoginInterceptor(channel)
                    );
                }
            } catch (Throwable failure) {
                logger.log(Level.WARNING,
                        "SLogin no pudo instalar Smart Auth en una conexión.",
                        failure);
            }
        });
    }

    private final class LoginInterceptor extends ChannelInboundHandlerAdapter {

        private final Channel channel;
        private final AtomicBoolean helloDecided = new AtomicBoolean(false);
        private PremiumAttempt premiumAttempt;

        private LoginInterceptor(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ServerboundHelloPacket hello
                    && helloDecided.compareAndSet(false, true)) {
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
            pause(channel);

            String name = hello.name();

            // Verified Premium identities and pending /premium migrations are
            // challenged directly against the exact UUID already stored.
            UUID expected = plugin.getPremiumAccounts().findVerifiedUuidByName(name);
            if (expected == null) {
                expected = plugin.getPremiumPreferences().expectedUuid(name);
            }

            if (expected != null) {
                debug("Challenge directo para " + name + " -> " + expected);
                startChallengeOrFail(hello, expected);
                return;
            }

            if (!plugin.getConfig().getBoolean(
                    "smart-auth.challenge-if-premium-nickname", true)) {
                continueOffline(ctx, originalPacket);
                return;
            }

            plugin.getPremium().check(name, result ->
                    channel.eventLoop().execute(() ->
                            finishProfileLookup(ctx, originalPacket, hello, result)));
        }

        private void finishProfileLookup(ChannelHandlerContext ctx,
                                         Object originalPacket,
                                         ServerboundHelloPacket hello,
                                         PremiumChecker.Result result) {
            if (!channel.isActive()) return;

            switch (result.status()) {
                case NOT_FOUND -> continueOffline(ctx, originalPacket);
                case UNAVAILABLE -> {
                    boolean protectedIdentity =
                            plugin.getPremiumAccounts().isProtectedName(hello.name())
                            || plugin.getPremiumPreferences().expectedUuid(hello.name()) != null;
                    boolean failClosed = plugin.getConfig().getBoolean(
                            "smart-auth.fail-closed-on-lookup-error", false);

                    if (protectedIdentity || failClosed) {
                        disconnect("premium.verification-unavailable");
                    } else {
                        continueOffline(ctx, originalPacket);
                    }
                }
                case PREMIUM -> {
                    debug("Nick Premium detectado: " + hello.name()
                            + " -> " + result.profileId());
                    startChallengeOrFail(hello, result.profileId());
                }
            }
        }

        private void startChallengeOrFail(ServerboundHelloPacket hello,
                                          UUID expectedUuid) {
            try {
                beginPremiumChallenge(hello, expectedUuid);
            } catch (Throwable failure) {
                failPremium(hello.name(), failure);
            }
        }

        private void continueOffline(ChannelHandlerContext ctx, Object originalPacket) {
            premiumAttempt = null;
            ctx.fireChannelRead(originalPacket);
            resume(channel);
        }

        private void beginPremiumChallenge(ServerboundHelloPacket hello,
                                           UUID expectedUuid)
                throws ReflectiveOperationException {
            if (expectedUuid == null) {
                throw new IllegalStateException("No existe UUID Premium esperado.");
            }

            Connection connection = connection();
            if (connection == null) {
                throw new IllegalStateException(
                        "Paper todavía no instaló packet_handler.");
            }

            if (!(connection.getPacketListener()
                    instanceof ServerLoginPacketListenerImpl login)) {
                throw new IllegalStateException(
                        "El listener de login de Paper no está disponible.");
            }

            MinecraftServer server = ((CraftServer) plugin.getServer()).getServer();
            KeyPair keyPair = server.getKeyPair();

            byte[] challenge = loginAccess.challenge(login);
            String serverId = loginAccess.serverId(login);

            // Recreates only the parts of vanilla handleHello required before
            // handleKey. The profile stays null until Mojang proves ownership.
            loginAccess.initializeHello(login, hello.name());

            premiumAttempt = new PremiumAttempt(
                    hello.name(),
                    expectedUuid,
                    challenge,
                    serverId,
                    keyPair,
                    login
            );

            debug("Enviando Encryption Request a " + hello.name());

            connection.send(new ClientboundHelloPacket(
                    serverId,
                    keyPair.getPublic().getEncoded(),
                    challenge
            ));

            resume(channel);
        }

        private void handlePremiumKey(ChannelHandlerContext ctx,
                                      ServerboundKeyPacket packet) {
            PremiumAttempt attempt = premiumAttempt;
            premiumAttempt = null;

            if (attempt == null) {
                disconnect("premium.verification-start-error");
                return;
            }

            pause(channel);

            try {
                Connection connection = connection();
                if (connection == null) {
                    throw new IllegalStateException("Conexión NMS no disponible.");
                }

                if (!packet.isChallengeValid(
                        attempt.challenge(),
                        attempt.keyPair().getPrivate())) {
                    throw new SecurityException(
                            "El verify token del Encryption Response no coincide.");
                }

                debug("Encryption Response válido de " + attempt.name());

                SecretKey sharedSecret =
                        packet.getSecretKey(attempt.keyPair().getPrivate());

                String serverHash = MinecraftSessionService.createServerHash(
                        attempt.serverId(),
                        sharedSecret,
                        attempt.keyPair().getPublic()
                );

                loginAccess.setAuthenticating(attempt.login());

                // Same transport transition vanilla performs before querying
                // hasJoined.
                connection.setupEncryption(sharedSecret);

                debug("Consultando hasJoined para " + attempt.name());

                plugin.getSessionService()
                        .hasJoined(attempt.name(), serverHash)
                        .whenComplete((profile, error) ->
                                channel.eventLoop().execute(() ->
                                        finishPremiumVerification(
                                                attempt,
                                                profile,
                                                error
                                        )));
            } catch (Throwable failure) {
                failPremium(attempt.name(), failure);
            }
        }

        private void finishPremiumVerification(
                PremiumAttempt attempt,
                MinecraftSessionService.VerifiedProfile verified,
                Throwable error) {
            if (!channel.isActive()) return;

            if (error != null) {
                failPremium(
                        attempt.name(),
                        new SecurityException(
                                "Mojang hasJoined no pudo confirmar la sesión.",
                                unwrap(error)
                        )
                );
                return;
            }

            if (verified == null
                    || !verified.uuid().equals(attempt.expectedUuid())
                    || !verified.name().equalsIgnoreCase(attempt.name())) {
                failPremium(
                        attempt.name(),
                        new SecurityException(
                                "El perfil devuelto por Mojang no coincide con el esperado.")
                );
                return;
            }

            GameProfile profile =
                    new GameProfile(verified.uuid(), verified.name());

            // Keep the remainder of the login on the server thread and let
            // Paper's own 1.20.1 method perform its VERIFYING transition.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!channel.isActive()) return;

                try {
                    loginAccess.startClientVerification(
                            attempt.login(),
                            profile
                    );

                    premiumTickets.put(
                            verified.uuid(),
                            new PremiumTicket(
                                    verified.name(),
                                    System.currentTimeMillis()
                                            + VERIFIED_TICKET_TTL_MS
                            )
                    );

                    debug("hasJoined confirmó " + verified.name()
                            + " (" + verified.uuid() + ")");
                    logger.info("SLogin verificó Premium: "
                            + verified.name() + " (" + verified.uuid() + ")");

                    channel.eventLoop().execute(() -> resume(channel));
                } catch (Throwable failure) {
                    channel.eventLoop().execute(() ->
                            failPremium(attempt.name(), failure));
                }
            });
        }

        private void failPremium(String name, Throwable failure) {
            if (name != null
                    && plugin.getPremiumPreferences().expectedUuid(name) != null
                    && !plugin.getPremiumPreferences().isVerified(name)) {
                plugin.getPremiumPreferences().clear(name);
                debug("Migración Premium pendiente revertida para " + name);
            }

            logger.log(Level.WARNING,
                    "SLogin no pudo verificar la sesión Premium de " + name,
                    unwrap(failure));

            disconnect("premium.verification-start-error");
        }

        private void disconnect(String messagePath) {
            Connection connection = connection();

            if (connection != null) {
                connection.disconnect(
                        Component.literal(plugin.getMessages().raw(messagePath))
                );
            } else {
                channel.close();
            }
        }
    }

    private static void pause(Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.config().setAutoRead(false);
        }
    }

    private static void resume(Channel channel) {
        if (channel == null || !channel.isActive()) return;
        channel.config().setAutoRead(true);
        channel.read();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("smart-auth.debug", false)) {
            logger.info("[SmartAuth] " + message);
        }
    }

    private record PremiumTicket(String name, long expiresAt) {}

    private record PremiumAttempt(
            String name,
            UUID expectedUuid,
            byte[] challenge,
            String serverId,
            KeyPair keyPair,
            ServerLoginPacketListenerImpl login
    ) {}
}
