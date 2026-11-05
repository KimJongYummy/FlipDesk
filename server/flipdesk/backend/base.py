"""Platform-independent input/OS backend interface."""
from __future__ import annotations

import os
import re
import webbrowser
from pathlib import Path
from typing import Any

SHORTCUT_KEYS: dict[str, tuple[list[str], str]] = {
    "copy": (["ctrl"], "c"),
    "paste": (["ctrl"], "v"),
    "cut": (["ctrl"], "x"),
    "undo": (["ctrl"], "z"),
    "redo": (["ctrl"], "y"),
    "select_all": (["ctrl"], "a"),
    "find": (["ctrl"], "f"),
    "save": (["ctrl"], "s"),
    "new_tab": (["ctrl"], "t"),
    "close_tab": (["ctrl"], "w"),
    "refresh": ([], "f5"),
    "alt_tab": (["alt"], "tab"),
    "app_next": (["alt"], "tab"),
    "app_prev": (["alt", "shift"], "tab"),
    "screenshot": (["win", "shift"], "s"),
    "show_desktop": (["win"], "d"),
    "task_view": (["win"], "tab"),
    "close_window": (["alt"], "f4"),
}

_SAFE_NAME = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


class Backend:
    """Subclasses implement the primitive operations; composite ones live here."""

    name = "base"

    # --- primitives -------------------------------------------------------------
    def mouse_move(self, dx: int, dy: int) -> None: raise NotImplementedError
    def mouse_abs(self, x: int, y: int) -> None: raise NotImplementedError
    def mouse_button(self, button: str, action: str) -> None: raise NotImplementedError
    def scroll(self, dx: int, dy: int) -> None: raise NotImplementedError
    def key(self, key: str, action: str = "press", mods: list[str] | None = None) -> None: raise NotImplementedError
    def text(self, s: str) -> None: raise NotImplementedError
    def media(self, action: str) -> None: raise NotImplementedError
    def get_volume(self) -> tuple[float, bool]: raise NotImplementedError
    def set_volume(self, level: float) -> None: raise NotImplementedError
    def set_mute(self, muted: bool) -> None: raise NotImplementedError
    def power(self, action: str) -> None: raise NotImplementedError
    def clipboard_get(self) -> str: raise NotImplementedError
    def clipboard_set(self, text: str) -> None: raise NotImplementedError
    def cursor_pos(self) -> tuple[int, int]: raise NotImplementedError
    def run_launcher(self, item: dict[str, Any]) -> None: raise NotImplementedError

    def open_url(self, url: str) -> None:
        webbrowser.open(url)

    def downloads_dir(self) -> Path:
        return Path.home() / "Downloads"

    # --- composites -------------------------------------------------------------
    def shortcut(self, name: str) -> None:
        mods, key = SHORTCUT_KEYS[name]
        self.key(key, "press", mods)

    def step_volume(self, delta: float) -> float:
        level, _ = self.get_volume()
        new = min(1.0, max(0.0, level + delta))
        self.set_volume(new)
        return new

    def toggle_mute(self, muted: bool | None = None) -> bool:
        if muted is None:
            muted = not self.get_volume()[1]
        self.set_mute(muted)
        return muted

    def unique_download_path(self, name: str) -> Path:
        name = _SAFE_NAME.sub("_", os.path.basename(name)).strip(" .") or "shared_file"
        d = self.downloads_dir()
        d.mkdir(parents=True, exist_ok=True)
        stem, ext = os.path.splitext(name)
        p, i = d / name, 1
        while p.exists():
            p = d / f"{stem} ({i}){ext}"
            i += 1
        return p
