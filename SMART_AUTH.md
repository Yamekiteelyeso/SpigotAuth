# SpigotAuth Smart Authentication

SpigotAuth supports automatic premium login on a standalone Spigot/Paper server in offline mode by integrating with FastLogin. FastLogin performs the Minecraft protocol handshake and verifies the client session against the official session service. SpigotAuth does not treat a public username lookup as proof of ownership.

## Requirements

- Spigot/Paper server with `online-mode=false`
- Java 17 for this SpigotAuth build
- FastLogin Bukkit build compatible with your Minecraft version
- ProtocolLib version required by your FastLogin build (the current FastLogin documentation lists ProtocolLib 5.3+ development build 720+ for standalone Bukkit/Paper)
- SpigotAuth 1.2.0+

Install ProtocolLib, FastLogin and SpigotAuth in the server's `plugins` folder. Restart the server; do not use PlugMan or hot-reload to install or update authentication plugins.

## FastLogin configuration

After the first server start, edit `plugins/FastLogin/config.yml` and set:

```yaml
autoRegister: true
autoLogin: true
premiumUuid: true
nameChangeCheck: false
auto-register-unknown: false
```

Keep FastLogin's own database enabled. It records the premium status needed for later joins. The exact available options may vary by FastLogin release; preserve unrelated settings from its generated configuration.

- `autoRegister: true` enables the premium account check on first connection.
- `autoLogin: true` allows verified premium sessions to skip local password authentication.
- `premiumUuid: true` makes premium players use their official premium UUID.
- `nameChangeCheck: false` avoids relying on name-change heuristics for account ownership.
- `auto-register-unknown: false` avoids silently taking over a pre-existing local account.

SpigotAuth checks these three required FastLogin settings at startup. If any is disabled, it will not install the premium bridge. With `autoRegister: true`, a cracked player cannot use the name of an existing premium Java profile; FastLogin requires a valid premium session for that name. This is intentional protection against premium-name impersonation.

Restart the server after changing FastLogin's configuration.

If you do not want FastLogin to display its generated registration password to the player, open `plugins/FastLogin/messages.yml` and set the `auto-register` message to an empty value. SpigotAuth stores only the password hash; the generated password is not needed for normal premium auto-login.

## How the bridge works

1. FastLogin intercepts the login protocol before the Bukkit player joins.
2. The client proves possession of a valid Minecraft session through the official session verification flow.
3. FastLogin passes the verified player to its authentication hook.
4. SpigotAuth's bridge checks the local account and the persistent premium UUID association.
5. A new premium account is registered with a cryptographically random password and linked to the verified premium UUID. Only its hash is stored by SpigotAuth.
6. A previously linked premium account is authenticated automatically.
7. A local account that exists but is not linked to that premium UUID is **not** auto-authenticated. Its owner must use the existing local password. This prevents a premium login from silently taking over a cracked account with the same name.
8. Players that FastLogin classifies as cracked continue to use `/login` or `/register`.

SpigotAuth stores UUID/name associations in `plugins/SpigotAuth/premium-accounts.yml`. Password hashes remain in `accounts.yml`.

## Important account migration behavior

Premium and offline UUIDs are different. Enabling `premiumUuid` can make a player's Bukkit UUID change. Back up player data and test on a staging copy before enabling this on a live server.

If a local account already exists but has no premium UUID association, Smart Authentication deliberately refuses to auto-login it. The player can still authenticate using the existing password. Automatic linking of that account should only happen after a successful local password login and an independently verified FastLogin premium status; it must never be based on the username alone.

## Failure behavior

- If FastLogin or ProtocolLib is missing or disabled, SpigotAuth falls back to local password authentication.
- If FastLogin's API is incompatible, the bridge does not grant premium access.
- If the session check fails or the verification service is unavailable, no premium auto-login is granted.
- A public Mojang profile lookup is informational only and is never an ownership check.

## Troubleshooting

Check the server console for:

- `Integración FastLogin instalada`: the bridge registered successfully.
- `FastLogin no está instalado`: install/enable FastLogin and ProtocolLib.
- `FastLogin ya tiene otro hook`: remove conflicting authentication-plugin integrations or use a dedicated SpigotAuth setup.
- `El login premium automático seguirá desactivado`: the API was unavailable or incompatible; players remain protected by local login.

Do not expose the backend port to untrusted networks if you later move behind a proxy. For a standalone server, keep the normal server firewall and whitelist/rate-limit administrative access.
