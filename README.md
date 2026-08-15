# Amar Console

An Android control surface for a self-hosted AI development stack. Start agent tasks
from your phone, watch them run, and see whether the services behind them are healthy.

The app is deployment-agnostic: no URL, host or credential is compiled into the binary.
You point it at your own OpenHands, OpenCode, LiteLLM and gateway endpoints in Settings,
and the same APK works against any of them.

> **Note on this repository.** `Amar-Helper-` previously held a Next.js web app,
> "Amar Doc-Helper" (a Bangladesh government-document assistant). That project is
> unrelated to this one and remains in git history and on the `main` branch; this branch
> replaces the working tree with the Android app. To recover the old files:
> `git checkout main -- app/page.js app/layout.js app/globals.css postcss.config.js talwind.config.js`.

## What it does

* **Dashboard** — service health at a glance, active sessions, recent activity, quick actions.
* **New task** — pick an agent, describe the work in natural language, submit.
* **Session console** — a terminal-style view of agent output, streamed where the
  provider supports it, with tool calls, errors and final results.
* **Sessions** — active and previous conversations across every configured provider.
* **Services** — per-service online/offline state, latency, version and last check.
* **Settings** — endpoints, credentials, connection tests, theme, polling, diagnostics.

Every screen has explicit loading, empty, error and retry states. The app does not crash
or hang when a backend is unreachable — it says what failed and offers the next step.

## Architecture

Compose UI → ViewModel → Repository → API client → backend. UI never performs I/O,
repositories never throw (`ApiResult<T>` carries a typed `AppError`), and each service
gets its own HTTP client carrying only its own credential. Full detail, including the
API contracts used and the gaps in them, is in [ARCHITECTURE.md](ARCHITECTURE.md).

**Stack:** Kotlin, Jetpack Compose, Material 3, Hilt, Coroutines/Flow, Retrofit + OkHttp,
kotlinx.serialization, DataStore, Android Keystore. `minSdk` 26, `targetSdk`/`compileSdk` 35.

## Setup

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35.0.0.

```bash
git clone https://github.com/RohanKamal123/Amar-Helper-.git
cd Amar-Helper-
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties   # not committed
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Configuration

Open Settings and enter the base URL of each service you run. Nothing is required except
the services you actually use.

| Service | Typical URL | Notes |
| --- | --- | --- |
| OpenHands | `https://openhands.your-tailnet.ts.net` | Bearer token if your deployment requires one |
| OpenCode | `http://box.your-tailnet.ts.net:4096` | started with `opencode serve` |
| LiteLLM | `https://litellm.your-tailnet.ts.net` | health probes only; the app never sends your master key |
| Gateway / API | `https://gw.your-tailnet.ts.net` | optional; supplies PostgreSQL and Redis health |

**Network topology.** The app is built for VPN-only access (Tailscale or WireGuard).
Cleartext `http://` is permitted to tailnet hosts — both MagicDNS names and
`100.64.0.0/10` addresses — plus RFC1918 LAN addresses and loopback. Any public host
must use `https://`. A bare host with no scheme defaults to `http://` when it is private
or tailnet, and `https://` otherwise, so pasting `vmi3507647.tail7bf6b1.ts.net:4096`
does the right thing.

**Credentials.** Tokens are encrypted with an AES-256 key held in the Android Keystore
and are never displayed again after saving. See [SECURITY.md](SECURITY.md).

## Development

```bash
./gradlew testDebugUnitTest    # unit, contract, integration and ViewModel tests
./gradlew lintDebug            # Android lint
./gradlew assembleDebug        # debug APK
./gradlew clean                # start over
./tools/scan_secrets.sh        # fail if anything credential-shaped is tracked
```

Run the app against a local stand-in for the whole stack:

```bash
python3 tools/mock_backend.py --port 8099
```

It implements the documented routes including the SSE stream, simulates an agent that
produces output over several seconds, and can inject failures (`/__control/fail?status=500`,
`/__control/latency?ms=3000`) so error and recovery states can be exercised. See
[TESTING.md](TESTING.md).

## Testing

Unit, HTTP contract, repository integration, streaming and ViewModel state-machine tests
run on the JVM. Instrumented UI tests live in `app/src/androidTest` and run on the
emulator job in CI. [TESTING.md](TESTING.md) documents what is covered and what has not
been executed.

## Release

```bash
./gradlew assembleRelease
```

Release builds are minified and shrunk with R8. **Signing is not configured in this
repository** — no keystore is committed and none is referenced, so `assembleRelease`
produces an unsigned APK. To sign, create a keystore outside the repo and add a
`signingConfigs` block reading from `keystore.properties` (already in `.gitignore`), or
use Play App Signing with an upload key held in CI secrets.

## CI

`.github/workflows/android.yml` builds, runs the JVM test suite, lints, assembles the
debug APK, and runs the instrumented suite on an emulator. `secret-scan.yml` runs the
credential scan on every push. No workflow depends on private credentials.

## Status and limitations

* Cancelling a running task, replaying an OpenHands transcript, and deleting an
  OpenHands conversation are **not supported by the published provider APIs**. The app
  says so where you would expect those actions, rather than pretending they worked.
* OpenHands sessions are polled for status; OpenCode sessions stream live output.
* Push notifications are not implemented.
* The instrumented UI suite has not been executed in this project's development
  environment (no KVM for an emulator); it runs in CI.
