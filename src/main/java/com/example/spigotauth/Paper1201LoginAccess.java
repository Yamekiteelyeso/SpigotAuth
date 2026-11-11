package com.example.spigotauth;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves and validates the exact Paper/Minecraft 1.20.1 login fields SLogin
 * needs. Resolution happens once at plugin startup instead of guessing fields
 * independently for every connection.
 */
final class Paper1201LoginAccess {

    private static final Set<String> REQUIRED_STATES =
            Set.of("HELLO", "KEY", "AUTHENTICATING", "VERIFYING");

    private final Field state;
    private final Field authenticatedProfile;
    private final Field challenge;
    private final Field serverId;
    private final Field requestedUsername;

    private Paper1201LoginAccess(Field state,
                                 Field authenticatedProfile,
                                 Field challenge,
                                 Field serverId,
                                 Field requestedUsername) {
        this.state = state;
        this.authenticatedProfile = authenticatedProfile;
        this.challenge = challenge;
        this.serverId = serverId;
        this.requestedUsername = requestedUsername;
    }

    static Paper1201LoginAccess resolve() throws ReflectiveOperationException {
        Class<?> owner = ServerLoginPacketListenerImpl.class;

        Field state = null;
        Field profile = null;
        Field challenge = null;
        Field serverId = null;
        Field requestedUsername = null;

        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;

            Class<?> type = field.getType();
            int modifiers = field.getModifiers();

            if (type.isEnum() && containsRequiredStates(type)) {
                state = unique(state, field, "login state");
            } else if (type == GameProfile.class) {
                profile = unique(profile, field, "authenticated profile");
            } else if (type == byte[].class && Modifier.isFinal(modifiers)) {
                challenge = unique(challenge, field, "challenge");
            } else if (type == String.class && Modifier.isFinal(modifiers)) {
                serverId = unique(serverId, field, "serverId");
            } else if (type == String.class && !Modifier.isFinal(modifiers)) {
                requestedUsername = unique(
                        requestedUsername, field, "requestedUsername");
            }
        }

        if (state == null) throw new NoSuchFieldException("1.20.1 login state");
        if (profile == null) throw new NoSuchFieldException("authenticatedProfile");
        if (challenge == null) throw new NoSuchFieldException("challenge");
        if (serverId == null) throw new NoSuchFieldException("serverId");
        if (requestedUsername == null) {
            throw new NoSuchFieldException("requestedUsername");
        }

        for (Field field : new Field[]{
                state, profile, challenge, serverId, requestedUsername}) {
            field.setAccessible(true);
        }

        return new Paper1201LoginAccess(
                state, profile, challenge, serverId, requestedUsername);
    }

    byte[] challenge(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        byte[] value = (byte[]) challenge.get(login);
        if (value == null || value.length == 0) {
            throw new IllegalStateException("Paper devolvió un challenge vacío.");
        }
        return value.clone();
    }

    String serverId(ServerLoginPacketListenerImpl login)
            throws IllegalAccessException {
        String value = (String) serverId.get(login);
        return value == null ? "" : value;
    }

    void initializeHello(ServerLoginPacketListenerImpl login,
                         String username)
            throws ReflectiveOperationException {
        requestedUsername.set(login, username);
        // Do not inject an unverified GameProfile here. The Mojang profile is
        // written only after hasJoined succeeds.
        authenticatedProfile.set(login, null);
        setState(login, "KEY");
    }

    void setAuthenticatedProfile(ServerLoginPacketListenerImpl login,
                                 GameProfile profile)
            throws IllegalAccessException {
        authenticatedProfile.set(login, profile);
    }

    void setState(ServerLoginPacketListenerImpl login, String stateName)
            throws ReflectiveOperationException {
        state.set(login, enumConstant(state.getType(), stateName));
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

    private static boolean containsRequiredStates(Class<?> type) {
        Set<String> names = Arrays.stream(type.getEnumConstants())
                .map(value -> ((Enum<?>) value).name())
                .collect(Collectors.toSet());
        return names.containsAll(REQUIRED_STATES);
    }

    private static Field unique(Field current, Field candidate, String label)
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
