package com.example.spigotauth;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Access validated against the real Paper 1.20.1 / protocol 763 login layout.
 *
 * 1.20.1 uses the legacy state machine:
 * HELLO -> KEY -> AUTHENTICATING -> READY_TO_ACCEPT
 * -> DELAY_ACCEPT (when needed) -> ACCEPTED.
 *
 * Newer VERIFYING / WAITING_FOR_DUPE_DISCONNECT / PROTOCOL_SWITCHING states
 * are intentionally rejected by the compatibility tests for this adapter.
 */
final class Paper1201LoginAccess {

    private static final Set<String> REQUIRED_STATES = Set.of(
            "HELLO",
            "KEY",
            "AUTHENTICATING",
            "READY_TO_ACCEPT",
            "DELAY_ACCEPT",
            "ACCEPTED"
    );

    private final Field state;
    private final Field gameProfile;
    private final Field challenge;
    private final Field serverId;

    private Paper1201LoginAccess(Field state,
                                 Field gameProfile,
                                 Field challenge,
                                 Field serverId) {
        this.state = state;
        this.gameProfile = gameProfile;
        this.challenge = challenge;
        this.serverId = serverId;
    }

    static Paper1201LoginAccess resolve() throws ReflectiveOperationException {
        Class<?> owner = ServerLoginPacketListenerImpl.class;

        Field state = null;
        Field profile = null;
        Field challenge = null;
        Field serverId = null;

        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;

            Class<?> type = field.getType();
            int modifiers = field.getModifiers();

            if (type.isEnum() && containsRequiredStates(type)) {
                state = unique(state, field, "login state");
            } else if (type == GameProfile.class) {
                profile = unique(profile, field, "gameProfile");
            } else if (type == byte[].class && Modifier.isFinal(modifiers)) {
                challenge = unique(challenge, field, "challenge");
            } else if (type == String.class && Modifier.isFinal(modifiers)) {
                serverId = unique(serverId, field, "serverId");
            }
        }

        if (state == null) throw new NoSuchFieldException("1.20.1 login state");
        if (profile == null) throw new NoSuchFieldException("gameProfile");
        if (challenge == null) throw new NoSuchFieldException("challenge");
        if (serverId == null) throw new NoSuchFieldException("serverId");

        for (Field field : new Field[]{state, profile, challenge, serverId}) {
            field.setAccessible(true);
        }

        return new Paper1201LoginAccess(state, profile, challenge, serverId);
    }

    byte[] challenge(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        byte[] value = (byte[]) challenge.get(login);

        if (value == null || value.length == 0) {
            throw new IllegalStateException(
                    "Paper devolvió un challenge de login vacío.");
        }

        return value.clone();
    }

    String serverId(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        String value = (String) serverId.get(login);
        return value == null ? "" : value;
    }

    /**
     * Reproduces the minimum part of vanilla handleHello's online branch that
     * is skipped because the server itself is running online-mode=false.
     */
    void beginKeyExchange(ServerLoginPacketListenerImpl login,
                          String username)
            throws ReflectiveOperationException {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Nombre de Minecraft inválido.");
        }

        // 1.20.1 stores the candidate in gameProfile before entering KEY.
        // UUID stays null until Authlib proves ownership.
        gameProfile.set(login, new GameProfile(null, username));
        setState(login, "KEY");
    }

    void setAuthenticating(ServerLoginPacketListenerImpl login)
            throws ReflectiveOperationException {
        setState(login, "AUTHENTICATING");
    }

    /**
     * Gives Paper the full Authlib-verified profile and returns control to its
     * normal server-thread state machine. Paper then performs the ordinary
     * whitelist/ban/full-server/duplicate-player acceptance path.
     */
    void readyToAccept(ServerLoginPacketListenerImpl login,
                       GameProfile verifiedProfile)
            throws ReflectiveOperationException {
        if (verifiedProfile == null
                || verifiedProfile.id() == null
                || verifiedProfile.name() == null) {
            throw new IllegalArgumentException(
                    "El GameProfile Premium verificado está incompleto.");
        }

        gameProfile.set(login, verifiedProfile);
        setState(login, "READY_TO_ACCEPT");
    }

    boolean supportsState(String stateName) {
        return stateNames().contains(stateName);
    }

    Set<String> stateNames() {
        return Arrays.stream(state.getType().getEnumConstants())
                .map(value -> ((Enum<?>) value).name())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void setState(ServerLoginPacketListenerImpl login,
                          String stateName)
            throws ReflectiveOperationException {
        state.set(login, enumConstant(state.getType(), stateName));
    }

    private static boolean containsRequiredStates(Class<?> type) {
        Set<String> names = Arrays.stream(type.getEnumConstants())
                .map(value -> ((Enum<?>) value).name())
                .collect(Collectors.toSet());

        return names.containsAll(REQUIRED_STATES);
    }

    private static Field unique(Field current,
                                Field candidate,
                                String label)
            throws NoSuchFieldException {
        if (current != null) {
            throw new NoSuchFieldException(
                    "Más de un campo compatible con " + label);
        }
        return candidate;
    }

    private static Object enumConstant(Class<?> type, String name)
            throws NoSuchFieldException {
        for (Object value : type.getEnumConstants()) {
            if (((Enum<?>) value).name().equals(name)) {
                return value;
            }
        }

        throw new NoSuchFieldException(
                "Estado NMS 1.20.1 no encontrado: " + name);
    }
}
