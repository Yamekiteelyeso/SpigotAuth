package com.example.spigotauth;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Paper 26.3/Java 25 login layout. Fully validated at plugin startup.
 * Uses the actual vanilla client-verification method after Mojang proof,
 * rather than forcing the removed legacy READY_TO_ACCEPT enum state.
 */
final class Paper1201LoginAccess {

    private final Field state;
    private final Field requestedUsername;
    private final Field authenticatedProfile;
    private final Field challenge;
    private final Field serverId;
    private final Method clientVerification;

    private Paper1201LoginAccess(Field state, Field requestedUsername,
                                Field authenticatedProfile, Field challenge,
                                Field serverId, Method clientVerification) {
        this.state = state;
        this.requestedUsername = requestedUsername;
        this.authenticatedProfile = authenticatedProfile;
        this.challenge = challenge;
        this.serverId = serverId;
        this.clientVerification = clientVerification;
    }

    static Paper1201LoginAccess resolve() throws ReflectiveOperationException {
        Class<?> owner = ServerLoginPacketListenerImpl.class;
        Field state = owner.getDeclaredField("state");
        Field username = owner.getDeclaredField("requestedUsername");
        Field profile = owner.getDeclaredField("authenticatedProfile");
        Field challenge = owner.getDeclaredField("challenge");
        Field serverId = owner.getDeclaredField("serverId");
        Method next = owner.getDeclaredMethod("startClientVerification", GameProfile.class);

        if (!state.getType().isEnum()
                || username.getType() != String.class
                || profile.getType() != GameProfile.class
                || challenge.getType() != byte[].class
                || serverId.getType() != String.class) {
            throw new IllegalStateException("Incompatible Paper 26.3 login field layout");
        }

        for (Field field : new Field[]{state, username, profile, challenge, serverId}) {
            field.setAccessible(true);
        }
        next.setAccessible(true);

        Paper1201LoginAccess access =
                new Paper1201LoginAccess(state, username, profile,
                        challenge, serverId, next);

        if (!access.supportsState("KEY") || !access.supportsState("AUTHENTICATING")) {
            throw new IllegalStateException(
                    "Unexpected 26.3 login states: " + access.stateNames());
        }
        return access;
    }

    byte[] challenge(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        byte[] value = (byte[]) challenge.get(login);
        if (value == null || value.length == 0) {
            throw new IllegalStateException("Paper 26.3: missing Premium challenge.");
        }
        return value.clone();
    }

    String serverId(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        String value = (String) serverId.get(login);
        return value == null ? "" : value;
    }

    void beginKeyExchange(ServerLoginPacketListenerImpl login, String username)
            throws ReflectiveOperationException {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Invalid Minecraft Java username.");
        }
        requestedUsername.set(login, username);
        setState(login, "KEY");
    }

    void setAuthenticating(ServerLoginPacketListenerImpl login)
            throws ReflectiveOperationException {
        setState(login, "AUTHENTICATING");
    }

    void readyToAccept(ServerLoginPacketListenerImpl login, GameProfile verifiedProfile)
            throws ReflectiveOperationException {
        if (verifiedProfile == null
                || verifiedProfile.id() == null || verifiedProfile.name() == null) {
            throw new IllegalArgumentException("Incomplete verified Premium GameProfile.");
        }
        authenticatedProfile.set(login, verifiedProfile);
        // Let the 26.3 server perform the normal profile validation,
        // async pre-login events and configuration-state transition.
        clientVerification.invoke(login, verifiedProfile);
    }

    String currentState(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        Object value = state.get(login);
        return value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value);
    }

    boolean supportsState(String name) {
        return stateNames().contains(name);
    }

    Set<String> stateNames() {
        return Arrays.stream(state.getType().getEnumConstants())
                .map(value -> ((Enum<?>) value).name())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void setState(ServerLoginPacketListenerImpl login, String name)
            throws ReflectiveOperationException {
        for (Object constant : state.getType().getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                state.set(login, constant);
                return;
            }
        }
        throw new NoSuchFieldException("Paper 26.3 login state missing: " + name);
    }
}
