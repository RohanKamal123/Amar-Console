# Testing

## What runs where

| Suite | Location | Runner | Runs in CI | Runs locally |
| --- | --- | --- | --- | --- |
| Unit — pure logic | `app/src/test` | JUnit4 | yes | yes |
| Contract — HTTP wire format | `app/src/test` | JUnit4 + MockWebServer | yes | yes |
| Integration — repository → HTTP | `app/src/test` | Robolectric + MockWebServer | yes | yes |
| ViewModel state machines | `app/src/test` | JUnit4 / Robolectric + coroutines-test | yes | yes |
| Instrumented UI | `app/src/androidTest` | Espresso/Compose on emulator | yes (emulator job) | needs a device |

```bash
./gradlew testDebugUnitTest      # everything except the instrumented suite
./gradlew lintDebug              # Android lint
./gradlew assembleDebug          # debug APK
./gradlew connectedDebugAndroidTest   # instrumented suite; requires a device or emulator
```

HTML reports land in `app/build/reports/tests/testDebugUnitTest/index.html`.

## Coverage by area

**Networking.** `RetryInterceptorTest` proves the policy that matters most: a GET is
retried with exponential backoff, a **POST that starts an agent task is never replayed**,
`Retry-After` on a 429 is honoured, 500 is surfaced immediately, and a dropped socket on
a safe request is retried. `ErrorMapperTest` covers every status the app claims to
handle (401/403/404/408/409/429/500/502/503/504), transport failures (DNS, refused,
timeout, generic IO), and that cancellation is not reported as a backend failure.

**Wire contracts.** `ApiContractTest` runs the real Retrofit interfaces against
MockWebServer with fixtures copied from live responses: it asserts the request path and
body for conversation creation (`initial_user_msg`, and that the Cloud-only
`initial_message` wrapper cannot reappear), parses the OSS `results` set field for field,
and covers the degenerate cases — unknown fields ignored, an empty 200 body reported, and
**the SPA shell the OSS server returns from its catch-all route reported as `Malformed`**
rather than silently parsed. That last one is the failure mode that hid a wrong API
contract behind an HTTP 200.

**Streaming.** `OpenCodeEventStreamTest` drives the SSE parser over a real socket:
text and tool parts become console lines, frames for other sessions are dropped, an
unparseable frame does not kill the stream, error frames are surfaced *without* echoing
the payload (a secret in an error frame must not reach the UI), and a rejected stream
fails the flow so the UI can offer reconnect.

**Repository integration.** `AgentRepositoryIntegrationTest` wires the real repository,
real DataStore configuration and real HTTP client to a mock server: OpenCode's
two-call submission (create session, then post prompt), a prompt rejected after session
creation, an unconfigured provider refused without a network call, sessions from both
providers merged and sorted by recency, **one dead provider not hiding the other's
sessions**, unsupported capabilities reported as unsupported, and malformed JSON handled.

**State machines.** ViewModel tests cover validation (a too-short prompt never reaches
the network, whitespace is trimmed, an empty repository field is not sent), submission
success and failure, retry after an outage, the dashboard keeping already-loaded
sessions when a refresh fails, the console's bounded buffer (1500 lines kept, overflow
counted), stream disconnect → reconnect, polling that stops at a terminal state, and
startup routing (no config → setup, reachable → dashboard, all down → actionable
failure).

## Failure testing

The following are exercised as tests: backend unavailable, invalid URL, timeout, 401,
403, 404, 429, 500/502/503/504, malformed JSON, empty response, stream disconnect and
reconnect, unsupported operations, and provider-partial outages.

Two remain manual, because they need a running app: **app restart during an active
session**, and **network disconnect mid-stream on a real device**. Both are in the
manual checklist below.

## Live workflow test

`LiveBackendWorkflowTest` drives the real repositories against a real HTTP server over a
real socket: service probes, task submission, streamed agent output, transcript replay,
sessions from both providers, an injected backend outage, recovery, and deletion. It is
skipped unless `MOCK_BACKEND_URL` is set, because it needs something to talk to.

```bash
python3 tools/mock_backend.py --port 8099 &
MOCK_BACKEND_URL=http://127.0.0.1:8099 \
  ./gradlew testDebugUnitTest --tests '*LiveBackendWorkflowTest*'
```

Point `MOCK_BACKEND_URL` at your own stack and it exercises that instead.

Executed against the mock backend during development, output abridged:

```
STEP 1 — service health
   OpenHands: ONLINE 325ms      OpenCode: ONLINE 82ms v=mock-0.4.11
   LiteLLM:   ONLINE 71ms       Gateway:  ONLINE 62ms v=mock-gateway-1.0
STEP 2 — dependencies reported by gateway: Postgres ONLINE, Redis ONLINE
STEP 3 — submit a task to OpenCode: session=ses_1 state=RUNNING
STEP 4 — streamed output: [AGENT] …  [SYSTEM] Agent finished this turn.
STEP 5 — transcript history: 3 lines replayed
STEP 6 — sessions from both providers listed and sorted
STEP 7 — backend failure: Failure(ServerError(500))
STEP 8 — recovery: 2 sessions
STEP 9 — delete a session: Success
WORKFLOW OK
```

## Manual end-to-end, against the mock backend

`tools/mock_backend.py` implements the same routes as the real services, using the
documented shapes, including the SSE stream and a simulated agent that produces output
over several seconds. It is a fixture for exercising the app, not a substitute for your
services.

```bash
python3 tools/mock_backend.py --port 8099
```

Then in the app: Settings → OpenCode → `http://10.0.2.2:8099` (emulator) or
`http://<host>.<tailnet>.ts.net:8099` (device on Tailscale) → Save → Test.

Failure injection, for the states that are awkward to reproduce against real services:

```bash
curl "http://localhost:8099/__control/fail?status=500"    # every request now fails
curl "http://localhost:8099/__control/fail?status=401"    # auth failure
curl "http://localhost:8099/__control/fail?status=0"      # recover
curl "http://localhost:8099/__control/latency?ms=3000"    # slow responses
```

Checklist:

1. Launch → splash probes services → dashboard.
2. Dashboard shows service health and latency.
3. New task → submit "Build a REST API for user authentication."
4. Console opens, status shows Streaming, agent output arrives line by line.
5. Task reaches a terminal state; Sessions lists it.
6. Navigate dashboard ↔ sessions ↔ services ↔ settings.
7. Inject `fail?status=500`; refresh; confirm an error state with Retry, no crash.
8. Recover with `fail?status=0`; tap Retry; confirm the app recovers.
9. Kill and relaunch the app mid-session; confirm the session reopens from history.
10. Rotate the device on the console screen; confirm output is not lost.

## Results

The JVM suite — 88 tests across 12 classes — passes, plus the opt-in live workflow test.

| Class | Tests |
| --- | --- |
| UrlValidatorTest | 11 |
| ApiContractTest | 9 |
| ErrorMapperTest | 6 |
| RetryInterceptorTest | 7 |
| OpenCodeEventStreamTest | 5 |
| AgentRepositoryIntegrationTest | 12 |
| StatusMappingTest | 7 |
| DashboardViewModelTest | 8 |
| NewTaskViewModelTest | 9 |
| SessionConsoleViewModelTest | 7 |
| SessionsViewModelTest | 4 |
| SplashViewModelTest | 4 |
| LiveBackendWorkflowTest | 1 (opt-in) |

The mock backend reproduces the self-hosted OpenHands shape, including the catch-all that
answers unknown paths with the frontend, so the live workflow exercises the same
responses a real server sends.

Two production bugs were found by these tests and fixed: `safeResponseCall` treated a
`204 No Content` as a malformed response (breaking OpenCode prompt submission), and the
OpenHands start-response status `WORKING` was unmapped, leaving a freshly submitted task
showing UNKNOWN.

## What has not been executed

Instrumented UI tests and the on-device checklist above have **not** been run in this
project's development environment: it has no KVM, so an Android emulator cannot run
there. The CI workflow includes an emulator job (`.github/workflows/android.yml`,
`instrumentation`) which runs them on GitHub's runners, where KVM is available. Treat
any claim about on-device behaviour as unverified until that job has run green or you
have run the checklist yourself.
