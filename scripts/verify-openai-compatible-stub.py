#!/usr/bin/env python3
"""Small deterministic OpenAI-compatible provider for local platform verification."""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    dimension = 1024

    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/") in ("/health", "/v1/models"):
            self.respond(200, {"status": "ok", "data": []})
            return
        self.respond(404, {"error": {"message": "not found", "type": "not_found"}})

    def do_POST(self):  # noqa: N802
        try:
            length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self.respond(400, {"error": {"message": "invalid JSON", "type": "invalid_request"}})
            return

        path = self.path.split("?", 1)[0].rstrip("/")
        if path.endswith("/embeddings"):
            self.embeddings(request)
            return
        if path.endswith("/chat/completions"):
            self.chat_completion()
            return
        self.respond(404, {"error": {"message": "not found", "type": "not_found"}})

    def embeddings(self, request):
        inputs = request.get("input", [])
        if isinstance(inputs, str):
            inputs = [inputs]
        vector = [1.0] + [0.0] * (self.dimension - 1)
        data = [
            {"object": "embedding", "index": index, "embedding": vector}
            for index, _ in enumerate(inputs)
        ]
        self.respond(
            200,
            {
                "object": "list",
                "data": data,
                "model": request.get("model", "verify-embedding"),
                "usage": {"prompt_tokens": max(1, len(inputs)), "total_tokens": max(1, len(inputs))},
            },
        )

    def chat_completion(self):
        content = json.dumps(
            {"answer": "Verified model response.", "status": "SUCCEEDED"},
            separators=(",", ":"),
        )
        self.respond(
            200,
            {
                "id": "verify-chat-completion",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": "verify-chat",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": content},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
            },
        )

    def respond(self, status, payload):
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string, *args):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--dimension", type=int, default=1024)
    args = parser.parse_args()
    if args.dimension < 1:
        raise SystemExit("dimension must be positive")
    Handler.dimension = args.dimension
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
