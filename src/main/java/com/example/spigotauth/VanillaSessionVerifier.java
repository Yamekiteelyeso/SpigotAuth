package com.example.spigotauth;

import com.mojang.authlib.GameProfile;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses the Minecraft server's own Authlib session service so Premium
 * verification follows the same Mojang implementation as vanilla and keeps
 * the full GameProfile properties returned by hasJoinedServer.
 */
final class VanillaSessionVerifier implements AutoCloseable {

    private final Object sessionService;
    private final Method hasJoinedServer;
    private final boolean acceptsAddress;
    private final ExecutorService executor;

    VanillaSessionVerifier(Object sessionService) throws NoSuchMethodException {
        this.sessionService = Objects.requireNonNull(
                sessionService, "sessionService");
        this.hasJoinedServer = resolveMethod(sessionService.getClass());
        this.hasJoinedServer.setAccessible(true);
        this.acceptsAddress =
                this.hasJoinedServer.getParameterCount() == 3;

        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "SLogin-SessionVerifier-" + counter.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };

        this.executor = Executors.newFixedThreadPool(2, factory);
    }

    CompletableFuture<GameProfile> hasJoined(
            String requestedName,
            String serverHash,
            InetAddress remoteAddress) {
        if (requestedName == null
                || !requestedName.matches("[A-Za-z0-9_]{3,16}")) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Nombre de Minecraft no válido."));
        }
        if (serverHash == null
                || !serverHash.matches("-?[0-9a-fA-F]{1,40}")) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Hash de sesión no válido."));
        }

        GameProfile requested = new GameProfile(null, requestedName);

        return CompletableFuture.supplyAsync(() -> {
            try {
                Object result;

                if (acceptsAddress) {
                    result = hasJoinedServer.invoke(
                            sessionService,
                            requested,
                            serverHash,
                            remoteAddress
                    );
                } else {
                    result = hasJoinedServer.invoke(
                            sessionService,
                            requested,
                            serverHash
                    );
                }

                if (result == null) return null;
                if (!(result instanceof GameProfile profile)) {
                    throw new IllegalStateException(
                            "Authlib devolvió un tipo de perfil inesperado.");
                }

                return profile;
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause() == null
                        ? exception
                        : exception.getCause();
                throw new CompletionException(cause);
            } catch (ReflectiveOperationException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    boolean acceptsRemoteAddress() {
        return acceptsAddress;
    }

    static Method resolveMethod(Class<?> sessionServiceClass)
            throws NoSuchMethodException {
        Method fallback = null;

        for (Method method : sessionServiceClass.getMethods()) {
            if (!method.getName().equals("hasJoinedServer")) continue;
            if (!GameProfile.class.isAssignableFrom(
                    method.getReturnType())) continue;

            Class<?>[] parameters = method.getParameterTypes();

            if (parameters.length == 3
                    && parameters[0] == GameProfile.class
                    && parameters[1] == String.class
                    && parameters[2] == InetAddress.class) {
                return method;
            }

            if (parameters.length == 2
                    && parameters[0] == GameProfile.class
                    && parameters[1] == String.class) {
                fallback = method;
            }
        }

        if (fallback != null) return fallback;

        throw new NoSuchMethodException(
                "Authlib hasJoinedServer(GameProfile,String[,InetAddress])");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
