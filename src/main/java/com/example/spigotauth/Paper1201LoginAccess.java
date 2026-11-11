package com.example.spigotauth;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validated access to the real Minecraft/Paper 1.20.1 login layout.
 *
 * 1.20.1 uses the legacy login state machine:
 * HELLO -> KEY -> AUTHENTICATING -> VERIFYING -> (optional duplicate wait) -> ACCEPTED.
 */
final class Paper1201LoginAccess {

    private static final Set<String> REQUIRED_STATES = Set.of(
            "HELLO",
            "KEY",
            "AUTHENTICATING",
            "VERIFYING",
            "WAITING_FOR_DUPE_DISCONNECT",
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

        return new Paper1201LoginAccess(
                state,
                profile,
                challenge,
                serverId
        );
    }

    byte[] challenge(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        byte[] value = (byte[]) challenge.get(login);
        if (value == null || value.length == 0) {
            throw new IllegalStateException(
                    "Paper devolvió un challenge vacío.");
        }
        return value.clone();
    }

    String serverId(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        String value = (String) serverId.get(login);
        return value == null ? "" : value;
    }

    /**
     * Mirrors the minimum online-mode branch of vanilla handleHello in 1.20.1.
     * The UUID remains null until Mojang hasJoinedServer proves ownership.
     */
    void beginKeyExchange(ServerLoginPacketListenerImpl login,
                          String username)
            throws ReflectiveOperationException {
        gameProfile.set(login, new GameProfile(null, username));
        setState(login, "KEY");
    }

    void setAuthenticating(ServerLoginPacketListenerImpl login)
            throws ReflectiveOperationException {
        setState(login, "AUTHENTICATING");
    }

    /**
     * Hands a fully Mojang-verified profile back to the legacy 1.20.1 tick
     * state machine. The server thread will then perform bans/whitelist/full
     * server/duplicate checks and complete the normal login.
     */
    void readyToAccept(ServerLoginPacketListenerImpl login,
                       GameProfile verifiedProfile)
            throws ReflectiveOperationException {
        gameProfile.set(login, verifiedProfile);
        setState(login, "VERIFYING");
    }

    boolean supportsState(String stateName) {
        return Arrays.stream(state.getType().getEnumConstants())
                .map(value -> ((Enum<?>) value).name())
                .anyMatch(stateName::equals);
    }

    Set<String> stateNames() {
        return Arrays.stream(state.getType().getEnumConstants())
                .map(value -> ((Enum<?>) value).name())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void setState(ServerLoginPacketListenerImpl login, String stateName)
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
            if (((Enum<?>) value).name().equals(name)) return value;
        }
        throw new NoSuchFieldException(
                "Estado NMS 1.20.1 no encontrado: " + name);
    }
}
