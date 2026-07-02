from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from time import time
import json
import os

DATA = Path(os.environ.get("DATA_FILE", "/data/devices.json"))
CONFIG = DATA.parent / "smb-config.json"  # ponytail: satu file terpisah dari devices
TOKEN = os.environ.get("MONITOR_TOKEN", "")
STALE_AFTER = int(os.environ.get("STALE_AFTER_SEC", "1800"))
LOGO = Path(__file__).with_name("assets") / "firefiles-logo.svg"


def read_devices():
    try:
        return json.loads(DATA.read_text())
    except Exception:
        return {}


def write_devices(devices):
    DATA.parent.mkdir(parents=True, exist_ok=True)
    DATA.write_text(json.dumps(devices, indent=2, sort_keys=True))


def read_config():
    try:
        return json.loads(CONFIG.read_text())
    except Exception:
        return {}


def write_config(cfg):
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps(cfg, indent=2))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api/devices":
            return self.json(devices_with_age())
        if self.path == "/api/smb-config":
            if TOKEN and self.headers.get("x-monitor-token") != TOKEN:
                return self.send_error(401)
            return self.json(read_config())
        if self.path == "/logo.svg":
            body = LOGO.read_bytes()
            self.send_response(200)
            self.send_header("content-type", "image/svg+xml")
            self.send_header("cache-control", "public, max-age=3600")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/":
            body = HTML.encode()
            self.send_response(200)
            self.send_header("content-type", "text/html; charset=utf-8")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        if self.path == "/api/smb-config":
            if TOKEN and self.headers.get("x-monitor-token") != TOKEN:
                return self.send_error(401)
            length = int(self.headers.get("content-length", "0"))
            try:
                payload = json.loads(self.rfile.read(length))
            except Exception:
                return self.send_error(400, "bad json")
            payload["registered_at"] = int(time())
            write_config(payload)
            return self.json({"ok": True})
        if self.path != "/api/heartbeat":
            return self.send_error(404)
        if TOKEN and self.headers.get("x-monitor-token") != TOKEN:
            return self.send_error(401)
        length = int(self.headers.get("content-length", "0"))
        try:
            payload = json.loads(self.rfile.read(length))
            device_id = payload["id"]
        except Exception:
            return self.send_error(400, "bad json; need id")

        payload["seen_at"] = int(time())
        devices = read_devices()
        devices[device_id] = payload
        write_devices(devices)
        self.json({"ok": True})

    def json(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("cache-control", "no-store")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        return


def devices_with_age():
    now = int(time())
    devices = read_devices()
    for item in devices.values():
        age = now - int(item.get("seen_at", 0))
        item["age_sec"] = age
        item["online"] = age < STALE_AFTER
    return devices


HTML = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>FireFiles Monitor</title>
  <style>
    :root{color-scheme:light;--bg:#eef2f7;--panel:#fff;--line:#d9e1ec;--text:#111827;--muted:#667085;--green:#047857;--amber:#b45309;--red:#b91c1c}
    *{box-sizing:border-box}body{font-family:Inter,ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif;margin:0;background:var(--bg);color:var(--text)}
    header{padding:22px clamp(16px,4vw,36px);background:#111827;color:white;display:flex;justify-content:space-between;gap:14px;align-items:center;flex-wrap:wrap}
    .brand{display:flex;align-items:center;gap:12px}.logo{width:44px;height:44px;border-radius:8px}.brand h1{font-size:clamp(22px,4vw,34px);margin:0;letter-spacing:0}.sub{color:#cbd5e1;margin-top:4px;font-size:14px}.time{font-size:13px;color:#cbd5e1}
    main{padding:clamp(14px,3vw,30px);display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,340px),1fr));gap:16px}
    .device{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:16px;box-shadow:0 10px 24px rgba(15,23,42,.06)}
    .top{display:flex;justify-content:space-between;gap:12px;align-items:flex-start;margin-bottom:14px}.name{font-weight:750;font-size:18px}.type{color:var(--muted);font-size:13px;margin-top:3px;text-transform:uppercase;letter-spacing:.06em}
    .badge{font-size:12px;font-weight:700;padding:6px 9px;border-radius:999px;white-space:nowrap}.healthy{background:#dcfce7;color:var(--green)}.warning{background:#fef3c7;color:var(--amber)}.offline{background:#fee2e2;color:var(--red)}
    .metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.metric{background:#f8fafc;border:1px solid #e5eaf2;border-radius:8px;padding:12px;min-width:0}.label{color:var(--muted);font-size:12px}.value{font-size:22px;font-weight:800;margin-top:4px}.free{color:var(--muted);font-size:12px;margin-top:4px;overflow-wrap:anywhere}
    .bar{height:8px;background:#e5e7eb;border-radius:999px;overflow:hidden;margin-top:10px}.fill{height:100%;background:#2563eb}.fill.warn{background:#f59e0b}.fill.bad{background:#dc2626}
    .meta{display:grid;grid-template-columns:80px 1fr;gap:8px 10px;border-top:1px solid var(--line);margin-top:14px;padding-top:14px;font-size:14px}.meta div:nth-child(odd){color:var(--muted)}.meta div:nth-child(even){text-align:right;overflow-wrap:anywhere}
    .empty{grid-column:1/-1;color:var(--muted);background:white;border:1px dashed var(--line);border-radius:8px;padding:32px;text-align:center}
    @media(max-width:520px){header{align-items:flex-start}.metrics{grid-template-columns:1fr}.meta div:nth-child(even){text-align:left}}
  </style>
</head>
<body>
  <header><div class="brand"><img class="logo" src="/logo.svg" alt=""><div><h1>FireFiles Monitor</h1><div class="sub">Windows CLI, Android APK, Ubuntu receiver</div></div></div><div class="time" id="updated">loading...</div></header>
  <main id="devices"></main>
  <script>
    const fmtBytes = n => {
      if (n == null) return "-";
      const units = ["B","KB","MB","GB","TB"]; let i = 0, v = Number(n);
      while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
      return `${v.toFixed(i ? 1 : 0)} ${units[i]}`;
    };
    const pct = n => n == null ? "-" : `${Math.round(n)}%`;
    const esc = s => String(s ?? "-").replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const fillClass = n => n >= 90 ? "bad" : n >= 75 ? "warn" : "";
    function health(d){
      if (!d.online) return ["Offline","offline"];
      if ((d.cpu_percent ?? 0) >= 90 || (d.ram_percent ?? 0) >= 90 || (d.storage_percent ?? 0) >= 90 || /missing|failed|error/i.test(d.status || "")) return ["Warning","warning"];
      return ["Healthy","healthy"];
    }
    function metric(label, value, free){
      const n = Number(value ?? 0), width = Math.max(0, Math.min(100, n));
      return `<div class="metric"><div class="label">${label}</div><div class="value">${pct(value)}</div><div class="free">${free}</div><div class="bar"><div class="fill ${fillClass(n)}" style="width:${width}%"></div></div></div>`;
    }
    async function load(){
      const data = await fetch("/api/devices", {cache:"no-store"}).then(r => r.json());
      const rows = Object.values(data).sort((a,b) => (a.name || a.id).localeCompare(b.name || b.id));
      devices.innerHTML = rows.length ? rows.map(d => {
        const [label, cls] = health(d);
        return `
        <section class="device">
          <div class="top">
            <div><div class="name">${esc(d.name || d.id)}</div><div class="type">${esc(d.kind || "-")}</div></div>
            <span class="badge ${cls}">${label}</span>
          </div>
          <div class="metrics">
            ${metric("CPU", d.cpu_percent, "current load")}
            ${metric("RAM", d.ram_percent, `${fmtBytes(d.ram_free_bytes)} free`)}
            ${metric("Storage", d.storage_percent, `${fmtBytes(d.storage_free_bytes)} free`)}
          </div>
          <div class="meta">
            <div>Status</div><div>${esc(d.status)}</div>
            <div>Last seen</div><div>${d.age_sec}s ago</div>
            <div>Detail</div><div>${esc(d.detail)}</div>
          </div>
        </section>`}).join("") : `<div class="empty">No heartbeat yet</div>`;
      updated.textContent = new Date().toLocaleTimeString();
    }
    load(); setInterval(load, 5000);
  </script>
</body>
</html>"""


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
