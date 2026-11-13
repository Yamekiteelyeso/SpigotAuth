# SLogin 1.9.4 — Synex Login

Standalone mixed-mode authentication for **Paper 1.20.1 / Java 17**.

## Configuration files

- `plugins/SLogin/config.yml` — language, security, teleports and Smart Auth settings.
- `plugins/SLogin/messages.yml` — all editable Spanish and English texts.
- `plugins/SLogin/accounts.yml` — local password hashes.
- `plugins/SLogin/premium-accounts.yml` and `premium-preferences.yml` — verified identities and migration requests.
- `plugins/SLogin/locations.yml` — optional login spawn and post-login destinations.

**Upgrading from 1.9.3 or earlier:** SLogin copies your existing `config.yml`
messages into `messages.yml` without losing custom keys; it creates a
`config.yml.backup-messages-...` before removing the legacy section.
Other settings remain unchanged. A new installation creates separated files.
`/slogin reload` reloads both settings and `messages.yml`. Use
`/slogin resetmessages` only if you want the bundled defaults; it backs
up `messages.yml` before resetting.

## New opt-in Premium registration mode

Edit `plugins/SLogin/config.yml`:

```yaml
smart-auth:
  enabled: true
  require-register-before-premium: true
```

Run `/slogin reload` or restart.

- **false (default):** current automatic Premium challenge and login works,
  including first-time accounts.
- **true:** an unlinked username enters via local `/register password password`
  (or `/login` for existing local users), and then can use `/premium`.
  The migration remains pending until they reconnect using the official
  Minecraft launcher and Mojang session verification succeeds.
- **Already verified Premium identities** still require their exact verified
  identity and sign in automatically. **Pending /premium migrations** still
  require the encrypted Mojang session challenge. Neither case is downgraded
  to local-password auth by the setting.
- Manual mode deliberately lets a player create a local password under a
  Premium nickname *before it is officially bound*; this does not establish
  Premium ownership. Staff should understand this ownership/claiming tradeoff.

**Security:** a public username or client-advertised UUID never proves Premium
ownership. Mojang's encrypted `hasJoined` check remains mandatory. If
`smart-auth.enabled` is off or Paper is not exactly 1.20.1, Premium
verification is unavailable even when the new mode is set to true.

## Commands

- `/register <password> <password>` (`/r`, `/reg`)
- `/login <password>` (`/l`, `/log`)
- `/premium`, `/premium status`, `/premium cancel`
- `/changepass <old> <new>`
- `/slogin help`, `/slogin status`, `/slogin reload`
- `/slogin spawnlogin`, `/slogin loginready` (set/show/delete/tp)
- `/slogin resetmessages` — restore only `messages.yml` (with backup)
- `/slogin premiumcancel <name>` — admin pending migration rollback
- `/slogin premiumcheck <name>` — public profile lookup, **not** ownership proof

## CI and compatibility

The GitHub Actions pipeline builds the production reobfuscated JAR, tests
unit-level authentication and messages migration, runs Paper 1.20.1 with
`online-mode=false`, and uses a separate test-only Mojang session fixture
to exercise protocol transitions. Manual mode is also tested against the
actual running Paper server. CI **does not** validate an external real
Microsoft-account session; test that with real clients before production.
