#!/usr/bin/env python3
import json
import os
import re
import subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

ENV_PATH = "/etc/default/rc-fpv-stream"
HOST = "0.0.0.0"
PORT = 8080

ALLOWED = {
    "STREAM_FPS": (int, 24, 120),
    "STREAM_BITRATE": (int, 1000000, 20000000),
    "STREAM_INTRA": (int, 1, 120),
    "STREAM_FORMAT": (str, {"mpegts", "raw", "rtp"}),
}


def read_env(path):
    data = {}
    if not os.path.exists(path):
        return data
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            data[k.strip()] = v.strip()
    return data


def write_env_updates(path, updates):
    lines = []
    existing = set()
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            lines = f.readlines()

    key_pattern = re.compile(r"^([A-Z0-9_]+)=(.*)$")
    new_lines = []
    for line in lines:
        m = key_pattern.match(line.strip())
        if not m:
            new_lines.append(line)
            continue
        key = m.group(1)
        if key in updates:
            new_lines.append(f"{key}={updates[key]}\n")
            existing.add(key)
        else:
            new_lines.append(line)

    for key, value in updates.items():
        if key not in existing:
            new_lines.append(f"{key}={value}\n")

    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.writelines(new_lines)
    os.replace(tmp, path)


def validate(payload):
    out = {}
    for key, val in payload.items():
        if key not in ALLOWED:
            continue
        rule = ALLOWED[key]
        if rule[0] is int:
            try:
                iv = int(val)
            except Exception as exc:
                raise ValueError(f"{key} must be integer") from exc
            lo, hi = rule[1], rule[2]
            if iv < lo or iv > hi:
                raise ValueError(f"{key} out of range {lo}-{hi}")
            out[key] = str(iv)
        else:
            sv = str(val).lower()
            if sv not in rule[1]:
                raise ValueError(f"{key} must be one of {sorted(rule[1])}")
            out[key] = sv
    return out


class Handler(BaseHTTPRequestHandler):
    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/api/stream-config":
            self._json(404, {"ok": False, "error": "not found"})
            return
        current = read_env(ENV_PATH)
        self._json(200, {"ok": True, "config": current})

    def do_POST(self):
        if self.path != "/api/stream-config":
            self._json(404, {"ok": False, "error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length) if length > 0 else b"{}"
            payload = json.loads(raw.decode("utf-8"))
            updates = validate(payload)
            if not updates:
                self._json(400, {"ok": False, "error": "no valid keys"})
                return
            write_env_updates(ENV_PATH, updates)
            subprocess.run(["systemctl", "restart", "rc-fpv-stream.service"], check=True)
            self._json(200, {"ok": True, "status": "ok", "applied": updates})
        except subprocess.CalledProcessError:
            self._json(500, {"ok": False, "error": "failed to restart stream service"})
        except ValueError as e:
            self._json(400, {"ok": False, "error": str(e)})
        except Exception as e:
            self._json(500, {"ok": False, "error": str(e)})

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    server = HTTPServer((HOST, PORT), Handler)
    server.serve_forever()
