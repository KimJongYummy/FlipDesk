"""Screen capture -> downscaled JPEG frames with skip-unchanged and ack-based adaptive rate."""
from __future__ import annotations

import asyncio
import io
import logging
import threading
import time
import zlib
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Awaitable, Callable

from PIL import Image

from .protocol import VideoFrame, pack_video

log = logging.getLogger(__name__)

_tls = threading.local()


def _mss():
    import mss
    if getattr(_tls, "sct", None) is None:
        _tls.sct = mss.MSS() if hasattr(mss, "MSS") else mss.mss()
    return _tls.sct


def list_monitors() -> list[dict]:
    """Physical monitors (index 0..n-1), excluding mss' combined virtual screen."""
    import mss
    with (mss.MSS() if hasattr(mss, "MSS") else mss.mss()) as sct:
        mons = sct.monitors[1:]
    return [{"index": i, "x": m["left"], "y": m["top"], "w": m["width"], "h": m["height"],
             "primary": bool(m.get("is_primary", i == 0)), "name": m.get("name", f"Display {i + 1}")}
            for i, m in enumerate(mons)]


@dataclass
class StreamParams:
    monitor: int = 0
    max_w: int = 1280
    max_h: int = 1280
    max_fps: float = 30.0
    quality: int = 70          # current JPEG quality (adapted)
    scale: float = 1.0         # extra downscale factor on top of max_w/max_h (adapted)
    min_quality: int = 30
    max_quality: int = 85
    # Zoomed views: capture only this rectangle (monitor px) and fit it into region_max.
    region: tuple[int, int, int, int] | None = None
    region_max: tuple[int, int] = (2048, 2048)


@dataclass
class _Stats:
    in_flight: dict[int, float] = field(default_factory=dict)
    rtt: float = 0.05
    stall: float = 0.0
    window_start: float = field(default_factory=time.monotonic)
    sent_in_window: int = 0


class ScreenStreamer:
    MAX_IN_FLIGHT = 2

    def __init__(self, send_bytes: Callable[[bytes], Awaitable[None]],
                 send_json: Callable[..., Awaitable[None]],
                 cursor_pos: Callable[[], tuple[int, int]],
                 grabber: Callable[[int], tuple[Image.Image, dict]] | None = None):
        self.send_bytes = send_bytes
        self.send_json = send_json
        self.cursor_pos = cursor_pos
        self.grab = grabber or self._grab_mss
        self.params = StreamParams()
        self.stats = _Stats()
        self._exec = ThreadPoolExecutor(max_workers=1, thread_name_prefix="capture")
        self._task: asyncio.Task | None = None
        self._ack = asyncio.Event()
        self._seq = 0
        self._last_hash: int | None = None
        self._last_cursor: tuple[int, int] | None = None

    # --- control -------------------------------------------------------------
    def start(self, monitor: int, max_w: int = 1280, max_h: int = 1280, max_fps: float = 30,
              quality: int | None = None) -> None:
        p = self.params
        p.monitor = monitor
        p.max_w = max(160, min(3840, int(max_w)))
        p.max_h = max(160, min(3840, int(max_h)))
        p.max_fps = max(1.0, min(60.0, float(max_fps)))
        if quality:
            p.quality = max(p.min_quality, min(p.max_quality, int(quality)))
        p.region = None
        self._last_hash = None  # force a full frame for the new stream
        self.stats = _Stats()
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._run())

    def set_region(self, region: tuple[int, int, int, int] | None,
                   max_w: int = 2048, max_h: int = 2048) -> None:
        """Stream only part of the monitor (for zoomed views), or the whole thing again (None)."""
        p = self.params
        p.region = region
        p.region_max = (max(160, min(3840, int(max_w))), max(160, min(3840, int(max_h))))
        self._last_hash = None  # the phone wants the new area right away

    def stop(self) -> None:
        if self._task:
            self._task.cancel()
            self._task = None

    def close(self) -> None:
        self.stop()
        self._exec.shutdown(wait=False, cancel_futures=True)

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    def ack(self, seq: int) -> None:
        sent = self.stats.in_flight.pop(seq, None)
        # Anything older than an acked frame was received too (in-order transport).
        for s in [s for s in self.stats.in_flight if s < seq]:
            self.stats.in_flight.pop(s, None)
        if sent is not None:
            self.stats.rtt = 0.8 * self.stats.rtt + 0.2 * (time.monotonic() - sent)
        self._ack.set()

    # --- capture -------------------------------------------------------------
    @staticmethod
    def _grab_mss(monitor: int, region: tuple[int, int, int, int] | None = None) -> tuple[Image.Image, dict]:
        sct = _mss()
        mons = sct.monitors
        m = mons[monitor + 1] if 0 <= monitor < len(mons) - 1 else mons[1]
        box = m
        if region:  # grabbing just the rectangle is also much cheaper than the whole screen
            x, y, w, h = region
            box = {"left": m["left"] + x, "top": m["top"] + y, "width": w, "height": h}
        raw = sct.grab(box)
        img = Image.frombuffer("RGB", raw.size, raw.bgra, "raw", "BGRX", 0, 1)
        return img, {"x": m["left"], "y": m["top"], "w": m["width"], "h": m["height"]}

    @staticmethod
    def _clamp_region(region, mon_w: int, mon_h: int):
        if not region:
            return None
        x, y, w, h = region
        x, y = min(max(0, x), mon_w - 16), min(max(0, y), mon_h - 16)
        w, h = min(w, mon_w - x), min(h, mon_h - y)
        if w >= mon_w and h >= mon_h:
            return None  # the whole monitor
        return x, y, w, h

    def _capture_encode(self, p: StreamParams, last_hash: int | None):
        region = p.region
        if self.grab is self._grab_mss:
            mons = _mss().monitors
            mon = mons[p.monitor + 1] if 0 <= p.monitor < len(mons) - 1 else mons[1]
            region = self._clamp_region(region, mon["width"], mon["height"])
            img, geo = self._grab_mss(p.monitor, region)
        else:  # test/custom grabbers return the whole monitor; crop here
            img, geo = self.grab(p.monitor)
            region = self._clamp_region(region, geo["w"], geo["h"])
            if region:
                x, y, w, h = region
                img = img.crop((x, y, x + w, y + h))
        max_w, max_h = p.region_max if region else (p.max_w, p.max_h)
        w, h = img.size
        fit = min(max_w / w, max_h / h, 1.0) * p.scale
        if fit < 0.999:
            img = img.resize((max(1, round(w * fit)), max(1, round(h * fit))),
                             Image.BILINEAR, reducing_gap=2.0)
        digest = zlib.crc32(img.tobytes()) ^ hash(region)
        if digest == last_hash:
            return None, digest, geo, region
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=p.quality)
        return buf.getvalue(), digest, geo, region

    async def _run(self) -> None:
        loop = asyncio.get_running_loop()
        try:
            while True:
                p, st = self.params, self.stats
                t0 = time.monotonic()
                # Flow control: wait for acks so frames never queue up behind the network.
                while len(st.in_flight) >= self.MAX_IN_FLIGHT:
                    self._ack.clear()
                    try:
                        await asyncio.wait_for(self._ack.wait(), timeout=1.0)
                    except asyncio.TimeoutError:
                        st.in_flight.clear()  # assume lost; keep going
                stall = time.monotonic() - t0
                st.stall += stall
                jpeg, digest, geo, region = await loop.run_in_executor(
                    self._exec, self._capture_encode, p, self._last_hash)
                cx, cy = self.cursor_pos()
                rel = (cx - geo["x"], cy - geo["y"])
                if not (0 <= rel[0] < geo["w"] and 0 <= rel[1] < geo["h"]):
                    rel = (-1, -1)
                if jpeg is not None:
                    self._last_hash = digest
                    self._seq += 1
                    st.in_flight[self._seq] = time.monotonic()
                    st.sent_in_window += 1
                    await self.send_bytes(pack_video(VideoFrame(
                        self._seq, geo["w"], geo["h"], rel[0], rel[1], p.monitor, p.quality, jpeg, region)))
                    self._last_cursor = rel
                elif rel != self._last_cursor:
                    self._last_cursor = rel
                    await self.send_json("cursor", x=rel[0], y=rel[1], monitor=p.monitor)
                self._adapt()
                interval = 1.0 / p.max_fps
                elapsed = time.monotonic() - t0
                await asyncio.sleep(max(0.0, interval - elapsed + stall))
        except asyncio.CancelledError:
            pass
        except Exception:
            log.exception("screen stream failed")

    def _adapt(self) -> None:
        """Once a second: lower quality/scale when we spent time blocked on acks, raise when idle."""
        st, p = self.stats, self.params
        now = time.monotonic()
        span = now - st.window_start
        if span < 1.0:
            return
        stall_ratio = st.stall / span
        if stall_ratio > 0.25 or st.rtt > 0.25:
            if p.quality > p.min_quality + 5:
                p.quality -= 10
            elif p.scale > 0.5:
                p.scale = round(p.scale * 0.85, 3)
        elif stall_ratio < 0.05 and st.rtt < 0.08:
            if p.scale < 1.0:
                p.scale = min(1.0, round(p.scale / 0.85, 3))
            elif p.quality < p.max_quality:
                p.quality += 5
        p.quality = max(p.min_quality, min(p.max_quality, p.quality))
        st.stall, st.window_start, st.sent_in_window = 0.0, now, 0
