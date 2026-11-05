"""Persistent server state: settings, paired devices and launchers, all JSON in the data dir."""
from __future__ import annotations

import json
import os
import socket
import sys
import threading
import uuid
from pathlib import Path
from typing import Any

from .protocol import DEFAULT_PORT, DISCOVERY_PORT


def default_data_dir() -> Path:
    if env := os.environ.get("FLIPDESK_DATA"):
        return Path(env)
    if sys.platform == "win32":
        return Path(os.environ.get("APPDATA", Path.home())) / "FlipDesk"
    return Path.home() / ".config" / "flipdesk"


DEFAULT_LAUNCHERS: list[dict[str, Any]] = [
    {"id": "browser", "name": "Browser", "type": "url", "target": "https://www.google.com", "icon": "web"},
    {"id": "youtube", "name": "YouTube", "type": "url", "target": "https://www.youtube.com", "icon": "play"},
    {"id": "explorer", "name": "Files", "type": "app", "target": "explorer.exe", "icon": "folder"},
    {"id": "notepad", "name": "Notepad", "type": "app", "target": "notepad.exe", "icon": "note"},
    {"id": "calc", "name": "Calculator", "type": "app", "target": "calc.exe", "icon": "calc"},
    {"id": "taskmgr", "name": "Task Manager", "type": "app", "target": "taskmgr.exe", "icon": "monitor"},
    {"id": "settings", "name": "Settings", "type": "url", "target": "ms-settings:", "icon": "settings"},
    {"id": "terminal", "name": "Terminal", "type": "command", "target": "start wt", "icon": "terminal"},
]


def _read_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default
    except (OSError, ValueError):
        # Keep a corrupt file for inspection instead of silently overwriting it.
        try:
            path.replace(path.with_suffix(path.suffix + ".bad"))
        except OSError:
            pass
        return default


def _write_json(path: Path, data: Any) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


class Config:
    def __init__(self, data_dir: Path | None = None):
        self.dir = Path(data_dir) if data_dir else default_data_dir()
        self.dir.mkdir(parents=True, exist_ok=True)
        self.settings_path = self.dir / "settings.json"
        self.devices_path = self.dir / "devices.json"
        self.launchers_path = self.dir / "launchers.json"
        self.cert_path = self.dir / "cert.pem"
        self.key_path = self.dir / "key.pem"
        self._lock = threading.Lock()
        s = _read_json(self.settings_path, {})
        changed = False
        for k, v in {
            "server_id": uuid.uuid4().hex,
            "name": socket.gethostname(),
            "port": DEFAULT_PORT,
            "discovery_port": DISCOVERY_PORT,
        }.items():
            if k not in s:
                s[k] = v
                changed = True
        self.settings: dict[str, Any] = s
        if changed:
            _write_json(self.settings_path, s)
        if not self.launchers_path.exists():
            _write_json(self.launchers_path, {"launchers": DEFAULT_LAUNCHERS})

    def set_setting(self, key: str, value: Any) -> None:
        with self._lock:
            self.settings[key] = value
            _write_json(self.settings_path, self.settings)

    @property
    def server_id(self) -> str:
        return self.settings["server_id"]

    @property
    def name(self) -> str:
        return self.settings["name"]

    # --- devices ---------------------------------------------------------------
    def devices(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            return _read_json(self.devices_path, {})

    def save_device(self, device_id: str, record: dict[str, Any]) -> None:
        with self._lock:
            d = _read_json(self.devices_path, {})
            d[device_id] = record
            _write_json(self.devices_path, d)

    def remove_device(self, device_id: str) -> None:
        with self._lock:
            d = _read_json(self.devices_path, {})
            d.pop(device_id, None)
            _write_json(self.devices_path, d)

    # --- launchers -------------------------------------------------------------
    def launchers(self) -> list[dict[str, Any]]:
        """Re-read each time so edits to launchers.json apply without a restart."""
        data = _read_json(self.launchers_path, {"launchers": DEFAULT_LAUNCHERS})
        items = data.get("launchers", []) if isinstance(data, dict) else []
        out = []
        for it in items:
            if isinstance(it, dict) and {"id", "name", "type", "target"} <= it.keys() \
                    and it["type"] in ("app", "url", "command"):
                out.append(it)
        return out
