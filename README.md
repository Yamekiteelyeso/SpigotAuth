# SLogin 1.9.3 — Synex Login

Standalone authentication plugin for Minecraft Java, **Paper 1.20.1 / Java 17**, with no plugin dependencies. Supports offline/cracked players and verified Premium Minecraft Java players on an online-mode=false server.

## Installation

1. Use the CI-built SLogin-1.9.3.jar production build, not the dev JAR.
2. Remove other conflicting login plugins. Install on Paper 1.20.1 and Java 17.
3. Restart, edit plugins/SLogin/config.yml and run /slogin status.
4. With online-mode=false and smart-auth.enabled=true, run `/slogin status`. Only Paper 1.20.1 can show ACTIVE; if inactive, the diagnostic line explains why.

IMPORTANT: A public nickname lookup is not proof of identity. Premium autologin happens only after the encrypted handshake and official Mojang session verification. A real Microsoft/Minecraft authenticated client is needed for a complete end-to-end acceptance test.

## Commands

| Command | Description |
| --- | --- |
| /register password confirm (aliases /r, /reg) | Create an offline account |
| /login password (aliases /l, /log) | Log in to a local account |
| /changepass old new | Change password |
| /premium | Request Premium migration for a signed-in local account (confirm on reconnect) |
| /premium status | Show whether the account is pending, verified, or blocked by configuration |
| /premium cancel | Cancel your own pending migration while authenticated locally |
| /slogin help | All admin commands |
| /slogin status | Premium handshake and location diagnostics |
| /slogin reload | Reload config and saved destinations |
| /slogin spawnlogin | Set login waiting spawn at your current standing block |
| /slogin loginready | Set post-auth destination at your current standing block |
| /slogin spawnlogin set | Move/update login spawn |
| /slogin loginready set | Move/update post-auth destination |
| /slogin spawnlogin show | Show location |
| /slogin loginready show | Show location |
| /slogin spawnlogin tp | Teleport to location |
| /slogin loginready tp | Teleport to location |
| /slogin spawnlogin delete | Delete location |
| /slogin loginready delete | Delete location |
| /slogin resetmessages | Apply redesigned ES/EN chat messages, backing up config.yml first |
| /slogin premiumcheck player | Test public profile lookup (does not authenticate a client) |
| /slogin premiumcancel player | Cancel a pending Premium migration (admin only; never changes verified Premium accounts) |
| /slogin premiumcheck player | Diagnose public Minecraft profile lookup (not authentication) |

Location actions: set/edit/editar, delete/remove/clear/borrar, show/info/ver, tp/teleport.
A player with slogin.admin (OP by default) must be standing on a block to save a location; the saved point centers X and Z, preserves standing height and view direction, and includes the world. Both points are independently saved in plugins/SLogin/locations.yml. If unset, automatic teleports are skipped. Console can show/delete locations but cannot set/teleport.

After a local /login or /register or successful verified Premium join, players are teleported to loginready if configured; unauthenticated players are teleported to spawnlogin.

## Main configuration

    language: es
    security:
      min-password-length: 8
      max-password-length: 64
      max-attempts: 5
      attempt-window-seconds: 600
      lock-minutes: 5
      login-timeout-seconds: 60
    teleports:
      on-join-to-spawnlogin: true
      after-auth-to-loginready: true
    smart-auth:
      enabled: true
      debug: false
      challenge-if-premium-nickname: true
      auto-login-registered-premium: true
      session-timeout-ms: 10000
      fail-closed-on-lookup-error: false

## Premium behavior

- Premium new and registered players receive the official challenge and bypass the password only when Mojang hasJoinedServer returns a verified matching identity.
- Previously verified names challenge their known UUID directly without a public name lookup.
- Existing local password data is preserved on Premium-name collisions; one username does not prove both identities have the same owner.
- Offline users with Premium usernames may be blocked by Premium-first challenge. To allow previously registered local usernames to use local login first, set smart-auth.auto-login-registered-premium to false. This deliberately changes the precedence/security policy.
- /premium requires an already registered and authenticated local account. The request is pending until official verification on a subsequent connection; failed attempts do not silently grant Premium. `/premium status` checks it, `/premium cancel` cancels it before verification.
- Other plugin namespaces are rejected before login, and password failure counts survive reconnects within the configured attempt window.
- If another plugin takes /login, remove the conflicting auth plugin; Bukkit's namespaced fallback /slogin:login can help diagnose collisions.

## Testing and limitations

CI verifies Java build, unit tests, command metadata and Paper 1.20.1 offline-mode boot/protocol smoke tests. It can validate that an Encryption Request is sent, but NOT a real Microsoft/Minecraft account's entire verified session. Test that final step on an actual client and inspect logs/latest.log with smart-auth.debug enabled. SLogin NMS logic is specific to Paper 1.20.1 (protocol 763), not a claim of support for arbitrary Spigot/Paper versions.

## Reliability notice

A failed Mojang challenge must **never** automatically cancel an owner's pending /premium migration, because another client can use the same username to force a failed challenge. An admin may clear a stuck pending migration with /slogin premiumcancel <player>, and the existing local password is retained. Verified Premium account mappings are not downgraded by that command.

An empty equipment slot must not trigger an exception during pre-auth cleanup; tests cover this. These are regression fixes, not proof of complete Premium Microsoft authentication on a real client.

## Premium connection troubleshooting

Run `/slogin status` to ensure Smart Auth is active on Paper 1.20.1, then `/slogin premiumcheck <name>` to diagnose whether Mojang's public profile service is reachable. A positive profile lookup is NOT proof of ownership; the client's session must pass Minecraft's encrypted challenge plus `hasJoinedServer`. The connection now reports a distinct message for invalid official sessions versus Mojang/network outages. A `/slogin reload` closes only connections still in the authentication handshake, not already logged-in players.

## Premium first-login improvements (1.9.0)

Paper 1.20.1 may include an optional client-supplied UUID in Login Start. When
smart-auth.challenge-if-client-online-uuid is enabled, a version-4 UUID triggers
the official encryption challenge even if public profile lookup is unavailable.
**A claimed UUID is never proof of ownership**: only a successful Mojang
hasJoinedServer verification can grant passwordless access. Some offline
launchers may send forged/random v4 UUIDs and be challenged; disable this option
when they must use local-password login for unprotected names.

The public nickname lookup now checks three official endpoints, and
premium-negative-cache-seconds limits negative cache duration. An unavailable
public lookup is not treated as proof of an offline account.

**If you already used an older SLogin version, run /slogin resetmessages**
to apply the refreshed Spanish/English messages. The plugin backs up your
config.yml before replacing only the messages section.

CI uses a **separate non-production** fixture plugin to emulate a successful
sessionserver response and tests actual RSA, AES and Login Success for
both pending /premium and a first-time Premium username. The fixture is not
shipped in SLogin-1.9.3.jar. CI cannot replace the final verification with a
real Microsoft/Mojang account session.

## 1.9.3: reconnect safety and pre-login isolation

Unauthenticated players are confined horizontally while gravity and vertical
movement remain free so an airborne reconnect doesn't get stuck and kicked for
flying. Only SLogin's own /spawnlogin teleport is allowed before authentication.
Other plugin teleports and additional interactions are blocked. Authenticated
state and pending login timers are bound to the actual Player connection, not
just its UUID, preventing a new connection from inheriting a previous login.

Test in-game: register/login, jump and disconnect while airborne, reconnect,
fall to the ground, and log in normally. The production Premium verification
path was not modified; also retest /premium on your Paper 1.20.1 server.

## 1.9.3 Premium impersonation protection and compact prompts

Already-verified Premium names are protected even on offline-mode servers.
When a connecting client advertises the standard offline UUID for a protected
Premium name, SLogin sends a clear "not the owner" kick before authentication.
A forged online UUID is NOT trusted: the official Mojang encrypted challenge
and hasJoined verification remain mandatory. A corrupt verified binding stays
fail-closed instead of permitting local password login.

Registration and login prompts now display a single short command line. The
existing aliases (/r, /reg, /l and /log) remain functional but hidden from
prompts. Update existing messages with /slogin resetmessages (creates backup).
CI exercises offline-UUID denial and the existing mocked Premium handshake,
but live official-account behavior should be tested in Minecraft.
