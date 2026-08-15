# Deploying the services this app talks to

The app is a client. Two of the three services it supports need work on the host before
it can reach them — this file records exactly what, based on an inspection of a running
`ai-stack` deployment.

## OpenHands — works today

Self-hosted OSS (`ghcr.io/all-hands-ai/openhands`), published on its own port. The app
targets this API directly; nothing to change.

## LiteLLM — not published, and not the upstream proxy

In the inspected stack the `litellm` service has no `ports:` entry in either
`docker-compose.yml` or `docker-compose.working.yml`, so it is reachable only from
inside the `ai_network` bridge — OpenHands talks to it as `http://litellm:4000/v1`.
`docker port ai_litellm` returns nothing, which is why the Services screen shows it
offline no matter what URL is entered.

It is also not the upstream LiteLLM proxy but a custom `litellm_router.py` FastAPI app,
so `/health/readiness` — the route the upstream proxy serves — may not exist.

Before exposing it, find out what it actually serves:

```bash
docker exec ai_litellm python3 -c \
  "import urllib.request; print(urllib.request.urlopen('http://localhost:4000/openapi.json').read()[:2000])"
# or, if it has no OpenAPI schema:
docker exec ai_litellm cat /app/litellm_router.py | grep -nE "@app\.(get|post)"
```

Then publish the port:

```yaml
  litellm:
    # ...existing config...
    ports:
      - "4000:4000"        # bind to the tailnet interface only if the host is multi-homed
```

If it serves no health route at all, either add one to `litellm_router.py`:

```python
@app.get("/health/readiness")
async def readiness():
    return {"status": "healthy"}
```

…or point the app at whatever route does exist: **Settings → LiteLLM → Health path**.
Any path that returns 2xx will do — the app decides reachability from the status code
and only mines the body for optional version detail.

## OpenCode — not deployed

The `ai_opencode` container runs `codercom/code-server`, which is a browser IDE. It is
not the `opencode` agent server, and it does not serve `/global/health`, `/session`,
`/session/{id}/prompt_async` or the `/event` SSE stream the app expects. That screen
will stay offline until the real agent server runs.

`opencode serve` is a separate process. Add it as its own service:

```yaml
  opencode:
    image: node:22-alpine
    container_name: ai_opencode_server
    working_dir: /workspace
    command: >
      sh -c "npm install -g opencode-ai &&
             opencode serve --hostname 0.0.0.0 --port 4096"
    ports:
      - "4096:4096"
    volumes:
      - ./workspace:/workspace
      - opencode_data:/root/.local/share/opencode
    environment:
      # Point it at the same router OpenHands uses.
      OPENAI_BASE_URL: http://litellm:4000/v1
      # Set OPENCODE_SERVER_PASSWORD to require HTTP basic auth on the API.
    networks:
      - ai_network
    restart: unless-stopped
```

Verify before pointing the app at it — the app's own probe calls the first of these:

```bash
curl -s http://localhost:4096/global/health      # {"healthy":true,"version":"..."}
curl -s http://localhost:4096/session            # []
curl -sN http://localhost:4096/event | head -2   # data: {"type":"server.connected",...}
```

Keep every published port on the tailnet interface rather than a public one — the app is
built for VPN-only access, and an agent server that executes arbitrary code should never
be internet-facing.
