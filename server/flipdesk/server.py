"""The FlipDesk WebSocket-over-TLS server."""
from __future__ import annotations

import asyncio
import logging
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

from websockets.asyncio.server import Server, ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from . import __version__
from . import protocol as P
from .backend import Backend
from .config import Config
from .discovery import start_discovery
from .media_info import NowPlaying
from .pairing import PairingManager
from .screen import ScreenStreamer, list_monitors
from .tlsutil import ensure_certificate, fingerprint, server_context

log = logging.getLogger(__name__)

AUTH_TIMEOUT_S = 10
MAX_FILE_BYTES = 4 * 1024 ** 3
CLOSE_UNAUTHORIZED = 4401


def _com_init() -> None:
    if sys.platform == "win32":
        try:
            import comtypes
            comtypes.CoInitialize()
        except Exception:
            pass


class _Transfer:
    def __init__(self, path: Path, size: int, name: str):
        self.final = path
        self.part = path.with_name(path.name + ".part")
        self.size = size
        self.name = name
        self.received = 0
        self.fh = open(self.part, "wb")

    def abort(self) -> None:
        self.fh.close()
        try:
            self.part.unlink()
        except OSError:
            pass


class Session:
    def __init__(self, server: "FlipDeskServer", ws: ServerConnection):
        self.server = server
        self.ws = ws
        self.device_id: str | None = None
        self.device_name = "?"
        self.peer = ws.remote_address[0] if ws.remote_address else "?"
        self.streamer = ScreenStreamer(self.send_bytes, self.send, server.backend.cursor_pos,
                                       grabber=server.grabber)
        self.transfers: dict[int, _Transfer] = {}
        self._move_rem = [0.0, 0.0]
        self._scroll_rem = [0.0, 0.0]
        self._send_lock = asyncio.Lock()

    async def send(self, t: str, **fields: Any) -> None:
        try:
            await self.ws.send(P.encode(t, **fields))
        except ConnectionClosed:
            pass

    async def send_bytes(self, data: bytes) -> None:
        try:
            await self.ws.send(data)
        except ConnectionClosed:
            pass

    # --- lifecycle -------------------------------------------------------------
    async def run(self) -> None:
        if not await self._authenticate():
            return
        self.server._client_joined(self)
        try:
            async for raw in self.ws:
                try:
                    if isinstance(raw, bytes):
                        await self._on_binary(raw)
                    else:
                        msg = P.validate(P.decode(raw))
                        await self._dispatch(msg)
                except P.ProtocolError as e:
                    await self.send("error", msg=str(e))
                except Exception as e:
                    log.exception("handler error")
                    await self.send("error", msg=f"server error: {e}")
        except ConnectionClosed:
            pass
        finally:
            self.streamer.close()
            for tr in self.transfers.values():
                tr.abort()
            self.transfers.clear()
            self.server._client_left(self)

    async def _authenticate(self) -> bool:
        srv = self.server
        try:
            raw = await asyncio.wait_for(self.ws.recv(), AUTH_TIMEOUT_S)
            msg = P.decode(raw)
        except (asyncio.TimeoutError, ConnectionClosed, P.ProtocolError):
            await self.ws.close(CLOSE_UNAUTHORIZED, "auth required")
            return False
        t = msg.get("t")
        if t == "pair":
            token, reason = srv.pairing.try_pair(msg)
            if token is None:
                log.info("pairing rejected from %s: %s", self.peer, reason)
                await self.send("pair_fail", reason=reason)
                await self.ws.close(CLOSE_UNAUTHORIZED, "pairing failed")
                return False
            self.device_id = msg["device_id"]
            self.device_name = str(msg.get("device_name") or "Phone")[:64]
            await self.send("paired", token=token, server_id=srv.config.server_id,
                            server_name=srv.config.name)
            log.info("paired new device %s (%s)", self.device_name, self.peer)
        elif t == "hello":
            rec = srv.pairing.authenticate(msg)
            if rec is None:
                log.info("rejected unpaired device from %s", self.peer)
                await self.send("auth_fail", reason="unknown device or token; pair again")
                await self.ws.close(CLOSE_UNAUTHORIZED, "not paired")
                return False
            self.device_id = msg["device_id"]
            self.device_name = rec.get("name", "Phone")
            srv.pairing.touch(self.device_id)
        else:
            await self.ws.close(CLOSE_UNAUTHORIZED, "auth required")
            return False
        await self.send("auth_ok", **(await srv.state_snapshot()))
        return True

    # --- dispatch --------------------------------------------------------------
    async def _dispatch(self, m: dict[str, Any]) -> None:
        srv, b, t = self.server, self.server.backend, m["t"]
        if t == "ping":
            await self.send("pong", ts=m.get("ts"), server_ts=int(time.time() * 1000))
        elif t == "get_state":
            await self.send("state", **(await srv.state_snapshot()))
        elif t == "mouse_move":
            dx, dy = self._accumulate(self._move_rem, m["dx"], m["dy"], 1)
            b.mouse_move(dx, dy)
        elif t == "mouse_abs":
            mon = srv.monitor(m["monitor"])
            x = mon["x"] + round(min(1.0, max(0.0, m["x"])) * (mon["w"] - 1))
            y = mon["y"] + round(min(1.0, max(0.0, m["y"])) * (mon["h"] - 1))
            b.mouse_abs(x, y)
        elif t == "mouse_button":
            b.mouse_button(m["button"], m["action"])
        elif t == "scroll":
            # 1.0 == one wheel notch (WHEEL_DELTA 120); fractions give smooth scrolling.
            dx, dy = self._accumulate(self._scroll_rem, m["dx"], m["dy"], 120)
            b.scroll(dx, dy)
        elif t == "key":
            b.key(m["key"], m.get("action", "press"), m.get("mods") or [])
        elif t == "text":
            b.text(m["text"])
        elif t == "shortcut":
            b.shortcut(m["name"])
        elif t == "media":
            if not await srv.now_playing.control(m["action"]):
                b.media(m["action"])
            srv.poke_media()
        elif t == "volume_set":
            await srv.com(b.set_volume, float(m["level"]))
            srv.poke_volume()
        elif t == "volume_step":
            await srv.com(b.step_volume, float(m["delta"]))
            srv.poke_volume()
        elif t == "mute":
            muted = m.get("muted")
            await srv.com(b.toggle_mute, muted if isinstance(muted, bool) else None)
            srv.poke_volume()
        elif t == "power":
            if m["action"] in P.DESTRUCTIVE_POWER and m.get("confirm") is not True:
                await self.send("error", msg=f"power {m['action']} needs confirm=true")
                return
            log.warning("power action %s requested by %s", m["action"], self.device_name)
            await self.send("ok", for_t="power", action=m["action"])
            await srv.com(b.power, m["action"])
        elif t == "launch":
            item = next((it for it in srv.config.launchers() if it["id"] == m["id"]), None)
            if item is None:
                await self.send("error", msg=f"no launcher {m['id']!r}")
                return
            await srv.com(b.run_launcher, item)
            await self.send("ok", for_t="launch", id=item["id"])
        elif t == "clipboard_push":
            await srv.com(b.clipboard_set, m["text"])
            await self.send("ok", for_t="clipboard_push")
        elif t == "clipboard_pull":
            text = await srv.com(b.clipboard_get)
            await self.send("clipboard", text=text[:1_000_000])
        elif t == "open_url":
            u = urlparse(m["url"].strip())
            if u.scheme not in ("http", "https") or not u.netloc:
                await self.send("error", msg="only http(s) links can be opened")
                return
            await srv.com(b.open_url, m["url"].strip())
            await self.send("ok", for_t="open_url")
        elif t == "file_begin":
            await self._file_begin(m)
        elif t == "file_end":
            await self._file_end(m["id"])
        elif t == "file_cancel":
            tr = self.transfers.pop(m["id"], None)
            if tr:
                tr.abort()
        elif t == "stream_start":
            mons = await srv.monitors()
            idx = m["monitor"] if 0 <= m["monitor"] < len(mons) else 0
            self.streamer.start(idx, m.get("max_w", 1280), m.get("max_h", 1280),
                                m.get("fps", 30), m.get("quality"))
            await self.send("stream_started", monitor=idx, monitors=mons)
        elif t == "stream_region":
            if "x" in m:
                self.streamer.set_region((m["x"], m["y"], m["w"], m["h"]),
                                         m.get("max_w", 2048), m.get("max_h", 2048))
            else:
                self.streamer.set_region(None)
        elif t == "stream_stop":
            self.streamer.stop()
        elif t == "frame_ack":
            self.streamer.ack(m["seq"])

    @staticmethod
    def _accumulate(rem: list[float], dx: float, dy: float, unit: int) -> tuple[int, int]:
        """Keep sub-unit remainders so slow, fractional phone motion is not lost."""
        rem[0] += dx * unit
        rem[1] += dy * unit
        ix, iy = int(rem[0]), int(rem[1])
        rem[0] -= ix
        rem[1] -= iy
        return ix, iy

    # --- file transfer ---------------------------------------------------------
    async def _file_begin(self, m: dict[str, Any]) -> None:
        if not 0 <= m["size"] <= MAX_FILE_BYTES:
            await self.send("error", msg="file too large")
            return
        if m["id"] in self.transfers:
            self.transfers.pop(m["id"]).abort()
        path = self.server.backend.unique_download_path(m["name"])
        self.transfers[m["id"]] = _Transfer(path, m["size"], m["name"])
        await self.send("file_ready", id=m["id"])

    async def _on_binary(self, data: bytes) -> None:
        tid, payload = P.unpack_file_chunk(data)
        tr = self.transfers.get(tid)
        if tr is None:
            raise P.ProtocolError(f"chunk for unknown transfer {tid}")
        tr.received += len(payload)
        if tr.received > tr.size:
            self.transfers.pop(tid).abort()
            raise P.ProtocolError("file larger than announced")
        tr.fh.write(payload)

    async def _file_end(self, tid: int) -> None:
        tr = self.transfers.pop(tid, None)
        if tr is None:
            raise P.ProtocolError(f"unknown transfer {tid}")
        if tr.received != tr.size:
            tr.abort()
            await self.send("error", msg=f"file incomplete ({tr.received}/{tr.size} bytes)")
            return
        tr.fh.close()
        final = tr.final if not tr.final.exists() else self.server.backend.unique_download_path(tr.name)
        os.replace(tr.part, final)
        log.info("saved shared file %s", final)
        await self.send("file_saved", id=tid, name=final.name, path=str(final))


class FlipDeskServer:
    def __init__(self, config: Config, backend: Backend, host: str = "0.0.0.0",
                 port: int | None = None, discovery: bool = True,
                 on_status: Callable[[list[str]], None] | None = None,
                 grabber=None, now_playing: NowPlaying | None = None):
        self.config = config
        self.backend = backend
        self.host = host
        self.port = port if port is not None else int(config.settings["port"])
        self.discovery_enabled = discovery
        self.on_status = on_status
        self.grabber = grabber
        ensure_certificate(config.cert_path, config.key_path, f"FlipDesk {config.name}")
        self.fingerprint = fingerprint(config.cert_path)
        self.pairing = PairingManager(config, self.fingerprint, self.port)
        # In dry-run mode we may read what is playing but never press its buttons.
        self.now_playing = now_playing or NowPlaying(read_only=backend.name == "dry-run")
        self.sessions: set[Session] = set()
        self._com = ThreadPoolExecutor(max_workers=1, initializer=_com_init, thread_name_prefix="com")
        self._server: Server | None = None
        self._udp = None
        self._tasks: list[asyncio.Task] = []
        self._volume: tuple[float, bool] | None = None
        self._media: dict | None = None
        self._monitors: list[dict] | None = None
        self._vol_poke = asyncio.Event()
        self._media_poke = asyncio.Event()
        self.ready = asyncio.Event()

    async def com(self, fn, *args):
        return await asyncio.get_running_loop().run_in_executor(self._com, fn, *args)

    # --- lifecycle -------------------------------------------------------------
    async def start(self) -> None:
        ctx = server_context(self.config.cert_path, self.config.key_path)
        self._server = await serve(self._handle, self.host, self.port, ssl=ctx,
                                   max_size=4 * 1024 * 1024, compression=None,
                                   ping_interval=10, ping_timeout=20,
                                   server_header=f"FlipDesk/{__version__}")
        if self.port == 0:  # tests ask for an ephemeral port
            self.port = self._server.sockets[0].getsockname()[1]
            self.pairing.port = self.port
        if self.discovery_enabled:
            self._udp = await start_discovery(self.host, int(self.config.settings["discovery_port"]),
                                              self.config.server_id, self.config.name,
                                              self.port, self.fingerprint)
        self._tasks = [asyncio.create_task(self._poll_volume()),
                       asyncio.create_task(self._poll_media())]
        log.info("FlipDesk listening on wss://%s:%s (fingerprint %s…)", self.host, self.port,
                 self.fingerprint[:16])
        self.ready.set()

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        if self._udp:
            self._udp.close()
        if self._server:
            self._server.close()
            await self._server.wait_closed()
        self._com.shutdown(wait=False, cancel_futures=True)

    async def serve_forever(self) -> None:
        await self.start()
        try:
            await asyncio.Future()
        finally:
            await self.stop()

    async def _handle(self, ws: ServerConnection) -> None:
        await Session(self, ws).run()

    # --- state -----------------------------------------------------------------
    async def monitors(self) -> list[dict]:
        if self._monitors is None:
            try:
                self._monitors = await asyncio.get_running_loop().run_in_executor(None, list_monitors)
            except Exception as e:
                log.warning("cannot list monitors: %s", e)
                self._monitors = [{"index": 0, "x": 0, "y": 0, "w": 1920, "h": 1080,
                                   "primary": True, "name": "Display 1"}]
        return self._monitors

    def monitor(self, idx: int) -> dict:
        mons = self._monitors or [{"x": 0, "y": 0, "w": 1920, "h": 1080}]
        return mons[idx] if 0 <= idx < len(mons) else mons[0]

    async def state_snapshot(self) -> dict[str, Any]:
        try:
            vol, muted = await self.com(self.backend.get_volume)
        except Exception:
            vol, muted = 0.0, False
        self._monitors = None  # re-detect, monitors may have been plugged in
        return {
            "v": P.PROTOCOL_VERSION, "server_id": self.config.server_id,
            "server_name": self.config.name, "server_version": __version__,
            "os": sys.platform, "backend": self.backend.name,
            "monitors": await self.monitors(), "volume": round(vol, 3), "muted": muted,
            "now_playing": self._media, "launchers": self.config.launchers(),
            "caps": {"now_playing": self.now_playing.available, "file_receive": True,
                     "power": ["lock", "sleep", "restart", "shutdown"]},
        }

    async def broadcast(self, t: str, **fields: Any) -> None:
        for s in list(self.sessions):
            await s.send(t, **fields)

    def poke_volume(self) -> None:
        self._vol_poke.set()

    def poke_media(self) -> None:
        self._media_poke.set()

    async def _wait_poke(self, ev: asyncio.Event, timeout: float) -> bool:
        """Sleep until poked or timed out; returns True if poked."""
        try:
            await asyncio.wait_for(ev.wait(), timeout)
            poked = True
        except asyncio.TimeoutError:
            poked = False
        ev.clear()
        return poked

    async def _poll_volume(self) -> None:
        while True:
            await self._wait_poke(self._vol_poke, 1.0)
            if not self.sessions:
                continue
            try:
                vol, muted = await self.com(self.backend.get_volume)
            except Exception:
                continue
            cur = (round(vol, 3), muted)
            if cur != self._volume:
                self._volume = cur
                await self.broadcast("volume", level=cur[0], muted=cur[1])

    async def _poll_media(self) -> None:
        while True:
            if await self._wait_poke(self._media_poke, 1.5):
                await asyncio.sleep(0.3)  # let the player react after a control press
            if not self.sessions:
                continue
            snap = await self.now_playing.snapshot()
            key = lambda d: d and {k: v for k, v in d.items() if k != "position_ms"}
            if key(snap) != key(self._media):
                self._media = snap
                await self.broadcast("now_playing", info=snap)

    # --- clients ---------------------------------------------------------------
    def _client_joined(self, s: Session) -> None:
        # A phone that comes back after Wi-Fi drops or the screen sleeps may leave a dead
        # connection behind; it would linger until the ping timeout, so drop it now.
        for old in [o for o in self.sessions if o.device_id == s.device_id]:
            self.sessions.discard(old)
            log.info("replacing stale connection from %s", old.device_name)
            asyncio.ensure_future(old.ws.close(1000, "replaced"))
        self.sessions.add(s)
        log.info("device connected: %s (%s)", s.device_name, s.peer)
        self._volume = None
        self._media = None
        self.poke_media()
        self._status()

    def _client_left(self, s: Session) -> None:
        if s in self.sessions:
            self.sessions.discard(s)
            log.info("device disconnected: %s", s.device_name)
            self._status()

    def connected_names(self) -> list[str]:
        return sorted({s.device_name for s in self.sessions})

    def _status(self) -> None:
        if self.on_status:
            try:
                self.on_status(self.connected_names())
            except Exception:
                pass
