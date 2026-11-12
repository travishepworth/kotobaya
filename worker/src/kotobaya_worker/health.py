"""Tiny HTTP liveness endpoint so the worker container has a health check."""

from http.server import BaseHTTPRequestHandler, HTTPServer


def health_status(path: str) -> tuple[int, bytes]:
    """Return the HTTP status and body for a request path."""
    if path == "/healthz":
        return 200, b"ok"
    return 404, b"not found"


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        status, body = health_status(self.path)
        self.send_response(status)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        return


def serve(port: int = 8081) -> None:
    HTTPServer(("0.0.0.0", port), _Handler).serve_forever()


if __name__ == "__main__":
    serve()
