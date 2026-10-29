"""Внутренний шлюз: только состояние и ограниченные логи контейнеров Sizy."""
import datetime
import http.client
import json
import os
import re
import socket
import struct
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit

PROJECT = os.environ.get("COMPOSE_PROJECT", "sizylift")
MAX_BYTES = 1024 * 1024
MAX_LINES = 500
PERIODS = {"1h": 3600, "24h": 86400, "7d": 604800}
SECRET = re.compile(r'(?i)((?:password|passwd|secret|access[_-]?token|refresh[_-]?token|authorization|cookie)\s*["\']?\s*[:=]\s*["\']?)([^\s,"\'}]+)')
BEARER = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*")
JWT = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
USERINFO = re.compile(r"(\w+://)[^\s/@]+:[^\s/@]+@")


class DockerConnection(http.client.HTTPConnection):
    def __init__(self):
        super().__init__("localhost", timeout=3)

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect("/var/run/docker.sock")


def docker_get(path, raw=False):
    connection = DockerConnection()
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        if response.status != 200:
            raise RuntimeError("Docker read unavailable")
        data = response.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            if not raw:
                raise RuntimeError("Docker response exceeds limit")
            return data[:MAX_BYTES], True
        return (data, False) if raw else json.loads(data)
    finally:
        connection.close()


def containers():
    query = urlencode({"all": "true", "filters": json.dumps({"label": ["com.docker.compose.project=" + PROJECT]})})
    rows = docker_get("/containers/json?" + query)
    return [row for row in rows if row.get("Labels", {}).get("com.docker.compose.project") == PROJECT]


def scrub(text):
    text = BEARER.sub("Bearer [REDACTED]", text)
    text = JWT.sub("[REDACTED]", text)
    text = USERINFO.sub(r"\1[REDACTED]@", text)
    return SECRET.sub(r"\1[REDACTED]", text)


def decode_logs(data, tty):
    if tty:
        return data.decode("utf-8", "replace")
    frames = []
    offset = 0
    while offset + 8 <= len(data):
        stream, padding, size = data[offset], data[offset + 1:offset + 4], struct.unpack(">I", data[offset + 4:offset + 8])[0]
        if stream not in (0, 1, 2) or padding != b"\0\0\0":
            raise RuntimeError("Invalid Docker log frame")
        frames.append(data[offset + 8:offset + 8 + size])
        offset += 8 + size
    return b"".join(frames).decode("utf-8", "replace")


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def container_states():
    items = []
    for row in containers():
        inspected = docker_get("/containers/" + row["Id"] + "/json")
        state = inspected.get("State", {})
        items.append({"id": row["Id"], "service": row["Labels"].get("com.docker.compose.service", ""),
                      "name": row.get("Names", [""])[0].lstrip("/"), "state": state.get("Status", "unknown"),
                      "health": state.get("Health", {}).get("Status", "unknown"),
                      "restarts": inspected.get("RestartCount", 0)})
    return {"fetchedAt": now(), "state": "ok", "items": sorted(items, key=lambda item: (item["service"], item["name"]))}


def logs(container_id, period, limit):
    if not re.fullmatch(r"[a-f0-9]{64}", container_id) or period not in PERIODS or not 1 <= limit <= MAX_LINES:
        raise ValueError()
    if not any(row["Id"] == container_id for row in containers()):
        raise LookupError()
    inspected = docker_get("/containers/" + container_id + "/json")
    query = urlencode({"stdout": "true", "stderr": "true", "timestamps": "true", "tail": limit,
                       "since": int(time.time()) - PERIODS[period], "follow": "false"})
    data, truncated = docker_get("/containers/" + container_id + "/logs?" + query, raw=True)
    lines = decode_logs(data, inspected.get("Config", {}).get("Tty", False)).splitlines()
    return {"fetchedAt": now(), "containerId": container_id, "lines": [scrub(line) for line in lines[-limit:]],
            "truncated": truncated or len(lines) >= limit}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        parsed = urlsplit(self.path)
        try:
            if parsed.path == "/health":
                result = {"state": "ok"}
            elif parsed.path == "/containers" and not parsed.query:
                result = container_states()
            elif match := re.fullmatch(r"/containers/([a-f0-9]{64})/logs", parsed.path):
                query = parse_qs(parsed.query, strict_parsing=True)
                if set(query) - {"period", "limit"} or any(len(value) != 1 for value in query.values()):
                    raise ValueError()
                result = logs(match[1], query.get("period", ["1h"])[0], int(query.get("limit", ["200"])[0]))
            else:
                raise LookupError()
            self.reply(200, result)
        except (ValueError, TypeError):
            self.reply(400, {"error": "Invalid parameters"})
        except LookupError:
            self.reply(404, {"error": "Not found"})
        except (RuntimeError, OSError, KeyError, json.JSONDecodeError):
            self.reply(503, {"error": "Container data unavailable"})

    def reply(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
