"""System-tray front end: status, connected device, pairing window, autostart toggle, quit."""
from __future__ import annotations

import asyncio
import logging
import os
import queue
import sys
import threading
import time
from pathlib import Path


def _point_tk_at_base_python() -> None:
    # Python 3.13.0 venvs can't locate Tcl/Tk ("Can't find a usable init.tcl"); fixed in 3.13.1.
    tcl = Path(sys.base_prefix) / "tcl"
    for var, name in (("TCL_LIBRARY", "tcl8.6"), ("TK_LIBRARY", "tk8.6")):
        if not os.environ.get(var) and (tcl / name / ("init.tcl" if var == "TCL_LIBRARY" else "tk.tcl")).exists():
            os.environ[var] = str(tcl / name)


if sys.platform == "win32" and not getattr(sys, "frozen", False):
    _point_tk_at_base_python()

import tkinter as tk  # noqa: E402
from typing import Callable

import pystray
import qrcode
from PIL import Image, ImageDraw, ImageTk

from .pairing import lan_addresses
from .server import FlipDeskServer
from .tlsutil import short_fingerprint

log = logging.getLogger(__name__)

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
APP_NAME = "FlipDesk"

BG, FG, MUTED, ACCENT = "#000000", "#f2f2f2", "#9a9a9a", "#7cc4ff"


def make_icon(color: str) -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((4, 4, 60, 60), radius=14, fill=color)
    # A folded phone glyph: two stacked rounded halves with a hinge gap.
    d.rounded_rectangle((20, 12, 44, 30), radius=5, fill="#000000")
    d.rounded_rectangle((20, 34, 44, 52), radius=5, fill="#000000")
    return img


ICONS = {"idle": "#5b6b7a", "connected": "#3ddc84", "pairing": "#ffb74d"}


# --- autostart (Windows HKCU Run key) ------------------------------------------
def autostart_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    pyw = Path(sys.executable).with_name("pythonw.exe")
    exe = pyw if pyw.exists() else Path(sys.executable)
    launcher = Path(__file__).resolve().parent.parent / "FlipDesk.pyw"
    return f'"{exe}" "{launcher}"'


def autostart_enabled() -> bool:
    if sys.platform != "win32":
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP_NAME)
            return True
    except OSError:
        return False


def set_autostart(enabled: bool) -> None:
    if sys.platform != "win32":
        return
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if enabled:
            winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(k, APP_NAME)
            except OSError:
                pass


# --- pairing window -------------------------------------------------------------
class PairingWindow:
    def __init__(self, root: tk.Tk, server: FlipDeskServer, on_closed: Callable[[], None]):
        self.server = server
        self.on_closed = on_closed
        self.session = server.pairing.open()
        self.devices_before = set(server.config.devices())
        self.win = tk.Toplevel(root, bg=BG)
        self.win.title("Pair your phone — FlipDesk")
        self.win.resizable(False, False)
        self.win.attributes("-topmost", True)
        self.win.protocol("WM_DELETE_WINDOW", self.close)

        qr = qrcode.QRCode(border=2, box_size=8, error_correction=qrcode.constants.ERROR_CORRECT_M)
        qr.add_data(server.pairing.qr_payload(self.session))
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white").get_image().convert("RGB")
        self.photo = ImageTk.PhotoImage(img)

        pad = {"padx": 24}
        tk.Label(self.win, text="Scan with FlipDesk on your phone", bg=BG, fg=FG,
                 font=("Segoe UI", 14, "bold")).pack(pady=(20, 10), **pad)
        tk.Label(self.win, image=self.photo, bg=BG).pack(**pad)
        tk.Label(self.win, text="or enter this PIN", bg=BG, fg=MUTED,
                 font=("Segoe UI", 11)).pack(pady=(14, 0), **pad)
        pin = self.session.pin
        tk.Label(self.win, text=f"{pin[:3]} {pin[3:]}", bg=BG, fg=ACCENT,
                 font=("Consolas", 34, "bold")).pack(**pad)
        tk.Label(self.win, text=f"Security code {short_fingerprint(server.fingerprint)}  ·  "
                                f"{', '.join(lan_addresses())}:{server.port}",
                 bg=BG, fg=MUTED, font=("Segoe UI", 10)).pack(pady=(4, 0), **pad)
        self.status = tk.Label(self.win, text="", bg=BG, fg=MUTED, font=("Segoe UI", 10))
        self.status.pack(pady=(8, 0), **pad)
        tk.Button(self.win, text="Close", command=self.close, relief="flat", bg="#1e1e1e",
                  fg=FG, activebackground="#333333", activeforeground=FG,
                  font=("Segoe UI", 11), padx=20, pady=4).pack(pady=(12, 20))
        self.win.update_idletasks()
        w, h = self.win.winfo_width(), self.win.winfo_height()
        x = (self.win.winfo_screenwidth() - w) // 2
        y = (self.win.winfo_screenheight() - h) // 3
        self.win.geometry(f"+{x}+{y}")
        self.win.lift()
        self.win.focus_force()
        self._done = False
        self._tick()

    def _tick(self) -> None:
        if self._done:
            return
        new = set(self.server.config.devices()) - self.devices_before
        if new:
            name = self.server.config.devices()[next(iter(new))].get("name", "phone")
            self.status.config(text=f"Paired with {name}!", fg="#3ddc84")
            self._done = True
            self.win.after(1500, self.close)
            return
        s = self.server.pairing.active()
        if s is None:
            self.status.config(text="Code expired — close and reopen to get a new one.")
            return
        left = int(s.expires - time.monotonic())
        self.status.config(text=f"Code valid for {left // 60}:{left % 60:02d}")
        self.win.after(500, self._tick)

    def close(self) -> None:
        self._done = True
        self.server.pairing.close()
        try:
            self.win.destroy()
        finally:
            self.on_closed()


# --- application ---------------------------------------------------------------
class TrayApp:
    def __init__(self, server: FlipDeskServer):
        self.server = server
        self.cmds: "queue.Queue[str]" = queue.Queue()
        self.root = tk.Tk()
        self.root.withdraw()
        self.pair_win: PairingWindow | None = None
        self.loop = asyncio.new_event_loop()
        self.server_task: asyncio.Future | None = None
        self.icon = pystray.Icon(APP_NAME, make_icon(ICONS["idle"]), "FlipDesk", menu=self._menu())
        server.on_status = lambda names: self.cmds.put("status")
        server.pairing.on_change = lambda: self.cmds.put("status")

    def _menu(self) -> pystray.Menu:
        def status_text(_):
            names = self.server.connected_names()
            return f"Connected: {', '.join(names)}" if names else "Waiting for phone…"

        def devices_menu():
            devs = self.server.config.devices()
            if not devs:
                return [pystray.MenuItem("No paired devices", None, enabled=False)]
            return [pystray.MenuItem(f"Forget {rec.get('name', did[:8])}",
                                     (lambda did: lambda: self._forget(did))(did))
                    for did, rec in devs.items()]

        return pystray.Menu(
            pystray.MenuItem(status_text, None, enabled=False),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Show pairing code…", lambda: self.cmds.put("pair"), default=True),
            pystray.MenuItem("Paired devices", pystray.Menu(devices_menu)),
            pystray.MenuItem("Edit launchers…", lambda: os.startfile(self.server.config.launchers_path)),
            pystray.MenuItem("Open data folder", lambda: os.startfile(self.server.config.dir)),
            pystray.MenuItem(f"Start with {'Windows' if sys.platform == 'win32' else 'computer'}",
                             self._toggle_autostart, checked=lambda _: autostart_enabled()),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit FlipDesk", lambda: self.cmds.put("quit")),
        )

    def _toggle_autostart(self) -> None:
        set_autostart(not autostart_enabled())
        self.icon.update_menu()

    def _forget(self, device_id: str) -> None:
        self.server.config.remove_device(device_id)
        for s in list(self.server.sessions):  # kick it if it is connected right now
            if s.device_id == device_id:
                asyncio.run_coroutine_threadsafe(s.ws.close(4401, "unpaired"), self.loop)
        self.icon.update_menu()

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.server_task = asyncio.ensure_future(self.server.serve_forever(), loop=self.loop)
        try:
            self.loop.run_until_complete(self.server_task)
        except asyncio.CancelledError:
            pass
        except OSError as e:
            if e.errno in (10048, 98):  # WSAEADDRINUSE / EADDRINUSE: started twice
                log.warning("port %s is already in use; is FlipDesk already running?", self.server.port)
                self.cmds.put("in_use")
            else:
                log.exception("server crashed")
                self.cmds.put("quit")
        except Exception:
            log.exception("server crashed")
            self.cmds.put("quit")

    def _poll(self) -> None:
        try:
            while True:
                cmd = self.cmds.get_nowait()
                if cmd == "pair":
                    if self.pair_win is None:
                        self.pair_win = PairingWindow(self.root, self.server, self._pair_closed)
                    else:
                        self.pair_win.win.lift()
                elif cmd == "status":
                    self._refresh_icon()
                elif cmd == "in_use":
                    from tkinter import messagebox
                    messagebox.showinfo(APP_NAME, "FlipDesk is already running.\n\nLook for its icon in the "
                                        "system tray (click ^ next to the clock).")
                    self._quit()
                    return
                elif cmd == "quit":
                    self._quit()
                    return
        except queue.Empty:
            pass
        self.root.after(100, self._poll)

    def _pair_closed(self) -> None:
        self.pair_win = None
        self._refresh_icon()

    def _refresh_icon(self) -> None:
        if self.server.pairing.active():
            state = "pairing"
        elif self.server.sessions:
            state = "connected"
        else:
            state = "idle"
        self.icon.icon = make_icon(ICONS[state])
        names = self.server.connected_names()
        self.icon.title = f"FlipDesk — {'connected to ' + ', '.join(names) if names else 'waiting for phone'}"
        self.icon.update_menu()

    def _quit(self) -> None:
        if self.server_task:
            self.loop.call_soon_threadsafe(self.server_task.cancel)
        self.icon.stop()
        self.root.quit()

    def run(self, open_pairing: bool) -> None:
        self.root.report_callback_exception = lambda *exc: log.error("tray error", exc_info=exc)
        # FlipDesk is meant to be always available at home: start with Windows unless the
        # user has unticked it (we only switch it on once).
        if not self.server.config.settings.get("autostart_initialized"):
            try:
                set_autostart(True)
            except OSError:
                log.warning("could not enable start with Windows", exc_info=True)
            self.server.config.set_setting("autostart_initialized", True)
        threading.Thread(target=self._run_loop, name="server", daemon=True).start()
        self.icon.run_detached()
        if open_pairing:
            self.root.after(800, lambda: self.cmds.put("pair"))
        self.root.after(100, self._poll)
        self.root.mainloop()
