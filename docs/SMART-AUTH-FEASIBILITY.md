# SpigotAuth Smart Authentication — feasibility decision

## Requested behavior

- One standalone Paper/Spigot server (no FastLogin, ProtocolLib, AuthMe, Velocity, or BungeeCord).
- Premium and offline/cracked Java clients can join the same server.
- Premium accounts are authenticated automatically on first connection, registered automatically, and receive their real Mojang UUID.
- Offline accounts use the local `/register` and `/login` flow.

## Blocking protocol constraint (Java 1.20.1)

A server cannot securely prove that an offline-mode connection owns a public Mojang profile merely by looking up the username. The profile endpoint proves that a profile exists, not that the connecting client controls it.

The ownership proof used by the vanilla Java login protocol is the encrypted login challenge:
1. Server sends an Encryption Request with its public key and verify token.
2. Client authenticates its access token with the Minecraft session service (`join`).
3. Client returns the encrypted shared secret and verify token.
4. Server enables AES/CFB8 and validates the session with `hasJoined`, receiving the authenticated profile UUID.

On Java 1.20.1, sending that request to a vanilla offline/cracked client causes the client to attempt session authentication and disconnect when it has no valid premium session. Therefore, sending the challenge to every connection breaks the required cracked-player support. Skipping the challenge preserves cracked access but removes the proof of account ownership. A public profile lookup, matching username, client-supplied UUID, or cached result is not a safe substitute.

This is a client protocol limitation, not something a Bukkit event listener can fix. `PlayerJoinEvent` and ordinary Bukkit authentication hooks run after the login identity has already been selected.

## Decision

Do not implement a fake premium detector that auto-registers based on a profile lookup. Do not mark a player authenticated because their username exists on Mojang. That would allow anyone to impersonate a premium username on an offline-mode server.

For the exact requested combination on unmodified 1.20.1 clients, there is no secure, transparent server-only handshake that both proves premium ownership and lets cracked clients pass through the same handshake unchanged.

## Supported paths

### A. Secure premium verification
Run the server in `online-mode=true`. Vanilla Minecraft authenticates the premium account and supplies the real UUID. This is secure and automatic, but cracked clients cannot join.

### B. Mixed premium + offline access
Keep `online-mode=false`. All players use local authentication. Mojang profile lookup may be displayed as informational only; it must never bypass `/login` or `/register`. This supports both client types, but does not provide automatic premium ownership verification or guaranteed premium UUIDs.

### C. Mixed access with premium auto-login
Requires an explicit additional trust mechanism, such as a client-side authentication mod/launcher integration, or a separately authenticated account-linking flow. The user has explicitly rejected OAuth/device-code setup and external plugin dependencies, so this is not silently introduced.

## Implementation guardrails

- Never trust a player name, profile lookup, client-provided UUID, or offline UUID as proof of premium ownership.
- Never silently fall back from a failed premium verification to a premium identity.
- Mojang/session-service outages must fail closed for premium verification.
- Preserve the existing local password flow for offline-mode users.
- Do not claim the feature is implemented or tested until an actual protocol implementation passes premium and cracked-client integration tests.

## Required acceptance tests before claiming completion

1. Premium Java 1.20.1 client: ownership proof succeeds and authenticated Mojang UUID is used.
2. Offline/cracked Java 1.20.1 client: can still connect and use local registration/login.
3. Premium username impersonation from an offline client: never receives premium identity or automatic authentication.
4. Invalid/revoked premium session: rejected as premium.
5. Mojang/session-service timeout, rate limit, and 5xx: no premium bypass.
6. Reconnect, duplicate connections, and concurrent login attempts: no identity mix-up.
