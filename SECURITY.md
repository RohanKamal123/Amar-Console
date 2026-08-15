# Security

## Threat model

Amar Console is a remote control for infrastructure that executes code. An attacker who
obtains its credentials can run arbitrary commands on your machines. The design assumes
the phone is the weakest link: it can be lost, backed up, or restored onto another
device.

## Credential handling

* Tokens are encrypted with **AES-256-GCM** using a key generated in the **Android
  Keystore** (`amar_console_credential_key`). The key is non-exportable and, on devices
  with a secure element, never enters application memory.
* Only the IV + ciphertext is persisted, in **DataStore**. `SharedPreferences` is not
  used anywhere in this app.
* There is **no API that returns a token to the UI layer.** Settings can ask whether a
  credential exists and when it was saved — nothing more. A saved token is never
  displayed, not even masked-but-recoverable.
* Each service has its own OkHttp client with its own `AuthInterceptor`, so a credential
  for one host is never attached to a request to another.
* If the Keystore key becomes unusable (device credential removed, restore onto new
  hardware), the unusable ciphertext is discarded and the user is asked to re-enter the
  token. Decryption failure never crashes the app.
* "Sign out of all services" in Settings erases every stored secret.
* Backups are disabled (`allowBackup="false"`) and the extraction rules exclude every
  data domain, so credentials cannot leave the device through cloud backup or
  device-to-device transfer.

## Network security

The intended topology is **VPN-only**: services stay unexposed to the public internet
and the phone reaches them over Tailscale or WireGuard.

Cleartext HTTP is refused for any host reachable from the public internet. It is allowed
only where it cannot be intercepted:

* `*.ts.net` — Tailscale MagicDNS names
* `100.64.0.0/10` — tailnet addresses (traffic is already WireGuard-encrypted)
* `10/8`, `192.168/16`, `172.16/12` — RFC1918 LAN addresses
* `localhost` / `127.0.0.0/8` — on-device testing

Everything else must be `https://`, validated against the system trust store. The app
does **not** disable certificate validation, does not ship a custom trust manager, and
does not accept user-installed CAs.

**Where this is enforced.** In `UrlValidator`, at the moment a URL is saved — not in
`network_security_config.xml`. That is a deliberate trade-off, and it is worth being
explicit about why the platform layer was given up:

Android's network security config matches hostnames, not address ranges. A static config
strict enough to block public cleartext also blocks `http://100.87.52.65:4096` — a
tailnet peer's own address, the one the Tailscale app shows first, and one that is not
routable from the internet at all. Blocking it produced a failure that looked like a
network outage while the VPN was healthy. Expressing "private ranges yes, public no"
requires range matching, which only the validator can do.

The consequence is that `UrlValidator` is now the single gate rather than one of two, so
it is held to that standard: it is the only writer of service URLs (Settings →
`saveUrl` → `ConfigStore`), and its rules are covered by unit tests including the
addresses immediately outside the tailnet range.

## Logging

* `AppLogger` compiles DEBUG/INFO out of release builds via `BuildConfig.VERBOSE_LOGGING`.
* HTTP body logging exists only in debug builds, is off unless enabled in Settings →
  Diagnostics, and redacts `Authorization`, `Proxy-Authorization`, `Cookie` and
  `Set-Cookie` unconditionally.
* Error text shown to the user is built from the mapped `AppError`, never from a raw
  response body, so a service that echoes a credential in an error cannot leak it into
  the UI or a screenshot. The SSE parser likewise emits a fixed message for error frames
  instead of the payload.
* No analytics, crash reporting, or telemetry of any kind is present. The app contacts
  only the hosts you configure.

## Secret management in the repository

* `.gitignore` excludes `local.properties`, `*.jks`, `*.keystore`, `keystore.properties`,
  `signing.properties`, `secrets.properties`, `.env*` (except `.env.example`), `*.pem`,
  `*.p12`, `*.key` and `google-services.json`.
* `tools/scan_secrets.sh` greps every tracked file for private key blocks, AWS keys,
  OpenAI/GitHub/Slack token shapes, JWTs, and `password=`/`secret=`/`api_key=`
  assignments, and fails if any env file or key material is tracked. It runs in CI on
  every push (`.github/workflows/secret-scan.yml`) and should be run before every
  release.
* No credential of any kind is compiled into the app. There is no default backend URL,
  no bundled API key, and no fallback token.

## Authentication architecture

`AuthInterceptor` sends `Authorization: Bearer <token>` when a credential is stored for
that service, and sends nothing when one is not — so an unauthenticated deployment
behind a VPN works unchanged.

The abstraction is deliberately thin so it can evolve: adding OAuth/OIDC means adding a
token provider that can refresh, behind the same interceptor. Expiry is currently
surfaced reactively — a 401 becomes `AppError.Unauthorized`, which tells the user to
update the token rather than retrying a request that will fail identically.

**Not yet implemented:** automatic token refresh, biometric gating before a credential
is used, and certificate pinning. Pinning in particular is worth adding if you move off
a VPN-only topology.

## If you expose these services publicly

Don't, if you can avoid it — OpenHands is an agent runtime that executes arbitrary code.
If you must, put an authenticating reverse proxy in front of it, terminate TLS with a
real certificate, and issue the phone a scoped token that is not your LiteLLM master
key.

## Reporting

This is a personal-infrastructure tool. Report issues through the repository's issue
tracker; do not include tokens, hostnames, or logs containing either.
