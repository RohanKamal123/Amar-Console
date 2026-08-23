# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A native Android client for a **self-hosted** AI development stack (OpenHands, OpenCode,
a LiteLLM-compatible router, code-server), reached over a Tailscale/WireGuard tailnet.
No URL, host, or credential is compiled in — everything is entered at runtime in
Settings, so one APK works against any deployment.

## Environment setup

The Android SDK is not part of the repo and `local.properties` is gitignored, so a fresh
checkout needs it recreated before Gradle will run:

```bash
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
```

`gradle.properties` is tuned for a memory-constrained container
(`-Xmx1g`, `org.gradle.parallel=false`, `kotlin.compiler.execution.strategy=in-process`).
Builds are slower than default but survive; raising these has caused OOM kills here.

## Commands

```bash
./gradlew testDebugUnitTest        # unit + contract + integration + ViewModel tests (~3-4 min)
./gradlew lintDebug                # Android lint (abortOnError = true)
./gradlew assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk
./tools/scan_secrets.sh            # fails if anything credential-shaped is tracked; also runs in CI

# A single class or method:
./gradlew testDebugUnitTest --tests '*UrlValidatorTest*'
./gradlew testDebugUnitTest --tests '*ApiContractTest.a dropped connection*'
```

`connectedDebugAndroidTest` (the Compose suite in `app/src/androidTest`) **cannot run in
this container** — there is no `/dev/kvm`, so no emulator. It runs in CI instead
(`.github/workflows/android.yml`, `instrumentation` job on GitHub's runners). Never claim
on-device behaviour was verified locally.

### Exercising the app without the VPS

`tools/mock_backend.py` implements the same routes as the real services — including the
OpenCode SSE stream and OpenHands' catch-all behaviour — with failure injection:

```bash
python3 tools/mock_backend.py --port 8099 &
curl "http://localhost:8099/__control/fail?status=500"   # then ?status=0 to recover
MOCK_BACKEND_URL=http://127.0.0.1:8099 ./gradlew testDebugUnitTest --tests '*LiveBackendWorkflowTest*'
```

`LiveBackendWorkflowTest` is skipped unless `MOCK_BACKEND_URL` is set (hence 1 skipped
test in a normal run). It drives the real repositories over a real socket and is the
closest thing to an end-to-end check available off-device.

## Architecture

### Two UIs coexist

The app pivoted to a **workspace shell**: `ui/workspace/WorkspaceScreen.kt` hosts the
upstream web interfaces (IDE / OpenCode / OpenHands) in isolated `WebView` tabs, plus
native Profile and Services tabs. The **native client layer still exists and still
works** — dashboard, new task, sessions, and the rich session console are all reachable
by route (`ui/navigation/Destinations.kt`) — but is no longer the default entry point.
Changes to the native path do not affect the WebView path and vice versa.

### Layering

```
Compose UI  →  ViewModel  →  Repository  →  API client (Retrofit/OkHttp)  →  backend
```

Composables perform no I/O. **Repositories never throw**: every call returns
`ApiResult<T>` carrying a closed `AppError` set (`Offline`, `Timeout`, `Unauthorized`,
`NotFound`, `RateLimited`, `ServerError`, `Malformed`, `NotConfigured`, `Unsupported`,
`Cancelled`…). Each error knows whether a retry could help, which is what drives whether
the UI offers a Retry button. Add new failure modes to `AppError`, not to screens.

`ApiClientFactory` builds and caches one Retrofit client per `(service, base URL)`; base
URLs are runtime config, so the cache is invalidated whenever a URL or credential
changes. Each service gets its own client and its own `AuthInterceptor`, so a token can
never be attached to a request for a different host.

### Providers differ, and the domain model hides it

| | OpenHands (self-hosted OSS) | OpenCode |
| --- | --- | --- |
| Transcript | `GET /api/conversations/{id}/events` | `GET /session/{id}/message` |
| Realtime | **Socket.IO** `/socket.io` | **SSE** `GET /event` |
| Send message | `oh_user_action` over the socket | `POST /session/{id}/prompt_async` |
| Cancel | `POST /api/conversations/{id}/stop` | not supported by its API |

`liveEvents()` returns `Flow<RealtimeUpdate>` — a sealed type of `Connected`,
`Reconnecting`, `Event`, `AgentStatus` — so both transports feed one console.

`OpenHandsRealtimeClient` connects with `conversation_id`, `latest_event_id`,
`providers_set` and an optional per-conversation key. The server **replays from
`latest_event_id + 1` on connect**, which is what makes reconnect-after-backgrounding
resume without duplicating transcript.

`OpenHandsEventMapper` classifies each incoming event as `Transcript`, `Status`, or
`Ignore`. This is load-bearing: `agent_state_changed` observations are bookkeeping and
must become the status indicator ("Agent: thinking…"), never transcript entries.

### Rich chat rendering

`ui/console/RichTextParser.kt` parses agent output into `RichBlock`s (heading,
paragraph, list, code, diff) and `RichChatContent.kt` renders them, with tool calls as
collapsible cards. The parser is deliberately Compose-free so it is unit-testable on the
JVM (`RichTextParserTest`). There is no markdown library dependency.

## Constraints that have already caused bugs

Each of these cost a debugging round. Do not undo them without reading the git history.

**OpenHands OSS is not OpenHands Cloud.** The app targets the self-hosted OSS API
(`/api/conversations`, `conversation_id`, `status` + `runtime_status`). The Cloud product
uses `/api/v1/app-conversations` with different field names. The OSS server serves its
frontend from a **catch-all**, so a wrong path returns **HTTP 200 with an HTML body**,
not a 404 — which surfaces as `AppError.Malformed`, not `NotFound`. Confirm any new
route against the server source (tag `0.62.0`: `openhands/server/routes/`), never against
Cloud documentation.

**`UrlValidator` is the only cleartext gate.** `network_security_config.xml` permits
cleartext at the platform level *on purpose*: Android matches hostnames, not CIDR ranges,
so a config strict enough to block public HTTP also blocked `http://100.x.y.z` — a
tailnet peer's own address. `UrlValidator` allows http to tailnet / RFC1918 / loopback
and refuses it for anything public. Its rules, including the addresses just outside
`100.64.0.0/10`, are covered by tests. Weakening it removes the only protection.

**POSTs are never replayed.** `RetryInterceptor` retries GET/HEAD/PUT/DELETE (and
requests opting in via `X-Retry-Safe`) on transport failures and 429/502/503/504 — never
a POST, because re-sending a task submission runs the task twice. OkHttp's own
`retryOnConnectionFailure` is left **on**: it handles IPv6→IPv4 failover and stale pooled
connections after a VPN reconnect, and only repeats requests the server never answered.

**`/events` caps `limit` at 100** — a larger value is rejected with 400, not clamped.

**The console buffer is bounded at 1500 entries**, dropping oldest and reporting the
count. A runaway agent must not be able to exhaust memory.

**LiteLLM's health path is configuration.** Deployments often run a custom router that
does not serve `/health/readiness`. Settings → LiteLLM → Health path; reachability is
decided by status code, not body shape.

**Credentials never reach the UI layer.** Tokens are AES-256-GCM encrypted under an
Android Keystore key, stored as ciphertext in DataStore; there is no API returning a
token to a screen. `session_api_key` from OpenHands responses is parsed but deliberately
never copied into the domain model — a test asserts it cannot appear in an `AgentSession`.

## Testing conventions

Tests touching DataStore or Keystore need `@RunWith(RobolectricTestRunner::class)` and
`@Config(sdk = [34])`; pure logic (parsers, mappers, error mapping) stays on plain JUnit.

`util/MainDispatcherRule.kt` provides `awaitUntil` and `awaitCurrent`. Use them rather
than `advanceUntilIdle()` when the code under test schedules work indefinitely — the
session console's poll loop always has another task queued, so `advanceUntilIdle()`
**spins forever** and `runTest` never completes. `awaitCurrent` drains only what is due
and tolerates DataStore's real-thread I/O.

## Documentation map

- `ARCHITECTURE.md` — layering, the exact backend routes used and their source, known contract gaps
- `SECURITY.md` — credential storage, the cleartext trade-off and why it was made
- `TESTING.md` — what each suite covers and what has never been executed
- `DEPLOYMENT.md` — VPS-side work: publishing LiteLLM's port, deploying OpenCode, verification curls
