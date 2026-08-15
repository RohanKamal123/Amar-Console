#!/usr/bin/env python3
"""
A local stand-in for the OpenCode / OpenHands / LiteLLM HTTP surface.

It implements the same routes the app calls, using the shapes documented in each
project's API reference, so the full workflow — connect, submit a task, watch output
stream in, review history — can be exercised without a live VPS. It is a test fixture,
not a substitute for the real services.

    python3 tools/mock_backend.py --port 8099
    # then in the app's Settings, set OpenCode to http://10.0.2.2:8099 (emulator)
    # or http://<your-machine>.<tailnet>.ts.net:8099 (device over Tailscale)

Failure injection, to exercise the app's error states:

    /__control/fail?status=500   every subsequent request returns that status
    /__control/fail?status=0     stop failing
    /__control/latency?ms=3000   delay every response
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

STATE = {
    "sessions": {},
    "messages": {},
    "fail_status": 0,
    "latency_ms": 0,
    "counter": 0,
}
LOCK = threading.Lock()


def now_ms() -> int:
    return int(time.time() * 1000)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the console readable
        print(f"[mock] {self.command} {self.path} -> {args[1] if len(args) > 1 else ''}")

    # -- helpers ---------------------------------------------------------

    def _send_json(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return {}

    def _maybe_fail(self) -> bool:
        if STATE["latency_ms"]:
            time.sleep(STATE["latency_ms"] / 1000)
        if STATE["fail_status"]:
            self._send_json({"detail": "injected failure"}, STATE["fail_status"])
            return True
        return False

    # -- routes ----------------------------------------------------------

    def do_GET(self):
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)

        if path == "/__control/fail":
            STATE["fail_status"] = int(query.get("status", ["0"])[0])
            return self._send_json({"fail_status": STATE["fail_status"]})
        if path == "/__control/latency":
            STATE["latency_ms"] = int(query.get("ms", ["0"])[0])
            return self._send_json({"latency_ms": STATE["latency_ms"]})

        if self._maybe_fail():
            return

        # OpenCode
        if path == "/global/health":
            return self._send_json({"healthy": True, "version": "mock-0.4.11"})
        if path == "/session":
            # OpenCode sessions only — OpenHands conversations live in the same store
            # but must not appear on this route.
            return self._send_json([s for s in STATE["sessions"].values() if not s.get("_openhands")])
        if path.startswith("/session/") and path.endswith("/message"):
            session_id = path.split("/")[2]
            return self._send_json(STATE["messages"].get(session_id, []))
        if path.startswith("/session/"):
            session_id = path.split("/")[2]
            session = STATE["sessions"].get(session_id)
            return self._send_json(session or {"detail": "not found"}, 200 if session else 404)
        if path == "/event":
            return self._stream_events()

        # LiteLLM
        if path in ("/health/liveliness", "/health/liveness"):
            return self._send_json("I'm alive!")
        if path == "/health/readiness":
            return self._send_json({"status": "healthy", "db": "connected", "litellm_version": "mock-1.55"})

        # Gateway
        if path == "/health":
            return self._send_json({
                "status": "ok",
                "version": "mock-gateway-1.0",
                "dependencies": {"postgres": "up", "redis": "up"},
            })

        # OpenHands
        if path == "/api/v1/app-conversations/search":
            items = [s for s in STATE["sessions"].values() if s.get("_openhands")]
            return self._send_json({"items": [self._as_conversation(s) for s in items]})
        if path == "/api/v1/app-conversations":
            ids = query.get("ids", [""])[0].split(",")
            items = [STATE["sessions"][i] for i in ids if i in STATE["sessions"]]
            return self._send_json([self._as_conversation(s) for s in items])

        self._send_json({"detail": f"no mock route for {path}"}, 404)

    def do_POST(self):
        url = urlparse(self.path)
        path = url.path
        if self._maybe_fail():
            return
        payload = self._read_json()

        if path == "/session":
            with LOCK:
                STATE["counter"] += 1
                session_id = f"ses_{STATE['counter']}"
                STATE["sessions"][session_id] = {
                    "id": session_id,
                    "title": payload.get("title") or "Untitled session",
                    "time": {"created": now_ms(), "updated": now_ms()},
                }
                STATE["messages"][session_id] = []
            return self._send_json(STATE["sessions"][session_id])

        if path.startswith("/session/") and path.endswith("/prompt_async"):
            session_id = path.split("/")[2]
            text = " ".join(p.get("text", "") for p in payload.get("parts", []))
            with LOCK:
                STATE["messages"].setdefault(session_id, []).append({
                    "info": {"id": f"m{len(STATE['messages'][session_id]) + 1}", "role": "user",
                             "time": {"created": now_ms()}},
                    "parts": [{"type": "text", "text": text}],
                })
            threading.Thread(target=self._simulate_agent, args=(session_id,), daemon=True).start()
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if path == "/api/v1/app-conversations":
            with LOCK:
                STATE["counter"] += 1
                conv_id = f"conv_{STATE['counter']}"
                message = payload.get("initial_message", {}).get("content", [{}])
                STATE["sessions"][conv_id] = {
                    "id": conv_id,
                    "title": (message[0].get("text") if message else "Task")[:60],
                    "_openhands": True,
                    "execution_status": "running",
                    "sandbox_status": "RUNNING",
                    "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "selected_repository": payload.get("selected_repository"),
                }
            threading.Thread(target=self._finish_conversation, args=(conv_id,), daemon=True).start()
            return self._send_json({
                "id": f"task_{conv_id}", "status": "WORKING",
                "app_conversation_id": conv_id, "sandbox_id": "sbx_mock",
                "created_at": STATE["sessions"][conv_id]["created_at"],
            })

        self._send_json({"detail": f"no mock route for {path}"}, 404)

    def do_DELETE(self):
        if self._maybe_fail():
            return
        path = urlparse(self.path).path
        if path.startswith("/session/"):
            session_id = path.split("/")[2]
            STATE["sessions"].pop(session_id, None)
            STATE["messages"].pop(session_id, None)
            return self._send_json({"deleted": True})
        self._send_json({"detail": "no mock route"}, 404)

    # -- simulation ------------------------------------------------------

    def _as_conversation(self, session):
        return {
            "id": session["id"],
            "title": session.get("title"),
            "execution_status": session.get("execution_status", "running"),
            "sandbox_status": session.get("sandbox_status", "RUNNING"),
            "created_at": session.get("created_at"),
            "updated_at": session.get("created_at"),
            "selected_repository": session.get("selected_repository"),
        }

    def _simulate_agent(self, session_id):
        """Append assistant output over a few seconds, as a real agent would."""
        steps = [
            ("text", "Reading the existing project layout."),
            ("tool", "bash"),
            ("text", "Adding an authentication router with signup, login and refresh."),
            ("tool", "edit"),
            ("text", "Done. Endpoints: POST /auth/signup, POST /auth/login, POST /auth/refresh."),
        ]
        for kind, value in steps:
            time.sleep(1.2)
            with LOCK:
                messages = STATE["messages"].setdefault(session_id, [])
                part = {"type": "text", "text": value} if kind == "text" else {"type": "tool", "tool": value}
                messages.append({
                    "info": {"id": f"m{len(messages) + 1}", "role": "assistant", "time": {"created": now_ms()}},
                    "parts": [part],
                })

    def _finish_conversation(self, conv_id):
        time.sleep(6)
        with LOCK:
            if conv_id in STATE["sessions"]:
                STATE["sessions"][conv_id]["execution_status"] = "finished"

    def _stream_events(self):
        """Server-sent events, mirroring OpenCode's /event contract."""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        seen = {}
        try:
            self.wfile.write(b'data: {"type":"server.connected","properties":{}}\n\n')
            self.wfile.flush()
            while True:
                time.sleep(0.5)
                with LOCK:
                    snapshot = {sid: list(msgs) for sid, msgs in STATE["messages"].items()}
                for session_id, messages in snapshot.items():
                    already = seen.setdefault(session_id, 0)
                    for message in messages[already:]:
                        for part in message["parts"]:
                            frame = {
                                "type": "message.part.updated",
                                "properties": {
                                    "sessionID": session_id,
                                    "part": {
                                        "id": f"{message['info']['id']}-{part.get('type')}",
                                        "sessionID": session_id,
                                        **part,
                                    },
                                },
                            }
                            self.wfile.write(f"data: {json.dumps(frame)}\n\n".encode())
                    if len(messages) > already:
                        idle = {"type": "session.idle", "properties": {"sessionID": session_id}}
                        self.wfile.write(f"data: {json.dumps(idle)}\n\n".encode())
                        seen[session_id] = len(messages)
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[mock] listening on http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
