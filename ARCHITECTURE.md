# Architecture

Amar Console is a native Android client for a self-hosted AI development stack. It is a
control surface: it starts agent tasks, watches them run, and reports the health of the
services behind them. It holds no business logic of its own beyond translating between
what the user wants and what each backend actually offers.

## Layering

```
Compose UI          screens, no I/O, no HTTP, no JSON
   ↓ StateFlow
ViewModel           screen state machines, validation, buffering
   ↓ suspend calls
Repository          provider fan-out, normalization, error policy
   ↓ Retrofit / OkHttp
API client          typed endpoints, one client per service
   ↓
Backend             OpenHands · OpenCode · LiteLLM · optional gateway
```

Composables never perform network calls. Repositories never throw: every call returns
`ApiResult<T>`, which is either `Success` or a `Failure` carrying an `AppError` — a
closed set of failure kinds the UI can render (`Offline`, `Timeout`, `Unauthorized`,
`Forbidden`, `NotFound`, `Conflict`, `RateLimited`, `ServerError`, `Http`, `Malformed`,
`NotConfigured`, `Unsupported`, `Cancelled`). Each error knows whether a retry is
sensible, so the UI offers "Retry" only where it could actually help.

Dependency injection is Hilt. There is one Gradle module; the package structure
(`core`, `data`, `domain`, `ui`) carries the layering, and the domain layer defines the
repository interfaces that the data layer implements.

## Configuration

Nothing about a deployment is compiled into the binary. Service URLs live in DataStore
and are entered in Settings, so one APK works against any environment (Development /
Staging / Production are labels selecting the active config).

`ApiClientFactory` builds and caches a Retrofit client per `(service, base URL)` pair.
Editing a URL invalidates the cache, so a stale client can never keep talking to the old
host. Each service gets its own OkHttp client carrying only its own credential —
a token for one service can never be sent to another.

## Backends

Every route the app calls comes from a published API reference. Nothing is invented.

| Service | Routes used | Source |
| --- | --- | --- |
| OpenHands (self-hosted OSS) | `GET /api/options/config`, `GET /api/conversations`, `GET /api/conversations/{id}`, `POST /api/conversations`, `DELETE /api/conversations/{id}`, `POST /api/conversations/{id}/stop`, `GET /api/conversations/{id}/events` | server source: `openhands/server/routes/manage_conversations.py`, `conversation.py`, `public.py` |
| OpenCode | `GET /global/health`, `POST /session`, `GET /session`, `GET /session/{id}`, `DELETE /session/{id}`, `POST /session/{id}/prompt_async`, `GET /session/{id}/message`, `GET /event` (SSE) | opencode.ai/docs/server |
| LiteLLM | `GET /health/liveliness`, `GET /health/readiness` | docs.litellm.ai/docs/proxy/health |
| Gateway | `GET /health` | **your** contract — see below |

### The gateway contract

PostgreSQL and Redis are deliberately not contacted by the app. A phone has no database
driver, and exposing a database port so a mobile client can ping it would be a serious
regression in security for no benefit. Instead, if you run a gateway in front of the
stack, the app reads their state from its health endpoint:

```json
GET /health
{
  "status": "ok",
  "version": "1.4.0",
  "dependencies": { "postgres": "up", "redis": "degraded" }
}
```

`up`/`ok`/`healthy`/`connected` render as online, `degraded`/`slow`/`warning` as
degraded, `down`/`error`/`unhealthy`/`disconnected` as offline, anything else as
unknown. With no gateway configured, the Services screen says so rather than inventing a
status.

### Which OpenHands

There are two different products behind the name, and they do not share an API:

* **Self-hosted OSS** (`ghcr.io/all-hands-ai/openhands`) — what this app targets.
  Conversations live under `/api/conversations`, keyed by `conversation_id`, with a
  `status` (`STARTING`/`RUNNING`/`STOPPED`/`ARCHIVED`) and a separate `runtime_status`
  (`STATUS$READY`, `STATUS$BUILDING_RUNTIME`, `STATUS$ERROR_*`).
* **Cloud / Enterprise** — `/api/v1/app-conversations`, keyed by `id`, with
  `sandbox_status` and `execution_status`. Documented at docs.openhands.dev.

An earlier version of this client was built against the Cloud contract and pointed at an
OSS server. That failure is worth recording, because the symptom was misleading: the OSS
server serves its frontend from a catch-all route, so a wrong path returns **HTTP 200
with an HTML body** instead of a 404. The app reported "the response wasn't in the
expected format" — technically true, and useless. `AgentRepositoryIntegrationTest` and
the mock backend both reproduce that catch-all now, so a contract regression fails a
test rather than reaching a phone.

### Known contract gaps

| Capability | Status |
| --- | --- |
| Cancelling a running OpenCode task | OpenCode's published API has no abort route. Reported as unsupported; deleting the session is offered instead. |
| Live streaming for OpenHands | The OSS server exposes a transcript (`GET .../events`) but no SSE endpoint the app uses, so the console polls at the configured interval and says "Polling status" rather than pretending to stream. |

Everything else the UI offers now maps to a real endpoint: OpenHands supports transcript
replay (`/events`), cancellation (`/stop`), deletion (`DELETE`) and follow-up messages
(`/message`).

## Task state

Providers disagree about vocabulary, so both are normalized onto one `TaskState`:
`QUEUED · RUNNING · WAITING · COMPLETED · FAILED · CANCELLED · UNKNOWN`.

OpenHands `execution_status` maps `running→RUNNING`, `idle→QUEUED`,
`paused`/`waiting_for_confirmation→WAITING`, `finished→COMPLETED`,
`error`/`stuck→FAILED`, falling back to `sandbox_status` when execution status is absent.
An unrecognised value becomes `UNKNOWN` rather than a guess.

## Streaming and polling

OpenCode publishes a server-sent event stream at `GET /event`. Retrofit cannot express
SSE, so `OpenCodeEventStream` uses OkHttp directly with the read timeout disabled and
emits a `Flow<ConsoleEvent>`; cancelling the collector cancels the call, so leaving the
console closes the socket.

Providers without a stream are polled at the user's configured interval (2–60s, default
5s), and polling stops the moment the task reaches a terminal state — there is no
unconditional background polling anywhere in the app.

The console buffer is bounded at 1500 lines. Beyond that, the oldest are dropped and the
UI reports how many were trimmed, so a runaway agent cannot exhaust memory. Rendering is
a keyed `LazyColumn`, so only visible lines are composed.

## Retry policy

`RetryInterceptor` retries GET/HEAD/PUT/DELETE (and requests that explicitly opt in via
`X-Retry-Safe`) on transport failures and 429/502/503/504, with exponential backoff and
`Retry-After` honoured up to 30s. **POSTs are never replayed** — re-sending a task
submission would run the task twice, which is worse than showing an error. 500 is not
retried automatically either; the user can retry explicitly.

Underneath, OkHttp's own `retryOnConnectionFailure` recovery is left enabled. The two
layers do different jobs: OkHttp fails over between a host's addresses (a tailnet name
resolves to both IPv6 and IPv4) and discards stale pooled connections after a VPN
reconnect, repeating only requests the server never answered; this interceptor decides
whether a request the server *did* answer may be sent again — and for a POST, never.

Timeouts: 10s connect, 30s read, 30s write, 60s per call.

## Security posture

See SECURITY.md. In short: credentials are encrypted with an AES-256 key held in the
Android Keystore and never returned to the UI layer; cleartext HTTP is refused except to
tailnet/loopback hosts; the app is designed for a VPN-only topology.
