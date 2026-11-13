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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    private final java.util.Set<Channel> activeChannels = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled;
    private volatile String failureReason;
    private Paper1201LoginAccess loginAccess;
    private VanillaSessionVerifier sessionVerifier;

    public Paper1201SmartAuthAdapter(SpigotAuth plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean enable() {
        if (!"1.20.1".equals(plugin.getServer().getMinecraftVersion())) {
            failureReason = "requiere Paper 1.20.1; detectado "
                    + plugin.getServer().getMinecraftVersion();
            logger.severe("SLogin Smart Auth " + failureReason);
            return false;
        }
        if (!(plugin.getServer() instanceof CraftServer)) {
            failureReason = "requiere el servidor Paper 1.20.1 (no Spigot ni forks incompatibles)";
            logger.severe("SLogin Smart Auth " + failureReason);
            return false;
        }
        if (plugin.getServer().getOnlineMode()) {
            failureReason = "online-mode=true: Minecraft ya verifica Premium";
            logger.info("SLogin detectó online-mode=true; Minecraft ya verifica Premium.");
            return false;
        }

        try {
            loginAccess = Paper1201LoginAccess.resolve();

            // Fail at startup if this Paper build does not expose the exact
            // encryption primitive expected by the 1.20.1 adapter.
            Connection.class.getDeclaredMethod("setupEncryption", SecretKey.class);

            MinecraftServer minecraftServer =
                    ((CraftServer) plugin.getServer()).getServer();
            sessionVerifier = new VanillaSessionVerifier(
                    minecraftServer.getSessionService());

            enabled = true;
            failureReason = null;
            ChannelInitializeListenerHolder.addListener(INITIALIZER_KEY, this::install);

            logger.info("SLogin Smart Auth validado y activo para online-mode=false.");
            debug("Estados NMS: " + loginAccess.stateNames());
            return true;
        } catch (Throwable failure) {
            enabled = false;
            String detail = failure.getMessage();
            failureReason = failure.getClass().getSimpleName()
                    + (detail == null || detail.isBlank() ? "" : ": " + detail);
            if (failureReason.length() > 200) {
                failureReason = failureReason.substring(0, 200);
            }
            loginAccess = null;
            if (sessionVerifier != null) {
                sessionVerifier.close();
                sessionVerifier = null;
            }
            logger.log(Level.SEVERE,
                    "SLogin desactivó Smart Auth: esta build de Paper 1.20.1 "
                            + "no coincide con el adaptador validado.",
                    failure);
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String failureReason() {
        return failureReason == null ? "sin detalles; revisa latest.log" : failureReason;
    }

    public void disable() {
        enabled = false;
        premiumTickets.clear();

        // During /slogin reload or plugin shutdown, an in-flight Premium
        // lookup may have disabled autoRead. Close only channels currently
        // owned by this adapter; otherwise they could hang indefinitely.
        for (Channel pending : activeChannels) {
            pending.eventLoop().execute(() -> {
                if (pending.isActive()) pending.close();
            });
        }
        activeChannels.clear();

        if (sessionVerifier != null) {
            sessionVerifier.close();
            sessionVerifier = null;
        }

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

                channel.closeFuture().addListener(done -> activeChannels.remove(channel));

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
            activeChannels.add(channel);
            pause(channel);

            String name = hello.name();

            // Verified Premium identities and pending /premium migrations are
            // challenged directly against the exact UUID already stored.
            UUID expected = plugin.getPremiumAccounts().findVerifiedUuidByName(name);
            if (expected == null) {
                expected = plugin.getPremiumPreferences().expectedUuid(name);
            }

            // A verified binding without a parseable UUID is an error, not
            // an invitation to downgrade to password/offline login.
            if (expected == null && plugin.getPremiumPreferences().isVerified(name)) {
                logger.warning("Protección Premium sin UUID válido para " + name);
                disconnect("premium.protected-login");
                return;
            }

            if (expected != null || plugin.getPremiumPreferences().isPending(name)) {
                // A vanilla offline UUID proves this is NOT the matching
                // official identity. An arbitrary/forged UUID still requires
                // the entire Mojang session challenge to be accepted.
                UUID advertised = hello.profileId().orElse(null);
                if (expected != null && isOfflineUuidClaim(name, advertised, expected)) {
                    debug("Acceso offline rechazado para nombre Premium " + name);
                    disconnect("premium.not-owner");
                    return;
                }
                debug("Verificación Premium obligatoria para " + name
                        + (expected == null ? " (migración pendiente)" : " -> " + expected));
                startChallengeOrFail(hello, expected);
                return;
            }

            // The optional 1.20.1 Login Start UUID is UNTRUSTED. Use it
            // solely as a hint to request the standard Mojang session proof.
            // This helps first-time official clients even if public nickname
            // lookups are throttled. Forged UUIDs can never grant access.
            UUID clientHint = hello.profileId().orElse(null);
            if (plugin.getConfig().getBoolean(
                    "smart-auth.challenge-if-client-online-uuid", true)
                    && looksLikeOnlineUuid(clientHint)) {
                debug("UUID online anunciado en Login Start de " + name
                        + "; verificando propiedad con el protocolo oficial.");
                startChallengeOrFail(hello, clientHint);
                return;
            }

            // Premium-first login: an existing local password record is NOT
            // evidence that the connecting client is offline. Challenge
            // registered names too when configured, but never merge local
            // and Mojang identities just because they share a username.
            if (plugin.getAccounts().isRegistered(name)
                    && !plugin.getConfig().getBoolean(
                    "smart-auth.auto-login-registered-premium", true)) {
                debug("Cuenta local con detección Premium desactivada: " + name);
                continueOffline(ctx, originalPacket);
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
            activeChannels.remove(channel);
            ctx.fireChannelRead(originalPacket);
            resume(channel);
        }

        private void beginPremiumChallenge(ServerboundHelloPacket hello,
                                           UUID expectedUuid)
                throws ReflectiveOperationException {
            // Pending /premium migration has no expected UUID yet. It must
            // still prove the official session and the exact username before
            // the verified UUID is stored. Other paths require a known UUID.
            if (expectedUuid == null
                    && !plugin.getPremiumPreferences().isPending(hello.name())) {
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
            loginAccess.beginKeyExchange(login, hello.name());

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

                MinecraftServer minecraftServer =
                        ((CraftServer) plugin.getServer()).getServer();

                InetAddress remoteAddress =
                        minecraftServer.getPreventProxyConnections()
                                ? remoteAddress(connection)
                                : null;

                long timeoutMs = Math.max(
                        1000L,
                        plugin.getConfig().getLong(
                                "smart-auth.session-timeout-ms", 5000L)
                );

                debug("Consultando Authlib hasJoinedServer para "
                        + attempt.name());

                sessionVerifier
                        .hasJoined(attempt.name(), serverHash, remoteAddress)
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
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
                GameProfile verified,
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
                    || verified.getId() == null
                    || verified.getName() == null
                    || (attempt.expectedUuid() != null
                        && !verified.getId().equals(attempt.expectedUuid()))
                    || !verified.getName().equalsIgnoreCase(attempt.name())) {
                failPremium(
                        attempt.name(),
                        new SecurityException(
                                "El perfil devuelto por Mojang no coincide con el esperado.")
                );
                return;
            }

            // Keep the remainder of the login on the server thread and hand
            // the verified profile back to the legacy 1.20.1
            // READY_TO_ACCEPT state. Paper's tick then performs the normal
            // whitelist/ban/full-server/duplicate-login path. The Authlib
            // GameProfile is passed through intact, including signed textures.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!channel.isActive()) return;

                try {
                    loginAccess.readyToAccept(
                            attempt.login(),
                            verified
                    );

                    premiumTickets.put(
                            verified.getId(),
                            new PremiumTicket(
                                    verified.getName(),
                                    System.currentTimeMillis()
                                            + VERIFIED_TICKET_TTL_MS
                            )
                    );

                    debug("hasJoined confirmó " + verified.getName()
                            + " (" + verified.getId() + ")");
                    logger.info("SLogin verificó Premium: "
                            + verified.getName() + " (" + verified.getId() + ")");

                    channel.eventLoop().execute(() -> {
                        activeChannels.remove(channel);
                        resume(channel);
                    });
                } catch (Throwable failure) {
                    channel.eventLoop().execute(() ->
                            failPremium(attempt.name(), failure));
                }
            });
        }

        private void failPremium(String name, Throwable failure) {
            // Never erase the owner's pending migration because a remote
            // connection failed its challenge. Otherwise a cracked client
            // can repeatedly connect with the victim's name and cancel their
            // /premium request. Only a verified join or an authenticated
            // admin cancellation may change the stored migration.
            Throwable root = unwrap(failure);
            logger.log(Level.WARNING,
                    "SLogin no pudo verificar la sesión Premium de " + name,
                    root);

            if (isNetworkOrTimeoutFailure(root)) {
                disconnect("premium.verification-unavailable");
            } else if (root instanceof SecurityException) {
                disconnect(isProtectedPremiumName(name)
                        ? "premium.not-owner" : "premium.verification-invalid");
            } else {
                disconnect("premium.verification-start-error");
            }
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

    private static InetAddress remoteAddress(Connection connection) {
        SocketAddress remote = connection.getRemoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            return inet.getAddress();
        }
        return null;
    }

    /**
     * UUIDs sent by clients are untrusted. This check only identifies the
     * standard offline-mode UUID and denies an already protected name.
     * All other clients must still prove ownership through Mojang.
     */
    static boolean isOfflineUuidClaim(String name, UUID clientUuid, UUID officialUuid) {
        if (name == null || clientUuid == null || officialUuid == null) return false;
        UUID offline = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return offline.equals(clientUuid) && !officialUuid.equals(clientUuid);
    }

    private boolean isProtectedPremiumName(String name) {
        return plugin.getPremiumAccounts().isProtectedName(name)
                || plugin.getPremiumPreferences().isVerified(name);
    }

    static boolean looksLikeOnlineUuid(UUID supplied) {
        // Online Minecraft Java accounts normally carry version 4 UUIDs.
        // An untrusted client can send this value; it is never sufficient
        // for authentication without a valid Mojang hasJoined response.
        return supplied != null && supplied.version() == 4;
    }

    static boolean isNetworkOrTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.io.IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
