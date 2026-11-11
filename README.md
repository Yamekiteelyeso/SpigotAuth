# SLogin 1.8 — Synex Login

Standalone authentication plugin for Minecraft Java, **Paper 1.20.1 / Java 17**, with no plugin dependencies. Supports offline/cracked players and verified Premium Minecraft Java players on an online-mode=false server.

## Installation

1. Use the CI-built SLogin-1.8.1.jar production build, not the dev JAR.
2. Remove other conflicting login plugins. Install on Paper 1.20.1 and Java 17.
3. Restart, edit plugins/SLogin/config.yml and run /slogin status.
4. With online-mode=false and smart-auth.enabled=true, Smart Auth should say ACTIVE.

IMPORTANT: A public nickname lookup is not proof of identity. Premium autologin happens only after the encrypted handshake and official Mojang session verification. A real Microsoft/Minecraft authenticated client is needed for a complete end-to-end acceptance test.

## Commands

| Command | Description |
| --- | --- |
| /register password confirm (aliases /r, /reg) | Create an offline account |
| /login password (aliases /l, /log) | Log in to a local account |
| /changepass old new | Change password |
| /premium | Request Premium migration as an already registered, authenticated local player |
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
| /slogin premiumcancel player | Cancel a pending Premium migration (admin only; never changes verified Premium accounts) |

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
- /premium requires an already registered and authenticated local account. The request is pending until official verification on a subsequent connection; failed attempts do not silently grant Premium.
- Other plugin namespaces are rejected before login, and password failure counts survive reconnects within the configured attempt window.
- If another plugin takes /login, remove the conflicting auth plugin; Bukkit's namespaced fallback /slogin:login can help diagnose collisions.

## Testing and limitations

CI verifies Java build, unit tests, command metadata and Paper 1.20.1 offline-mode boot/protocol smoke tests. It can validate that an Encryption Request is sent, but NOT a real Microsoft/Minecraft account's entire verified session. Test that final step on an actual client and inspect logs/latest.log with smart-auth.debug enabled. SLogin NMS logic is specific to Paper 1.20.1 (protocol 763), not a claim of support for arbitrary Spigot/Paper versions.

## Reliability notice

A failed Mojang challenge must **never** automatically cancel an owner's pending /premium migration, because another client can use the same username to force a failed challenge. An admin may clear a stuck pending migration with /slogin premiumcancel <player>, and the existing local password is retained. Verified Premium account mappings are not downgraded by that command.

An empty equipment slot must not trigger an exception during pre-auth cleanup; tests cover this. These are regression fixes, not proof of complete Premium Microsoft authentication on a real client.
