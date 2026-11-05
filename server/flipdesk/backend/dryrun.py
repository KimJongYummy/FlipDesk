"""Backend that records every call instead of touching the OS (tests and --dry-run)."""
from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Any

from .base import Backend


class DryRunBackend(Backend):
    name = "dry-run"

    def __init__(self, downloads: Path | None = None):
        self.calls: list[tuple[Any, ...]] = []
        self.volume, self.muted = 0.5, False
        self.clip = ""
        self.cursor = (100, 100)
        self._downloads = downloads or Path(tempfile.mkdtemp(prefix="flipdesk-dl-"))

    def _rec(self, *call: Any) -> None:
        self.calls.append(call)

    def mouse_move(self, dx, dy): self._rec("mouse_move", dx, dy)
    def mouse_abs(self, x, y): self._rec("mouse_abs", x, y)
    def mouse_button(self, button, action): self._rec("mouse_button", button, action)
    def scroll(self, dx, dy): self._rec("scroll", dx, dy)
    def key(self, key, action="press", mods=None): self._rec("key", key, action, tuple(mods or ()))
    def text(self, s): self._rec("text", s)
    def media(self, action): self._rec("media", action)
    def get_volume(self): return self.volume, self.muted
    def power(self, action): self._rec("power", action)
    def clipboard_get(self): return self.clip
    def cursor_pos(self): return self.cursor
    def run_launcher(self, item): self._rec("launch", item["id"])
    def open_url(self, url): self._rec("open_url", url)
    def downloads_dir(self): return self._downloads

    def set_volume(self, level):
        self.volume = level
        self._rec("set_volume", round(level, 3))

    def set_mute(self, muted):
        self.muted = muted
        self._rec("set_mute", muted)

    def clipboard_set(self, text):
        self.clip = text
        self._rec("clipboard_set", text)
