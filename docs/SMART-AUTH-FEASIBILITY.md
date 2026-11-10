# SpigotAuth Smart Authentication — protocol design and implementation status

## Required behavior

- One standalone server, with no FastLogin, ProtocolLib, AuthMe, Velocity, or BungeeCord dependency.
- Premium and offline/cracked Java clients can join the same server.
- A premium player is verified automatically, auto-registered on first join, and receives the authenticated Mojang UUID.
- Offline players use the local `/register` and `/login` flow.
- Target baseline: Paper 1.20.1, Java 17. Spigot compatibility must be evaluated separately.

## Important correction: selective premium challenges exist

The earlier feasibility note incorrectly concluded that the mixed-mode behavior could not be achieved transparently with unmodified clients.

nLogin documents selective online authentication challenges in offline-mode:
- `challenge-if-premium-uuid` (documented for 1.19.1+).
- `challenge-if-premium-nickname`.
- A premium-name restriction option is also documented.

This establishes that a server can selectively challenge connections it classifies as premium instead of challenging every offline-mode connection. A cracked client attempting to claim a protected premium identity must not be granted that identity; a cracked player using an unclaimed/offline identity can continue through local authentication.

Official reference: https://docs.nickuc.com/es/nlogin/config/template/

This document describes the protocol behavior only. It does not copy nLogin code or claim that SpigotAuth already implements it.

## Why a normal Bukkit listener is insufficient

The ownership challenge belongs to the Minecraft login protocol, before Bukkit's `PlayerJoinEvent` and before the final player profile/UUID is established.

The relevant Java login exchange is:
1. Client sends Login Start.
2. Server optionally sends an Encryption Request containing its public key and verify token.
3. A premium client completes its authenticated session exchange and returns the encrypted response.
4. Server validates the response and asks the Minecraft session service `hasJoined` for the authenticated profile.
5. The server must use the UUID returned by that verified session before creating the Bukkit player.

A public Mojang profile lookup is only a discovery signal. It is not proof of ownership and must never trigger auto-login by itself.

Protocol reference: https://minecraft.wiki/w/Java_Edition_protocol/Encryption

## Implementation boundary for Paper 1.20.1

Paper's public Bukkit/Paper plugin API does not expose a supported event that lets a plugin replace the login authentication decision before the server finalizes the profile. The challenge therefore needs a version-specific login-layer integration, such as a carefully isolated NMS/connection adapter or a server-side patch.

Paper documents NMS as unstable and not guaranteed to remain compatible across versions:
https://docs.papermc.io/paper/dev/internals/

The adapter must:
- Intercept the login flow before the vanilla offline profile is finalized.
- Classify the login without treating a username lookup as authentication.
- Challenge only identities selected for premium verification.
- Validate the encrypted response and the session-service `hasJoined` result.
- Set the authenticated Mojang profile/UUID before player creation.
- Let non-premium identities continue through the normal offline login path.
- Reject an offline client attempting to impersonate a protected premium identity.
- Fail closed for a premium challenge if the session service is unavailable, times out, or returns an invalid response. Never downgrade that connection into a cracked identity with the same protected name.
- Avoid global online-mode toggles; they are process-wide and unsafe for concurrent connections.
- Keep all cryptographic/session work off the main server thread and protect against duplicate/concurrent login attempts.

No external plugin dependency is required by this design. However, an NMS adapter is tied to the exact server implementation/version and must be tested against the exact Paper 1.20.1 build. Spigot support cannot be claimed until separately verified.

## Current repository implementation status

Implemented today:
- Local password registration/login.
- Password hashing and lockout/timeout behavior.
- Informational Mojang profile lookup.
- In online-mode, automatic authentication based on the server's own successful vanilla session verification.

Implemented as an isolated building block in the current feature branch:
- `MinecraftSessionService` calculates the Minecraft signed SHA-1 server hash from the negotiated shared secret and server public key.
- It calls the official session service `hasJoined` asynchronously, validates the returned profile name, and returns the verified UUID. Invalid sessions return no profile; service/transport errors complete exceptionally so callers can fail closed.

Still not implemented:
- Selective pre-join premium challenge in offline-mode.
- Decrypting and validating the protocol Encryption Response in a login-layer adapter.
- Calling `MinecraftSessionService` from that adapter and applying the verified premium UUID before Bukkit player creation.
- Integration of `PremiumAuthService` into the pre-join adapter so it can only receive a session-verified profile.

`MinecraftSessionService` is not wired into the plugin yet and must not be treated as an authentication bypass. The protocol adapter remains the blocking component.

The current `PremiumChecker` is informational only. It must not be used to bypass local authentication in offline-mode.

## Acceptance tests required before enabling Smart Authentication

1. Premium Java 1.20.1 client: challenge succeeds; the session service returns the profile; Bukkit sees the real Mojang UUID; no password prompt.
2. First premium connection: account is created automatically and linked to the verified UUID.
3. Returning premium connection: auto-login succeeds without a password.
4. Offline/cracked client with a non-premium name: can connect and use `/register` and `/login`.
5. Offline client impersonating a protected premium name: rejected before player creation.
6. Invalid/revoked premium session: rejected; never falls back to offline identity.
7. Session-service timeout, rate limit, 5xx, and malformed responses: no premium bypass and no identity downgrade.
8. Concurrent connections with the same name, duplicate sessions, reconnects, and disconnects: no cross-connection identity mix-up.
9. Verify behavior on the exact Paper 1.20.1 build. Test Spigot independently before advertising compatibility.

Do not advertise Smart Authentication as implemented until these tests pass.


## Protocol implementation notes (Paper 1.20.1)

The Java Edition login protocol supports an Encryption Request even when the server is operating in offline mode. A vanilla premium client performs the launcher/session authentication and returns an Encryption Response; the server decrypts the shared secret and verify token, checks the token, computes the Minecraft signed SHA-1 server hash, and calls `hasJoined`. Only a successful response is an authenticated premium identity.

For 1.20.1, the relevant internal entry points are `ServerLoginPacketListenerImpl.handleHello` and `ServerLoginPacketListenerImpl.handleKey`; the corresponding packet exposes decryption of the secret and challenge validation. These are NMS internals, not Bukkit API contracts. Their names/signatures are version- and mapping-dependent. A standalone plugin adapter would need a Paper-specific integration strategy and lifecycle-safe interception before the login listener finishes profile creation. Reflectively replacing a private listener or mutating the pipeline without a tested compatibility layer is not accepted as production-ready.

References used for protocol design:
- Minecraft Java Edition protocol encryption: Encryption Request/Response, shared-secret verification and session-server hash.
- Minecraft 1.20.1 mappings for ServerLoginPacketListenerImpl and ServerboundKeyPacket.

## Automated tests added

JUnit tests cover:
- Mojang profile UUID normalization.
- Rejection of a session response whose name differs from Login Start.
- Rejection of malformed responses.
- Input validation before network requests.

These tests do not constitute a live Premium/Cracked integration test. A real client test matrix remains mandatory.


## Cryptographic challenge primitive added

`PremiumEncryptionChallenge` now implements the JDK-only cryptographic portion of the login exchange:
1. Generates a fresh 4-byte verify token using `SecureRandom`.
2. Decrypts the client's RSA/PKCS#1 v1.5 encrypted verify token and compares it in constant time.
3. Decrypts the RSA-encrypted shared secret and requires exactly 16 bytes before returning an AES key.
4. Returns defensive copies of the challenge token.

JUnit tests cover valid encrypted responses, incorrect tokens, and defensive copying. This primitive is not yet connected to a live Netty channel; it does not install AES/CFB8 stream ciphers, replace the login listener, or create the verified GameProfile. Those steps remain part of the Paper 1.20.1 adapter.


## AES/CFB8 transport primitive added

`MinecraftCipherFactory` now creates independent JDK `AES/CFB8/NoPadding` encrypt/decrypt ciphers using the 16-byte shared secret as both AES key and IV, matching the Java Edition login encryption setup. It rejects unsupported cipher modes and invalid key lengths. Unit tests verify a stream round-trip and invalid inputs.

This is a transport primitive only. It is not installed into a live Netty pipeline and must not be treated as a completed login adapter. The next integration task must preserve packet ordering and switch both channel directions only after the RSA verify token has passed.
