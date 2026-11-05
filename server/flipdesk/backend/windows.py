"""Windows backend: SendInput for mouse/keyboard, Core Audio for volume, Win32 for power."""
from __future__ import annotations

import ctypes
import os
import subprocess
import uuid
from ctypes import wintypes
from pathlib import Path
from typing import Any

import pyperclip

from .base import Backend

user32 = ctypes.WinDLL("user32", use_last_error=True)
shell32 = ctypes.WinDLL("shell32")
ole32 = ctypes.WinDLL("ole32")

try:  # Physical-pixel coordinates everywhere, matching the screen capture.
    user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # PER_MONITOR_AWARE_V2
except (AttributeError, OSError):
    pass

ULONG_PTR = ctypes.c_size_t
INPUT_MOUSE, INPUT_KEYBOARD = 0, 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x0008, 0x0010
MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP = 0x0020, 0x0040
MOUSEEVENTF_WHEEL, MOUSEEVENTF_HWHEEL = 0x0800, 0x1000
MOUSEEVENTF_VIRTUALDESK, MOUSEEVENTF_ABSOLUTE = 0x4000, 0x8000

KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x0001, 0x0002, 0x0004

SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN = 76, 77, 78, 79


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]


user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wintypes.UINT
user32.MapVirtualKeyW.argtypes = (wintypes.UINT, wintypes.UINT)
user32.MapVirtualKeyW.restype = wintypes.UINT

VK: dict[str, int] = {
    "backspace": 0x08, "tab": 0x09, "enter": 0x0D, "shift": 0xA0, "ctrl": 0xA2, "alt": 0xA4,
    "pause": 0x13, "capslock": 0x14, "esc": 0x1B, "space": 0x20, "pageup": 0x21,
    "pagedown": 0x22, "end": 0x23, "home": 0x24, "left": 0x25, "up": 0x26, "right": 0x27,
    "down": 0x28, "printscreen": 0x2C, "insert": 0x2D, "delete": 0x2E, "win": 0x5B,
    "menu": 0x5D, "volume_mute": 0xAD, "volume_down": 0xAE, "volume_up": 0xAF,
    "media_next": 0xB0, "media_prev": 0xB1, "media_stop": 0xB2, "media_play_pause": 0xB3,
}
VK.update({f"f{i}": 0x6F + i for i in range(1, 13)})
VK.update({chr(c): c for c in range(ord("0"), ord("9") + 1)})
VK.update({chr(c).lower(): c for c in range(ord("A"), ord("Z") + 1)})

EXTENDED = {VK[k] for k in ("pageup", "pagedown", "end", "home", "left", "up", "right", "down",
                            "insert", "delete", "win", "menu", "printscreen")} | set(range(0xAD, 0xB4))
MOD_ORDER = ("ctrl", "alt", "shift", "win")


def _send(*inputs: INPUT) -> None:
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def _mouse(flags: int, dx: int = 0, dy: int = 0, data: int = 0) -> INPUT:
    return INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0))


def _vk(vk: int, up: bool) -> INPUT:
    flags = (KEYEVENTF_KEYUP if up else 0) | (KEYEVENTF_EXTENDEDKEY if vk in EXTENDED else 0)
    scan = user32.MapVirtualKeyW(vk, 0) & 0xFF
    return INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(vk, scan, flags, 0, 0))


def _unicode(unit: int, up: bool) -> INPUT:
    flags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0)
    return INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(0, unit, flags, 0, 0))


_BUTTONS = {
    "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
    "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
}


class _GUID(ctypes.Structure):
    _fields_ = [("Data1", wintypes.DWORD), ("Data2", wintypes.WORD), ("Data3", wintypes.WORD),
                ("Data4", ctypes.c_ubyte * 8)]


def _known_folder(guid: str) -> Path | None:
    u = uuid.UUID(guid)
    g = _GUID.from_buffer_copy(u.bytes_le)
    p = ctypes.c_wchar_p()
    if shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(p)) != 0:
        return None
    try:
        return Path(p.value)
    finally:
        ole32.CoTaskMemFree(p)


class WindowsBackend(Backend):
    name = "windows"

    def mouse_move(self, dx: int, dy: int) -> None:
        # Positioned absolutely (cursor + delta) rather than as relative mickeys, so Windows'
        # pointer-speed and "enhance pointer precision" settings don't rescale the phone's
        # own acceleration curve and sensitivity slider.
        if dx or dy:
            x, y = self.cursor_pos()
            self.mouse_abs(x + dx, y + dy)

    def mouse_abs(self, x: int, y: int) -> None:
        vx, vy = user32.GetSystemMetrics(SM_XVIRTUALSCREEN), user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
        vw, vh = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN), user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)
        x = min(max(x, vx), vx + vw - 1)
        y = min(max(y, vy), vy + vh - 1)
        # Verified pixel-exact on a 3-monitor virtual desktop (see tests/test_real_input.py).
        nx = round((x - vx) * 65535 / max(1, vw - 1))
        ny = round((y - vy) * 65535 / max(1, vh - 1))
        _send(_mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, nx, ny))

    def mouse_button(self, button: str, action: str) -> None:
        down, up = _BUTTONS[button]
        seq = {"down": [down], "up": [up], "click": [down, up], "double": [down, up, down, up]}[action]
        _send(*(_mouse(f) for f in seq))

    def scroll(self, dx: int, dy: int) -> None:
        # dy > 0 scrolls content up (wheel away from user), matching WHEEL_DELTA sign.
        events = []
        if dy:
            events.append(_mouse(MOUSEEVENTF_WHEEL, data=dy))
        if dx:
            events.append(_mouse(MOUSEEVENTF_HWHEEL, data=dx))
        if events:
            _send(*events)

    def key(self, key: str, action: str = "press", mods: list[str] | None = None) -> None:
        vk = VK.get(key.lower())
        mod_vks = [VK[m] for m in MOD_ORDER if m in (mods or [])]
        events: list[INPUT] = []
        if action in ("press", "down"):
            events += [_vk(m, False) for m in mod_vks]
        if vk is not None:
            if action in ("press", "down"):
                events.append(_vk(vk, False))
            if action in ("press", "up"):
                events.append(_vk(vk, True))
        elif len(key) == 1:  # any other single character
            if action in ("press", "down"):
                events.append(_unicode(ord(key), False))
            if action in ("press", "up"):
                events.append(_unicode(ord(key), True))
        if action in ("press", "up"):
            events += [_vk(m, True) for m in reversed(mod_vks)]
        if events:
            _send(*events)

    def text(self, s: str) -> None:
        events: list[INPUT] = []
        for ch in s.replace("\r\n", "\n"):
            if ch == "\n":
                events += [_vk(VK["enter"], False), _vk(VK["enter"], True)]
            elif ch == "\t":
                events += [_vk(VK["tab"], False), _vk(VK["tab"], True)]
            else:
                b = ch.encode("utf-16-le")
                for i in range(0, len(b), 2):  # surrogate pairs become two code units
                    unit = int.from_bytes(b[i:i + 2], "little")
                    events += [_unicode(unit, False), _unicode(unit, True)]
        if events:
            _send(*events)

    def media(self, action: str) -> None:
        self.key({"play_pause": "media_play_pause", "next": "media_next", "prev": "media_prev",
                  "stop": "media_stop"}[action])

    # Core Audio objects are apartment-bound; the server calls these from one COM worker thread.
    def _endpoint(self):
        from pycaw.pycaw import AudioUtilities
        return AudioUtilities.GetSpeakers().EndpointVolume

    def get_volume(self) -> tuple[float, bool]:
        ev = self._endpoint()
        return float(ev.GetMasterVolumeLevelScalar()), bool(ev.GetMute())

    def set_volume(self, level: float) -> None:
        self._endpoint().SetMasterVolumeLevelScalar(min(1.0, max(0.0, float(level))), None)

    def set_mute(self, muted: bool) -> None:
        self._endpoint().SetMute(1 if muted else 0, None)

    def power(self, action: str) -> None:
        if action == "lock":
            user32.LockWorkStation()
        elif action == "sleep":
            ctypes.WinDLL("powrprof").SetSuspendState(False, False, False)
        elif action == "restart":
            subprocess.Popen(["shutdown", "/r", "/t", "3"], creationflags=subprocess.CREATE_NO_WINDOW)
        elif action == "shutdown":
            subprocess.Popen(["shutdown", "/s", "/t", "3"], creationflags=subprocess.CREATE_NO_WINDOW)

    def clipboard_get(self) -> str:
        return pyperclip.paste() or ""

    def clipboard_set(self, text: str) -> None:
        pyperclip.copy(text)

    def cursor_pos(self) -> tuple[int, int]:
        pt = wintypes.POINT()
        user32.GetCursorPos(ctypes.byref(pt))
        return pt.x, pt.y

    def run_launcher(self, item: dict[str, Any]) -> None:
        target, args = item["target"], item.get("args")
        if item["type"] == "command":
            subprocess.Popen(target, shell=True, creationflags=subprocess.CREATE_NO_WINDOW)
        elif args:
            os.startfile(target, arguments=args if isinstance(args, str) else subprocess.list2cmdline(args))
        else:
            os.startfile(target)  # apps, .lnk shortcuts, http(s) URLs and ms-settings: URIs

    def open_url(self, url: str) -> None:
        os.startfile(url)

    def downloads_dir(self) -> Path:
        return _known_folder("374DE290-123F-4565-9164-39C4925E467B") or Path.home() / "Downloads"
